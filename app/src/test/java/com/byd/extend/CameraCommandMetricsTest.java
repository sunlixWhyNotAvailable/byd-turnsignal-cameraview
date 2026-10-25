package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Deterministic input records exercise only the production aggregate calculation;
 * they do not simulate Handler execution, EGL calls, or dependency outcomes.
 */
public final class CameraCommandMetricsTest {
    @Test public void recordsQueueExecutionOutcomesAndBoundedFlushes() {
        List<CameraCommandMetrics.Report> reports = new ArrayList<>();
        CameraCommandMetrics metrics = new CameraCommandMetrics(reports::add);
        CameraCommandMetrics.Context firstContext = new CameraCommandMetrics.Context(
                1, 4, 12, 900, false);
        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 1,
                firstContext, 100, 110, false, CameraCommandMetrics.Outcome.SUCCESS, 130, null,
                new PhaseInput(CameraCommandMetrics.Phase.MAPPING_LEASE_BIND, 112, 117, null),
                new PhaseInput(CameraCommandMetrics.Phase.EGL_CREATE_WINDOW_SURFACE,
                        120, 123, null)));

        assertEquals(1, reports.size());
        CameraCommandMetrics.Report initial = reports.get(0);
        assertEquals("first", initial.reason);
        assertEquals(1, initial.commands);
        assertEquals(1, initial.successes);
        assertEquals(10L, initial.queueTotalNs);
        assertEquals(20L, initial.executionTotalNs);
        assertEquals(30L, initial.lastTotalNs);
        assertEquals(5L, initial.phases[
                CameraCommandMetrics.Phase.MAPPING_LEASE_BIND.ordinal()].totalNs);
        assertEquals(3L, initial.phases[
                CameraCommandMetrics.Phase.EGL_CREATE_WINDOW_SURFACE.ordinal()].maxNs);
        assertEquals(900, initial.lastContext.surfaceIdentity);

        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 1,
                firstContext, 200, 220, false, CameraCommandMetrics.Outcome.SUCCESS, 260, null));
        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 1,
                firstContext, 300, -1, false, CameraCommandMetrics.Outcome.QUEUED_CANCEL, 350,
                new TimeoutException("queued command cancelled")));
        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 1,
                firstContext, 400, -1, false, CameraCommandMetrics.Outcome.POST_REJECT, 405,
                new IllegalStateException("handler rejected command")));
        assertEquals(1, reports.size());

        long nextReportNs = 130 + CameraCommandMetrics.REPORT_INTERVAL_NS;
        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 1,
                firstContext, nextReportNs - 50, nextReportNs - 40, false,
                CameraCommandMetrics.Outcome.SUCCESS, nextReportNs, null));
        assertEquals(2, reports.size());
        CameraCommandMetrics.Report interval = reports.get(1);
        assertEquals("interval", interval.reason);
        assertEquals(4, interval.commands);
        assertEquals(2, interval.successes);
        assertEquals(1, interval.queuedCancels);
        assertEquals(1, interval.postRejects);
        assertEquals(3, interval.queueSamples);
        assertEquals(2, interval.executionSamples);
        assertEquals(165L, interval.totalNs);
        assertEquals(50L, interval.lastTotalNs);
        assertEquals(60L, interval.worstTotalNs);

        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 1,
                new CameraCommandMetrics.Context(1, 5, 12, 901, true), nextReportNs + 1,
                nextReportNs + 4, false, CameraCommandMetrics.Outcome.SUCCESS,
                nextReportNs + 9, null));
        metrics.flushWorker(1, "source_retired", nextReportNs + 10);
        assertEquals(3, reports.size());
        assertEquals("source_retired", reports.get(2).reason);
        assertTrue(reports.get(2).lastContext.recordingOutput);

        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 1,
                firstContext, nextReportNs + 20, nextReportNs + 21, false,
                CameraCommandMetrics.Outcome.SUCCESS, nextReportNs + 22, null));
        assertEquals(4, reports.size());
        assertEquals("first", reports.get(3).reason);

        metrics.flushAll("initial_fps_window_complete", nextReportNs + 30);
        int beforeUnseenOperation = reports.size();
        record(metrics, new Input(CameraCommandMetrics.Operation.HUB_CLOSE, -1,
                CameraCommandMetrics.Context.EMPTY, nextReportNs + 40,
                nextReportNs + 40, true, CameraCommandMetrics.Outcome.SUCCESS,
                nextReportNs + 45, null));
        assertEquals(beforeUnseenOperation + 1, reports.size());
        assertEquals("first", reports.get(reports.size() - 1).reason);
    }

    @Test public void retainsAttachFailureCauseAndPhaseDuration() {
        List<CameraCommandMetrics.Report> reports = new ArrayList<>();
        CameraCommandMetrics metrics = new CameraCommandMetrics(reports::add);
        CameraCommandMetrics.Context context = new CameraCommandMetrics.Context(
                2, 17, 31, 4421, false);
        IllegalArgumentException nativeCause = new IllegalArgumentException("egl=0x3003");
        IllegalStateException failure = new IllegalStateException(
                "downstream EGL surface failed", nativeCause);
        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 2,
                context, 1_000, 1_020, false, CameraCommandMetrics.Outcome.FAILURE,
                1_050, failure,
                new PhaseInput(CameraCommandMetrics.Phase.EGL_CREATE_WINDOW_SURFACE,
                        1_025, 1_035, failure)));

        assertEquals(1, reports.size());
        CameraCommandMetrics.Report report = reports.get(0);
        assertEquals(1, report.failures);
        assertSame(failure, report.firstFailure);
        assertSame(nativeCause, report.lastFailure.getCause());
        assertEquals(CameraCommandMetrics.Phase.EGL_CREATE_WINDOW_SURFACE, report.failedPhase);
        CameraCommandMetrics.PhaseStats phase = report.phases[
                CameraCommandMetrics.Phase.EGL_CREATE_WINDOW_SURFACE.ordinal()];
        assertEquals(10L, phase.totalNs);
        assertEquals(1, phase.failures);
        assertEquals(2, report.lastFailureContext.sourceIndex);
        assertEquals(17, report.lastFailureContext.requestId);
        assertEquals(31, report.lastFailureContext.producerEpoch);
        assertEquals(4421, report.lastFailureContext.surfaceIdentity);

        IllegalStateException laterFailure = new IllegalStateException("later command failed");
        record(metrics, new Input(CameraCommandMetrics.Operation.WORKER_ATTACH, 2,
                new CameraCommandMetrics.Context(2, 18, 31, 4422, false),
                1_100, 1_105, false, CameraCommandMetrics.Outcome.FAILURE,
                1_110, laterFailure));
        metrics.flushWorker(2, "source_retired", 1_120);
        CameraCommandMetrics.Report residual = reports.get(1);
        assertNull(residual.failedPhase);
        assertSame(laterFailure, residual.lastFailure);
        assertEquals(18, residual.lastFailureContext.requestId);
    }

    @Test public void directCoordinatorCommandsHaveZeroQueueAndHubFlushKeepsContext() {
        List<CameraCommandMetrics.Report> reports = new ArrayList<>();
        CameraCommandMetrics metrics = new CameraCommandMetrics(reports::add);
        record(metrics, new Input(CameraCommandMetrics.Operation.HUB_INITIALIZE, -1,
                CameraCommandMetrics.Context.EMPTY, 100, 100, true,
                CameraCommandMetrics.Outcome.SUCCESS, 125, null));

        assertEquals(1, reports.size());
        assertEquals(0L, reports.get(0).queueTotalNs);
        assertEquals(25L, reports.get(0).executionTotalNs);
        assertEquals(1, reports.get(0).inlineCommands);

        CameraCommandMetrics.Context sourceContext = new CameraCommandMetrics.Context(
                3, -1, -1, -1, false);
        record(metrics, new Input(CameraCommandMetrics.Operation.RELEASE_SOURCE, -1,
                sourceContext, 200, 210, true, CameraCommandMetrics.Outcome.SUCCESS, 220, null));
        assertEquals("first", reports.get(1).reason);
        record(metrics, new Input(CameraCommandMetrics.Operation.RELEASE_SOURCE, -1,
                sourceContext, 221, 222, true, CameraCommandMetrics.Outcome.SUCCESS, 223, null));
        assertEquals(2, reports.size());
        metrics.flushAll("hub_close", 230);
        assertEquals(3, reports.size());
        assertEquals("hub_close", reports.get(2).reason);
        assertEquals(1, reports.get(2).commands);
        assertEquals(3, reports.get(2).lastContext.sourceIndex);
        metrics.flushAll("hub_close", 240);
        assertEquals(3, reports.size());
    }

    private static void record(CameraCommandMetrics metrics, Input input) {
        CameraCommandMetrics.Command command = metrics.begin(input.operation, input.workerIndex,
                input.context, input.enqueuedNs);
        if (input.startedNs >= 0L) command.started(input.startedNs, input.inline);
        for (PhaseInput phase : input.phases) {
            command.phase(phase.phase, phase.startedNs, phase.endedNs, phase.failure);
        }
        command.complete(input.outcome, input.endedNs, input.failure);
    }

    private static final class Input {
        final CameraCommandMetrics.Operation operation;
        final int workerIndex;
        final CameraCommandMetrics.Context context;
        final long enqueuedNs, startedNs, endedNs;
        final boolean inline;
        final CameraCommandMetrics.Outcome outcome;
        final Throwable failure;
        final PhaseInput[] phases;

        Input(CameraCommandMetrics.Operation operation, int workerIndex,
                CameraCommandMetrics.Context context, long enqueuedNs, long startedNs,
                boolean inline, CameraCommandMetrics.Outcome outcome, long endedNs,
                Throwable failure, PhaseInput... phases) {
            this.operation = operation;
            this.workerIndex = workerIndex;
            this.context = context;
            this.enqueuedNs = enqueuedNs;
            this.startedNs = startedNs;
            this.inline = inline;
            this.outcome = outcome;
            this.endedNs = endedNs;
            this.failure = failure;
            this.phases = phases;
        }
    }

    private static final class PhaseInput {
        final CameraCommandMetrics.Phase phase;
        final long startedNs, endedNs;
        final Throwable failure;

        PhaseInput(CameraCommandMetrics.Phase phase, long startedNs, long endedNs,
                Throwable failure) {
            this.phase = phase;
            this.startedNs = startedNs;
            this.endedNs = endedNs;
            this.failure = failure;
        }
    }
}
