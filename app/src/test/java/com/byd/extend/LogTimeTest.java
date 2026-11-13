package com.byd.extend;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real formatting and a real file writer; no clock or writer results are fabricated. */
public class LogTimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static long parse(String iso) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(iso).getTime();
    }

    @Test public void lineGetsHumanTimeFromItsOwnWallClockFirst() throws Exception {
        long own = 1794567740411L;
        JSONObject fromWallTimeMs = new JSONObject(LogTime.withWallTime(
                "{\"kind\":\"adb_foreground_started\",\"t_ms\":5,\"wall_time_ms\":" + own + "}", 1L));
        assertEquals(own, parse(fromWallTimeMs.getString("wall_time")));
        assertEquals(5, fromWallTimeMs.getLong("t_ms"));
        assertEquals("adb_foreground_started", fromWallTimeMs.getString("kind"));

        JSONObject fromWallMs = new JSONObject(LogTime.withWallTime(
                "{\"kind\":\"cellular_recovery\",\"wall_ms\":" + own + "}", 1L));
        assertEquals(own, parse(fromWallMs.getString("wall_time")));

        JSONObject fallback = new JSONObject(LogTime.withWallTime(
                "{\"kind\":\"service_create\",\"t_ms\":7}", own));
        assertEquals(own, parse(fallback.getString("wall_time")));
    }

    @Test public void existingTimeAndNonJsonLinesStayUnchanged() {
        String stamped = "{\"kind\":\"x\",\"wall_time\":\"2026-11-13T16:02:20.411+05:00\"}";
        assertSame(stamped, LogTime.withWallTime(stamped, 1L));
        String handshake = "READY pid=21474 protocol=11 build=127";
        assertSame(handshake, LogTime.withWallTime(handshake, 1L));
        assertTrue(LogTime.fileStamp(1794567740411L).matches("\\d{8}-\\d{6}"));
    }

    @Test public void realServiceLogWritesHumanTimeForLifecycleEvents() throws Exception {
        File file = temporary.newFile("helper-service-time.jsonl");
        AsyncServiceLog log = new AsyncServiceLog(() -> file, 0);
        long before = System.currentTimeMillis();
        try {
            log.appendLifecycle("service_create", 1, "reason", "service_create");
            CountDownLatch done = new CountDownLatch(1);
            log.flush(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally { log.close(); }
        long after = System.currentTimeMillis();
        JSONObject saved = new JSONObject(new String(Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim());
        long written = parse(saved.getString("wall_time"));
        assertTrue(written >= before - 1 && written <= after + 1);
        assertEquals("service_create", saved.getString("reason"));
    }
}
