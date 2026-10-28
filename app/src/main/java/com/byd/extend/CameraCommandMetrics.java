package com.byd.extend;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Bounded command timing buckets; records actual queue/execution boundaries, not frame work. */
final class CameraCommandMetrics {
    static final long REPORT_INTERVAL_NS = 5_000_000_000L;
    private static final int WORKER_SLOTS = 6; // coordinator (-1), then source workers 0..4

    enum Operation {
        HUB_INITIALIZE("hub_initialize"),
        SOURCE_ACQUIRE("source_acquire"),
        ATTACH_BATCH("attach_batch"),
        DETACH_BATCH("detach_batch"),
        ACTIVATE_TARGET("activate_target"),
        RELEASE_SOURCE("release_source"),
        HUB_CLOSE("hub_close"),
        WORKER_INITIALIZE("worker_initialize"),
        WORKER_SOURCE("worker_source_acquire"),
        WORKER_ATTACH("worker_attach"),
        WORKER_DETACH("worker_detach"),
        WORKER_ACTIVATE("worker_activate"),
        WORKER_CLOSE("worker_close");

        final String key;
        Operation(String key) { this.key = key; }
    }

    enum Phase {
        RECORDING_PREPARE("recording_prepare"),
        MAPPING_LEASE_BIND("mapping_lease_bind"),
        MESH_REQUEST("mesh_request"),
        EGL_CREATE_WINDOW_SURFACE("egl_create_window_surface"),
        TARGET_SIZE_REGISTER_CAPTURE("target_size_register_capture");

        final String key;
        Phase(String key) { this.key = key; }
    }

    enum Outcome { SUCCESS, FAILURE, QUEUED_CANCEL, POST_REJECT }

    static final class Context {
        static final Context EMPTY = new Context(-1, -1, -1, -1, false);
        final int sourceIndex, requestId, producerEpoch, surfaceIdentity;
        final boolean recordingOutput;

        Context(int sourceIndex, int requestId, int producerEpoch,
                int surfaceIdentity, boolean recordingOutput) {
            this.sourceIndex = sourceIndex;
            this.requestId = requestId;
            this.producerEpoch = producerEpoch;
            this.surfaceIdentity = surfaceIdentity;
            this.recordingOutput = recordingOutput;
        }
    }

    static final class PhaseStats {
        final Phase phase;
        final int count, failures;
        final long totalNs, maxNs;

        PhaseStats(Phase phase, int count, int failures, long totalNs, long maxNs) {
            this.phase = phase;
            this.count = count;
            this.failures = failures;
            this.totalNs = totalNs;
            this.maxNs = maxNs;
        }
    }

    static final class Report {
        final boolean detailed;
        final Operation operation;
        final int workerIndex;
        final String reason;
        final int commands, successes, failures, queuedCancels, postRejects, inlineCommands;
        final int queueSamples, executionSamples;
        final long queueTotalNs, queueMaxNs, executionTotalNs, executionMaxNs;
        final long totalNs, totalMaxNs, lastTotalNs, worstTotalNs;
        final Context lastContext, worstContext, lastFailureContext;
        final Context firstFailureContext;
        final Phase failedPhase;
        final Throwable firstFailure, lastFailure;
        final PhaseStats[] phases;

        Report(Window window, Operation operation, int workerIndex, String reason,
                boolean detailed) {
            this.detailed = detailed;
            this.operation = operation;
            this.workerIndex = workerIndex;
            this.reason = reason;
            commands = window.commands;
            successes = window.successes;
            failures = window.failures;
            queuedCancels = window.queuedCancels;
            postRejects = window.postRejects;
            inlineCommands = window.inlineCommands;
            queueSamples = window.queueSamples;
            executionSamples = window.executionSamples;
            queueTotalNs = window.queueTotalNs;
            queueMaxNs = window.queueMaxNs;
            executionTotalNs = window.executionTotalNs;
            executionMaxNs = window.executionMaxNs;
            totalNs = window.totalNs;
            totalMaxNs = window.totalMaxNs;
            lastTotalNs = window.lastTotalNs;
            worstTotalNs = window.worstTotalNs;
            lastContext = window.lastContext;
            worstContext = window.worstContext;
            lastFailureContext = window.lastFailureContext;
            firstFailureContext = window.firstFailureContext;
            failedPhase = window.failedPhase;
            firstFailure = window.firstFailure;
            lastFailure = window.lastFailure;
            phases = new PhaseStats[Phase.values().length];
            for (Phase phase : Phase.values()) {
                int i = phase.ordinal();
                if (window.phaseCounts[i] > 0) {
                    phases[i] = new PhaseStats(phase, window.phaseCounts[i],
                            window.phaseFailures[i], window.phaseTotalsNs[i],
                            window.phaseMaxNs[i]);
                }
            }
        }

