package com.byd.extend;

import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Observes only our active operations; never changes their deadlines or lifecycle. */
final class CriticalOperationTrace implements AutoCloseable {
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<Integer, CriticalOperationTrace> ACTIVE = new LinkedHashMap<>();
    private static final LinkedHashMap<Integer, Long> REPORTED = new LinkedHashMap<>();
    private static ScheduledExecutorService observer;
    private static ScheduledFuture<?> pending;
    private static final ThreadLocal<CriticalOperationTrace> CURRENT = new ThreadLocal<>();
    private final Thread thread = Thread.currentThread();
    private final int tid = Process.myTid();
    private final long started = SystemClock.elapsedRealtime(), revision = DiagnosticLogPolicy.revision();
    private final BiConsumer<String, Object[]> events;
    private final Object[] context;
    private final CriticalOperationTrace previous;
    private volatile String phase;
    private volatile boolean closed;

    private CriticalOperationTrace(String phase, BiConsumer<String, Object[]> events, Object[] context) {
        this.phase = phase; this.events = events; this.context = context.clone(); previous = CURRENT.get();
    }
    static CriticalOperationTrace begin(String phase, BiConsumer<String, Object[]> events, Object... context) {
        if (!DiagnosticLogPolicy.extended()) return null;
        CriticalOperationTrace span = new CriticalOperationTrace(phase, events, context);
        synchronized (LOCK) {
            if (ACTIVE.size() >= 32 && !ACTIVE.containsKey(span.tid)) return null;
            ACTIVE.put(span.tid, span); CURRENT.set(span);
            if (observer == null) observer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "diagnostic-pauses"); t.setDaemon(true); return t;
            });
            schedule();
        }
        return span;
    }
    static void phase(String name) { CriticalOperationTrace span = CURRENT.get(); if (span != null) span.phase = name; }
    static void end(CriticalOperationTrace span) { if (span != null) span.close(); }
    @Override public void close() {
        closed = true;
        synchronized (LOCK) {
            if (ACTIVE.get(tid) == this) {
                if (previous != null && !previous.closed && previous.revision == DiagnosticLogPolicy.revision()) ACTIVE.put(tid, previous);
                else ACTIVE.remove(tid);
            }
            CURRENT.set(previous);
            if (ACTIVE.isEmpty() && pending != null) { pending.cancel(false); pending = null; }
        }
    }
    static void reset() {
        synchronized (LOCK) {
            ACTIVE.clear(); REPORTED.clear();
            if (pending != null) pending.cancel(false);
            pending = null;
        }
    }
    private static void schedule() {
        if (pending == null && !ACTIVE.isEmpty()) pending = observer.schedule(CriticalOperationTrace::sample, 100, TimeUnit.MILLISECONDS);
    }
    private static void sample() {
        ArrayList<CriticalOperationTrace> spans;
        synchronized (LOCK) { pending = null; spans = new ArrayList<>(ACTIVE.values()); }
        for (CriticalOperationTrace span : spans) {
            long now = SystemClock.elapsedRealtime();
            if (span.closed || !DiagnosticLogPolicy.extended() || span.revision != DiagnosticLogPolicy.revision() || now - span.started < 100) continue;
            synchronized (LOCK) {
                if (now - REPORTED.getOrDefault(span.tid, Long.MIN_VALUE / 2) < 5000) continue;
                REPORTED.put(span.tid, now);
                if (REPORTED.size() > 64) REPORTED.remove(REPORTED.keySet().iterator().next());
            }
            ArrayList<Object> fields = new ArrayList<>(java.util.Arrays.asList(span.context));
            java.util.Collections.addAll(fields, "operation", span.phase, "started_ms", span.started,
                    "sample_started_ms", now, "tid", span.tid, "thread", span.thread.getName(),
                    "thread_state", span.thread.getState().name());
            try {
                java.util.Collections.addAll(fields, "java_stack", java.util.Arrays.toString(span.thread.getStackTrace()));
                long readStart = SystemClock.elapsedRealtime();
                try (BufferedReader input = new BufferedReader(new FileReader("/proc/self/task/" + span.tid + "/schedstat"))) {
                    java.util.Collections.addAll(fields, "schedstat", input.readLine(), "schedstat_read_started_ms", readStart,
                            "schedstat_read_finished_ms", SystemClock.elapsedRealtime());
                } catch (Exception failure) {
                    java.util.Collections.addAll(fields, "schedstat_unavailable", com.byd.extend.remote.CameraViewingDiagnostics.describe(failure));
                }
            } catch (Exception failure) {
                java.util.Collections.addAll(fields, "sample_error", com.byd.extend.remote.CameraViewingDiagnostics.describe(failure));
            }
            java.util.Collections.addAll(fields, "sample_finished_ms", SystemClock.elapsedRealtime(), "completed_during_sample", span.closed);
            if (DiagnosticLogPolicy.extended() && span.revision == DiagnosticLogPolicy.revision()) {
                try { span.events.accept("critical_thread_pause", fields.toArray()); }
                catch (RuntimeException failure) {
                    DiagnosticEvent.camera("diagnostic_observer_delivery_failed", "operation", span.phase,
                            "error", com.byd.extend.remote.CameraViewingDiagnostics.describe(failure));
                }
            }
        }
        synchronized (LOCK) { if (DiagnosticLogPolicy.extended()) schedule(); else ACTIVE.clear(); }
    }
}
