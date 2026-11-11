package com.byd.extend;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AsyncServiceLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static void flush(AsyncServiceLog log) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        log.flush(done::countDown);
        assertTrue("real writer completed", done.await(5, TimeUnit.SECONDS));
    }

    @Test public void realWriterFlushesBothTelemetrySummariesAndPreservesChangesAndCauses() throws Exception {
        File file = temporary.newFile("telemetry.jsonl");
        boolean previous = DiagnosticLogPolicy.extended();
        DiagnosticLogPolicy.configure(true);
        AsyncServiceLog log = new AsyncServiceLog(() -> file, 0);
        try {
            log.appendLifecycle("vehicle_state", 1, "valid", true, "blink", 0, "speed_kph", 0);
            log.appendLifecycle("reverse_steering_state", 2, "valid", true, "angle_degrees", 0, "observed_ms", 2);
            log.appendLifecycle("vehicle_state", 3, "valid", true, "blink", 0, "speed_kph", 0);
            log.appendLifecycle("reverse_steering_state", 4, "valid", true, "angle_degrees", 0, "observed_ms", 4);
            flush(log);
            java.util.List<org.json.JSONObject> records = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(file.toPath())) records.add(new org.json.JSONObject(line));
            assertEquals("two full states and their two flush summaries",4,records.size());
            assertEquals("vehicle_state",records.get(2).getString("event"));
            assertEquals(1,records.get(2).getInt("count"));
            assertEquals("reverse_steering_state",records.get(3).getString("event"));
            assertEquals(4,records.get(3).getLong("last_observed_ms"));
            log.appendLifecycle("vehicle_state", 5, "valid", true, "blink", 1, "speed_kph", 0);
            log.appendLifecycle("reverse_steering_state", 6, "valid", false, "error", "OEM -1", "stack", "read -> failed");
            log.appendLifecycle("reverse_steering_state", 7, "valid", false, "error", "OEM -2", "stack", "read -> denied");
            flush(log);
            java.util.List<String> saved = Files.readAllLines(file.toPath());
            assertEquals(1,new org.json.JSONObject(saved.get(4)).getInt("blink"));
            assertEquals("OEM -1",new org.json.JSONObject(saved.get(5)).getString("error"));
            assertEquals("read -> denied",new org.json.JSONObject(saved.get(6)).getString("stack"));
            DiagnosticLogPolicy.configure(false); DiagnosticLogPolicy.configure(true);
            log.appendLifecycle("vehicle_state", 8, "valid", true, "blink", 1, "speed_kph", 0);
            flush(log);
            assertEquals(8,Files.readAllLines(file.toPath()).size());
        } finally { log.close(); DiagnosticLogPolicy.configure(previous); }
    }

    @Test public void realFileFailureIsRetainedWhenStorageBecomesWritable() throws Exception {
        File parent = temporary.newFile("blocked-parent");
        File file = new File(parent, "helper-service-test.jsonl");
        AsyncServiceLog log = new AsyncServiceLog(() -> file, 0);
        try {
            log.appendRaw("{\"kind\":\"session_started\"}");
            flush(log);
            assertFalse(file.exists());
            Files.delete(parent.toPath());
            Files.createDirectory(parent.toPath());
            log.appendRaw("{\"kind\":\"session_closed\"}");
            flush(log);
            String saved = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(saved.contains("log_writer_failed"));
            assertTrue(saved.contains("java.lang.IllegalStateException"));
            assertTrue(saved.contains("log directory unavailable"));
            assertTrue(saved.contains("AsyncServiceLog.ensureWriter"));
            assertTrue(saved.contains("session_closed"));
        } finally { log.close(); }
    }

    @Test public void ordinaryFileDropsDetailsAndKeepsStateChangesAndErrors() throws Exception {
        File file = temporary.newFile("helper-service-test.jsonl");
        boolean previous = DiagnosticLogPolicy.extended();
        DiagnosticLogPolicy.configure(false);
        AsyncServiceLog log = new AsyncServiceLog(() -> file, 0);
        try {
            log.appendLifecycle("camera_fps_window_stats", 1, "fps", 25);
            log.appendLifecycle("app_permission_readback", 2, "permission", "wss", "granted", true);
            log.appendLifecycle("app_permission_readback", 3, "permission", "wss", "granted", true);
            log.appendLifecycle("app_permission_readback", 4, "permission", "wss", "granted", false);
            log.appendLifecycle("camera_source_command_failed", 5, "error", "native status -110");
            flush(log);
            String saved = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(saved.contains("camera_fps_window_stats"));
            assertEquals(3, saved.lines().count());
            assertTrue(saved.contains("native status -110"));
            assertTrue(saved.contains("\"granted\":false"));
        } finally { log.close(); DiagnosticLogPolicy.configure(previous); }
    }

    @Test public void distanceSourcesPersistBothMeasurementsOnlyInExtendedLocalLog() throws Exception {
        File directory=temporary.newFolder("captures");
        File file=new File(directory,"helper-service-distance.jsonl");
        DistanceSources sources=new DistanceSources();
        double[] values=new double[8]; java.util.Arrays.fill(values,81);
        sources.adas(new DistanceFrame(1,100,true,"ready",values));
        ParkingRadarState radar=new ParkingRadarState(); radar.begin(0,1); radar.registered(0,1);
        radar.callback(0x99000062,65,100,1); sources.radar(radar.snapshot());
        boolean previous=DiagnosticLogPolicy.extended();
        AsyncServiceLog log=new AsyncServiceLog(()->file,0);
        try {
            DiagnosticLogPolicy.configure(false);
            log.appendLifecycle("distance_sources_detail",100,sources.diagnostic(sources.frame(100)));
            flush(log); assertFalse(file.exists());
            DiagnosticLogPolicy.configure(true);
            log.appendLifecycle("distance_sources_detail",101,sources.diagnostic(sources.frame(101)));
            flush(log);
            String saved=new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();
            org.json.JSONObject event=new org.json.JSONObject(saved);
            org.json.JSONObject front=event.getJSONArray("sectors").getJSONObject(0);
            assertEquals(81,front.getDouble("adas_cm"),0);
            assertEquals(65,front.getDouble("radar_cm"),0);
            assertEquals(65,front.getDouble("selected_cm"),0);
            assertEquals("radar",front.getString("selected_source"));
            assertEquals(16,event.getJSONArray("radar_raw").length());
            assertEquals(100,event.getLong("adas_received_ms"));
            assertEquals(1,DiagnosticLogExporter.snapshot(directory).sources.size());
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)) {
                DiagnosticLogExporter.writeAppLogs(zip,DiagnosticLogExporter.snapshot(directory),
                        new java.util.ArrayList<>(),new DiagnosticLogExporter.ProgressTracker(null));
            }
            try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                assertEquals("logs/helper-service-distance.jsonl",zip.getNextEntry().getName());
                java.io.ByteArrayOutputStream entry=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[1024]; int count;
                while((count=zip.read(buffer))!=-1) entry.write(buffer,0,count);
                assertEquals(saved,entry.toString("UTF-8").trim());
            }
        } finally { log.close(); DiagnosticLogPolicy.configure(previous); }
    }
}
