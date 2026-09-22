package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

public final class CompatibilityExportSpaceTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void lowSpaceIsRecognizedThroughWrappedCausesOnly() {
        IOException low = new CompatibilityExportSpace.LowSpaceException(1L, 512L);
        assertTrue(CompatibilityExportSpace.isLowSpace(low));
        assertTrue(CompatibilityExportSpace.isLowSpace(
                new RuntimeException(new IOException("export failed", low))));
        assertFalse(CompatibilityExportSpace.isLowSpace(new IOException("remote read failed")));
        assertFalse(CompatibilityExportSpace.isLowSpace(null));
    }

    @Test
    public void reserveThresholdAndTempZipCoexistenceAreExact() throws Exception {
        File directory = temporary.newFolder("space");
        CompatibilityExportSpace.Guard exact = new CompatibilityExportSpace.Guard(
                directory, ignored -> CompatibilityExportSpace.RESERVE_BYTES + 25L);
        exact.check(25L);
        try {
            exact.check(26L);
            throw new AssertionError("expected low space");
        } catch (CompatibilityExportSpace.LowSpaceException expected) {
            assertTrue(expected.getMessage().contains("insufficient_space"));
        }

        long capturedTempBytes = 7L * 1024L * 1024L;
        CompatibilityExportSpace.Guard coexistence = new CompatibilityExportSpace.Guard(
                directory, ignored -> CompatibilityExportSpace.RESERVE_BYTES + capturedTempBytes);
        coexistence.check(capturedTempBytes);
    }

    @Test
    public void outputChecksEachAdditionalMebibyteAndRemembersCaughtFailure() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        CompatibilityExportSpace.Guard guard = new CompatibilityExportSpace.Guard(
                temporary.getRoot(), ignored -> checks.incrementAndGet() < 2
                        ? Long.MAX_VALUE : CompatibilityExportSpace.RESERVE_BYTES);
        CompatibilityExportSpace.CheckedOutputStream output =
                new CompatibilityExportSpace.CheckedOutputStream(
                        new ByteArrayOutputStream(), guard);
        output.write(new byte[(int) CompatibilityExportSpace.CHECK_INTERVAL_BYTES]);
        try {
            output.write(1);
            throw new AssertionError("expected low space");
        } catch (IOException expected) {
            // Simulates a command runner swallowing its OutputStream failure.
        }
        try {
            output.rethrowFailure();
            throw new AssertionError("expected remembered write failure");
        } catch (IOException expected) {
            assertEquals(2, checks.get());
        }
    }

    @Test
    public void outputPreservesStandardRangeValidation() throws Exception {
        CompatibilityExportSpace.CheckedOutputStream output =
                new CompatibilityExportSpace.CheckedOutputStream(new ByteArrayOutputStream(),
                        new CompatibilityExportSpace.Guard(
                                temporary.getRoot(), ignored -> Long.MAX_VALUE));
        try {
            output.write(new byte[4], 3, 2);
            throw new AssertionError("expected range failure");
        } catch (IndexOutOfBoundsException expected) {
            // expected
        }
    }

    @Test
    public void lowSpaceAbortsWholeExportAndClearsOnlyOwnPartials() throws Exception {
        File cache = temporary.newFolder("cache");
        File shared = new File(cache, "shared_logs");
        assertTrue(shared.mkdirs());
        File unrelated = new File(shared, "owner-preset.json");
        java.nio.file.Files.write(unrelated.toPath(), new byte[]{1});
        CompatibilityExportSpace.Guard low = new CompatibilityExportSpace.Guard(
                shared, ignored -> CompatibilityExportSpace.RESERVE_BYTES - 1L);

        try {
            CompatibilityBundleExporter.export(cache, new TestSharedPreferences(), identity(),
                    (command, limit) -> CompatibilityBundleExporter.CommandResult.success(""),
                    (command, output, limit) -> CompatibilityBundleExporter.StreamResult.success(0, 0),
                    7_000L, new CompatibilityBundleExporter.ExportControl(), low);
            throw new AssertionError("expected low-space abort");
        } catch (CompatibilityExportSpace.LowSpaceException expected) {
            // expected
        }

        assertTrue(unrelated.exists());
        File[] own = shared.listFiles(file -> file.getName().contains("compatibility"));
        assertTrue(own == null || own.length == 0);
        assertFalse(new File(cache, "other").exists());
    }

    @Test
    public void lowSpaceDuringTemporaryStreamingAbortsEvenWhenRunnerCatchesWriteFailure()
            throws Exception {
        File cache = temporary.newFolder("temp-stream-low");
        File shared = new File(cache, "shared_logs");
        assertTrue(shared.mkdirs());
        AtomicInteger checks = new AtomicInteger();
        CompatibilityExportSpace.Guard guard = new CompatibilityExportSpace.Guard(shared,
                ignored -> checks.incrementAndGet() <= 6 ? Long.MAX_VALUE
                        : CompatibilityExportSpace.RESERVE_BYTES);

        try {
            CompatibilityBundleExporter.export(cache, new TestSharedPreferences(), identity(),
                    (command, limit) -> CompatibilityBundleExporter.CommandResult.success(""),
                    (command, output, limit) -> {
                        try {
                            output.write(new byte[(int) CompatibilityExportSpace.CHECK_INTERVAL_BYTES]);
                            output.write(1);
                        } catch (IOException caughtByRunner) {
                            return CompatibilityBundleExporter.StreamResult.failure(
                                    "remote_caught_write", -1, 0);
                        }
                        return CompatibilityBundleExporter.StreamResult.success(0,
                                CompatibilityExportSpace.CHECK_INTERVAL_BYTES + 1L);
                    }, 9_000L, new CompatibilityBundleExporter.ExportControl(), guard);
            throw new AssertionError("expected low-space abort");
        } catch (CompatibilityExportSpace.LowSpaceException expected) {
            assertTrue(checks.get() >= 7);
        }
        assertNoOwnArtifacts(shared);
    }

    @Test
    public void lowSpaceDuringZipWriteAbortsWithCapturedTempStillPresent() throws Exception {
        File cache = temporary.newFolder("zip-low");
        File shared = new File(cache, "shared_logs");
        assertTrue(shared.mkdirs());
        AtomicInteger checks = new AtomicInteger();
        CompatibilityExportSpace.Guard guard = new CompatibilityExportSpace.Guard(shared,
                ignored -> checks.incrementAndGet() <= 8 ? Long.MAX_VALUE
                        : CompatibilityExportSpace.RESERVE_BYTES);
        byte[] incompressible = new byte[2 * (int) CompatibilityExportSpace.CHECK_INTERVAL_BYTES];
        new Random(91L).nextBytes(incompressible);

        try {
            CompatibilityBundleExporter.export(cache, new TestSharedPreferences(), identity(),
                    (command, limit) -> CompatibilityBundleExporter.CommandResult.success(""),
                    (command, output, limit) -> {
                        try { output.write(incompressible); }
                        catch (IOException error) {
                            return CompatibilityBundleExporter.StreamResult.failure(
                                    "remote_caught_write", -1, 0);
                        }
                        return CompatibilityBundleExporter.StreamResult.success(
                                0, incompressible.length);
                    }, 10_000L, new CompatibilityBundleExporter.ExportControl(), guard);
            throw new AssertionError("expected low-space abort");
        } catch (CompatibilityExportSpace.LowSpaceException expected) {
            assertTrue(checks.get() >= 9);
        }
        assertNoOwnArtifacts(shared);
    }

    @Test
    public void runnerOriginIOExceptionRemainsBestEffortRemoteFailure() throws Exception {
        File archive = CompatibilityBundleExporter.export(
                temporary.newFolder("remote-io"), new TestSharedPreferences(), identity(),
                (command, limit) -> CompatibilityBundleExporter.CommandResult.success(""),
                (command, output, limit) -> {
                    CompatibilityExportSpaceTest.<RuntimeException>sneakyThrow(
                            new IOException("remote read failed"));
                    return null;
                }, 11_000L);

        assertTrue(archive.isFile());
        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains("IOException: remote read failed"));
        assertTrue(manifest.contains("\"status\":\"error\""));
    }

    @Test
    public void metadataFailurePreventsArchivePublication() throws Exception {
        File cache = temporary.newFolder("metadata-failure");
        File shared = new File(cache, "shared_logs");
        assertTrue(shared.mkdirs());
        File archive = new File(shared, CompatibilityBundleExporter.archiveName(8_000L));
        File blockedTemporaryMetadata = new File(archive.getPath() + ".expiry.json.part");
        assertTrue(blockedTemporaryMetadata.mkdirs());

        try {
            CompatibilityBundleExporter.export(cache, new TestSharedPreferences(), identity(),
                    (command, limit) -> CompatibilityBundleExporter.CommandResult.success(""),
                    (command, output, limit) ->
                            CompatibilityBundleExporter.StreamResult.failure("shell_exit_44", 44, 0),
                    8_000L);
            throw new AssertionError("expected metadata failure");
        } catch (IOException expected) {
            // expected
        }

        assertFalse(archive.exists());
        assertFalse(new File(archive.getPath() + ".part").exists());
        assertFalse(new File(archive.getPath() + ".expiry.json").exists());
    }

    private static CompatibilityBundleExporter.Identity identity() {
        return new CompatibilityBundleExporter.Identity(
                "com.byd.turnsignalguard.capture", "1.2.1", 103,
                "BYD", "Sea Lion", "DiLink", "tablet", "10", 29);
    }

    private static void assertNoOwnArtifacts(File shared) {
        File[] own = shared.listFiles(file -> file.getName().contains("compatibility"));
        assertTrue(own == null ? "Unable to inspect " + shared
                : java.util.Arrays.toString(own), own == null || own.length == 0);
    }

    private static String readEntry(File archive, String name) throws Exception {
        try (ZipFile zip = new ZipFile(archive);
                InputStream input = zip.getInputStream(zip.getEntry(name));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable error) throws T {
        throw (T) error;
    }
}
