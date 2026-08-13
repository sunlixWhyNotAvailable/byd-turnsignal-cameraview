package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class CompatibilityBundleExporterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void budgetGateEnforcesPerFileAndAggregateLimits() {
        assertTrue(CompatibilityBundleExporter.fitsBudget(4, 6, 4, 10));
        assertFalse(CompatibilityBundleExporter.fitsBudget(5, 0, 4, 10));
        assertFalse(CompatibilityBundleExporter.fitsBudget(4, 7, 4, 10));
    }

    @Test
    public void exportsAllowlistedEvidenceAndLeavesDiagnosticArchivesAlone() throws Exception {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putInt("camera_rear_left_scale", 120);
        preferences.putBoolean("reverse_dewarp_enabled", true);
        preferences.putString("adb_private_key", "must-not-export");
        preferences.putString("personal_vin", "must-not-export");

        File cache = temporary.newFolder("cache");
        File shared = new File(cache, "shared_logs");
        assertTrue(shared.mkdirs());
        File diagnostic = new File(shared, "byd-turnsignal-diagnostics-old.zip");
        Files.write(diagnostic.toPath(), new byte[]{1});

        CompatibilityBundleExporter.TextCommandRunner text = (command, limit) -> {
            if (command.startsWith("pm path")) {
                return CompatibilityBundleExporter.CommandResult.success(
                        "package:/data/app/~~abc/com.byd.avc-1/base.apk\n"
                                + "package:/data/app/~~abc/com.byd.avc-1/split_config.arm64_v8a.apk\n");
            }
            if (command.startsWith("find /system/framework")) {
                return CompatibilityBundleExporter.CommandResult.success(
                        "/vendor/framework/byd-camera.jar\n");
            }
            if (command.startsWith("find /vendor/etc")) {
                return CompatibilityBundleExporter.CommandResult.success(
                        "/vendor/etc/avm-camera.xml\n");
            }
            return CompatibilityBundleExporter.CommandResult.success("property=value\n");
        };
        CompatibilityBundleExporter.StreamCommandRunner stream = (command, output, limit) -> {
            try {
                output.write("binary\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                return CompatibilityBundleExporter.StreamResult.failure("write_failed", -1, 0);
            }
            return CompatibilityBundleExporter.StreamResult.success(0, 7);
        };

        File archive = CompatibilityBundleExporter.export(
                cache, preferences, identity(), text, stream, 1000L);

        assertTrue(archive.isFile());
        assertTrue(diagnostic.isFile());
        assertNotNull(readEntry(archive, "manifest.json"));
        assertNotNull(readEntry(archive, "app/sanitized-camera-preferences.json"));
        assertNotNull(readEntry(archive, "remote/data/app/~~abc/com.byd.avc-1/base.apk"));
        String prefs = readEntry(archive, "app/sanitized-camera-preferences.json");
        assertTrue(prefs.contains("camera_rear_left_scale"));
        assertTrue(prefs.contains("reverse_dewarp_enabled"));
        assertFalse(prefs.contains("adb_private_key"));
        assertFalse(prefs.contains("personal_vin"));
        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains("\"status\":\"included\""));
        assertTrue(manifest.contains("\"sha256\":\""));
    }

    @Test
    public void rejectsUnallowlistedPathsAndKeepsMissingRemoteFilesNonFatal() throws Exception {
        assertTrue(CompatibilityBundleExporter.isAllowedRemotePath(
                "/system/framework/framework.jar"));
        assertFalse(CompatibilityBundleExporter.isAllowedRemotePath("/sdcard/private.apk"));
        assertFalse(CompatibilityBundleExporter.isAllowedRemotePath("/system/etc/../passwd"));
        assertTrue(CompatibilityBundleExporter.isAllowedJarPath("/vendor/framework/pano.jar"));
        assertFalse(CompatibilityBundleExporter.isAllowedJarPath("/vendor/framework/random.jar"));

        File cache = temporary.newFolder("cache-missing");
        CompatibilityBundleExporter.StreamCommandRunner stream = (command, output, limit) ->
                CompatibilityBundleExporter.StreamResult.failure("shell_exit_44", 44, 0);
        File archive = CompatibilityBundleExporter.export(
                cache, new TestSharedPreferences(), identity(),
                (command, limit) -> CompatibilityBundleExporter.CommandResult.success(""),
                stream, 2000L);
        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains("\"status\":\"missing\""));
        assertTrue(manifest.contains("\"status\":\"included\""));
    }

    @Test
    public void preservesPartialTextAndUsesBoundedMissingPermissionCommands() throws Exception {
        String path = "/system/framework/framework.jar";
        assertEquals("(if [ ! -e '" + path + "' ]; then exit 44; "
                        + "elif [ ! -r '" + path + "' ]; then exit 13; "
                        + "else cat '" + path + "' 2>/dev/null; fi)",
                CompatibilityBundleExporter.catCommandForTest(path));

        String marker = "__TEST_EXIT__:";
        LocalAdbClient.Result missing = LocalAdbClient.parseStreamingResponseForTest(
                (marker + "44\n").getBytes(StandardCharsets.US_ASCII), marker,
                new ByteArrayOutputStream(), 0L);
        assertFalse(missing.ok);
        assertEquals(44, missing.exitCode);
        assertEquals("shell_exit_44", missing.error);
        LocalAdbClient.Result denied = LocalAdbClient.parseStreamingResponseForTest(
                (marker + "13\n").getBytes(StandardCharsets.US_ASCII), marker,
                new ByteArrayOutputStream(), 0L);
        assertFalse(denied.ok);
        assertEquals(13, denied.exitCode);
        assertEquals("shell_exit_13", denied.error);

        File cache = temporary.newFolder("cache-partial-text");
        final long[] observedLimit = {Long.MAX_VALUE};
        CompatibilityBundleExporter.TextCommandRunner text = (command, limit) -> {
            observedLimit[0] = Math.min(observedLimit[0], limit);
            return CompatibilityBundleExporter.CommandResult.failure(
                    "partial stdout", "shell_exit_7", 7);
        };
        File archive = CompatibilityBundleExporter.export(
                cache, new TestSharedPreferences(), identity(), text,
                (command, output, limit) ->
                        CompatibilityBundleExporter.StreamResult.failure("shell_exit_44", 44, 0),
                3000L);
        String manifest = readEntry(archive, "manifest.json");
        assertEquals(CompatibilityBundleExporter.MAX_TEXT_BYTES, observedLimit[0]);
        assertEquals("partial stdout", readEntry(archive, "device/properties-0.txt"));
        assertTrue(manifest.contains("\"status\":\"partial\""));
        assertTrue(manifest.contains("\"status\":\"missing\""));

        CompatibilityBundleExporter.CommandResult mapped =
                CompatibilityBundleExporter.fromAdbResult(LocalAdbClient.Result.failed(
                        "shell_exit_9", "mapped partial", 9, "fingerprint"));
        assertFalse(mapped.ok);
        assertEquals("mapped partial", mapped.output);
        assertEquals(9, mapped.exitCode);
    }

    @Test
    public void enforcesExactTextLimitAndStreamingLimit() throws Exception {
        String marker = "__TEST_LIMIT__:";
        byte[] response = ("1234" + marker + "7\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream exact = new ByteArrayOutputStream();
        LocalAdbClient.Result exactResult = LocalAdbClient.parseStreamingResponseForTest(
                response, marker, exact, 4L);
        assertEquals(7, exactResult.exitCode);
        assertEquals("1234", exact.toString("UTF-8"));

        ByteArrayOutputStream over = new ByteArrayOutputStream();
        LocalAdbClient.Result overResult = LocalAdbClient.parseStreamingResponseForTest(
                response, marker, over, 3L);
        assertEquals("too_large", overResult.error);
        assertEquals(0, over.size());

        char[] tooLong = new char[(int) CompatibilityBundleExporter.MAX_TEXT_BYTES + 1];
        Arrays.fill(tooLong, 'x');
        final boolean[] first = {true};
        File archive = CompatibilityBundleExporter.export(
                temporary.newFolder("cache-text-limit"), new TestSharedPreferences(), identity(),
                (command, limit) -> {
                    if (!first[0]) return CompatibilityBundleExporter.CommandResult.success("");
                    first[0] = false;
                    return CompatibilityBundleExporter.CommandResult.success(new String(tooLong));
                },
                (command, output, limit) ->
                        CompatibilityBundleExporter.StreamResult.failure("shell_exit_44", 44, 0),
                4000L);
        try (ZipFile zip = new ZipFile(archive)) {
            assertEquals(CompatibilityBundleExporter.MAX_TEXT_BYTES,
                    zip.getEntry("device/properties-0.txt").getSize());
        }
        assertTrue(readEntry(archive, "manifest.json").contains("\"status\":\"too_large\""));
    }

    @Test
    public void includesAndHashesNonzeroPartialBinaryOutput() throws Exception {
        byte[] partial = "partial".getBytes(StandardCharsets.UTF_8);
        File archive = CompatibilityBundleExporter.export(
                temporary.newFolder("cache-partial-binary"), new TestSharedPreferences(), identity(),
                (command, limit) -> CompatibilityBundleExporter.CommandResult.success(""),
                (command, output, limit) -> {
                    if (!command.contains("framework.jar")) {
                        return CompatibilityBundleExporter.StreamResult.failure(
                                "shell_exit_44", 44, 0);
                    }
                    try {
                        output.write(partial);
                    } catch (IOException error) {
                        return CompatibilityBundleExporter.StreamResult.failure(
                                "write_failed", -1, 0);
                    }
                    return CompatibilityBundleExporter.StreamResult.failure(
                            "shell_exit_7", 7, partial.length);
                },
                5000L);

        String entry = "remote/system/framework/framework.jar";
        assertEquals("partial", readEntry(archive, entry));
        String manifest = readEntry(archive, "manifest.json");
        assertTrue(manifest.contains("\"entry\":\"" + entry
                + "\",\"source\":\"/system/framework/framework.jar\""
                + ",\"status\":\"partial\",\"size_bytes\":7"
                + ",\"sha256\":\"" + sha256(partial) + "\""
                + ",\"error\":\"shell_exit_7\""));
    }

    private static CompatibilityBundleExporter.Identity identity() {
        return new CompatibilityBundleExporter.Identity(
                "com.byd.turnsignalguard.capture", "0.48.6", 76,
                "BYD", "Sea Lion", "DiLink", "tablet", "10", 29);
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

    private static String sha256(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) {
            result.append(String.format("%02x", b & 0xff));
        }
        return result.toString();
    }
}
