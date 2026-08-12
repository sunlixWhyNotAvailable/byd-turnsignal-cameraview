package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class DirectCameraSourceHubStatsTest {
    @Test
    public void reportsAndResetsFiveSecondPerSourceWindow() {
        DirectCameraSourceHub.StatsWindow first = new DirectCameraSourceHub.StatsWindow();
        DirectCameraSourceHub.StatsWindow second = new DirectCameraSourceHub.StatsWindow();

        assertNull(first.record(0L, 2L, 8L, 5L, 2, 11L, 2, 1920, 1300));
        assertNull(first.record(4_000_000_000L, 4L, 6L, 6L, 1, 12L, 1, 1920, 1300));
        DirectCameraSourceHub.Stats report = first.record(
                5_000_000_000L, 6L, 10L, 7L, 2, 13L, 3, 1920, 1300);

        assertEquals(3, report.callbacks);
        assertEquals(2, report.callbackGaps);
        assertEquals(5_000_000_000L, report.callbackGapTotalNs);
        assertEquals(4_000_000_000L, report.callbackGapMaxNs);
        assertEquals(12L, report.updateTotalNs);
        assertEquals(6L, report.updateMaxNs);
        assertEquals(5, report.swaps);
        assertEquals(24L, report.drawTotalNs);
        assertEquals(7L, report.drawMaxNs);
        assertEquals(36L, report.renderTotalNs);
        assertEquals(13L, report.renderMaxNs);
        assertEquals(3, report.targetsCurrent);
        assertEquals(3, report.targetsMax);
        assertEquals(1920, report.sourceWidth);
        assertEquals(1300, report.sourceHeight);

        assertNull(second.record(5_000_000_000L, 1L, 1L, 1L, 1,
                1L, 1, 1920, 990));
        assertNull(first.record(5_100_000_000L, 1L, 1L, 1L, 1,
                1L, 1, 1920, 1300));
    }
}
