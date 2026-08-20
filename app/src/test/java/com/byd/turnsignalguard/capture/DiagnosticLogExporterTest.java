package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class DiagnosticLogExporterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void exportsAllRetainedLogsAndDeletesOldZipOnlyAfterSuccess() throws Exception {
        File external = temporary.newFolder("external-captures");
        File fallback = temporary.newFolder("files-fallback");
        write(new File(external, "guard-camera-a.jsonl"), "{\"a\":1}\n");
        write(new File(external, "ignore.txt"), "ignored\n");
        write(new File(fallback, "helper-service-b.jsonl"), "{\"b\":2}\n");

        DiagnosticLogExporter.Snapshot snapshot =
                DiagnosticLogExporter.snapshot(external, fallback, external);
        assertEquals(2, snapshot.sources.size());

        File cache = temporary.newFolder("cache-success");
        File outputDirectory = new File(cache, "shared_logs");
        assertTrue(outputDirectory.mkdirs());
        File old = new File(outputDirectory, "byd-turnsignal-diagnostics-old.zip");
        write(old, "old");

        File archive = DiagnosticLogExporter.export(
                cache, snapshot, identity(), command -> missing(), 1000L);

        assertFalse(old.exists());
        assertEquals("{\"a\":1}\n", readEntry(archive, "logs/guard-camera-a.jsonl"));
        assertEquals("{\"b\":2}\n", readEntry(archive, "logs/helper-service-b.jsonl"));
        assertNotNull(readEntry(archive, "manifest.json"));
    }

    @Test
    public void honorsSnapshotBoundAndDropsOnlyIncompleteFinalJsonlLine() throws Exception {
        File logs = temporary.newFolder("bounded-logs");
        File source = new File(logs, "guard-camera-bounded.jsonl");
        write(source, "first\nincomplete");
        DiagnosticLogExporter.Snapshot snapshot = DiagnosticLogExporter.snapshot(logs);
        Files.write(source.toPath(), "\nlate\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        File archive = DiagnosticLogExporter.export(
                temporary.newFolder("cache-bounded"),
                snapshot,
                identity(),
                command -> missing(),
                2000L);

        assertEquals("first\n", readEntry(archive, "logs/guard-camera-bounded.jsonl"));
        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains("\"status\":\"partial\""));
        assertTrue(manifest.contains("\"dropped_tail_bytes\":10"));
    }

    @Test
    public void usesOnlyFixedCollectorsAndKeepsPartialAdbOutputInVersionedManifest()
            throws Exception {
        List<String> commands = new ArrayList<>();
        String[] expected = {
                "tail -c 1048576 /data/local/tmp/bydturnguard_helper.log 2>/dev/null",
                "tail -c 1048576 /data/local/tmp/bydturnguard_camera.log 2>/dev/null",
                "logcat -b all -v threadtime -d 2>/dev/null",
                "logcat -b crash -v threadtime -d 2>/dev/null | tail -c 2097152",
                "for tag in system_server_crash system_app_crash data_app_crash; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576",
                "for tag in system_server_native_crash system_app_native_crash data_app_native_crash; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576",
                "for tag in system_server_anr system_app_anr data_app_anr; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576",
                "for tag in system_server_tombstone system_app_tombstone data_app_tombstone; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576",
                "for tag in system_server_watchdog system_server_wtf; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576",
                "dumpsys dropbox --print 2>/dev/null | tail -c 2097152",
                "for file in $(ls -1t /data/tombstones/tombstone_* 2>/dev/null | head -n 3); do printf '\\n--- %s ---\\n' \"$file\"; tail -c 524288 \"$file\" 2>/dev/null; done",
                "for prop in ro.build.version.release ro.build.version.incremental ro.build.version.security_patch ro.build.id ro.build.display.id ro.product.board ro.board.platform ro.hardware ro.boot.hardware ro.product.cpu.abi ro.product.cpu.abilist; do printf '%s=' \"$prop\"; getprop \"$prop\"; done",
                "dumpsys package com.byd.avc 2>/dev/null | grep -E 'versionName=|versionCode=|longVersionCode=|firstInstallTime=|lastUpdateTime=|enabled=' | head -n 20"
        };
        assertArrayEquals(expected, DiagnosticLogExporter.fixedCommands());

        DiagnosticLogExporter.CommandRunner runner = command -> {
            commands.add(command);
            if (commands.size() == 1) {
                return DiagnosticLogExporter.CommandResult.success("helper line");
            }
            if (commands.size() == 2) {
                return DiagnosticLogExporter.CommandResult.failure(
                        "partial camera", "shell_exit_1", 1);
            }
            return missing();
        };

        File archive = DiagnosticLogExporter.export(
                temporary.newFolder("cache-adb"),
                new DiagnosticLogExporter.Snapshot(Collections.emptyList()),
                identity(),
                runner,
                3000L);

        assertArrayEquals(expected, commands.toArray(new String[0]));
        assertEquals("partial camera", readEntry(archive, "system/bydturnguard_camera.log"));
        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains("\"schema_version\":1"));
        assertTrue(manifest.contains("\"version_name\":\"0.47.0\""));
        assertTrue(manifest.contains("\"version_code\":62"));
        assertTrue(manifest.contains("\"manufacturer\":\"BYD\""));
        assertTrue(manifest.contains("\"status\":\"partial\""));
        assertTrue(manifest.contains("\"error\":\"shell_exit_1\""));
        assertFalse(manifest.contains("serial"));
        assertFalse(manifest.contains("android_id"));
        assertFalse(manifest.contains("fingerprint"));
    }

    @Test
    public void recordsIndependentCollectorFailureStatuses() throws Exception {
        List<String> commands = new ArrayList<>();
        DiagnosticLogExporter.CommandRunner runner = command -> {
            int index = commands.size();
            commands.add(command);
            switch (index) {
                case 0:
                    return DiagnosticLogExporter.CommandResult.success("helper");
                case 1:
                    return DiagnosticLogExporter.CommandResult.success("");
                case 2:
                    return DiagnosticLogExporter.CommandResult.failure(
                            "partial logcat", "shell_exit_1", 1);
                case 3:
                    return DiagnosticLogExporter.CommandResult.failure(
                        "", "shell_exit_13", 13);
                default:
                    return DiagnosticLogExporter.CommandResult.failure(
                            "", "shell_exit_1", 1);
            }
        };

        File archive = DiagnosticLogExporter.export(
                temporary.newFolder("cache-statuses"),
                new DiagnosticLogExporter.Snapshot(Collections.emptyList()),
                identity(),
                runner,
                5000L);

        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains(
                "\"entry\":\"system/bydturnguard_helper.log\",\"status\":\"included\""));
        assertTrue(manifest.contains(
                "\"entry\":\"system/bydturnguard_camera.log\",\"status\":\"missing\""));
        assertTrue(manifest.contains(
                "\"entry\":\"system/logcat-all.txt\",\"status\":\"partial\""));
        assertTrue(manifest.contains(
                "\"entry\":\"system/logcat-crash-threadtime.txt\",\"status\":\"permission_denied\""));
        assertTrue(manifest.contains(
                "\"entry\":\"system/dropbox-crash.txt\",\"status\":\"error\""));
        assertTrue(manifest.contains("\"error\":\"shell_exit_13\""));
        assertEquals(DiagnosticLogExporter.fixedCommands().length, commands.size());
    }

    @Test
    public void fullLogcatStreamsPastFormerFourMegabyteLimit() throws Exception {
        List<String> streamed = new ArrayList<>();
        byte[] block = new byte[64 * 1024];
        File archive = DiagnosticLogExporter.export(
                temporary.newFolder("cache-full-logcat"),
                new DiagnosticLogExporter.Snapshot(Collections.emptyList()),
                identity(),
                command -> missing(),
                (command, output) -> {
                    streamed.add(command);
                    for (int i = 0; i < 65; i++) output.write(block);
                    return DiagnosticLogExporter.CommandResult.success("");
                },
                5250L);

        assertEquals(Collections.singletonList(
                "logcat -b all -v threadtime -d 2>/dev/null"), streamed);
        assertEquals(65L * block.length, entrySize(archive, "system/logcat-all.txt"));
        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains(
                "\"entry\":\"system/logcat-all.txt\",\"status\":\"included\""));
        assertTrue(manifest.contains("\"archived_bytes\":" + 65L * block.length));
    }

    @Test
    public void fixedCollectorsStayAllowlistedAndKeepOtherArtifactsBounded() {
        for (String command : DiagnosticLogExporter.fixedCommands()) {
            assertFalse(command.contains("bugreport"));
        }
        assertFalse(DiagnosticLogExporter.fixedCommands()[2].contains("tail -c"));
        assertTrue(DiagnosticLogExporter.fixedCommands()[10].contains("head -n 3"));
        assertTrue(DiagnosticLogExporter.fixedCommands()[10].contains("tail -c 524288"));
        assertTrue(DiagnosticLogExporter.fixedCommands()[11].contains("ro.product.cpu.abilist"));
        assertTrue(DiagnosticLogExporter.fixedCommands()[12].contains("com.byd.avc"));
    }

    @Test
    public void successfulExportPreservesCompatibilityZip() throws Exception {
        File cache = temporary.newFolder("cache-compatible");
        File outputDirectory = new File(cache, "shared_logs");
        assertTrue(outputDirectory.mkdirs());
        File compatibility = new File(outputDirectory, "legacy.zip");
        File oldDiagnostic = new File(
                outputDirectory, "byd-turnsignal-diagnostics-older.zip");
        write(compatibility, "legacy");
        write(oldDiagnostic, "old diagnostic");

        File archive = DiagnosticLogExporter.export(
                cache,
                new DiagnosticLogExporter.Snapshot(Collections.emptyList()),
                identity(),
                command -> missing(),
                5500L);

        assertTrue(archive.exists());
        assertTrue(compatibility.exists());
        assertFalse(oldDiagnostic.exists());
    }

    @Test
    public void removesPartAndPreservesOldZipWhenArchiveFails() throws Exception {
        File cache = temporary.newFolder("cache-failure");
        File outputDirectory = new File(cache, "shared_logs");
        assertTrue(outputDirectory.mkdirs());
        File old = new File(outputDirectory, "old.zip");
        write(old, "old");
        DiagnosticLogExporter.Snapshot broken = new DiagnosticLogExporter.Snapshot(
                Collections.singletonList(null));

        try {
            DiagnosticLogExporter.export(cache, broken, identity(), command -> missing(), 4000L);
            fail("Expected export failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unable to create"));
        }

        assertTrue(old.exists());
        File[] parts = outputDirectory.listFiles(file -> file.getName().endsWith(".part"));
        assertNotNull(parts);
        assertEquals(0, parts.length);
    }

    private static DiagnosticLogExporter.Identity identity() {
        return new DiagnosticLogExporter.Identity(
                "com.byd.turnsignalguard.capture",
                "0.47.0",
                62,
                "BYD",
                "SL06",
                "DiLink",
                "tablet",
                "10",
                29);
    }

    private static DiagnosticLogExporter.CommandResult missing() {
        return DiagnosticLogExporter.CommandResult.failure(
                "", "authorization_required", -1);
    }

    private static void write(File file, String value) throws IOException {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readEntry(File archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull("Missing ZIP entry " + name, entry);
            try (InputStream input = zip.getInputStream(entry);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static long entrySize(File archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull("Missing ZIP entry " + name, entry);
            return entry.getSize();
        }
    }
}
