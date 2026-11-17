package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AccessibilityRecoveryGateTest {
    @Test public void onlyConfirmedLostBindingSkipsTheInitialWait() {
        assertTrue(AccessibilityRecoveryGate.isQuickbootExit("quickboot", 100, 200));
        assertFalse(AccessibilityRecoveryGate.isQuickbootExit("quickboot", 300, 200));
        assertFalse(AccessibilityRecoveryGate.isQuickbootExit("low memory", 100, 200));
        assertFalse(AccessibilityRecoveryGate.isQuickbootExit(null, 100, 200));
        assertTrue(AccessibilityRecoveryGate.immediateRebind("disconnect", false));
        assertTrue(AccessibilityRecoveryGate.immediateRebind("service_start", true));
        assertFalse(AccessibilityRecoveryGate.immediateRebind("service_start", false));
    }

    @Test public void rebindCannotDuplicateRunningRecoveryOrSurviveCancellation() {
        AccessibilityRecoveryGate gate = new AccessibilityRecoveryGate();
        long epoch = gate.begin();
        assertTrue(gate.isCurrent(epoch));
        assertEquals(0, gate.begin());
        gate.cancel();
        assertFalse(gate.isCurrent(epoch));
        assertFalse(gate.finish(epoch));
        long next = gate.begin();
        assertTrue(gate.finish(next));
        assertFalse(gate.finish(next));
    }
}
