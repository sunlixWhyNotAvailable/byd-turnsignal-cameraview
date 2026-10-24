package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TurnSignalGestureStateTest {
    @Test public void earlyNeutralIsRememberedAndTickFinishesDebounce() {
        TurnSignalGestureState state = readyState();
        assertActivation(state.observeStalk(2, 1_000, 1, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);

        assertEquals(TurnSignalGestureState.Event.NONE,
                state.observeStalk(1, 1_100, 1, 0).event);
        state.tick(1_150);

        assertActivation(state.observeStalk(2, 1_160, 1, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);
    }

    @Test public void confirmedNeutralAllowsFreshCycleBeforeOldDebounceEnds() {
        TurnSignalGestureState state = readyState();
        assertActivation(state.observeStalk(2, 1_000, 1, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);
        state.observeStalk(1, 1_100, 1, 0);

        TurnSignalGestureState.Result next = state.observeStalk(4, 1_110, 1, 0);
        assertActivation(next, TurnSignalGestureState.Event.NEW_CYCLE);
        assertEquals(TurnSignalGestureState.RIGHT, next.direction);
    }

    @Test public void shortToLongPromotesOnceAndOppositeDirectionStartsNewCycle() {
        TurnSignalGestureState state = readyState();
        assertActivation(state.observeStalk(2, 1_000, 1, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);

        TurnSignalGestureState.Result promotion = state.observeStalk(3, 1_050, 1, 0);
        assertActivation(promotion, TurnSignalGestureState.Event.PROMOTION);
        assertEquals(TurnSignalGestureState.LEFT, promotion.direction);
        TurnSignalGestureState.Result duplicate = state.observeStalk(3, 1_060, 1, 0);
        assertEquals(TurnSignalGestureState.Event.NONE, duplicate.event);
        assertEquals(TurnSignalGestureState.Rejection.DUPLICATE, duplicate.rejection);

        TurnSignalGestureState.Result opposite = state.observeStalk(4, 1_070, 1, 0);
        assertActivation(opposite, TurnSignalGestureState.Event.NEW_CYCLE);
        assertEquals(TurnSignalGestureState.RIGHT, opposite.direction);
    }

    @Test public void blinkOnsetCorrelatesInEitherCallbackOrderAndIsConsumedOnce() {
        TurnSignalGestureState blinkFirst = readyState();
        blinkFirst.observeBlink(2, 1_000);
        assertActivation(blinkFirst.observeStalk(3, 1_100, 2, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);

        TurnSignalGestureState queued = readyState();
        queued.observeBlink(2, 1_000);
        queued.tick(1_300);
        assertActivation(queued.observeStalk(3, 1_200, 2, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);

        TurnSignalGestureState stalkFirst = readyState();
        assertActivation(stalkFirst.observeStalk(3, 1_000, 1, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);
        stalkFirst.observeBlink(2, 1_100);
        stalkFirst.observeStalk(1, 1_120, 2, 0);
        TurnSignalGestureState.Result second = stalkFirst.observeStalk(2, 1_130, 2, 0);
        assertFalse(second.activation);
        assertEquals(TurnSignalGestureState.Rejection.BLINK_ONSET_UNAVAILABLE,
                second.rejection);
    }

    @Test public void activeSameSideGestureCancelsAndCannotPromoteBackIntoSession() {
        TurnSignalGestureState state = readyState();
        TurnSignalGestureState.Result cancel = state.observeStalk(2, 1_000, 1, 2);
        assertEquals(TurnSignalGestureState.Event.NEW_CYCLE, cancel.event);
        assertFalse(cancel.activation);
        assertEquals(TurnSignalGestureState.Rejection.ACTIVE_TURN_CANCELLATION,
                cancel.rejection);

        TurnSignalGestureState.Result promotion = state.observeStalk(3, 1_050, 1, 2);
        assertEquals(TurnSignalGestureState.Event.PROMOTION, promotion.event);
        assertFalse(promotion.activation);
        assertEquals(TurnSignalGestureState.Rejection.ACTIVE_TURN_CANCELLATION,
                promotion.rejection);
    }

    @Test public void oppositeShortLongWorksWithoutNeutralWhenStalkArrivesFirst() {
        TurnSignalGestureState state = readyState(2);
        TurnSignalGestureState.Result cancel = state.observeStalk(2, 1_000, 2, 2);
        assertFalse(cancel.activation);
        assertEquals(TurnSignalGestureState.Rejection.ACTIVE_TURN_CANCELLATION,
                cancel.rejection);

        TurnSignalGestureState.Result rightShort = state.observeStalk(4, 1_010, 2, 0);
        assertActivation(rightShort, TurnSignalGestureState.Event.NEW_CYCLE);
        state.observeBlink(4, 1_020);
        assertActivation(state.observeStalk(5, 1_030, 4, 0),
                TurnSignalGestureState.Event.PROMOTION);
    }

    @Test public void oppositeTurnCorrelatesBlinkChangeBeforeStalkAfterNeutral() {
        TurnSignalGestureState state = readyState(4);
        TurnSignalGestureState.Result cancel = state.observeStalk(4, 1_000, 4, 4);
        assertFalse(cancel.activation);
        state.observeStalk(1, 1_010, 4, 4);
        state.observeBlink(2, 1_020);

        TurnSignalGestureState.Result leftShort = state.observeStalk(2, 1_030, 2, 0);
        assertActivation(leftShort, TurnSignalGestureState.Event.NEW_CYCLE);
        assertActivation(state.observeStalk(3, 1_040, 2, 0),
                TurnSignalGestureState.Event.PROMOTION);
    }

    @Test public void activeDirectionSurvivesBlinkFirstCancellationBeforeOppositeStalk() {
        TurnSignalGestureState state = readyState();
        assertActivation(state.observeStalk(3, 1_000, 1, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);
        state.observeBlink(2, 1_010, 2);
        state.observeStalk(1, 1_020, 2, 2);

        // The caller captures LEFT before it cancels that session on the RIGHT blink callback.
        state.observeBlink(4, 1_030, 2);
        TurnSignalGestureState.Result right = state.observeStalk(5, 1_040, 4, 0);
        assertActivation(right, TurnSignalGestureState.Event.NEW_CYCLE);
        assertEquals(TurnSignalGestureState.RIGHT, right.direction);
        assertTrue(right.longMode);
    }

    @Test public void recoveryRequiresNeutralAndSeededBlinkDoesNotCreateAnOnset() {
        TurnSignalGestureState state = new TurnSignalGestureState();
        state.observeHealthySnapshot(5, 1, 900);
        TurnSignalGestureState.Result held = state.observeStalk(5, 1_000, 1, 0);
        assertFalse(held.activation);
        assertEquals(TurnSignalGestureState.Rejection.AWAITING_NEUTRAL, held.rejection);
        assertEquals(TurnSignalGestureState.Rejection.AWAITING_NEUTRAL,
                state.observeStalk(5, 1_010, 1, 0).rejection);

        state.observeHealthySnapshot(1, 2, 1_100);
        TurnSignalGestureState.Result seededDirection = state.observeStalk(3, 1_120, 2, 0);
        assertFalse(seededDirection.activation);
        assertEquals(TurnSignalGestureState.Rejection.BLINK_ONSET_UNAVAILABLE,
                seededDirection.rejection);

        state.resetForRecovery();
        state.observeHealthySnapshot(1, 2, 1_200);
        TurnSignalGestureState.Result afterRecovery = state.observeStalk(3, 1_210, 2, 0);
        assertFalse(afterRecovery.activation);
        assertEquals(TurnSignalGestureState.Rejection.BLINK_ONSET_UNAVAILABLE,
                afterRecovery.rejection);
    }

    @Test public void expiredDeferredIntentCannotConsumeTheNextSameSidePress() {
        TurnSignalGestureState state = readyState();
        TurnSignalDeferredIntent intent = new TurnSignalDeferredIntent();
        assertActivation(state.observeStalk(3, 1_000, 1, 0),
                TurnSignalGestureState.Event.NEW_CYCLE);
        intent.start(2, false, 1_000);
        state.observeStalk(1, 1_100, 1, intent.direction());
        state.observeBlink(1, 2_950, intent.direction());
        assertTrue(intent.expired(3_001));
        intent.clear();
        state.forgetExpiredIntent();
        assertActivation(state.observeStalk(3, 3_001, 1, intent.direction()),
                TurnSignalGestureState.Event.NEW_CYCLE);
    }

    @Test public void recentSameSideBlinkOffIsCancellationAndMismatchedOnsetStaysAmbiguous() {
        TurnSignalGestureState canceled = readyState();
        canceled.observeBlink(2, 1_000);
        canceled.observeBlink(1, 1_100);
        TurnSignalGestureState.Result recentCancel = canceled.observeStalk(3, 1_200, 1, 0);
        assertFalse(recentCancel.activation);
        assertEquals(TurnSignalGestureState.Rejection.RECENT_CANCELLATION,
                recentCancel.rejection);

        TurnSignalGestureState ambiguous = readyState();
        ambiguous.observeBlink(2, 1_000);
        TurnSignalGestureState.Result wrongSide = ambiguous.observeStalk(5, 1_010, 2, 0);
        assertFalse(wrongSide.activation);
        assertEquals(TurnSignalGestureState.Rejection.BLINK_DIRECTION_ACTIVE,
                wrongSide.rejection);
        ambiguous.observeStalk(1, 1_020, 2, 0);
        TurnSignalGestureState.Result laterSameSide = ambiguous.observeStalk(3, 1_030, 2, 0);
        assertFalse(laterSameSide.activation);
        assertEquals(TurnSignalGestureState.Rejection.BLINK_ONSET_UNAVAILABLE,
                laterSameSide.rejection);
    }

    private static TurnSignalGestureState readyState() {
        return readyState(1);
    }

    private static TurnSignalGestureState readyState(int blink) {
        TurnSignalGestureState state = new TurnSignalGestureState();
        state.observeHealthySnapshot(1, blink, 900);
        return state;
    }

    private static void assertActivation(
            TurnSignalGestureState.Result result, TurnSignalGestureState.Event event) {
        assertEquals(event, result.event);
        assertTrue(result.activation);
        assertEquals(TurnSignalGestureState.Rejection.NONE, result.rejection);
    }
}
