package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DirectCameraSourceHubStatsTest {
    @Test
    public void retriesInterruptedFinalizerAndRestoresInterrupt() throws Exception {
        int[] attempts = {0};
        try {
            String result = DirectCameraSourceHub.callUninterruptibly(() -> {
                if (attempts[0]++ == 0) throw new InterruptedException("test");
                return "closed";
            });

            assertEquals("closed", result);
            assertEquals(2, attempts[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void dispatchesEveryWorkerCloseBeforeAwaitingAndPreservesFailures() {
        List<String> trace = new ArrayList<>();
        DirectCameraSourceHub.WorkerFinalizer[] finalizers = {
                new RecordingFinalizer("a", trace, false, true),
                new RecordingFinalizer("b", trace, true, false),
                new RecordingFinalizer("c", trace, false, false)
        };

        Exception failure = null;
        try {
            DirectCameraSourceHub.closeWorkers(finalizers);
        } catch (Exception expected) {
            failure = expected;
        }

        assertEquals(Arrays.asList(
                "dispatch:a", "dispatch:b", "dispatch:c",
                "await:a", "await:b", "await:c"), trace);
        assertNotNull(failure);
        assertEquals("dispatch:b", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("await:a", failure.getSuppressed()[0].getMessage());
    }

    @Test
    public void namesWorkersByPhysicalSourceIndex() {
        assertEquals("direct-camera-source-0", DirectCameraSourceHub.workerThreadName(0));
        assertEquals("direct-camera-source-4", DirectCameraSourceHub.workerThreadName(4));
    }

    @Test
    public void pausedTargetSkipsOnlyMatchingSurfaceAndLeavesOtherSameIndexActive() {
        assertEquals(true, DirectCameraSourceHub.shouldRenderTarget(2, 2, true));
        assertEquals(false, DirectCameraSourceHub.shouldRenderTarget(2, 2, false));
        assertEquals(false, DirectCameraSourceHub.shouldRenderTarget(2, 3, true));
    }

    @Test
    public void reportsAndResetsFiveSecondPerSourceWindow() {
        DirectCameraSourceHub.StatsWindow first = new DirectCameraSourceHub.StatsWindow();
        DirectCameraSourceHub.StatsWindow second = new DirectCameraSourceHub.StatsWindow();

        assertNull(first.record(
                0L, 1_000_000_000L, 1_100_000_000L,
                2L, 8L, 5L, 4L, 3L, 8L, 2, 11L,
                2, 200L, 20, 10,
                new int[]{20, 20}, new int[]{10, 10}, 2, 1920, 1300));
        assertNull(first.record(
                4_000_000_000L, 1_033_333_333L, 1_133_333_333L,
                4L, 6L, 6L, 2L, 2L, 8L, 1, 12L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300));
        DirectCameraSourceHub.Stats report = first.record(
                5_000_000_000L, 1_066_666_666L, 1_166_666_666L,
                6L, 10L, 7L, 6L, 4L, 11L, 2, 13L,
                3, 300L, 30, 10,
                new int[]{10, 10, 10}, new int[]{10, 10, 10}, 3, 1920, 1300);

        assertEquals(5_000_000_000L, report.intervalNs);
        assertEquals(3, report.callbacks);
        assertEquals(3, report.frameSignals);
        assertEquals(3, report.renderedFrames);
        assertEquals(0, report.coalescedFrames);
        assertEquals(0L, report.queueDelayTotalNs);
        assertEquals(0L, report.queueDelayAvgNs);
        assertEquals(0L, report.queueDelayMaxNs);
        assertEquals("", report.workerName);
        assertEquals(2, report.callbackGaps);
        assertEquals(5_000_000_000L, report.callbackGapTotalNs);
        assertEquals(4_000_000_000L, report.callbackGapMaxNs);
        assertEquals(12L, report.updateTotalNs);
        assertEquals(6L, report.updateMaxNs);
        assertEquals(2, report.producerTimestampDeltas);
        assertEquals(66_666_666L, report.producerTimestampDeltaTotalNs);
        assertEquals(33_333_333L, report.producerTimestampDeltaMinNs);
        assertEquals(33_333_333L, report.producerTimestampDeltaMaxNs);
        assertEquals(0, report.producerTimestampRepeated);
        assertEquals(0, report.producerTimestampInvalid);
        assertEquals(3, report.frameAgeSamples);
        assertEquals(300_000_000L, report.frameAgeTotalNs);
        assertEquals(100_000_000L, report.frameAgeMaxNs);
        assertEquals(5, report.swaps);
        assertEquals(24L, report.preSwapTotalNs);
        assertEquals(7L, report.preSwapMaxNs);
        assertEquals(12L, report.swapWaitTotalNs);
        assertEquals(4L, report.swapWaitMaxNs);
        assertEquals(11L, report.drawMaxNs);
        assertEquals(36L, report.renderTotalNs);
        assertEquals(13L, report.renderMaxNs);
        assertEquals(3, report.targetsCurrent);
        assertEquals(3, report.targetsMax);
        assertEquals(300L, report.targetPixelsCurrent);
        assertEquals(300L, report.targetPixelsMax);
        assertEquals(30, report.targetWidthMax);
        assertEquals(10, report.targetHeightMax);
        assertEquals("10x10;10x10;10x10", report.targetDimensions);
        assertEquals(1920, report.sourceWidth);
        assertEquals(1300, report.sourceHeight);

        assertNull(second.record(
                5_000_000_000L, 2_000_000_000L, 2_010_000_000L,
                1L, 1L, 1L, 1L, 1L, 2L, 1, 1L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 990));
        assertNull(first.record(
                5_100_000_000L, 1_066_666_666L, 1_166_666_666L,
                1L, 1L, 1L, 1L, 1L, 2L, 1, 1L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300));
    }

    @Test
    public void producerTimestampRejectsRepeatedInvalidAndBackwardSamples() {
        DirectCameraSourceHub.StatsWindow window = new DirectCameraSourceHub.StatsWindow();
        assertNull(window.record(
                0L, 1_000_000_000L, 1_010_000_000L,
                1L, 1L, 1L, 1L, 2L, 2L, 1, 3L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300));
        assertNull(window.record(
                1_000_000_000L, 1_000_000_000L, 1_020_000_000L,
                1L, 1L, 1L, 1L, 2L, 2L, 1, 3L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300));
        assertNull(window.record(
                2_000_000_000L, 900_000_000L, 1_030_000_000L,
                1L, 1L, 1L, 1L, 2L, 2L, 1, 3L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300));
        assertNull(window.record(
                3_000_000_000L, 0L, 1_040_000_000L,
                1L, 1L, 1L, 1L, 2L, 2L, 1, 3L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300));
        DirectCameraSourceHub.Stats report = window.record(
                5_000_000_000L, 1_033_333_333L, 1_043_333_333L,
                1L, 1L, 1L, 1L, 2L, 2L, 1, 3L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300);

        assertEquals(1, report.producerTimestampDeltas);
        assertEquals(33_333_333L, report.producerTimestampDeltaTotalNs);
        assertEquals(1, report.producerTimestampRepeated);
        assertEquals(2, report.producerTimestampInvalid);
        assertEquals(3, report.frameAgeSamples);
    }

    @Test
    public void reportsCoalescedSignalsAndWorkerQueueDelay() {
        DirectCameraSourceHub.StatsWindow window = new DirectCameraSourceHub.StatsWindow();
        assertNull(window.record(
                0L, 1_000_000_000L, 1_010_000_000L,
                1L, 1L, 1L, 1L, 2L, 2L, 1, 3L,
                2, 1, 6L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300,
                "direct-camera-source-2"));
        DirectCameraSourceHub.Stats report = window.record(
                5_000_000_000L, 1_033_333_333L, 1_043_333_333L,
                1L, 1L, 1L, 1L, 2L, 2L, 1, 3L,
                3, 2, 10L,
                1, 100L, 10, 10,
                new int[]{10}, new int[]{10}, 1, 1920, 1300,
                "direct-camera-source-2");

        assertEquals(5, report.frameSignals);
        assertEquals(2, report.renderedFrames);
        assertEquals(3, report.coalescedFrames);
        assertEquals(16L, report.queueDelayTotalNs);
        assertEquals(8L, report.queueDelayAvgNs);
        assertEquals(10L, report.queueDelayMaxNs);
        assertEquals("direct-camera-source-2", report.workerName);
    }

    private static final class RecordingFinalizer
            implements DirectCameraSourceHub.WorkerFinalizer {
        private final String name;
        private final List<String> trace;
        private final boolean failDispatch;
        private final boolean failAwait;

        RecordingFinalizer(
                String name, List<String> trace,
                boolean failDispatch, boolean failAwait) {
            this.name = name;
            this.trace = trace;
            this.failDispatch = failDispatch;
            this.failAwait = failAwait;
        }

        @Override
        public void dispatchClose() throws Exception {
            trace.add("dispatch:" + name);
            if (failDispatch) throw new Exception("dispatch:" + name);
        }

        @Override
        public void awaitClose() throws Exception {
            trace.add("await:" + name);
            if (failAwait) throw new Exception("await:" + name);
        }
    }
}
