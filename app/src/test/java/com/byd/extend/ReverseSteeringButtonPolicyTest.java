package com.byd.extend;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReverseSteeringButtonPolicyTest {
    private static final long LONG_PRESS_TIMEOUT = 400L;
    private static final long MULTI_PRESS_TIMEOUT = 300L;

    @Test
    public void firstDownExcludesRepeatAndUp() {
        assertTrue(ReverseSteeringButtonPolicy.isFirstDown(KeyEvent.ACTION_DOWN, 0));
        assertFalse(ReverseSteeringButtonPolicy.isFirstDown(KeyEvent.ACTION_DOWN, 1));
        assertFalse(ReverseSteeringButtonPolicy.isFirstDown(KeyEvent.ACTION_UP, 0));
    }

    @Test
    public void assignedIdentityIncludesOnlyConfirmedShortLongPairs() {
        assertTrue(ReverseSteeringButtonPolicy.isMappedKey(42, 42));
        assertFalse(ReverseSteeringButtonPolicy.isMappedKey(41, 42));
        assertFalse(ReverseSteeringButtonPolicy.isMappedKey(42,
                ReverseSteeringButtonPreferences.UNASSIGNED));

        assertTrue(ReverseSteeringButtonPolicy.isAssignedPhysicalKey(303, 88));
        assertTrue(ReverseSteeringButtonPolicy.isAssignedPhysicalKey(302, 87));
        assertTrue(ReverseSteeringButtonPolicy.isAssignedPhysicalKey(306, 305));
        assertTrue(ReverseSteeringButtonPolicy.isAssignedPhysicalKey(352, 351));
        assertFalse(ReverseSteeringButtonPolicy.isAssignedPhysicalKey(126, 353));
        assertFalse(ReverseSteeringButtonPolicy.isAssignedPhysicalKey(313, 353));
    }

    @Test
    public void preferencesNormalizeAndResetBinding() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertEquals(ReverseSteeringButtonPreferences.UNASSIGNED,
                ReverseSteeringButtonPreferences.load(preferences));
        ReverseSteeringButtonPreferences.save(preferences, 42);
        assertEquals(42, ReverseSteeringButtonPreferences.load(preferences));
        ReverseSteeringButtonPreferences.save(preferences, -9);
        assertEquals(ReverseSteeringButtonPreferences.UNASSIGNED,
                ReverseSteeringButtonPreferences.load(preferences));
        preferences.edit().putString(ReverseSteeringButtonPreferences.KEY_CODE, "42").apply();
        assertEquals(ReverseSteeringButtonPreferences.UNASSIGNED,
                ReverseSteeringButtonPreferences.load(preferences));
        ReverseSteeringButtonPreferences.save(preferences, 42);
        ReverseSteeringButtonPreferences.reset(preferences);
        assertEquals(ReverseSteeringButtonPreferences.UNASSIGNED,
                ReverseSteeringButtonPreferences.load(preferences));
    }

    @Test
    public void singleConsumesImmediatelyAndTogglesOnlyAfterFirstUpWindow() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();

        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                down(state, 42, 100L, 100L, 42));
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                up(state, 42, 100L, 150L, 0, 42));
        assertEquals(451L, state.getDeadline());
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS, state.advanceTime(450L, 42, 0L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                state.advanceTime(451L, 42, 0L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS, state.advanceTime(900L, 42, 0L));
    }

    @Test
    public void doubleCompletesOnSecondUpAndThirdClickStartsANewSingle() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();

        down(state, 42, 100L, 100L, 42);
        up(state, 42, 100L, 140L, 0, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS, state.advanceTime(439L, 42, 0L));
        down(state, 42, 439L, 439L, 42);
        up(state, 42, 439L, 470L, 0, 42);
        assertEquals(-1L, state.getDeadline());
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS, state.advanceTime(769L, 42, 0L));
        down(state, 42, 769L, 769L, 42);
        up(state, 42, 769L, 800L, 0, 42);

        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(1100L, 42, 0L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                state.advanceTime(1101L, 42, 0L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(2000L, 42, 0L));
    }

    @Test
    public void exactDoubleBoundaryRemainsDoubleRegardlessOfTimerEventOrder() {
        for (boolean timerFirst : new boolean[] {false, true}) {
            ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
            down(state, 42, 100L, 100L, 42);
            up(state, 42, 100L, 150L, 0, 42);
            if (timerFirst) {
                assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                        state.advanceTime(450L, 42, 0L));
            }
            down(state, 42, 450L, 450L, 42);
            assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                    state.advanceTime(451L, 42, 0L));
            up(state, 42, 450L, 480L, 0, 42);
            assertEquals(-1L, state.getDeadline());
            assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                    state.advanceTime(1000L, 42, 0L));
        }
    }

    @Test
    public void oneMillisecondOutsideDoubleWindowProducesTwoSingles() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();

        down(state, 42, 100L, 100L, 42);
        up(state, 42, 100L, 150L, 0, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                state.advanceTime(451L, 42, 0L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                down(state, 42, 451L, 451L, 42));
        up(state, 42, 451L, 480L, 0, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                state.advanceTime(781L, 42, 0L));
    }

    @Test
    public void consecutiveDoublesProduceNoSinglesAndHoldDoesNotBlockTheNextClick() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        for (long start : new long[] {100L, 300L}) {
            down(state, 42, start, start, 42);
            up(state, 42, start, start + 40L, 0, 42);
            down(state, 42, start + 80L, start + 80L, 42);
            up(state, 42, start + 80L, start + 120L, 0, 42);
            assertEquals(-1L, state.getDeadline());
        }
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(800L, 42, 0L));

        down(state, 42, 1000L, 1000L, 42);
        up(state, 42, 1000L, 1400L, 0, 42);
        assertEquals(-1L, state.getDeadline());
        down(state, 42, 1401L, 1401L, 42);
        up(state, 42, 1401L, 1450L, 0, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                state.advanceTime(1751L, 42, 0L));
    }

    @Test
    public void duplicateDownAndRepeatsDoNotBecomeAdditionalClicks() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        down(state, 42, 100L, 100L, 42);
        down(state, 42, 100L, 110L, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                apply(state, KeyEvent.ACTION_DOWN, 42, 1, 100L, 120L, 0, 42));
        up(state, 42, 100L, 150L, 0, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                state.advanceTime(451L, 42, 0L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(900L, 42, 0L));
    }

    @Test
    public void durationRepeatLongFlagAndCancellationRejectSingles() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();

        down(state, 42, 100L, 100L, 42);
        up(state, 42, 100L, 500L, 0, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS, state.advanceTime(800L, 42, 0L));

        down(state, 42, 1000L, 1000L, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                apply(state, KeyEvent.ACTION_DOWN, 42, 1, 1000L, 1200L, 0, 42));
        up(state, 42, 1000L, 1400L, 0, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(1550L, 42, 0L));

        down(state, 42, 2000L, 2000L, 42);
        up(state, 42, 2000L, 2050L, KeyEvent.FLAG_LONG_PRESS, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(2350L, 42, 0L));

        down(state, 42, 3000L, 3000L, 42);
        up(state, 42, 3000L, 3050L, KeyEvent.FLAG_CANCELED, 42);
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(3350L, 42, 0L));
    }

    @Test
    public void explicitOemLongFeedbackCannotCancelOrReleaseOrdinaryGesture() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();

        down(state, 88, 100L, 100L, 88);
        up(state, 88, 100L, 140L, 0, 88);
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                down(state, 303, 300L, 300L, 88));
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                up(state, 303, 300L, 330L, 0, 88));
        assertEquals(441L, state.getDeadline());
        assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                state.advanceTime(441L, 88, 0L));

        down(state, 88, 500L, 500L, 88);
        down(state, 303, 600L, 600L, 88);
        up(state, 303, 600L, 630L, KeyEvent.FLAG_CANCELED, 88);
        assertEquals(-1L, state.getDeadline());
        up(state, 88, 500L, 900L, 0, 88);
        assertEquals(-1L, state.getDeadline());
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(1200L, 88, 0L));

        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                down(state, 302, 1000L, 1000L, 88));

        int[][] pairs = {{88, 303}, {87, 302}, {305, 306}, {351, 352}};
        long time = 2000L;
        for (int[] pair : pairs) {
            ReverseSteeringButtonPolicy.State pairState =
                    new ReverseSteeringButtonPolicy.State();
            assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                    down(pairState, pair[1], time, time, pair[0]));
            assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                    up(pairState, pair[1], time, time + 20L, 0, pair[0]));
            assertEquals(-1L, pairState.getDeadline());
            assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                    pairState.advanceTime(time + 320L, pair[0], 0L));
            time += 1000L;
        }
    }

    @Test
    public void oemFeedbackBetweenClicksPreservesDoubleAndLateAliasUpPreservesNewSingle() {
        int[][] pairs = {{88, 303}, {87, 302}, {305, 306}, {351, 352}};
        for (int[] pair : pairs) {
            ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
            int key = pair[0];
            int alias = pair[1];
            down(state, key, 100L, 100L, key);
            up(state, key, 100L, 150L, 0, key);
            down(state, alias, 200L, 200L, key);
            up(state, alias, 200L, 230L, 0, key);
            assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                    state.advanceTime(450L, key, 0L));
            down(state, key, 450L, 450L, key);
            up(state, key, 450L, 480L, 0, key);
            assertEquals(-1L, state.getDeadline());

            down(state, key, 500L, 500L, key);
            assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                    up(state, alias, 200L, 510L, 0, key));
            up(state, key, 500L, 550L, 0, key);
            assertEquals(ReverseSteeringButtonPolicy.Decision.TOGGLE,
                    state.advanceTime(851L, key, 0L));
        }
    }

    @Test
    public void learningUsesSameClassifierAndNeverLearnsExplicitLongCode() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        state.beginLearning();

        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                down(state, KeyEvent.KEYCODE_BACK, 10L, 10L, -1));
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                down(state, 303, 100L, 100L, -1));
        up(state, 303, 100L, 130L, 0, -1);
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(430L, -1, 0L));
        assertTrue(state.isLearning());

        down(state, 88, 500L, 500L, -1);
        up(state, 88, 500L, 550L, 0, -1);
        assertEquals(ReverseSteeringButtonPolicy.Decision.LEARNED,
                state.advanceTime(851L, -1, 0L));
        assertEquals(88, state.getConfirmedKeyCode());
        assertFalse(state.isLearning());
    }

    @Test
    public void cancelAndBindingChangeDropActionButKeepOwnedTailConsumed() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();

        down(state, 42, 100L, 100L, 42);
        state.bindingChanged();
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                up(state, 42, 100L, 130L, 0, 43));
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(1000L, 43, 0L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                down(state, 42, 1200L, 1200L, 43));

        state.beginLearning();
        down(state, 44, 2000L, 2000L, 43);
        state.cancel();
        assertFalse(state.isLearning());
        assertTrue(state.consumeOwnedTail(KeyEvent.ACTION_UP, 44, 2000L));
        assertFalse(state.consumeOwnedTail(KeyEvent.ACTION_UP, 44, 2000L));
    }

    @Test
    public void ownerContextChangeCancelsPendingToggle() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                state.apply(KeyEvent.ACTION_DOWN, 42, 0, 100L, 100L, 0, 42,
                        7L, LONG_PRESS_TIMEOUT, MULTI_PRESS_TIMEOUT));
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                state.apply(KeyEvent.ACTION_UP, 42, 0, 100L, 150L, 0, 42,
                        7L, LONG_PRESS_TIMEOUT, MULTI_PRESS_TIMEOUT));

        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(450L, 42, 8L));
        assertEquals(ReverseSteeringButtonPolicy.Decision.PASS,
                state.advanceTime(1000L, 42, 8L));
    }

    @Test
    public void resetReleasesOwnershipWhenServiceCanNoLongerReceiveTail() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        down(state, 42, 100L, 100L, 42);
        state.reset();
        assertFalse(state.consumeOwnedTail(KeyEvent.ACTION_UP, 42, 100L));
    }

    @Test
    public void firstObservedRepeatStillOwnsItsTailAcrossCancellation() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        assertEquals(ReverseSteeringButtonPolicy.Decision.CONSUME,
                apply(state, KeyEvent.ACTION_DOWN, 42, 2, 100L, 300L, 0, 42));
        state.cancel();
        assertTrue(state.consumeOwnedTail(KeyEvent.ACTION_UP, 42, 100L));
    }

    private static ReverseSteeringButtonPolicy.Decision down(
            ReverseSteeringButtonPolicy.State state, int keyCode, long downTime,
            long eventTime, int configuredKeyCode) {
        return apply(state, KeyEvent.ACTION_DOWN, keyCode, 0, downTime, eventTime, 0,
                configuredKeyCode);
    }

    private static ReverseSteeringButtonPolicy.Decision up(
            ReverseSteeringButtonPolicy.State state, int keyCode, long downTime,
            long eventTime, int flags, int configuredKeyCode) {
        return apply(state, KeyEvent.ACTION_UP, keyCode, 0, downTime, eventTime, flags,
                configuredKeyCode);
    }

    private static ReverseSteeringButtonPolicy.Decision apply(
            ReverseSteeringButtonPolicy.State state, int action, int keyCode, int repeatCount,
            long downTime, long eventTime, int flags, int configuredKeyCode) {
        return state.apply(action, keyCode, repeatCount, downTime, eventTime, flags,
                configuredKeyCode, 0L, LONG_PRESS_TIMEOUT, MULTI_PRESS_TIMEOUT);
    }
}
