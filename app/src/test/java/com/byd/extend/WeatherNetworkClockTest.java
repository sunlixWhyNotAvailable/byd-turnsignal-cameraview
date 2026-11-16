package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherNetworkClockTest {
    @Test public void UnavailableApiIsDiscoveredOnceAndRetainsItsCauseAcrossMonitorRestarts() {
        // An actual class without this capability, not a substitute clock returning invented time.
        WeatherNetworkClock clock = new WeatherNetworkClock(Object.class, false);
        assertNull(clock.read(0));
        Throwable cause = clock.failure();
        assertTrue(cause instanceof NoSuchMethodException);
        assertNull(clock.read(500)); assertSame(cause, clock.failure());
        assertNull(clock.read(1500)); assertSame(cause, clock.failure());
        clock.resetSample(); assertNull(clock.failure());
        assertNull(clock.read(2000)); assertSame(cause, clock.failure());
    }
}
