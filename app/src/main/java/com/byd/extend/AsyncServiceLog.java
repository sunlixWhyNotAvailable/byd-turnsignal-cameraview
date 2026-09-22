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
    // Detail traffic may use at most 75% of each limit, including in-flight records.
    static final int ESSENTIAL_RESERVE_BYTES = MAX_BYTES / 4, ESSENTIAL_RESERVE_RECORDS = MAX_RECORDS / 4;
    private final Supplier<File> fileFactory;
    private final long flushDelayMs;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "service-log-io"); t.setDaemon(true); return t;
    });
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
    private int bufferedBytes, bufferedRecords, detailBytes, detailRecords;
    private int peakBytes, peakRecords;
    private long maxQueueAgeMs;
    private Loss dropped;
    private boolean draining, accepting = true;
    private File file;
    private BufferedWriter writer;
    private ScheduledFuture<?> scheduledFlush;

    private static final class Entry {
        final String line;
        final int bytes;
        final boolean detail;
        final Runnable barrier;
        final long enqueuedAt = elapsedMs();
        Entry(String line, boolean detail, Runnable barrier) {
            this.line = line; this.detail = detail; this.barrier = barrier;
            bytes = line == null ? 0 : line.getBytes(StandardCharsets.UTF_8).length + 1;
        }
    }
    private static long elapsedMs() { return System.nanoTime() / 1_000_000L; }

    /** Fixed-size accounting: a failing disk cannot grow another queue of loss reports. */
    private static final class Loss {
        long detail, essential, capacity, oversized, writeFailure, from = Long.MAX_VALUE, to;
        void add(Entry entry, String reason) {
            if (entry.detail) detail++; else essential++;
            if ("capacity".equals(reason)) capacity++;
            else if ("oversized".equals(reason)) oversized++;
            else writeFailure++;
            from = Math.min(from, entry.enqueuedAt);
            to = Math.max(to, elapsedMs());
        }
        void merge(Loss other) {
            detail += other.detail; essential += other.essential;
            capacity += other.capacity; oversized += other.oversized; writeFailure += other.writeFailure;
            from = Math.min(from, other.from); to = Math.max(to, other.to);
        }
        String report(int peakBytes, int peakRecords, long maxAgeMs) {
            return "{\"kind\":\"log_records_dropped\",\"count\":" + (detail + essential)
                    + ",\"detail_count\":" + detail + ",\"essential_count\":" + essential
                    + ",\"capacity_count\":" + capacity + ",\"oversized_count\":" + oversized
                    + ",\"write_failure_count\":" + writeFailure
                    + ",\"from_elapsed_ms\":" + from + ",\"to_elapsed_ms\":" + to
                    + ",\"queue_peak_bytes\":" + peakBytes + ",\"queue_peak_records\":" + peakRecords
                    + ",\"max_queue_age_ms\":" + maxAgeMs
                    + ",\"essential_incomplete\":" + (essential != 0) + "}";
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
        if (entry.line != null) {
            // Batch size is a drain target, not a record-size limit. Never fragment JSON.
            int recordBudget = entry.detail ? MAX_BYTES - ESSENTIAL_RESERVE_BYTES : MAX_BYTES;
            if (entry.bytes > recordBudget) {
                recordLoss(entry, "oversized"); scheduleDrain(); return;
            }
            Iterator<Entry> candidates = queue.iterator();
            while (!fits(entry) && candidates.hasNext()) {
                Entry old = candidates.next();
                if (old.line != null && old.detail) {
                    candidates.remove(); release(old); recordLoss(old, "capacity");
                }
            }
            if (!fits(entry)) {
                recordLoss(entry, "capacity"); scheduleDrain(); return;
            }
            bufferedBytes += entry.bytes; bufferedRecords++;
            if (entry.detail) { detailBytes += entry.bytes; detailRecords++; }
            peakBytes = Math.max(peakBytes, bufferedBytes);
            peakRecords = Math.max(peakRecords, bufferedRecords);
        }
        queue.add(entry); scheduleDrain();
    }
    private boolean fits(Entry entry) {
        return bufferedBytes + entry.bytes <= MAX_BYTES && bufferedRecords < MAX_RECORDS
                && (!entry.detail || (detailBytes + entry.bytes <= MAX_BYTES - ESSENTIAL_RESERVE_BYTES
                && detailRecords < MAX_RECORDS - ESSENTIAL_RESERVE_RECORDS));
    }
    private void release(Entry entry) {
        bufferedBytes -= entry.bytes; bufferedRecords--;
        if (entry.detail) { detailBytes -= entry.bytes; detailRecords--; }
        maxQueueAgeMs = Math.max(maxQueueAgeMs, elapsedMs() - entry.enqueuedAt);
    }
    private void recordLoss(Entry entry, String reason) {
        if (dropped == null) dropped = new Loss();
        dropped.add(entry, reason);
        maxQueueAgeMs = Math.max(maxQueueAgeMs, elapsedMs() - entry.enqueuedAt);
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
            Loss lost;
            int peakByteSnapshot, peakRecordSnapshot;
            long maxAgeSnapshot;
            boolean closing;
            synchronized (this) {
                int bytes = 0;
                while (!queue.isEmpty()) {
                    Entry next = queue.peek();
                    if (!batch.isEmpty() && bytes + next.bytes > BATCH_BYTES) break;
                    batch.add(queue.remove()); bytes += next.bytes;
                    if (next.barrier != null || bytes >= BATCH_BYTES) break;
                }
                if (!batch.isEmpty())
                    maxQueueAgeMs = Math.max(maxQueueAgeMs, elapsedMs() - batch.get(0).enqueuedAt);
                lost = dropped; dropped = null;
                peakByteSnapshot = peakBytes; peakRecordSnapshot = peakRecords; maxAgeSnapshot = maxQueueAgeMs;
                closing = !accepting;
                if (batch.isEmpty() && lost == null) draining = false;
            }
            if (batch.isEmpty() && lost == null) {
                if (closing) { cancelScheduledFlush(); closeWriter(); worker.shutdown(); }
                return;
            }
            // Counters include the in-flight batch until I/O completes. No I/O under the lock.
            boolean failed = false;
            if (lost != null && !write(lost.report(peakByteSnapshot, peakRecordSnapshot, maxAgeSnapshot))) {
                synchronized (this) {
                    if (dropped == null) dropped = lost; else dropped.merge(lost);
                }
                failed = true;
            }
            for (Entry entry : batch) {
                if (entry.barrier != null) { cancelScheduledFlush(); flushOnWorker(); complete(entry.barrier); }
                else {
                    if (!write(entry.line)) {
                        synchronized (this) { recordLoss(entry, "write_failure"); }
                        failed = true;
                    }
                    synchronized (this) { release(entry); }
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
