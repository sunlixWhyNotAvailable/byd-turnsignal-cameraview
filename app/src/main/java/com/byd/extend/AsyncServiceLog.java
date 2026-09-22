package com.byd.extend;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded non-blocking submission; a single drain owns all file I/O. */
final class AsyncServiceLog {
    static final int MAX_BYTES = 1024 * 1024, MAX_RECORDS = 2048, BATCH_BYTES = 64 * 1024;
    private final Supplier<File> fileFactory;
    private final long flushDelayMs;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "service-log-io"); t.setDaemon(true); return t;
    });
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
    private int bufferedBytes, bufferedRecords;
    private long dropped;
    private boolean draining, accepting = true;
    private File file;
    private BufferedWriter writer;
    private ScheduledFuture<?> scheduledFlush;

    private static final class Entry {
        final String line;
        final int bytes;
        final boolean detail;
        final Runnable barrier;
        Entry(String line, boolean detail, Runnable barrier) {
            this.line = line; this.detail = detail; this.barrier = barrier;
            bytes = line == null ? 0 : line.getBytes(StandardCharsets.UTF_8).length + 1;
        }
    }
    AsyncServiceLog(Supplier<File> fileFactory, long flushDelayMs) {
        if (fileFactory == null) throw new IllegalArgumentException("fileFactory is null");
        this.fileFactory = fileFactory; this.flushDelayMs = Math.max(0L, flushDelayMs);
    }
    synchronized void appendRaw(String line) {
        if (!accepting || line == null) return;
        String kind = DiagnosticLogPolicy.kind(line);
        if (!DiagnosticLogPolicy.shouldPersist(kind)) return;
        String summary = policy.before(line, kind, System.nanoTime() / 1_000_000L);
        if (summary != null) enqueue(new Entry(summary, false, null));
        if (!policy.suppressed()) enqueue(new Entry(line, DiagnosticLogPolicy.isDetail(kind), null));
    }
    void appendLifecycle(String kind, long elapsedMs, Object... fields) {
        if (!DiagnosticLogPolicy.shouldPersist(kind)) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind)
                    .put("source", "helper_service").put("t_ms", elapsedMs);
            for (int i = 0; fields != null && i + 1 < fields.length; i += 2)
                event.put(String.valueOf(fields[i]), fields[i + 1]);
            appendRaw(event.toString());
        } catch (Throwable ignored) { }
    }
    private void enqueue(Entry entry) {
        // Keep JSON records intact: an oversized record is counted, never split across batches.
        if (entry.bytes > BATCH_BYTES) { dropped++; scheduleDrain(); return; }
        if (entry.line != null) {
            Iterator<Entry> candidates = queue.iterator();
            while ((bufferedBytes + entry.bytes > MAX_BYTES || bufferedRecords >= MAX_RECORDS)
                    && candidates.hasNext()) {
                Entry old = candidates.next();
                if (old.line != null && old.detail) {
                    candidates.remove(); bufferedBytes -= old.bytes; bufferedRecords--; dropped++;
                }
            }
            if (bufferedBytes + entry.bytes > MAX_BYTES || bufferedRecords >= MAX_RECORDS) {
                dropped++; scheduleDrain(); return;
            }
            bufferedBytes += entry.bytes; bufferedRecords++;
        }
        queue.add(entry); scheduleDrain();
    }
    private void scheduleDrain() {
        if (!draining) { draining = true; worker.execute(this::drain); }
    }
    synchronized void flush(Runnable completion) {
        if (!accepting) {
            if (!worker.isShutdown()) worker.execute(() -> complete(completion));
            else complete(completion);
            return;
        }
        String summary = policy.finish();
        if (summary != null) enqueue(new Entry(summary, false, null));
        enqueue(new Entry(null, false, completion == null ? () -> { } : completion));
    }
    synchronized void close() {
        if (!accepting) return;
        String summary = policy.finish();
        if (summary != null) enqueue(new Entry(summary, false, null));
        accepting = false; scheduleDrain();
    }
    private void drain() {
        while (true) {
            List<Entry> batch = new ArrayList<>();
            long lost;
            boolean closing;
            synchronized (this) {
                int bytes = 0;
                while (!queue.isEmpty()) {
                    Entry next = queue.peek();
                    if (!batch.isEmpty() && bytes + next.bytes > BATCH_BYTES) break;
                    batch.add(queue.remove()); bytes += next.bytes;
                    if (next.barrier != null || bytes >= BATCH_BYTES) break;
                }
                lost = dropped; dropped = 0;
                closing = !accepting;
                if (batch.isEmpty() && lost == 0) draining = false;
            }
            if (batch.isEmpty() && lost == 0) {
                if (closing) { cancelScheduledFlush(); closeWriter(); worker.shutdown(); }
                return;
            }
            // Counters include the in-flight batch until I/O completes. No I/O under the lock.
            boolean failed = false;
            if (lost != 0 && !write("{\"kind\":\"log_records_dropped\",\"count\":" + lost + "}")) {
                synchronized (this) { dropped += lost; } failed = true;
            }
            for (Entry entry : batch) {
                if (entry.barrier != null) { cancelScheduledFlush(); flushOnWorker(); complete(entry.barrier); }
                else {
                    if (!write(entry.line)) { synchronized (this) { dropped++; } failed = true; }
                    synchronized (this) { bufferedBytes -= entry.bytes; bufferedRecords--; }
                }
            }
            if (failed) {
                synchronized (this) {
                    if (!queue.isEmpty()) continue;
                    // A later append/flush retries the retained counter; never spin on failed storage.
                    draining = false; closing = !accepting;
                }
                if (closing) { cancelScheduledFlush(); closeWriter(); worker.shutdown(); }
                return;
            }
        }
    }
    private boolean write(String line) {
        try {
            ensureWriter(); writer.write(line); writer.newLine();
            if (scheduledFlush == null || scheduledFlush.isDone())
                scheduledFlush = worker.schedule(this::flushOnWorker, flushDelayMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable ignored) { closeWriter(); return false; }
    }
    private static void complete(Runnable completion) {
        try { if (completion != null) completion.run(); } catch (Throwable ignored) { }
    }
    private void ensureWriter() throws Exception {
        if (writer != null && file != null && !file.exists()) closeWriter();
        if (writer != null) return;
        file = fileFactory.get();
        if (file == null) throw new IllegalStateException("log file unavailable");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IllegalStateException("log directory unavailable");
        writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
    }
    private void flushOnWorker() {
        scheduledFlush = null;
        if (writer == null) return;
        try { writer.flush(); } catch (Throwable ignored) { closeWriter(); }
    }
    private void cancelScheduledFlush() {
        if (scheduledFlush != null) scheduledFlush.cancel(false);
        scheduledFlush = null;
    }
    private void closeWriter() {
        if (writer == null) return;
        try { writer.close(); } catch (Throwable ignored) { }
        writer = null;
    }
    synchronized int bufferedBytesForTest() { return bufferedBytes; }
    synchronized int bufferedRecordsForTest() { return bufferedRecords; }
}
