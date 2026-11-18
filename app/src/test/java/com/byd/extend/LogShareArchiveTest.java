package com.byd.extend;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Real files and ZIPs; does not simulate ADB, Binder or Sentry delivery. */
public class LogShareArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final DiagnosticLogExporter.Identity identity = new DiagnosticLogExporter.Identity(
            "com.byd.extend", "1.10.2", 132, "BYD", "tablet", "test", "device", "12", 31);

    private File export(File cache, DiagnosticLogExporter.Snapshot snapshot,
            CompatibilityBundleExporter.ExportControl control) throws Exception {
        return DiagnosticLogExporter.export(cache, snapshot, identity, null, null, 123456789L, control);
    }

    private byte[] entry(File archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive); InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    @Test public void continuousLogcatExportsRecordedBytesAndOmitsClearedFile() throws Exception {
        File cache = temporary.newFolder("cache");
        File logs = temporary.newFolder("recorder");
        File log = new File(logs, "logcat.txt");
        byte[] recorded = "recorded line\n".getBytes(StandardCharsets.UTF_8);
        Files.write(log.toPath(), recorded);
        final String entryName = "system/logcat-continuous.txt";
        for (String state : new String[]{"included", "missing", "error"}) {
            if (state.equals("missing")) {
                assertEquals(1, new ContinuousLogcatRecorder(logs, "boot", "test", null).clearStored());
            }
            // A real ENOTDIR must retain its diagnostic, even when the same file was just cleared.
            String path = state.equals("error") ? "./status.txt/logcat.txt" : "./logcat.txt";
            File archive = DiagnosticLogExporter.export(cache, DiagnosticLogExporter.snapshot(), identity, command -> {
                if (!command.contains(ContinuousLogcatRecorder.LOG_PATH)) {
                    throw new UnsupportedOperationException("Android system collector unavailable on host");
                }
                try {
                    Process process = new ProcessBuilder("sh").directory(logs).redirectErrorStream(true).start();
                    try (OutputStream script = process.getOutputStream()) {
                        script.write(command.replace(ContinuousLogcatRecorder.LOG_PATH, path)
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    int exit = process.waitFor();
                    return exit == 0 ? DiagnosticLogExporter.CommandResult.success(output)
                            : DiagnosticLogExporter.CommandResult.failure(output, null, exit);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }, 123456789L);
            try (ZipFile zip = new ZipFile(archive)) {
                org.json.JSONArray sources = new JSONObject(new String(entry(archive, "manifest.json"),
                        StandardCharsets.UTF_8)).getJSONArray("sources");
                JSONObject record = null;
                for (int i = 0; i < sources.length(); i++) {
                    if (entryName.equals(sources.getJSONObject(i).getString("entry"))) record = sources.getJSONObject(i);
                }
                assertNotNull(record);
                assertEquals(record.toString(), state, record.getString("status"));
                if (state.equals("included")) assertArrayEquals(recorded, entry(archive, entryName));
                else assertNull(zip.getEntry(entryName));
                if (state.equals("error")) assertTrue(record.toString(), record.getString("error").contains("Not a directory"));
                else assertTrue(record.isNull("error"));
            }
        }
    }

    @Test public void snapshotKeepsWholeRowsAndNextExportKeepsPreviousArchive() throws Exception {
        File logs = temporary.newFolder("logs");
        File file = new File(logs, "guard-camera-session.jsonl");
        Files.write(file.toPath(), "{\"frame\":1}\n{\"frame\":".getBytes(StandardCharsets.UTF_8));
        DiagnosticLogExporter.Snapshot snapshot = DiagnosticLogExporter.snapshot(logs);
        Files.write(file.toPath(), "{\"frame\":1}\n{\"frame\":2}\n".getBytes(StandardCharsets.UTF_8));
        File cache = temporary.newFolder("cache");
        File first = export(cache, snapshot, null);
        assertEquals("{\"frame\":1}\n", new String(entry(first, "logs/" + file.getName()), StandardCharsets.UTF_8));
        JSONObject manifest = new JSONObject(new String(entry(first, "manifest.json"), StandardCharsets.UTF_8));
        assertEquals("partial", manifest.getJSONArray("sources").getJSONObject(0).getString("status"));
        for (int i = 1; i < manifest.getJSONArray("sources").length(); i++) {
            JSONObject source = manifest.getJSONArray("sources").getJSONObject(i);
            assertEquals("unavailable", source.getString("status"));
            assertEquals("adb_not_available", source.getString("error"));
        }
        byte[] original = Files.readAllBytes(first.toPath());
        File second = export(cache, DiagnosticLogExporter.snapshot(logs), null);
        assertNotEquals(first, second);
        assertArrayEquals(original, Files.readAllBytes(first.toPath()));
        assertEquals("{\"frame\":1}\n{\"frame\":2}\n", new String(entry(second, "logs/" + file.getName()), StandardCharsets.UTF_8));
    }

    @Test public void transferredSnapshotPreservesBytesAndPartialSourceEvidence() throws Exception {
        File logs = temporary.newFolder("logs");
        File file = new File(logs, "helper-service-session.jsonl");
        Files.write(file.toPath(), "{\"state\":\"ON\"}\nunfinished".getBytes(StandardCharsets.UTF_8));
        File original = export(temporary.newFolder("source"), DiagnosticLogExporter.snapshot(logs), null);
        File forwarded = export(temporary.newFolder("destination"), new DiagnosticLogExporter.Snapshot(original), null);
        assertArrayEquals(entry(original, "logs/" + file.getName()), entry(forwarded, "logs/" + file.getName()));
        JSONObject manifest = new JSONObject(new String(entry(forwarded, "manifest.json"), StandardCharsets.UTF_8));
        assertEquals("partial", manifest.getJSONArray("sources").getJSONObject(0).getString("status"));
        assertEquals(10, manifest.getJSONArray("sources").getJSONObject(0).getLong("dropped_tail_bytes"));
    }

    @Test public void cancelledPreparationNeverPublishesAnArchive() throws Exception {
        File cache = temporary.newFolder("cancel");
        CompatibilityBundleExporter.ExportControl control = new CompatibilityBundleExporter.ExportControl();
        control.cancel();
        assertThrows(CompatibilityBundleExporter.CancellationException.class,
                () -> export(cache, DiagnosticLogExporter.snapshot(), control));
        assertEquals(0, cache.list().length);
    }

    @Test public void uploadValidationChecksRealZipAndExactSizeLimit() throws Exception {
        File valid = temporary.newFile("valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(valid))) {
            out.putNextEntry(new ZipEntry("manifest.json")); out.write("{}".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        SentryLogUploader.validate(valid);
        File empty = temporary.newFile("empty.zip");
        try (ZipOutputStream ignored = new ZipOutputStream(new FileOutputStream(empty))) { }
        assertThrows(IOException.class, () -> SentryLogUploader.validate(empty));
        Files.write(empty.toPath(), "not a ZIP".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> SentryLogUploader.validate(empty));
        try (RandomAccessFile sized = new RandomAccessFile(valid, "rw")) { sized.setLength(39_000_000); }
        IOException error = assertThrows(IOException.class, () -> SentryLogUploader.validate(valid));
        assertTrue(error.getMessage().contains("38,999,999"));
    }

    @Test public void reportPreservesCommentWhileTitleUsesUnicodeCodePoints() throws Exception {
        String comment = "😀".repeat(101) + "\nfull diagnostic comment";
        String title = LogShareFiles.title("1.10.2", comment, "Logs");
        assertEquals("BYD Extend 1.10.2 — " + "😀".repeat(100), title);
        io.sentry.SentryEvent event = SentryLogUploader.event("operation", title, comment, false);
        assertEquals(comment, event.getExtras().get("user_comment"));
        assertEquals(title, event.getMessage().getMessage());
        assertEquals("unavailable_without_adb", event.getTag("system_logs"));
        assertEquals("BYD Extend 1.10.2 — Logs", LogShareFiles.title("1.10.2", " \n", "Logs"));
    }

    @Test public void operationRecordReopensWithoutLosingInputAndRejectsEscapedDirectory() throws Exception {
        File root = temporary.newFolder("jobs");
        String id = LogShareFiles.newId();
        File dir = LogShareFiles.directory(root, id);
        LogShareFiles.write(dir, new JSONObject().put("id", id).put("comment", "Журнали 😀").put("created_at", 123));
        JSONObject reopened = LogShareFiles.read(LogShareFiles.directory(root, id));
        assertEquals(id, reopened.getString("id"));
        assertEquals("Журнали 😀", reopened.getString("comment"));
        assertEquals(123, reopened.getLong("created_at"));
        assertFalse(new File(dir, "state.json.part").exists());
        assertThrows(IOException.class, () -> LogShareFiles.directory(root, "../outside"));
    }

    @Test public void collectorHeaderLeavesBinaryArchivePayloadUntouched() throws Exception {
        byte[] payload = { 0x50, 0x4b, 3, 4, 0, (byte) 0xff, 10 };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("12345\n".getBytes(StandardCharsets.US_ASCII)); bytes.write(payload);
        try (InputStream in = new ByteArrayInputStream(bytes.toByteArray())) {
            assertEquals(12345, LogShareWorker.readCollectorPid(in));
            assertArrayEquals(payload, in.readAllBytes());
        }
        for (String invalid : new String[] { "", "0\n", "-12\n", "999999999999\n", "123" }) {
            assertThrows(IOException.class, () -> LogShareWorker.readCollectorPid(
                    new ByteArrayInputStream(invalid.getBytes(StandardCharsets.US_ASCII))));
        }
    }
}
