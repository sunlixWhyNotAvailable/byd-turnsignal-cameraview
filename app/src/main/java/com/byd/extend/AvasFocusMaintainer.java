package com.byd.extend;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Session-owned periodic audio-focus reassertion with synchronous shutdown. */
final class AvasFocusMaintainer implements AutoCloseable {
    static final long PERIOD_MILLIS = 120;
    private static final int MAX_DETAIL_REPORTS = 3;

    interface Requester { int request() throws Exception; }
    interface Reporter { void report(Report report); }
    interface ScheduledTask { void cancel(); }
    interface Scheduler extends AutoCloseable {
        ScheduledTask schedule(Runnable task, long initialDelayMillis, long periodMillis);
        @Override void close();
    }

    static final class Report {
        final String phase;
        final int result;
        final long successes;
        final long failures;
        final String error;

        Report(String phase, int result, long successes, long failures, String error) {
            this.phase = phase;
            this.result = result;
            this.successes = successes;
            this.failures = failures;
            this.error = error;
        }
    }

    private final Object callLock = new Object();
    private final Requester requester;
    private final Reporter reporter;
    private final Scheduler scheduler;
    private final int expectedResult;
    private ScheduledTask task;
    private boolean started;
    private boolean active;
    private boolean closed;
    private int lastResult;
    private int detailReports;
    private long successes;
    private long failures;

    AvasFocusMaintainer(Requester requester, int expectedResult, Reporter reporter) {
        this(requester, expectedResult, reporter, new ExecutorScheduler());
    }

    AvasFocusMaintainer(Requester requester, int expectedResult, Reporter reporter,
            Scheduler scheduler) {
        this.requester = requester;
        this.expectedResult = expectedResult;
        this.lastResult = expectedResult;
        this.reporter = reporter;
        this.scheduler = scheduler;
    }

    void start() {
        synchronized (callLock) {
            if (started || closed) return;
            started = true;
            active = true;
            task = scheduler.schedule(this::tick, PERIOD_MILLIS, PERIOD_MILLIS);
        }
    }

    private void tick() {
        synchronized (callLock) {
            if (!active) return;
            try {
                int result = requester.request();
                if (result == expectedResult) successes++;
                else failures++;
                if (result != lastResult && detailReports < MAX_DETAIL_REPORTS) {
                    detailReports++;
                    reporter.report(new Report("changed", result, successes, failures, ""));
                }
                lastResult = result;
            } catch (Exception failure) {
                failures++;
                if (detailReports < MAX_DETAIL_REPORTS) {
                    detailReports++;
                    reporter.report(new Report("failure", Integer.MIN_VALUE, successes, failures,
                            failure.toString()));
                }
            }
        }
    }

    @Override
    public void close() {
        ScheduledTask owned;
        Report summary;
        synchronized (callLock) {
            if (closed) return;
            closed = true;
            active = false;
            owned = task;
            summary = new Report("summary", lastResult, successes, failures, "");
        }
        if (owned != null) owned.cancel();
        scheduler.close();
        reporter.report(summary);
    }

    private static final class ExecutorScheduler implements Scheduler {
        private final ScheduledExecutorService executor;

        ExecutorScheduler() {
            ThreadFactory factory = task -> {
                Thread thread = new Thread(task, "avas-focus-maintainer");
                thread.setDaemon(true);
                return thread;
            };
            executor = Executors.newSingleThreadScheduledExecutor(factory);
        }

        @Override public ScheduledTask schedule(Runnable task, long initialDelayMillis,
                long periodMillis) {
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(task, initialDelayMillis,
                    periodMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override public void close() {
            executor.shutdown();
            boolean interrupted = false;
            try {
                while (!executor.isTerminated()) {
                    try {
                        if (executor.awaitTermination(1, TimeUnit.SECONDS)) break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