        Report(Command command, Outcome outcome, Throwable failure) {
            detailed = false;
            operation = command.operation;
            workerIndex = command.workerIndex;
            reason = "command_failure";
            commands = 1;
            successes = 0;
            failures = outcome == Outcome.SUCCESS && failure == null ? 0 : 1;
            queuedCancels = outcome == Outcome.QUEUED_CANCEL ? 1 : 0;
            postRejects = outcome == Outcome.POST_REJECT ? 1 : 0;
            inlineCommands = queueSamples = executionSamples = 0;
            queueTotalNs = queueMaxNs = executionTotalNs = executionMaxNs = 0L;
            totalNs = totalMaxNs = lastTotalNs = worstTotalNs = 0L;
            lastContext = worstContext = lastFailureContext = command.context;
            firstFailureContext = command.context;
            failedPhase = command.failedPhase;
            firstFailure = lastFailure = failure;
            phases = new PhaseStats[Phase.values().length];
        }
    }

    private final Window[][] windows = new Window[Operation.values().length][WORKER_SLOTS];
    private final Consumer<Report> reporter;
    private long policyRevision = DiagnosticLogPolicy.revision();

    CameraCommandMetrics(Consumer<Report> reporter) {
        this.reporter = reporter;
        for (int operation = 0; operation < windows.length; operation++) {
            for (int worker = 0; worker < WORKER_SLOTS; worker++)
                windows[operation][worker] = new Window();
        }
    }

    Command begin(Operation operation, int workerIndex, Context context, long enqueuedNs) {
        if (operation == null || workerIndex < -1 || workerIndex > 4)
            throw new IllegalArgumentException("invalid camera command key");
        refreshPolicy();
        return new Command(this, operation, workerIndex,
                context == null ? Context.EMPTY : context, enqueuedNs,
                DiagnosticLogPolicy.extended(), DiagnosticLogPolicy.revision());
    }

    void flushWorker(int workerIndex, String reason, long nowNs) {
        if (workerIndex < 0 || workerIndex > 4)
            throw new IllegalArgumentException("invalid camera source worker");
        flush(workerIndex, reason, nowNs, true);
    }

    void flushAll(String reason, long nowNs) {
        flush(-1, reason, nowNs, false);
    }

    private void flush(int workerIndex, String reason, long nowNs, boolean resetThrottle) {
        refreshPolicy();
        boolean detailed = DiagnosticLogPolicy.extended();
        List<Report> reports = new ArrayList<>();
        synchronized (this) {
            int first = workerIndex < 0 ? 0 : workerIndex + 1;
            int last = workerIndex < 0 ? WORKER_SLOTS : workerIndex + 2;
            for (int operation = 0; operation < windows.length; operation++) {
                for (int worker = first; worker < last; worker++) {
                    Window window = windows[operation][worker];
                    if (window.commands > 0 && (detailed || window.firstFailure != null)) {
                        reports.add(new Report(window, Operation.values()[operation],
                                worker - 1, reason, detailed));
                        window.reset(resetThrottle ? Long.MIN_VALUE : nowNs);
                    } else if (window.commands > 0) {
                        window.reset(resetThrottle ? Long.MIN_VALUE : nowNs);
                    } else if (resetThrottle) {
                        window.reset(Long.MIN_VALUE);
                    }
                }
            }
        }
        publish(reports);
    }

