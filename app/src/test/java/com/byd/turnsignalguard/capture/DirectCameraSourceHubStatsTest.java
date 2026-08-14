package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class DirectCameraSourceHubStatsTest {
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
}
