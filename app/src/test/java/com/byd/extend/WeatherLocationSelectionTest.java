package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class WeatherLocationSelectionTest {
    @Test public void vehiclePowerSuspendsUnknownAndOffAndDeduplicatesOn() {
        WeatherPowerState power = new WeatherPowerState();
        assertFalse(power.allowsRequests());
        assertFalse(power.update(false, false));
        assertTrue(power.update(true, true));
        assertFalse(power.allowsRequests());
        assertTrue(power.update(true, false));
        assertTrue(power.allowsRequests());
        assertFalse(power.update(true, false));
        assertTrue(power.update(false, false));
        assertFalse(power.allowsRequests());
        assertTrue(power.update(true, false));
        assertTrue(power.allowsRequests());
    }

    @Test public void suspendedPowerCannotScheduleOrAcceptAnOlderRequestGeneration() {
        WeatherPowerState power = new WeatherPowerState();
        assertFalse(WeatherRuntime.canScheduleNormal(true, power.allowsRequests(), false, false, false));
        power.update(true, false);
        assertTrue(WeatherRuntime.canScheduleNormal(true, power.allowsRequests(), false, false, false));
        assertFalse(WeatherRuntime.canScheduleNormal(true, power.allowsRequests(), true, false, false));
        assertFalse(WeatherRuntime.canScheduleNormal(true, power.allowsRequests(), false, true, false));
        power.update(true, true);
        assertFalse(WeatherRuntime.canScheduleNormal(true, power.allowsRequests(), false, false, false));
        assertFalse(WeatherRuntime.ownsGeneration(4, 5));
        assertTrue(WeatherRuntime.ownsGeneration(5, 5));
    }

    @Test
    public void acquisitionDeadlineIsNotExtendedByRepeatedChecks() {
        WeatherLocationAttempt attempt = new WeatherLocationAttempt();
        attempt.start(1_000L);
        assertEquals(60_000L, attempt.remaining(1_000L));
        attempt.start(30_000L);
        assertEquals(31_000L, attempt.remaining(30_000L));
        assertEquals(1L, attempt.remaining(60_999L));
        assertEquals(0L, attempt.remaining(61_000L));
        assertEquals(0L, attempt.remaining(400_000L));
        assertEquals(60_000L, attempt.duration(61_000L));
    }

    @Test
    public void finishingOrCancellingAcquisitionAllowsAFreshAttempt() {
        WeatherLocationAttempt attempt = new WeatherLocationAttempt();
        attempt.start(0L);
        assertEquals(0L, attempt.remaining(60_000L));
        attempt.clear();
        assertEquals(0L, attempt.duration(360_000L));
        attempt.start(360_000L);
        assertEquals(60_000L, attempt.remaining(360_000L));
        attempt.clear();
        attempt.start(361_000L);
        assertEquals(59_999L, attempt.remaining(361_001L));
    }

    @Test
    public void selectsFreshKyivOverOlderNativeLimaFix() {
        long nowNanos = 16_193_272_020_801L;

        assertEquals(1, WeatherLocationSelection.selectIndex(nowNanos,
                12_676_503_367_663L, 16_193_271_020_801L));
    }

    @Test
    public void acceptsInclusiveAgeBoundaryAndRejectsOneNanosecondOlder() {
        long nowNanos = WeatherLocationSelection.MAX_AGE_NANOS + 10L;

        assertNull(WeatherLocationSelection.rejectionReason(
                nowNanos - WeatherLocationSelection.MAX_AGE_NANOS, nowNanos));
        assertEquals("stale", WeatherLocationSelection.rejectionReason(
                nowNanos - WeatherLocationSelection.MAX_AGE_NANOS - 1L, nowNanos));
    }

    @Test
    public void rejectsUnavailableInvalidFutureAndExpiredTimestamps() {
        long nowNanos = WeatherLocationSelection.MAX_AGE_NANOS + 10L;

        assertEquals("invalid_elapsed", WeatherLocationSelection.rejectionReason(0L, nowNanos));
        assertEquals("invalid_elapsed", WeatherLocationSelection.rejectionReason(-1L, nowNanos));
        assertEquals("future_elapsed", WeatherLocationSelection.rejectionReason(nowNanos + 1L,
                nowNanos));
        assertEquals(-1, WeatherLocationSelection.selectIndex(nowNanos,
                0L, -1L, nowNanos + 1L,
                nowNanos - WeatherLocationSelection.MAX_AGE_NANOS - 1L));
    }

    @Test
    public void selectsNewestRegardlessOfPositionAndKeepsEarlierEqualCandidate() {
        assertEquals(1, WeatherLocationSelection.selectIndex(5_000_000_000L,
                3_000_000_000L, 4_000_000_000L, 2_000_000_000L));
        assertEquals(2, WeatherLocationSelection.selectIndex(5_000_000_000L,
                2_000_000_000L, 3_000_000_000L, 4_000_000_000L));
        assertEquals(0, WeatherLocationSelection.selectIndex(1_000L, 800L, 800L));
    }

    @Test
    public void replacesOnlyWhenCandidateIsNewerOrCurrentIsExpired() {
        long nowNanos = 10_000L;

        assertFalse(WeatherLocationSelection.shouldReplace(9_000L, 9_500L, nowNanos));
        assertFalse(WeatherLocationSelection.shouldReplace(9_500L, 9_500L, nowNanos));
        assertFalse(WeatherLocationSelection.shouldReplace(0L, 9_500L, nowNanos));
        assertTrue(WeatherLocationSelection.shouldReplace(9_600L, 9_500L, nowNanos));
        assertTrue(WeatherLocationSelection.shouldReplace(9_000L, 10_001L, nowNanos));
        assertFalse(WeatherLocationSelection.shouldReplace(10_001L, 9_500L, nowNanos));

        long boundaryNowNanos = WeatherLocationSelection.MAX_AGE_NANOS + 10_000L;
        assertTrue(WeatherLocationSelection.shouldReplace(boundaryNowNanos,
                boundaryNowNanos - WeatherLocationSelection.MAX_AGE_NANOS - 1L,
                boundaryNowNanos));
        assertFalse(WeatherLocationSelection.shouldReplace(1L,
                boundaryNowNanos - 10L, boundaryNowNanos));
    }
}
