package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public final class TurnSignalCenterReturnTrackerTest {
    private static final int LEFT = 2;
    private static final int RIGHT = 4;

    @Test public void outwardArmedSessionSeedCompletesWhenNextSampleSkipsSmallCenterBand() {
        TurnSignalGuardRuntime.CenterReturnTracker left = tracker(LEFT, 0.3f);
        assertTrue(left.observe(LEFT, true, 0.1f, -0.2f));

        TurnSignalGuardRuntime.CenterReturnTracker right = tracker(RIGHT, -0.3f);
        assertTrue(right.observe(RIGHT, true, 0.1f, 0.2f));
    }

    @Test public void currentGetCanCompleteButCannotArmAnUnobservedOutwardTurn() {
        for (int direction : new int[]{LEFT, RIGHT}) {
            float sign = direction == LEFT ? 1f : -1f;
            TurnSignalGuardRuntime.CenterReturnTracker tracker =
                    new TurnSignalGuardRuntime.CenterReturnTracker();
            tracker.start(direction, 0f, 90f);
            assertFalse(tracker.observeSession(direction, false, 90f, 10f, 100f * sign));
            assertFalse(tracker.armed());
            assertFalse(tracker.observeSession(direction, false, 90f, 10f, 0f));
            assertFalse(tracker.observeSession(direction, true, 90f, 10f, 90f * sign));
            assertTrue(tracker.armed());
            assertTrue(tracker.observeSession(direction, false, 90f, 10f, 0f));
        }
    }

    private static TurnSignalGuardRuntime.CenterReturnTracker tracker(
            int direction, float initialAngle) {
        TurnSignalGuardRuntime.CenterReturnTracker tracker =
                new TurnSignalGuardRuntime.CenterReturnTracker();
        tracker.seed(initialAngle);
        return tracker;
    }
}
