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

        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(new WeatherLocationSources.Fix("gps", "gps", false, true,
                -12, -77, 5, 12_676_503_367_663L / 1_000_000, 1), nowNanos / 1_000_000);
        sources.accept(new WeatherLocationSources.Fix("network", "network", false, false,
                50, 30, 100, 16_193_271_020_801L / 1_000_000, 1), nowNanos / 1_000_000);
        assertEquals("network", sources.select(nowNanos / 1_000_000, null).source);
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
        assertEquals("stale", WeatherLocationSelection.rejectionReason(
                nowNanos - WeatherLocationSelection.MAX_AGE_NANOS - 1L, nowNanos));
    }

}