    private void record(Command command, Outcome outcome, long endedNs,
            long queueNs, boolean queueSample, long executionNs,
            boolean executionSample, Throwable error) {
        refreshPolicy();
        if (!command.collectMetrics()) {
            if (outcome != Outcome.SUCCESS || error != null) recordFailure(command, outcome, error);
            return;
        }
        Report report = null;
        synchronized (this) {
            Window window = windows[command.operation.ordinal()][command.workerIndex + 1];
            window.record(command, outcome, endedNs, queueNs, queueSample,
                    executionNs, executionSample, error);
            if (window.lastReportNs == Long.MIN_VALUE
                    || endedNs - window.lastReportNs >= REPORT_INTERVAL_NS) {
                report = new Report(window, command.operation, command.workerIndex,
                        window.lastReportNs == Long.MIN_VALUE ? "first" : "interval", true);
                window.reset(endedNs);
            }
        }
        if (report != null) publish(report);
    }

    private void recordFailure(Command command, Outcome outcome, Throwable failure) {
        if (outcome == Outcome.SUCCESS && failure == null) return;
        refreshPolicy();
        publish(new Report(command, outcome, failure));
    }

    private void refreshPolicy() {
        long revision = DiagnosticLogPolicy.revision();
        List<Report> pending = null;
        synchronized (this) {
            if (revision == policyRevision) return;
            pending = new ArrayList<>();
            for (int operation = 0; operation < windows.length; operation++) {
                for (int worker = 0; worker < WORKER_SLOTS; worker++) {
                    Window window = windows[operation][worker];
                    if (window.commands > 0 && window.firstFailure != null) {
                        pending.add(new Report(window, Operation.values()[operation],
                                worker - 1, "policy_changed", false));
                    }
                    window.reset(Long.MIN_VALUE);
                }
            }
            policyRevision = revision;
        }
        publish(pending);
    }

    private void publish(List<Report> reports) {
        for (Report report : reports) publish(report);
    }

    private void publish(Report report) {
        if (reporter != null) reporter.accept(report);
    }

    static final class Command {
        private final CameraCommandMetrics owner;
        final Operation operation;
        final int workerIndex;
        private final long enqueuedNs;
        private final int[] phaseCounts;
        private final int[] phaseFailures;
        private final long[] phaseTotalsNs;
        private final long[] phaseMaxNs;
        private Context context;
        private long startedNs = -1L, queueNs, executionNs;
        private boolean queueSample, executionSample, inline, terminal;
        private Phase failedPhase;
        private Throwable phaseFailure;
        private final boolean metricsEnabled;
        private final long policyRevision;

        Command(CameraCommandMetrics owner, Operation operation, int workerIndex,
                Context context, long enqueuedNs, boolean metricsEnabled,
                long policyRevision) {
            this.owner = owner;
            this.operation = operation;
            this.workerIndex = workerIndex;
            this.context = context;
            this.enqueuedNs = enqueuedNs;
            this.metricsEnabled = metricsEnabled;
            this.policyRevision = policyRevision;
            phaseCounts = metricsEnabled ? new int[Phase.values().length] : null;
            phaseFailures = metricsEnabled ? new int[Phase.values().length] : null;
            phaseTotalsNs = metricsEnabled ? new long[Phase.values().length] : null;
            phaseMaxNs = metricsEnabled ? new long[Phase.values().length] : null;
        }

        boolean collectMetrics() {
            return metricsEnabled && DiagnosticLogPolicy.extended()
                    && policyRevision == DiagnosticLogPolicy.revision();
        }

        void context(Context value) {
            if (!terminal && value != null) context = value;
        }

        void started(long nowNs, boolean inline) {
            if (!collectMetrics() || terminal || startedNs >= 0L) return;
            startedNs = nowNs;
            queueNs = inline ? 0L : duration(nowNs - enqueuedNs);
            queueSample = true;
            this.inline = inline;
        }

        void phase(Phase phase, long startedNs, long endedNs, Throwable failure) {
            if (!metricsEnabled) {
                if (failure != null) {
                    failedPhase = phase;
                    phaseFailure = failure;
                }
                return;
            }
            if (!collectMetrics()) {
                if (failure != null) {
                    failedPhase = phase;
                    phaseFailure = failure;
                }
                return;
            }
            int i = phase.ordinal();
            long elapsed = duration(endedNs - startedNs);
            phaseCounts[i]++;
            phaseTotalsNs[i] += elapsed;
            phaseMaxNs[i] = Math.max(phaseMaxNs[i], elapsed);
            if (failure != null) {
                phaseFailures[i]++;
                failedPhase = phase;
                phaseFailure = failure;
            }
        }

