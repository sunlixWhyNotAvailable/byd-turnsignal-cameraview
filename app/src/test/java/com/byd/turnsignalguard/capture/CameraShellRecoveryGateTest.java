package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraShellRecoveryGateTest {
    @Test
    public void newerAttachClaimsRecoveryOnlyOnce() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.onDeath(4, true));
        assertTrue(gate.pending());
        assertFalse(gate.claim(4, true));
        assertTrue(gate.claim(5, true));
        assertFalse(gate.pending());
        assertFalse(gate.claim(5, true));
        assertFalse(gate.claim(6, true));
    }

    @Test
    public void staleEventsAndLostIntentDoNotRecover() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.isNewDeath(3));
        assertFalse(gate.onDeath(3, false));
        assertFalse(gate.isNewDeath(3));
        assertFalse(gate.onDeath(3, true));
        assertFalse(gate.claim(4, true));
        assertTrue(gate.onDeath(5, true));
        assertFalse(gate.claim(6, false));
        assertFalse(gate.claim(7, true));
    }

    @Test
    public void laterDeathCanScheduleAnotherEpoch() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.onDeath(1, true));
        assertTrue(gate.claim(2, true));
        assertTrue(gate.onDeath(2, true));
        assertTrue(gate.claim(3, true));
        gate.clear();
        assertFalse(gate.pending());
    }
}
