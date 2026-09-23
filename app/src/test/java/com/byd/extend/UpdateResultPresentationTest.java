package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateResultPresentationTest {
    @Test public void foregroundOffersOnlyAndBackgroundDoesNotReplayThatResult() {
        UpdateResultPresentation state = new UpdateResultPresentation();
        assertFalse(state.accept("check1", true, true, true, true));
        assertTrue(state.hasOffer("check1"));
        assertFalse(state.accept("check1", false, false, true, true));
        assertFalse(state.accept("check1", true, false, true, true));
    }

    @Test public void backgroundNewResultHintsOnceAndRemainsAvailableForOwnUi() {
        UpdateResultPresentation state = new UpdateResultPresentation();
        assertTrue(state.accept("check1", true, false, true, true));
        assertTrue(state.hasOffer("check1"));
        assertFalse(state.accept("check1", false, true, true, true));
        assertFalse(state.accept("check1", true, false, true, true));
    }

    @Test public void declineCannotReplayButNewCheckOfSameVersionCanNotify() {
        UpdateResultPresentation state = new UpdateResultPresentation();
        assertTrue(state.accept("version1-check1", true, false, true, true));
        state.consume("version1-check1");
        assertFalse(state.hasOffer("version1-check1"));
        assertFalse(state.accept("version1-check1", false, false, true, true));
        assertFalse(state.accept("version1-check1", true, false, true, true));
        assertTrue(state.accept("version1-check2", true, false, true, true));
        assertTrue(state.hasOffer("version1-check2"));
    }

    @Test public void disabledOrMissingPermissionNeverPreventsTheOrdinaryOffer() {
        UpdateResultPresentation state = new UpdateResultPresentation();
        assertFalse(state.accept("disabled", true, false, false, true));
        assertTrue(state.hasOffer("disabled"));
        assertFalse(state.accept("denied", true, false, true, false));
        assertTrue(state.hasOffer("denied"));
        assertFalse(state.accept("denied", false, false, true, true));
    }

    @Test public void staleActionsAndCachedEmptyResultDoNotInvalidateNewOffer() {
        UpdateResultPresentation state = new UpdateResultPresentation();
        state.accept("new", true, true, true, true);
        state.consume("old");
        assertFalse(state.accept(null, false, false, true, true));
        assertTrue(state.hasOffer("new"));
        assertFalse(state.hasOffer("old"));
        state.accept(null, true, false, true, true);
        assertFalse(state.hasOffer("new"));
    }

    @Test public void shutdownInvalidatesSavedOffer() {
        UpdateResultPresentation state = new UpdateResultPresentation();
        state.accept("check1", true, false, true, true);
        state.invalidate();
        assertFalse(state.hasOffer("check1"));
        assertFalse(state.accept("check1", false, false, true, true));
    }

    @Test public void offDisplayRetainsResultUntilOneSuccessfulFirstDelivery() {
        UpdateResultPresentation state = pending();
        assertEquals(0L, state.beginAttempt(0));
        assertEquals(-1L, state.delayUntilAttempt(40 * 60_000L));
        assertTrue(state.hasPendingHint());
        assertTrue(state.hasPendingOffer());
        state.setDisplayReady(true);
        long attempt = state.beginAttempt(40 * 60_000L);
        assertTrue(attempt > 0);
        assertEquals(0L, state.beginAttempt(40 * 60_000L));
        assertTrue(state.shown("check1", attempt));
        state.setDisplayReady(false);
        state.setDisplayReady(true);
        assertEquals(0L, state.beginAttempt(50 * 60_000L));
        assertTrue(state.hasOffer("check1"));
    }

    @Test public void technicalFailuresHaveOnlyTwoCompletionAnchoredRetries() {
        UpdateResultPresentation state = pending();
        state.setDisplayReady(true);
        long first = state.beginAttempt(0);
        assertTrue(state.failed("check1", first, 200));
        assertEquals(1_000L, state.delayUntilAttempt(200));
        assertEquals(0L, state.beginAttempt(1199));
        long second = state.beginAttempt(1200);
        assertTrue(second > first);
        assertTrue(state.failed("check1", second, 1300));
        assertEquals(3_000L, state.delayUntilAttempt(1300));
        assertEquals(0L, state.beginAttempt(4299));
        long third = state.beginAttempt(4300);
        assertTrue(third > second);
        assertTrue(state.failed("check1", third, 4400));
        state.setDisplayReady(false);
        state.setDisplayReady(true);
        assertEquals(0L, state.beginAttempt(100_000));
        assertFalse(state.hasPendingHint());
        assertTrue(state.hasOffer("check1"));
    }

    @Test public void displaySuspensionPreservesRetryDeadlineAndRejectsLateCallbacks() {
        UpdateResultPresentation state = pending();
        state.setDisplayReady(true);
        long first = state.beginAttempt(0);
        state.failed("check1", first, 50);
        state.setDisplayReady(false);
        assertEquals(0L, state.beginAttempt(600));
        state.setDisplayReady(true);
        assertEquals(450L, state.delayUntilAttempt(600));
        long second = state.beginAttempt(1050);
        state.setDisplayReady(false);
        assertFalse(state.failed("check1", second, 1100));
        assertEquals(1, state.failureCount());
        state.setDisplayReady(true);
        long resumed = state.beginAttempt(1200);
        assertTrue(resumed > second);
        assertFalse(state.shown("check1", second));
        assertTrue(state.failed("check1", resumed, 1250));
        assertEquals(3_000L, state.delayUntilAttempt(1250));
    }

    @Test public void displayDeferralBeforeAttachDoesNotSpendFailureBudget() {
        UpdateResultPresentation state = pending();
        state.setDisplayReady(true);
        long first = state.beginAttempt(0);
        assertTrue(state.deferred("check1", first));
        state.setDisplayReady(false);
        assertEquals(0, state.failureCount());
        assertEquals(0L, state.beginAttempt(100));
        state.setDisplayReady(true);
        long second = state.beginAttempt(100);
        assertTrue(second > first);
        assertTrue(state.shown("check1", second));
        assertFalse(state.failed("check1", second, 150));
        assertFalse(state.hasPendingHint());
    }

    @Test public void cancellationAndReplacementCannotResurrectOldAttempts() {
        UpdateResultPresentation state = pending();
        state.setDisplayReady(true);
        long first = state.beginAttempt(0);
        assertTrue(state.cancelHint()); // Own UI, preference OFF, or lost permission.
        assertFalse(state.failed("check1", first, 10));
        assertEquals(0L, state.beginAttempt(10_000));
        assertTrue(state.hasOffer("check1"));
        state.accept("check2", true, false, true, true);
        long current = state.beginAttempt(20_000);
        assertFalse(state.cancelled("check1", first));
        assertFalse(state.cancelled("check2", first));
        assertTrue(state.shown("check2", current));
        assertFalse(state.accept("check2", false, false, true, true));
    }

    @Test public void consumedOrInvalidatedOfferDropsDeferredDelivery() {
        UpdateResultPresentation state = pending();
        state.consume("check1");
        state.setDisplayReady(true);
        assertEquals(0L, state.beginAttempt(0));
        assertFalse(state.hasPendingOffer());
        state.accept("check2", true, false, true, true);
        long attempt = state.beginAttempt(1);
        state.invalidate();
        assertFalse(state.shown("check2", attempt));
        assertEquals(0L, state.beginAttempt(10_000));
    }

    @Test public void successAfterRetryStopsFurtherDelivery() {
        UpdateResultPresentation state = pending();
        state.setDisplayReady(true);
        state.failed("check1", state.beginAttempt(0), 0);
        long retry = state.beginAttempt(1000);
        assertTrue(state.shown("check1", retry));
        assertFalse(state.failed("check1", retry, 2000));
        assertEquals(-1L, state.delayUntilAttempt(5000));
    }

    private static UpdateResultPresentation pending() {
        UpdateResultPresentation state = new UpdateResultPresentation();
        assertTrue(state.accept("check1", true, false, true, true));
        return state;
    }

}