        void complete(Outcome outcome, long endedNs, Throwable failure) {
            if (terminal) return;
            terminal = true;
            if (!collectMetrics()) {
                if (failure == null && phaseFailure != null) failure = phaseFailure;
                owner.recordFailure(this, outcome, failure);
                return;
            }
            boolean ran = startedNs >= 0L;
            if (!ran && outcome == Outcome.QUEUED_CANCEL) {
                queueNs = duration(endedNs - enqueuedNs);
                queueSample = true;
            }
            if (ran) {
                executionNs = duration(endedNs - startedNs);
                executionSample = true;
            }
            if (failure == null && phaseFailure != null) failure = phaseFailure;
            owner.record(this, outcome, endedNs, queueNs, queueSample,
                    executionNs, executionSample, failure);
        }

        private static long duration(long value) { return Math.max(0L, value); }
    }

    private static final class Window {
        long lastReportNs = Long.MIN_VALUE;
        int commands, successes, failures, queuedCancels, postRejects, inlineCommands;
        int queueSamples, executionSamples;
        long queueTotalNs, queueMaxNs, executionTotalNs, executionMaxNs;
        long totalNs, totalMaxNs, lastTotalNs, worstTotalNs;
        Context lastContext = Context.EMPTY, worstContext = Context.EMPTY;
        Context lastFailureContext = Context.EMPTY;
        Context firstFailureContext = Context.EMPTY;
        Phase failedPhase;
        Throwable firstFailure, lastFailure;
        final int[] phaseCounts = new int[Phase.values().length];
        final int[] phaseFailures = new int[Phase.values().length];
        final long[] phaseTotalsNs = new long[Phase.values().length];
        final long[] phaseMaxNs = new long[Phase.values().length];

        void record(Command command, Outcome outcome, long endedNs,
                long queueNs, boolean queueSample, long executionNs,
                boolean executionSample, Throwable error) {
            commands++;
            if (outcome == Outcome.SUCCESS) successes++;
            else if (outcome == Outcome.FAILURE) failures++;
            else if (outcome == Outcome.QUEUED_CANCEL) queuedCancels++;
            else postRejects++;
            if (command.inline) inlineCommands++;
            if (queueSample) {
                queueSamples++;
                queueTotalNs += queueNs;
                queueMaxNs = Math.max(queueMaxNs, queueNs);
            }
            if (executionSample) {
                executionSamples++;
                executionTotalNs += executionNs;
                executionMaxNs = Math.max(executionMaxNs, executionNs);
            }
            long total = Math.max(0L, endedNs - command.enqueuedNs);
            totalNs += total;
            lastTotalNs = total;
            lastContext = command.context;
            if (total > totalMaxNs) totalMaxNs = total;
            if (total >= worstTotalNs) {
                worstTotalNs = total;
                worstContext = command.context;
            }
            for (int i = 0; i < phaseCounts.length; i++) {
                phaseCounts[i] += command.phaseCounts[i];
                phaseFailures[i] += command.phaseFailures[i];
                phaseTotalsNs[i] += command.phaseTotalsNs[i];
                phaseMaxNs[i] = Math.max(phaseMaxNs[i], command.phaseMaxNs[i]);
            }
            if (error != null) {
                if (firstFailure == null) {
                    firstFailure = error;
                    firstFailureContext = command.context;
                }
                lastFailure = error;
                lastFailureContext = command.context;
                failedPhase = command.failedPhase;
            }
        }

        void reset(long nextReportNs) {
            lastReportNs = nextReportNs;
            commands = successes = failures = queuedCancels = postRejects = inlineCommands = 0;
            queueSamples = executionSamples = 0;
            queueTotalNs = queueMaxNs = executionTotalNs = executionMaxNs = 0L;
            totalNs = totalMaxNs = lastTotalNs = worstTotalNs = 0L;
            lastContext = worstContext = lastFailureContext = firstFailureContext = Context.EMPTY;
            failedPhase = null;
            firstFailure = lastFailure = null;
            for (int i = 0; i < phaseCounts.length; i++) {
                phaseCounts[i] = phaseFailures[i] = 0;
                phaseTotalsNs[i] = phaseMaxNs[i] = 0L;
            }
        }
    }
}
