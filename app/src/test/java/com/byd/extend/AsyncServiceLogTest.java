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
}
