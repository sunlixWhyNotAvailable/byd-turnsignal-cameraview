package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.*;

public class MotionFramePerformanceTest {
    @Test public void capsFrameStartsWithoutCatchupAndResetsForANewReader() {
        MotionDetectorShellMain.FrameCadence cadence = new MotionDetectorShellMain.FrameCadence();
        assertTrue(cadence.take(0L));
        assertFalse(cadence.take(399_999_999L));
        assertTrue(cadence.take(400_000_000L));
        assertFalse(cadence.take(401_000_000L));
        assertTrue(cadence.take(1_800_000_000L));
        assertFalse(cadence.take(1_800_000_001L));
        assertFalse(cadence.take(2_199_999_999L));
        assertTrue(cadence.take(2_200_000_000L));
        cadence.reset();
        assertTrue(cadence.take(2_200_000_001L));
    }

    @Test public void separatesPackingDetectionAndWaitingTimeAtThirtySecondBoundary() {
        MotionDetectorShellMain.FramePerformance stats = new MotionDetectorShellMain.FramePerformance();
        stats.record(1_000_000_000L, 1_010_000_000L, 1_015_000_000L,
                1_020_000_000L, 1_035_000_000L, 10_000_000L, 11L);
        stats.record(30_900_000_000L, 30_905_000_000L, 30_910_000_000L,
                30_915_000_000L, 30_950_000_000L, 30_000_000L, 12L);
        assertFalse(stats.due());
        assertEquals(2, stats.frames);
        assertEquals(11L, stats.firstOperationId);
        assertEquals(12L, stats.lastOperationId);
        assertEquals(15_000_000L, stats.packTotalNs);
        assertEquals(10_000_000L, stats.packMaxNs);
        assertEquals(10_000_000L, stats.nativeQueueTotalNs);
        assertEquals(5_000_000L, stats.nativeQueueMaxNs);
        assertEquals(50_000_000L, stats.nativeRunTotalNs);
        assertEquals(35_000_000L, stats.nativeRunMaxNs);
        assertEquals(40_000_000L, stats.detectTotalNs);
        assertEquals(30_000_000L, stats.detectMaxNs);
        assertEquals(29_950_000_000L, stats.windowNs());

        stats.record(30_970_000_000L, 30_980_000_000L, 30_985_000_000L,
                30_990_000_000L, 31_000_000_000L, 8_000_000L, 13L);
        assertTrue(stats.due());
        assertEquals(30_000_000_000L, stats.windowNs());
        assertEquals(13L, stats.lastOperationId);
    }

    @Test public void resetDropsPreviousWindowAndIdleTime() {
        MotionDetectorShellMain.FramePerformance stats = new MotionDetectorShellMain.FramePerformance();
        assertEquals(0, stats.frames);
        assertFalse(stats.due());
        stats.record(1, 11, 16, 20, 36, 12, 21);
        assertEquals(35, stats.windowNs());
        assertEquals(21L, stats.firstOperationId);
        stats.reset();
        assertEquals(0, stats.frames);
        assertEquals(0, stats.windowNs());
        assertEquals(0, stats.packTotalNs);
        assertEquals(0, stats.nativeQueueTotalNs);
        assertEquals(0, stats.nativeRunTotalNs);
        assertEquals(0, stats.detectTotalNs);
        assertFalse(stats.due());

        stats.record(60_000_000_000L, 60_000_000_003L, 60_000_000_009L,
                60_000_000_010L, 60_000_000_013L, 3, 22L);
        assertEquals(1, stats.frames);
        assertEquals(22L, stats.firstOperationId);
        assertEquals(22L, stats.lastOperationId);
        assertEquals(3, stats.packMaxNs);
        assertEquals(1, stats.nativeQueueMaxNs);
        assertEquals(3, stats.nativeRunMaxNs);
        assertEquals(3, stats.detectMaxNs);
        assertEquals(13, stats.windowNs());
        assertFalse(stats.due());
    }
}
