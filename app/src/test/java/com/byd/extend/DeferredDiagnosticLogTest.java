package com.byd.extend;

import android.app.Application;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class, sdk=33)
public class DeferredDiagnosticLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @After public void reset() { DiagnosticLogPolicy.configure(false); }
    private static void flush(AsyncServiceLog log) throws Exception {
        CountDownLatch done = new CountDownLatch(1); log.flush(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
    @Test public void immutablePayloadKeepsReceiptTimeAndOrderInRealFile() throws Exception {
        File file = temporary.newFile(); AsyncServiceLog log = new AsyncServiceLog(() -> file, 0);
        try {
            int[] raw = {65, 150}; JSONObject nested = new JSONObject().put("value", 81);
            DiagnosticEvent first = new DiagnosticEvent("distance", "distance_source_ready", "raw", raw, "nested", nested);
            raw[0] = 99; nested.put("value", 90);
            log.appendEvent(first); log.appendEvent(new DiagnosticEvent("distance", "distance_source_stopped"));
            flush(log);
            java.util.List<String> lines = Files.readAllLines(file.toPath());
            JSONObject one = new JSONObject(lines.get(0)), two = new JSONObject(lines.get(1));
            assertEquals(65, one.getJSONArray("raw").getInt(0));
            assertEquals(81, one.getJSONObject("nested").getInt("value"));
            assertEquals(first.elapsedMs, one.getLong("t_ms"));
            assertEquals(first.wallMs, one.getLong("wall_time_ms"));
            assertTrue(one.getLong("event_sequence") < two.getLong("event_sequence"));
        } finally { log.close(); }
    }
    @Test public void boundedBacklogKeepsEssentialRecordAndExportsLossThenClearsOldDetailOnToggle() throws Exception {
        File file = temporary.newFile(); AsyncServiceLog log = new AsyncServiceLog(() -> file, 0);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            DiagnosticLogPolicy.configure(true);
            log.flush(() -> { blocked.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            String payload = new String(new char[8192]).replace('\0', 'x');
            for (int i = 0; i < 100; i++) log.appendEvent(new DiagnosticEvent("camera", "camera_output_phases", "payload", payload));
            log.appendEvent(new DiagnosticEvent("camera", "camera_error", "error", "native status -110", "operation", "release"));
            DiagnosticLogPolicy.configure(false); DiagnosticLogPolicy.configure(true);
            release.countDown(); flush(log);
            String saved = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(saved.contains("log_records_dropped"));
            assertTrue(saved.contains("native status -110"));
            assertFalse(saved.contains(payload));
        } finally { release.countDown(); log.close(); }
    }

    @Test public void invalidDiagnosticNumberRetainsOriginalOperationAndExactEncodingCause() throws Exception {
        DiagnosticEvent event = new DiagnosticEvent("guard", "sample_error",
                "operation", "read FID", "invalid", Double.NaN, "primary_error", "OEM status -110");
        JSONObject result = new JSONObject(event.line());
        assertEquals("event_serialization_failed", result.getString("kind"));
        assertEquals("sample_error", result.getString("original_kind"));
        assertEquals("read FID", result.getJSONObject("original_fields").getString("operation"));
        assertEquals("OEM status -110", result.getJSONObject("original_fields").getString("primary_error"));
        assertTrue(result.getString("error").contains("JSONException"));
        assertTrue(result.getJSONObject("field_errors").getString("invalid").contains("NaN"));
    }
}
