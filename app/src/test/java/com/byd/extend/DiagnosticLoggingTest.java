package com.byd.extend;

import org.junit.After;
import org.junit.Test;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class DiagnosticLoggingTest {
    @After public void resetPolicy() { DiagnosticLogPolicy.configure(false); }

    @Test public void detailOffPreservesFunctionalDeliveryAndEssentialRecords() {
        DiagnosticLogPolicy.configure(false);
        for (String kind : new String[]{"vehicle_state", "parking_radar_snapshot", "parking_radar_state",
                "camera_overlay_frame", "reverse_overlay_frame", "music_metadata_publish",
                "music_journal_snapshot", "lifetime_counters"}) {
            assertFalse(kind, DiagnosticLogPolicy.shouldPersist(kind));
            assertTrue(kind, DiagnosticLogPolicy.shouldProduce(kind));
        }
        assertFalse(DiagnosticLogPolicy.shouldProduce("telemetry_sample"));
        assertTrue(DiagnosticLogPolicy.shouldPersist("correction_requested"));
        assertTrue(DiagnosticLogPolicy.shouldPersist("camera_error"));
        DiagnosticLogPolicy.configure(true);
        assertTrue(DiagnosticLogPolicy.shouldProduce("telemetry_sample"));
        assertTrue(DiagnosticLogPolicy.shouldPersist("vehicle_state"));
    }

    @Test public void boundedQueueIncludesBlockedInFlightAndEvictsDetailFirst() throws Exception {
        DiagnosticLogPolicy.configure(true);
        File file = Files.createTempFile("bounded-log", ".jsonl").toFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AsyncServiceLog log = new AsyncServiceLog(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timeout"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            return file;
        }, 60_000);
        log.appendRaw("{\"kind\":\"first\"}");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        String detail = "{\"kind\":\"telemetry_sample\",\"payload\":\"" + repeat('x', 60_000) + "\"}";
        for (int i = 0; i < 100; i++) log.appendRaw(detail);
        log.appendRaw("{\"kind\":\"last_essential\"}");
        assertTrue(log.bufferedBytesForTest() <= AsyncServiceLog.MAX_BYTES);
        assertTrue(log.bufferedRecordsForTest() <= AsyncServiceLog.MAX_RECORDS);
        assertTrue(log.bufferedRecordsForTest() > 1);
        CountDownLatch done = new CountDownLatch(1);
        log.flush(done::countDown);
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        log.close();
        List<String> lines = Files.readAllLines(file.toPath());
        assertEquals("first", DiagnosticLogPolicy.kind(lines.get(0)));
        assertEquals("last_essential", DiagnosticLogPolicy.kind(lines.get(lines.size() - 1)));
        assertTrue(lines.stream().anyMatch(line -> line.contains("log_records_dropped")));
    }

    @Test public void recordBoundOversizeAndCloseKeepAcceptedOrder() throws Exception {
        File file = Files.createTempFile("record-bound", ".jsonl").toFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AsyncServiceLog log = new AsyncServiceLog(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return file;
        }, 60_000);
        log.appendRaw("{\"kind\":\"first\"}");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        for (int i = 0; i < 3000; i++) log.appendRaw("{\"kind\":\"essential\",\"n\":" + i + "}");
        log.appendRaw(repeat('x', AsyncServiceLog.MAX_BYTES + 1));
        assertEquals(AsyncServiceLog.MAX_RECORDS, log.bufferedRecordsForTest());
        log.close();
        log.appendRaw("{\"kind\":\"after_close\"}");
        CountDownLatch done = new CountDownLatch(1);
        log.flush(done::countDown);
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        List<String> lines = Files.readAllLines(file.toPath());
        int previous = -1;
        for (String line : lines) {
            JSONObject value = new JSONObject(line);
            if (value.has("n")) assertEquals(++previous, value.getInt("n"));
            assertNotEquals("after_close", value.getString("kind"));
        }
        assertEquals(2046, previous);
    }

    @Test public void recordSizeIsBoundedByBatchIncludingUtf8AndNewline() throws Exception {
        File file = Files.createTempFile("batch-bound", ".jsonl").toFile();
        AsyncServiceLog log = new AsyncServiceLog(() -> file, 60_000);
        String prefix = "{\"kind\":\"essential\",\"payload\":\"Київ";
        String suffix = "\"}";
        int overhead = (prefix + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
        String exact = prefix + repeat('x', AsyncServiceLog.BATCH_BYTES - overhead) + suffix;
        log.appendRaw(exact);
        log.appendRaw(exact + " ");
        CountDownLatch done = new CountDownLatch(1);
        log.flush(done::countDown);
        assertTrue(done.await(3, TimeUnit.SECONDS));
        log.close();
        List<String> lines = Files.readAllLines(file.toPath());
        assertTrue(lines.contains(exact));
        assertFalse(lines.contains(exact + " "));
        long dropped = 0;
        for (String line : lines) {
            assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1
                    <= AsyncServiceLog.BATCH_BYTES);
            JSONObject value = new JSONObject(line);
            if ("log_records_dropped".equals(value.getString("kind")))
                dropped += value.getLong("count");
        }
        assertEquals(1L, dropped);
    }

    @Test public void identicalErrorsAggregateWithoutDroppingFirstOrFinalSummary() throws Exception {
        File file = Files.createTempFile("repeated-errors", ".jsonl").toFile();
        AsyncServiceLog log = new AsyncServiceLog(() -> file, 60_000);
        for (int i = 0; i < 10; i++) log.appendRaw("{\"kind\":\"camera_error\",\"error\":\"same\",\"t_ms\":" + i + "}");
        CountDownLatch done = new CountDownLatch(1);
        log.flush(done::countDown);
        assertTrue(done.await(3, TimeUnit.SECONDS));
        log.close();
        List<String> lines = Files.readAllLines(file.toPath());
        assertEquals(2, lines.size());
        assertEquals("camera_error", DiagnosticLogPolicy.kind(lines.get(0)));
        assertEquals(9, new JSONObject(lines.get(1)).getInt("count"));
    }

    @Test public void helperLoggingFlagIsAuthorizedAndReplayedWithoutFilteringCallbacks() throws Exception {
        for (String name : new String[]{"TurnSignalShellMain", "CameraShellMain", "StockAvmShellMain"}) {
            String source = source(name);
            int configure = source.indexOf("TX_CONFIGURE_LOGGING");
            assertTrue(name, configure > source.indexOf("isCallerAllowed(Binder.getCallingUid()"));
            assertTrue(name, configure > source.indexOf("data.enforceInterface("));
            assertTrue(name, source.contains("enabled != 0 && enabled != 1"));
            assertTrue(name, source.contains("DiagnosticLogPolicy.configure(enabled == 1)"));
        }
        String controller = source("TurnSignalController");
        for (String protocol : new String[]{"TurnSignalShellProtocol", "CameraShellProtocol", "StockAvmShellProtocol"}) {
            assertTrue(protocol, controller.contains("transactLogging(value, " + protocol + ".DESCRIPTOR"));
            assertTrue(protocol, controller.contains(protocol + ".TX_CONFIGURE_LOGGING"));
        }
        assertTrue(source("CameraHelperService").contains("helper.configureLogging()"));
        assertTrue(source("TurnSignalGuardApplication").contains("DiagnosticLogPolicy.PREF_ENABLED, false"));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/byd/extend/" + name + ".java")), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count]; java.util.Arrays.fill(chars, value); return new String(chars);
    }
}
