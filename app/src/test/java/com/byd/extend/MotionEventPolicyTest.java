package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionEventPolicyTest {
    @Test public void warmsFor2500msAndRequiresContinuousMiddleSensitivityR() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 1_000L);

        assertTrue(policy.observe(-3, 3_499L).suppressed);
        assertTrue(policy.observe(-3, 6_498L).suppressed);
        MotionEventPolicy.Decision levelOne = policy.observe(-3, 6_499L);
        assertEquals(MotionEventPolicy.LEVEL_ONE, levelOne.level);
        assertEquals(6_499L, levelOne.elapsedMs);
        assertEquals(16_499L, levelOne.warningUntilMs);

        MotionEventPolicy.Decision levelTwo = policy.observe(4, 8_000L);
        assertEquals(MotionEventPolicy.LEVEL_TWO, levelTwo.level);
        assertEquals(18_000L, levelTwo.warningUntilMs);
        assertTrue(policy.warningActive(17_999L));
        assertFalse(policy.warningActive(18_000L));
    }

    @Test public void zeroAndErrorResetOnlyTheUnfinishedCandidate() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        assertTrue(policy.observe(-1, 2_500L).suppressed);
        assertTrue(policy.observe(-1, 5_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 5_500L).level);
        policy.configure(2);
        assertTrue(policy.warningActive(5_501L));
        policy.configure(1);
        assertEquals(MotionEventPolicy.LEVEL_NONE, policy.observe(0, 11_000L).level);
        assertEquals(15_500L, policy.warningUntilMs());

        MotionEventPolicy.Decision error = policy.observe(-2, 11_100L);
        assertTrue(error.error);
        assertEquals(-2, error.nativeResult);
        assertTrue(policy.warningActive(11_100L));
        assertTrue(policy.observe(-1, 11_101L).suppressed);
        assertTrue(policy.observe(-1, 14_100L).suppressed);
        assertFalse(policy.warningActive(15_500L));
    }

    @Test public void rawZeroAndStaleTimeCannotBridgeRContinuity() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        assertTrue(policy.observe(-1, 2_500L).suppressed);
        assertTrue(policy.observe(-1, 5_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_NONE, policy.observe(0, 5_499L).level);
        assertTrue(policy.observe(-1, 5_500L).suppressed);
        assertTrue(policy.observe(-1, 8_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_NONE, policy.observe(0, 8_499L).level);
        assertTrue(policy.observe(-1, 8_500L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 11_500L).level);

        assertEquals(MotionEventPolicy.LEVEL_NONE, policy.observe(0, 11_510L).level);
        assertTrue(policy.observe(-1, 11_600L).suppressed);
        assertTrue(policy.observe(-1, 11_599L).suppressed);
        assertTrue(policy.observe(-1, 11_601L).suppressed);
        assertTrue(policy.observe(-1, 14_600L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 14_601L).level);
    }

    @Test public void lowPositiveBypassesRFilterAndHighRThresholdIsZero() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.configure(0);
        policy.setEligible(true, 0L);
        assertTrue(policy.observe(-1, 2_500L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_TWO, policy.observe(2, 2_501L).level);
        assertTrue(policy.observe(-1, 2_502L).suppressed);
        assertTrue(policy.observe(-1, 8_501L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 8_502L).level);

        MotionEventPolicy high = new MotionEventPolicy();
        high.configure(2);
        high.setEligible(true, 0L);
        assertTrue(high.observe(-1, 2_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, high.observe(-1, 2_500L).level);
    }

    @Test public void liveSensitivityChangesPreserveCandidateAcrossBothThresholdDirections() {
        MotionEventPolicy higherThreshold = new MotionEventPolicy();
        higherThreshold.setEligible(true, 0L);
        assertTrue(higherThreshold.observe(-1, 2_500L).suppressed);
        assertTrue(higherThreshold.observe(-1, 4_000L).suppressed);
        higherThreshold.setSensitivity(0);
        assertTrue(higherThreshold.observe(-1, 8_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, higherThreshold.observe(-1, 8_500L).level);

        MotionEventPolicy lowerThreshold = new MotionEventPolicy();
        lowerThreshold.setSensitivity(0);
        lowerThreshold.setEligible(true, 0L);
        assertTrue(lowerThreshold.observe(-1, 2_500L).suppressed);
        assertTrue(lowerThreshold.observe(-1, 4_000L).suppressed);
        lowerThreshold.setSensitivity(1);
        assertTrue(lowerThreshold.observe(-1, 5_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, lowerThreshold.observe(-1, 5_500L).level);
    }

    @Test public void unchangedSensitivityKeepsCandidateAndRevision() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        assertTrue(policy.observe(-1, 2_500L).suppressed);
        long revision = policy.revision();
        policy.setSensitivity(MotionConfig.DEFAULT_RECORDING_SENSITIVITY);
        assertEquals(revision, policy.revision());
        assertTrue(policy.observe(-1, 5_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 5_500L).level);
    }

    @Test public void eligibilityResetAndExplicitStopClearWarning() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 5L);
        policy.observe(2, 2_505L);
        policy.setEligible(false, 2_600L);
        assertFalse(policy.eligible());
        assertFalse(policy.warningActive(2_600L));

        policy.setEligible(true, 3_000L);
        assertTrue(policy.observe(2, 5_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_TWO, policy.observe(2, 5_500L).level);
        policy.stop(5_600L);
        assertFalse(policy.warningActive(5_600L));
    }

    @Test public void twoOneAndAHalfSecondRunsSeparatedByZeroDoNotQualify() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        assertTrue(policy.observe(-1, 2_500L).suppressed);
        assertTrue(policy.observe(-1, 4_000L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_NONE, policy.observe(0, 4_100L).level);
        assertTrue(policy.observe(-1, 4_200L).suppressed);
        assertTrue(policy.observe(-1, 5_700L).suppressed);
        assertFalse(policy.warningActive(5_700L));
    }

    @Test public void configChangeAndStopInvalidateOlderCandidatesAndFrames() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.configure(0);
        policy.setEligible(true, 0L);
        long beforeConfig = policy.revision();
        assertTrue(policy.observe(-1, 2_500L, beforeConfig).suppressed);

        policy.configure(1);
        long afterConfig = policy.revision();
        assertTrue(policy.observe(-1, 9_000L, beforeConfig).suppressed);
        assertTrue(policy.observe(-1, 9_000L, afterConfig).suppressed);
        assertTrue(policy.observe(-1, 11_999L, afterConfig).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 12_000L, afterConfig).level);

        policy.stop(13_000L);
        assertFalse(policy.warningActive(13_000L));
        policy.setEligible(true, 14_000L);
        assertTrue(policy.observe(-1, 16_500L).suppressed);
        assertTrue(policy.observe(-1, 19_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 19_500L).level);
    }

    @Test public void visibleDeadlineStartsAfterCoverAndRenewsOnAcceptedDetection() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        policy.observe(1, 2_500L);
        policy.beginVisibleWarning(6_000L);
        assertEquals(16_000L, policy.warningUntilMs());

        policy.observe(2, 15_500L);
        assertEquals(25_500L, policy.warningUntilMs());
        policy.observe(0, 16_000L);
        assertTrue(policy.warningActive(25_499L));
        assertFalse(policy.warningActive(25_500L));
    }

    @Test public void lateWindowDrawCannotRearmAnIneligibleSession() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        policy.observe(1, 2_500L);
        policy.setEligible(false, 3_000L);
        policy.beginVisibleWarning(4_000L);
        assertEquals(0L, policy.warningUntilMs());
        assertFalse(policy.warningActive(4_000L));
    }
}
