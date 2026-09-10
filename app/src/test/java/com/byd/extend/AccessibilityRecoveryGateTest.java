package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AccessibilityRecoveryGateTest {
    @Test
    public void concurrentRequestsCoalesceUntilAttemptFinishes() {
        AccessibilityRecoveryGate gate = new AccessibilityRecoveryGate();
        long first = gate.begin();
        assertTrue(first > 0);
        assertEquals(0, gate.begin());
        assertTrue(gate.isCurrent(first));

        assertTrue(gate.finish(first));
        long second = gate.begin();
        assertTrue(second > first);
    }

    @Test
    public void cancellationInvalidatesStaleAttemptAndAllowsNewOne() {
        AccessibilityRecoveryGate gate = new AccessibilityRecoveryGate();
        long stale = gate.begin();
        gate.cancel();
        assertFalse(gate.isCurrent(stale));

        long current = gate.begin();
        assertFalse(gate.finish(stale));
        assertTrue(gate.isCurrent(current));
    }
}
