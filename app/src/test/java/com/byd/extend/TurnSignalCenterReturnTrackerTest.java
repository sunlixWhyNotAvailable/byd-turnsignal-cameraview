package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TurnSignalCenterReturnTrackerTest {
    private static final int LEFT = 2;
    private static final int RIGHT = 4;

    @Test public void exactZeroCompletesBothDirectionsAfterArming() {
        TurnSignalGuardRuntime.CenterReturnTracker left = tracker(LEFT, 25.0f);
        assertTrue(left.observe(LEFT, true, 0.0f, 0.0f));

        TurnSignalGuardRuntime.CenterReturnTracker right = tracker(RIGHT, -25.0f);
        assertTrue(right.observe(RIGHT, true, 0.0f, 0.0f));
    }

    @Test public void outwardArmedSessionSeedCompletesWhenNextSampleSkipsSmallCenterBand() {
        TurnSignalGuardRuntime.CenterReturnTracker left = tracker(LEFT, 0.3f);
        assertTrue(left.observe(LEFT, true, 0.1f, -0.2f));

        TurnSignalGuardRuntime.CenterReturnTracker right = tracker(RIGHT, -0.3f);
        assertTrue(right.observe(RIGHT, true, 0.1f, 0.2f));
    }

    @Test public void unarmedAndOppositeDirectionCrossingsDoNotComplete() {
        TurnSignalGuardRuntime.CenterReturnTracker unarmed = tracker(LEFT, 0.3f);
        assertFalse(unarmed.observe(LEFT, false, 0.1f, -0.2f));

        TurnSignalGuardRuntime.CenterReturnTracker left = tracker(LEFT, -0.3f);
        assertFalse(left.observe(LEFT, true, 0.1f, 0.2f));

        TurnSignalGuardRuntime.CenterReturnTracker right = tracker(RIGHT, 0.3f);
        assertFalse(right.observe(RIGHT, true, 0.1f, -0.2f));
    }

    @Test public void resetPreventsOldOrStaleSampleFromCompletingNewSession() {
        TurnSignalGuardRuntime.CenterReturnTracker tracker = tracker(LEFT, 0.3f);
        tracker.reset();
        assertFalse(tracker.observe(LEFT, true, 0.1f, -0.2f));

        assertFalse(tracker.observe(LEFT, true, 0.1f, -0.4f));
        assertFalse(tracker.observe(LEFT, true, 0.1f, 0.2f));
    }

    @Test public void centerBandRemainsIndependentOfOutwardThreshold() {
        assertTrue(TurnSignalGuardRuntime.validThresholds(0.0f, 10.0f));
        assertTrue(TurnSignalGuardRuntime.validThresholds(90.0f, 0.0f));

        TurnSignalGuardRuntime.CenterReturnTracker tracker = tracker(LEFT, 30.0f);
        assertTrue(tracker.observe(LEFT, true, 10.0f, 9.0f));
        assertFalse(tracker(LEFT, 0.3f).observe(LEFT, true, 0.0f, 0.2f));
    }

    private static TurnSignalGuardRuntime.CenterReturnTracker tracker(
            int direction, float initialAngle) {
        TurnSignalGuardRuntime.CenterReturnTracker tracker =
                new TurnSignalGuardRuntime.CenterReturnTracker();
        tracker.seed(initialAngle);
        return tracker;
    }
}
