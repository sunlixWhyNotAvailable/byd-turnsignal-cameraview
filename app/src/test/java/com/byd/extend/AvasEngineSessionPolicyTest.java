package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AvasEngineSessionPolicyTest {
    @Test
    public void baselineRestoresOnlyOnAndARealOnEdgeStarts() {
        AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
        assertEquals(AvasEngineSessionPolicy.Action.NONE, policy.configure(true, true));
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_OK, true));
        assertFalse(policy.desiredActive());
        assertEquals(AvasEngineSessionPolicy.Action.START,
                policy.observePower(AvasEngineSessionPolicy.POWER_ON, false));
        assertTrue(policy.desiredActive());

        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_ACC, false));
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_FAKE_OK, false));
        assertTrue(policy.desiredActive());
        assertEquals(AvasEngineSessionPolicy.Action.STOP_WITH_TAIL,
                policy.observePower(AvasEngineSessionPolicy.POWER_OFF, false));
        assertFalse(policy.desiredActive());
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_OFF, false));
        assertEquals(AvasEngineSessionPolicy.Action.START,
                policy.observePower(AvasEngineSessionPolicy.POWER_ON, false));
    }

    @Test
    public void baselineAccOkAndFakeOkStaySilent() {
        int[] powers = {AvasEngineSessionPolicy.POWER_ACC, AvasEngineSessionPolicy.POWER_OK,
                AvasEngineSessionPolicy.POWER_FAKE_OK};
        for (int power : powers) {
            AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
            policy.configure(true, true);
            assertEquals(AvasEngineSessionPolicy.Action.NONE, policy.observePower(power, true));
            assertFalse(policy.desiredActive());
        }
    }

    @Test
    public void initialAndRecoveredPowerTwoRestoreWithoutIgnition() {
        AvasEngineSessionPolicy baseline = new AvasEngineSessionPolicy();
        baseline.configure(true, true);
        assertEquals(AvasEngineSessionPolicy.Action.RESTORE,
                baseline.observePower(AvasEngineSessionPolicy.POWER_ON, true));

        AvasEngineSessionPolicy recovery = new AvasEngineSessionPolicy();
        recovery.configure(true, true);
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                recovery.observePower(AvasEngineSessionPolicy.POWER_ACC, true));
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                recovery.observePower(AvasEngineSessionPolicy.POWER_INVALID, false));
        assertFalse(recovery.hasValidPower());
        assertEquals(AvasEngineSessionPolicy.Action.RESTORE,
                recovery.observePower(AvasEngineSessionPolicy.POWER_ON, false));
        assertEquals(AvasEngineSessionPolicy.POWER_ON, recovery.rawPower());
        assertEquals(AvasEngineSessionPolicy.POWER_ON, recovery.lastValidPower());
    }

    @Test
    public void offReconciliationFinishesWithTailAndUnknownPowerCannotRestoreStaleOn() {
        AvasEngineSessionPolicy active = new AvasEngineSessionPolicy();
        active.configure(true, true);
        active.observePower(AvasEngineSessionPolicy.POWER_ON, true);
        assertEquals(AvasEngineSessionPolicy.Action.STOP_WITH_TAIL,
                active.observePower(AvasEngineSessionPolicy.POWER_OFF, true));
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                active.observePower(AvasEngineSessionPolicy.POWER_OFF, true));

        AvasEngineSessionPolicy unknown = new AvasEngineSessionPolicy();
        unknown.configure(true, true);
        unknown.observePower(AvasEngineSessionPolicy.POWER_ON, true);
        unknown.playbackFailed();
        unknown.observePower(AvasEngineSessionPolicy.POWER_INVALID, false);
        assertEquals(AvasEngineSessionPolicy.Action.NONE, unknown.configure(true, true));
        assertFalse(unknown.desiredActive());
    }

    @Test
    public void manualStopSuppressesUntilOffAndManualStartOverridesIt() {
        AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
        policy.configure(true, true);
        policy.observePower(AvasEngineSessionPolicy.POWER_ACC, true);
        assertEquals(AvasEngineSessionPolicy.Action.START,
                policy.observePower(AvasEngineSessionPolicy.POWER_ON, false));
        assertEquals(AvasEngineSessionPolicy.Action.STOP_NOW, policy.manualStop());
        assertFalse(policy.desiredActive());
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_ACC, false));
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_ON, false));
        assertFalse(policy.desiredActive());
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_OFF, false));
        assertEquals(AvasEngineSessionPolicy.Action.START,
                policy.observePower(AvasEngineSessionPolicy.POWER_ON, false));

        assertEquals(AvasEngineSessionPolicy.Action.STOP_NOW, policy.manualStop());
        assertEquals(AvasEngineSessionPolicy.Action.START, policy.manualStart());
        assertTrue(policy.desiredActive());
    }

    @Test
    public void repeatedOffDoesNotStopManualStartButNextOffEdgeDoes() {
        AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
        policy.configure(true, true);
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_OFF, true));
        assertEquals(AvasEngineSessionPolicy.Action.START, policy.manualStart());
        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_OFF, false));
        assertTrue(policy.desiredActive());

        assertEquals(AvasEngineSessionPolicy.Action.NONE,
                policy.observePower(AvasEngineSessionPolicy.POWER_ACC, false));
        assertEquals(AvasEngineSessionPolicy.Action.STOP_WITH_TAIL,
                policy.observePower(AvasEngineSessionPolicy.POWER_OFF, false));
        assertFalse(policy.desiredActive());
    }

    @Test
    public void failedPlaybackCanBeSilentlyRestoredAndUnavailableOutputsFailClosed() {
        AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
        policy.configure(true, true);
        assertEquals(AvasEngineSessionPolicy.Action.RESTORE,
                policy.observePower(AvasEngineSessionPolicy.POWER_ON, true));
        policy.playbackFailed();
        assertFalse(policy.desiredActive());
        assertEquals(AvasEngineSessionPolicy.Action.RESTORE, policy.manualStart());

        assertEquals(AvasEngineSessionPolicy.Action.STOP_NOW,
                policy.configure(true, false));
        assertFalse(policy.desiredActive());
        assertEquals(AvasEngineSessionPolicy.Action.NONE, policy.manualStart());
    }
}
