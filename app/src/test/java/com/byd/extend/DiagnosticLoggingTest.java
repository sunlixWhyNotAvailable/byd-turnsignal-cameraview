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

    @Test public void batchTargetDoesNotRejectWholeUtf8Records() throws Exception {
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
        assertTrue(lines.contains(exact + " "));
        assertEquals(2, lines.size());
    }

    @Test public void essentialByteReserveIncludesDetailAlreadyBeingWritten() throws Exception {
        DiagnosticLogPolicy.configure(true);
        File file = Files.createTempFile("byte-reserve", ".jsonl").toFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AsyncServiceLog log = blockedLog(file, entered, release);
        String detail = sizedRecord("telemetry_sample",
                AsyncServiceLog.MAX_BYTES - AsyncServiceLog.ESSENTIAL_RESERVE_BYTES);
        log.appendRaw(detail);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        log.appendRaw("{\"kind\":\"telemetry_sample\"}");
        String essential = sizedRecord("camera_error", AsyncServiceLog.ESSENTIAL_RESERVE_BYTES);
        log.appendRaw(essential);
        assertEquals(AsyncServiceLog.MAX_BYTES, log.bufferedBytesForTest());
        List<String> lines = finishBlocked(log, release, file);
        assertTrue(lines.contains(detail));
        assertTrue(lines.contains(essential));
        JSONObject loss = loss(lines);
        assertEquals(1, loss.getLong("detail_count"));
        assertEquals(0, loss.getLong("essential_count"));
        assertEquals(1, loss.getLong("capacity_count"));
        assertFalse(loss.getBoolean("essential_incomplete"));
        assertEquals(AsyncServiceLog.MAX_BYTES, loss.getInt("queue_peak_bytes"));
        assertTrue(loss.getLong("to_elapsed_ms") >= loss.getLong("from_elapsed_ms"));
        assertTrue(loss.getLong("max_queue_age_ms") >= 0);
    }

    @Test public void capturedPeakEssentialRateSurvivesTenSecondsOfPausedWrites() throws Exception {
        // 23.09.2026/1 (actual 22 Sep): busiest retained source has at most
        // 46 essential records / 11,043 UTF-8 bytes per one-second bin.
        // Repeat that peak for 10 seconds with 256-byte records (11,776 bytes/s).
        DiagnosticLogPolicy.configure(true);
        File file = Files.createTempFile("capture-peak", ".jsonl").toFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AsyncServiceLog log = blockedLog(file, entered, release);
        log.appendRaw(sizedRecord("telemetry_sample",
                AsyncServiceLog.MAX_BYTES - AsyncServiceLog.ESSENTIAL_RESERVE_BYTES));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        String essential = sizedRecord("essential", 256);
        for (int i = 0; i < 46 * 10; i++) log.appendRaw(essential);
        List<String> lines = finishBlocked(log, release, file);
        assertEquals(461, lines.size());
        assertEquals(460, lines.stream().filter(essential::equals).count());
        assertFalse(lines.stream().anyMatch(line -> line.contains("log_records_dropped")));
    }

    @Test public void recordReserveProtectsEssentialEventsAndEvictsOnlyDetail() throws Exception {
        DiagnosticLogPolicy.configure(true);
        File file = Files.createTempFile("record-reserve", ".jsonl").toFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AsyncServiceLog log = blockedLog(file, entered, release);
        log.appendRaw("{\"kind\":\"first\"}");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        for (int i = 0; i < AsyncServiceLog.MAX_RECORDS - AsyncServiceLog.ESSENTIAL_RESERVE_RECORDS; i++)
            log.appendRaw("{\"kind\":\"telemetry_sample\",\"n\":" + i + "}");
        for (int i = 1; i < AsyncServiceLog.ESSENTIAL_RESERVE_RECORDS; i++)
            log.appendRaw("{\"kind\":\"essential\",\"n\":" + i + "}");
        assertEquals(AsyncServiceLog.MAX_RECORDS, log.bufferedRecordsForTest());
        log.appendRaw("{\"kind\":\"last_essential\"}");
        List<String> lines = finishBlocked(log, release, file);
        assertEquals(AsyncServiceLog.ESSENTIAL_RESERVE_RECORDS - 1,
                lines.stream().filter(line -> "essential".equals(DiagnosticLogPolicy.kind(line))).count());
        assertEquals("last_essential", DiagnosticLogPolicy.kind(lines.get(lines.size() - 1)));
        assertEquals(1, loss(lines).getLong("detail_count"));
        assertEquals(0, loss(lines).getLong("essential_count"));
    }

    @Test public void essentialExhaustionAndOversizedRecordsAreExplicit() throws Exception {
        File file = Files.createTempFile("essential-loss", ".jsonl").toFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AsyncServiceLog log = blockedLog(file, entered, release);
        String exact = sizedRecord("essential", AsyncServiceLog.MAX_BYTES);
        log.appendRaw(exact);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        log.appendRaw("{\"kind\":\"another_essential\"}");
        log.appendRaw(exact + " ");
        assertEquals(AsyncServiceLog.MAX_BYTES, log.bufferedBytesForTest());
        List<String> lines = finishBlocked(log, release, file);
        assertTrue(lines.contains(exact));
        JSONObject loss = loss(lines);
        assertEquals(2, loss.getLong("essential_count"));
        assertEquals(1, loss.getLong("capacity_count"));
        assertEquals(1, loss.getLong("oversized_count"));
        assertTrue(loss.getBoolean("essential_incomplete"));
    }

    @Test public void writeFailureAccountingSurvivesUntilStorageRecovers() throws Exception {
        File file = Files.createTempFile("storage-loss", ".jsonl").toFile();
        java.util.concurrent.atomic.AtomicBoolean available = new java.util.concurrent.atomic.AtomicBoolean();
        AsyncServiceLog log = new AsyncServiceLog(() -> {
            if (!available.get()) throw new IllegalStateException("storage unavailable");
            return file;
        }, 60_000);
        log.appendRaw("{\"kind\":\"important_event\"}");
        CountDownLatch failed = new CountDownLatch(1);
        log.flush(failed::countDown);
        assertTrue(failed.await(3, TimeUnit.SECONDS));
        available.set(true);
        log.appendRaw("{\"kind\":\"recovered\"}");
        CountDownLatch done = new CountDownLatch(1);
        log.flush(done::countDown);
        assertTrue(done.await(3, TimeUnit.SECONDS));
        log.close();
        JSONObject loss = loss(Files.readAllLines(file.toPath()));
        assertEquals(1, loss.getLong("write_failure_count"));
        assertEquals(1, loss.getLong("essential_count"));
        assertEquals(1, loss.getLong("count"));
    }

    @Test public void errorAggregationRetainsTimeRangeAndDistinctErrors() {
        DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
        String first = "{\"kind\":\"camera_error\",\"error\":\"first\"}";
        String second = "{\"kind\":\"camera_error\",\"error\":\"second\"}";
        assertNull(policy.before(first, "camera_error", 100));
        assertFalse(policy.suppressed());
        assertNull(policy.before(first, "camera_error", 125));
        assertTrue(policy.suppressed());
        JSONObject summary = new JSONObject(policy.before(second, "camera_error", 150));
        assertFalse(policy.suppressed());
        assertEquals(100, summary.getLong("first_elapsed_ms"));
        assertEquals(125, summary.getLong("last_elapsed_ms"));
        assertEquals(1, summary.getLong("count"));
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

    private static AsyncServiceLog blockedLog(File file, CountDownLatch entered, CountDownLatch release) {
        return new AsyncServiceLog(() -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test timeout"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            return file;
        }, 60_000);
    }

    private static List<String> finishBlocked(AsyncServiceLog log, CountDownLatch release, File file)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        log.flush(done::countDown);
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        log.close();
        return Files.readAllLines(file.toPath());
    }

    private static JSONObject loss(List<String> lines) {
        return lines.stream().filter(line -> "log_records_dropped".equals(DiagnosticLogPolicy.kind(line)))
                .map(JSONObject::new).findFirst().orElseThrow(() -> new AssertionError("missing loss report"));
    }

    private static String sizedRecord(String kind, int bytes) {
        String prefix = "{\"kind\":\"" + kind + "\",\"payload\":\"";
        return prefix + repeat('x', bytes - prefix.length() - 3) + "\"}";
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count]; java.util.Arrays.fill(chars, value); return new String(chars);
    }
}
