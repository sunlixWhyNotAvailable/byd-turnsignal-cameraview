package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AsyncServiceLogTest {
    @Test
    public void rawAndLifecycleLinesFlushInSubmissionOrder() throws Exception {
        File directory = Files.createTempDirectory("async-service-log").toFile();
        File output = new File(directory, "service.jsonl");
        AsyncServiceLog log = new AsyncServiceLog(() -> output, 60_000L);
        CountDownLatch flushed = new CountDownLatch(1);

        log.appendRaw("{\"kind\":\"raw\"}");
        log.appendLifecycle("service_test", 42L, "enabled", true, "count", 3);
        log.flush(flushed::countDown);

        assertTrue(flushed.await(3, TimeUnit.SECONDS));
        List<String> lines = Files.readAllLines(output.toPath());
        assertEquals(2, lines.size());
        assertEquals("raw", new JSONObject(lines.get(0)).getString("kind"));
        JSONObject lifecycle = new JSONObject(lines.get(1));
        assertEquals("service_test", lifecycle.getString("kind"));
        assertEquals("helper_service", lifecycle.getString("source"));
        assertEquals(42L, lifecycle.getLong("t_ms"));
        assertTrue(lifecycle.getBoolean("enabled"));
        assertEquals(3, lifecycle.getInt("count"));
        log.close();
    }
}
