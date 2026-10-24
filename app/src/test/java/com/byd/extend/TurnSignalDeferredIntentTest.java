package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.*;

public final class TurnSignalDeferredIntentTest {
    @Test public void speedBoundaryAllowsTheLimitButRejectsOverspeedAndUnknownValues() {
        assertTrue(TurnSignalGuardRuntime.speedAllowed(29.9f, 30));
        assertTrue(TurnSignalGuardRuntime.speedAllowed(30f, 30));
        assertFalse(TurnSignalGuardRuntime.speedAllowed(30.1f, 30));
        assertFalse(TurnSignalGuardRuntime.speedAllowed(Float.NaN, 30));
        assertFalse(TurnSignalGuardRuntime.speedAllowed(Float.POSITIVE_INFINITY, 30));
    }

    @Test public void missingBlinkExpiresWithoutAnotherCallbackAndNextIntentIsIndependent() {
        TurnSignalDeferredIntent intent = new TurnSignalDeferredIntent();
        intent.start(2, false, 100);
        assertFalse(intent.expired(2_099));
        assertTrue(intent.expired(2_100));
        assertEquals("blink_start_timeout", intent.observeBlink(2, 2_100));
        intent.clear();
        assertEquals(0, intent.direction());
        intent.start(2, false, 3_000);
        // Receipt precedes the deadline even when dispatch happens after it.
        assertNull(intent.observeBlink(2, 4_999));
        assertTrue(intent.blinkSeen());
        assertFalse(intent.expired(5_050));
        assertFalse(intent.expired(20_000));
    }

    @Test public void matchingBlinkKeepsIntentUntilOffOppositeOrHazard() {
        TurnSignalDeferredIntent intent = new TurnSignalDeferredIntent();
        for (int ending : new int[]{1, 4, 6}) {
            intent.start(2, false, 100);
            assertNull(intent.observeBlink(1, 120));
            assertNull(intent.observeBlink(2, 200));
            assertEquals("blink_changed", intent.observeBlink(ending, 300));
            intent.clear();
        }
    }
}
