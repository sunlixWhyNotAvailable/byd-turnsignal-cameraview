package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionScreenCyclePolicyTest {
    @Test public void userDismissalAndUncertaintyNeedConfirmedBaselineBeforeAnotherWake() {
        MotionScreenCyclePolicy policy = new MotionScreenCyclePolicy();
        policy.beginSession();

        assertTrue(policy.mayStartPreflight(true, true, false));
        assertFalse(policy.mayStartPreflight(true, true, true));
        assertTrue(policy.mayWriteWake());
        policy.beginWakeAttempt();
        policy.markUserTakeover();

        assertTrue(policy.mayStartPreflight(true, true, false));
        assertTrue(policy.mayStartPreflight(true, true, true));
        assertFalse(policy.mayWriteWake());
        assertTrue(policy.needsFreshBlackOffBaseline());

        // This transition is invoked only by MotionScreenControl after real platform reads.
        policy.confirmFreshBlackOffBaseline();
        assertTrue(policy.mayWriteWake());
        policy.beginWakeAttempt();
        policy.markUncertain();

        assertTrue(policy.mayStartPreflight(true, true, false));
        assertTrue(policy.mayStartPreflight(true, true, true));
        assertFalse(policy.mayWriteWake());
        policy.confirmFreshBlackOffBaseline();
        assertTrue(policy.mayWriteWake());
    }
}
