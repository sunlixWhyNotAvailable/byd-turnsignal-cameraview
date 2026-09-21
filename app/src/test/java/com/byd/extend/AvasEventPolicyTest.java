package com.byd.extend;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class AvasEventPolicyTest {
    @Test public void initialRepeatedAndUnknownAreSilent() {
        AvasEventPolicy policy = new AvasEventPolicy();
        assertTrue(policy.sample(0, 1, 2, true).isEmpty());
        assertTrue(policy.sample(250, 4, 2, true).isEmpty());
        assertTrue(policy.sample(500, -1, -1, true).isEmpty());
        assertTrue(policy.sample(750, 0, 1, true).isEmpty());
    }

    @Test public void powerFirstSuppressesAssociatedUnlockWithoutDelayingOff() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        assertEquals(Collections.singletonList("power_off"), policy.sample(250, 0, 1, true));
        assertTrue(policy.wasUnlockSuppressed());
        assertEquals(Collections.singletonList("lock"), policy.sample(500, 0, 2, true));
        assertEquals(Collections.singletonList("unlock"), policy.sample(750, 0, 1, true));
        assertEquals(Collections.singletonList("power_on"), policy.sample(1000, 2, 1, true));
    }

    @Test public void matchesObserved258msUnlockButNotDelayedIndependentUnlock() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        assertEquals(Collections.singletonList("power_off"), policy.sample(100, 0, 2, true));
        assertTrue(policy.sample(358, 0, 1, true).isEmpty());
        assertTrue(policy.wasUnlockSuppressed());
        policy.reset();
        policy.sample(0, 1, 2, true);
        policy.sample(100, 0, 2, true);
        assertEquals(Collections.singletonList("unlock"), policy.sample(1101, 0, 1, true));
    }

    @Test public void offNotReadyAndPowerOnDoNotConsumeUnlock() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, false);
        assertEquals(Arrays.asList("power_off", "unlock"), policy.sample(100, 0, 1, false));
        policy.reset();
        policy.sample(0, 1, 2, true);
        policy.sample(100, 0, 2, true);
        assertEquals(Arrays.asList("power_on", "unlock"), policy.sample(300, 1, 1, true));
    }

    @Test public void resetSeedsCurrentStateAndDoesNotReplay() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        policy.sample(100, 0, 2, true);
        policy.reset();
        assertTrue(policy.sample(500, 0, 1, true).isEmpty());
        assertEquals(Collections.singletonList("lock"), policy.sample(600, 0, 2, true));
    }

    @Test public void silentReconcileNeverEmitsOrArmsPowerSuppression() {
        AvasEventPolicy policy = new AvasEventPolicy();
        assertTrue(policy.reconcile(2, 2));
        assertTrue(policy.reconcile(0, 2));
        assertEquals(Collections.singletonList("unlock"),
                policy.sample(100, 0, 1, true, true));
        assertFalse(policy.wasEventSuppressed());
        assertFalse(policy.reconcile(-1, 1));
        assertTrue(policy.sample(200, 2, 2, true, true).isEmpty());
    }

    @Test public void oneMissingSignalMakesWholeSampleSilentAndReseedsBoth() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        assertTrue(policy.sample(250, 0, -1, true).isEmpty());
        assertTrue(policy.sample(500, 0, 1, true).isEmpty());
        assertEquals(Collections.singletonList("lock"), policy.sample(750, 0, 2, true));
    }

    @Test public void eachPowerProfileIndependentlySuppressesEitherNextLockEdge() {
        for (boolean powerOn : new boolean[]{false, true}) {
            for (int nextLock : new int[]{1, 2}) {
                AvasEventPolicy policy = new AvasEventPolicy();
                int initialPower = powerOn ? 0 : 1;
                int changedPower = powerOn ? 1 : 0;
                int initialLock = nextLock == 1 ? 2 : 1;
                policy.sample(0, initialPower, initialLock, true, true);

                assertEquals(Collections.singletonList(
                        powerOn ? "power_on" : "power_off"),
                        policy.sample(100, changedPower, initialLock, powerOn, !powerOn));
                assertTrue(policy.sample(350, changedPower, nextLock, powerOn, !powerOn).isEmpty());
                assertEquals(nextLock == 1 ? "unlock" : "lock", policy.suppressedProfile());
                assertEquals(powerOn ? "power_on" : "power_off",
                        policy.suppressionPowerProfile());
                assertEquals(250, policy.suppressionDeltaMs());
            }
        }
    }

    @Test public void disabledSwitchesNeverSuppressForEitherPowerDirection() {
        AvasEventPolicy off = new AvasEventPolicy();
        off.sample(0, 1, 2, false, false);
        assertEquals(Arrays.asList("power_off", "unlock"),
                off.sample(100, 0, 1, false, false));

        AvasEventPolicy on = new AvasEventPolicy();
        on.sample(0, 0, 1, false, false);
        assertEquals(Arrays.asList("power_on", "lock"),
                on.sample(100, 1, 2, false, false));
    }

    @Test public void inclusiveWindowHasExact9991000And1001Boundaries() {
        assertBoundarySuppressed(999, true);
        assertBoundarySuppressed(1000, true);
        assertBoundarySuppressed(1001, false);
    }

    @Test public void sameSampleIsPowerFirstAndTokenIsConsumedOnlyOnce() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 0, 2, true, false);
        assertEquals(Collections.singletonList("power_on"),
                policy.sample(100, 1, 1, true, false));
        assertEquals("unlock", policy.suppressedProfile());
        assertEquals(0, policy.suppressionDeltaMs());
        assertEquals(Collections.singletonList("lock"),
                policy.sample(200, 1, 2, true, false));
    }

    @Test public void eligibilityLossInvalidTelemetryAndNewPowerEdgeInvalidateOrReplaceToken() {
        AvasEventPolicy eligibility = armedPowerOffPolicy();
        eligibility.invalidateIneligible(false, false);
        assertEquals(Collections.singletonList("unlock"),
                eligibility.sample(200, 0, 1, false, false));

        AvasEventPolicy telemetry = armedPowerOffPolicy();
        assertTrue(telemetry.sample(150, -1, 2, false, true).isEmpty());
        assertTrue(telemetry.sample(200, 0, 1, false, true).isEmpty());

        AvasEventPolicy replacement = armedPowerOffPolicy();
        assertEquals(Collections.singletonList("power_on"),
                replacement.sample(200, 1, 2, true, true));
        assertTrue(replacement.sample(300, 1, 1, true, true).isEmpty());
        assertEquals("power_on", replacement.suppressionPowerProfile());
    }

    @Test public void acceptedPowerEventIsImmediateAndLaterSwitchChangeCannotRetractIt() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, false, true);
        List<String> accepted = policy.sample(100, 0, 2, false, true);
        policy.invalidateIneligible(false, false);

        assertEquals(Collections.singletonList("power_off"), accepted);
        assertEquals(Collections.singletonList("unlock"),
                policy.sample(200, 0, 1, false, false));
    }

    private static AvasEventPolicy armedPowerOffPolicy() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, false, true);
        assertEquals(Collections.singletonList("power_off"),
                policy.sample(100, 0, 2, false, true));
        return policy;
    }

    private static void assertBoundarySuppressed(long delta, boolean suppressed) {
        AvasEventPolicy policy = armedPowerOffPolicy();
        List<String> result = policy.sample(100 + delta, 0, 1, false, true);
        assertEquals(suppressed, result.isEmpty());
        assertEquals(suppressed, policy.wasEventSuppressed());
        if (!suppressed) assertEquals(Collections.singletonList("unlock"), result);
    }
}
