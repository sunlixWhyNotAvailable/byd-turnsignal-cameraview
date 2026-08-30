package com.byd.extend;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Serial JSONL writer that never performs file work on its caller's thread. */
final class AsyncServiceLog {
    private final Supplier<File> fileFactory;
    private final long flushDelayMs;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "service-log-io");
                thread.setDaemon(true);
                return thread;
            });
    private File file;
    private BufferedWriter writer;
    private ScheduledFuture<?> scheduledFlush;
    private boolean accepting = true;

    AsyncServiceLog(Supplier<File> fileFactory, long flushDelayMs) {
        if (fileFactory == null) throw new IllegalArgumentException("fileFactory is null");
        this.fileFactory = fileFactory;
        this.flushDelayMs = Math.max(0L, flushDelayMs);
    }

    synchronized void appendRaw(String line) {
        if (!accepting || line == null) return;
        worker.execute(() -> appendOnWorker(line));
    }

    synchronized void appendLifecycle(String kind, long elapsedMs, Object... fields) {
        if (!accepting) return;
        Object[] snapshot = fields == null ? new Object[0] : fields.clone();
        worker.execute(() -> {
            try {
                JSONObject event = new JSONObject();
                event.put("kind", kind);
                event.put("source", "helper_service");
                event.put("t_ms", elapsedMs);
                for (int i = 0; i + 1 < snapshot.length; i += 2) {
                    event.put(String.valueOf(snapshot[i]), snapshot[i + 1]);
                }
                appendOnWorker(event.toString());
            } catch (Throwable ignored) {
                // Logging must not affect service recovery.
            }
        });
    }

    synchronized void flush(Runnable completion) {
        if (!accepting) {
            if (completion != null) completion.run();
            return;
        }
        worker.execute(() -> {
            cancelScheduledFlush();
            flushOnWorker();
            if (completion != null) completion.run();
        });
    }

    synchronized void close() {
        if (!accepting) return;
        accepting = false;
        worker.execute(() -> {
            cancelScheduledFlush();
            closeWriter();
        });
        worker.shutdown();
    }

    private void appendOnWorker(String line) {
        try {
            ensureWriter();
            writer.write(line);
            writer.newLine();
            if (scheduledFlush == null || scheduledFlush.isDone()) {
                scheduledFlush = worker.schedule(
                        this::flushOnWorker, flushDelayMs, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {
            closeWriter();
        }
    }

    private void ensureWriter() throws Exception {
        if (writer != null && file != null && !file.exists()) closeWriter();
        if (writer != null) return;
        file = fileFactory.get();
        if (file == null) throw new IllegalStateException("log file unavailable");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("log directory unavailable");
        }
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8));
    }

    private void flushOnWorker() {
        scheduledFlush = null;
        if (writer == null) return;
        try {
            writer.flush();
        } catch (Throwable ignored) {
            closeWriter();
        }
    }

    private void cancelScheduledFlush() {
        if (scheduledFlush != null) scheduledFlush.cancel(false);
        scheduledFlush = null;
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.flush();
        } catch (Throwable ignored) {
        }
        try {
            writer.close();
        } catch (Throwable ignored) {
        }
        writer = null;
    }
}
