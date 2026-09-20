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

}
