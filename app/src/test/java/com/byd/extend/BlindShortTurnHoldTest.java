package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.*;

public final class BlindShortTurnHoldTest {
    private static final int REAR_LEFT = 1, REAR_RIGHT = 2, FRONT_LEFT = 4, FRONT_RIGHT = 8;
    private static final int ALL = 15;
    private static final int STALK = TurnSignalTelemetryController.LIVE_STALK;
    private static final int BLINK = TurnSignalTelemetryController.LIVE_BLINK;

    private BlindShortTurnSignal seeded() {
        BlindShortTurnSignal signal = new BlindShortTurnSignal();
        signal.observe(1, 1, TurnSignalTelemetryController.Source.INITIAL, 0, false, 1);
        return signal;
    }

    private boolean live(BlindShortTurnSignal signal, int stalk, int blink, int mask, long now) {
        return signal.observe(stalk, blink, TurnSignalTelemetryController.Source.CALLBACK,
                mask, false, now);
    }

    @Test public void neutralReleaseDoesNotEndShortTurnAndRepeatedOffDoesNotExtendHold() {
        BlindShortTurnSignal signal = seeded();
        live(signal, 2, 1, STALK, 10);
        live(signal, 1, 1, STALK, 20); // Neutral may arrive even before the logical blink.
        live(signal, 1, 2, BLINK, 30);
        assertEquals(0, signal.endedAt());
        assertTrue(live(signal, 1, 1, BLINK, 1000));
        assertEquals(2, signal.endedDirection());
        BlindCameraHold hold = new BlindCameraHold();
        hold.retainedMask(2, REAR_LEFT, ALL, 30);
        hold.acceptEnd(signal.endedAt(), signal.endedDirection(), 2, REAR_LEFT, 1000);
        assertEquals(REAR_LEFT, hold.retainedMask(1, 0, ALL, 1000));
        assertFalse(live(signal, 1, 1, BLINK, 2000));
        hold.acceptEnd(signal.endedAt(), signal.endedDirection(), 1, REAR_LEFT, 2000);
        assertEquals(4000, hold.nextDeadline());
        assertEquals(REAR_LEFT, hold.retainedMask(1, 0, ALL, 3999));
        assertEquals(0, hold.retainedMask(1, 0, ALL, 4000));
    }

    @Test public void oppositeShortOrLongStartsDoNotCancelPreviousCamera() {
        for (int oppositeStalk : new int[]{4, 5}) {
            BlindShortTurnSignal signal = seeded();
            BlindCameraHold hold = new BlindCameraHold();
            live(signal, 2, 1, STALK, 10);
            live(signal, 2, 2, BLINK, 20);
            hold.retainedMask(2, REAR_LEFT, ALL, 20);
            live(signal, 1, 2, STALK, 30);
            live(signal, oppositeStalk, 2, STALK, 40);
            assertTrue(live(signal, oppositeStalk, 4, BLINK, 100));
            hold.acceptEnd(signal.endedAt(), signal.endedDirection(), 2, REAR_LEFT, 100);
            assertEquals(REAR_LEFT, hold.retainedMask(4, REAR_RIGHT, ALL, 100));
            live(signal, 1, 4, STALK, 200);
            boolean rightShortEnded = live(signal, 1, 1, BLINK, 500);
            assertEquals(oppositeStalk == 4, rightShortEnded);
            hold.acceptEnd(signal.endedAt(), signal.endedDirection(), 4, REAR_RIGHT, 500);
            int rightHold = oppositeStalk == 4 ? REAR_RIGHT : 0;
            assertEquals(REAR_LEFT | rightHold, hold.retainedMask(1, 0, ALL, 500));
            assertEquals(rightHold, hold.retainedMask(1, 0, ALL, 3100));
            assertEquals(0, hold.retainedMask(1, 0, ALL, 3500));
        }
    }

    @Test public void longPromotionAndRepeatedShortNudgeNeverProduceShortCompletion() {
        for (int firstStalk : new int[]{2, 3}) {
            BlindShortTurnSignal signal = seeded();
            live(signal, firstStalk, 1, STALK, 10);
            live(signal, firstStalk, 2, BLINK, 20);
            live(signal, 3, 2, STALK, 30);
            live(signal, 1, 2, STALK, 40);
            live(signal, 2, 2, STALK, 50); // Short nudge cancels a long signal, not a new short.
            assertFalse(live(signal, 2, 1, BLINK, 60));
            assertEquals(0, signal.endedAt());
        }
    }

    @Test public void blinkBeforeStalkCanBeClassifiedButSeedAndReconcileCannotInventGestures() {
        BlindShortTurnSignal signal = seeded();
        live(signal, 1, 4, BLINK, 10);
        live(signal, 4, 4, STALK, 20);
        assertTrue(live(signal, 1, 1, BLINK | STALK, 30));
        assertEquals(4, signal.endedDirection());
        for (TurnSignalTelemetryController.Source source : new TurnSignalTelemetryController.Source[]{
                TurnSignalTelemetryController.Source.INITIAL,
                TurnSignalTelemetryController.Source.RECOVERY_SEED,
                TurnSignalTelemetryController.Source.RECONCILE}) {
            signal = seeded();
            live(signal, 2, 1, STALK, 10);
            live(signal, 2, 2, BLINK, 20);
            signal.observe(1, 1, source, 0, true, 30);
            assertFalse(live(signal, 1, 1, BLINK, 40));
            assertEquals(0, signal.endedAt());
        }
        signal = new BlindShortTurnSignal();
        signal.observe(2, 2, TurnSignalTelemetryController.Source.FALLBACK, 0, false, 1);
        assertFalse(signal.observe(1, 1, TurnSignalTelemetryController.Source.FALLBACK, 0, false, 10));
        signal.observe(4, 4, TurnSignalTelemetryController.Source.FALLBACK, 0, false, 20);
        assertTrue(signal.observe(1, 1, TurnSignalTelemetryController.Source.FALLBACK, 0, false, 30));
    }

    @Test public void hazardsErrorsResetAndUnusedNeutralStalkDoNotLeaveShortOwnership() {
        for (int faultBlink : new int[]{3, 5, 6, 7, 8, 9}) {
            BlindShortTurnSignal signal = seeded();
            live(signal, 2, 1, STALK, 10);
            live(signal, 2, 2, BLINK, 20);
            assertFalse(live(signal, 1, faultBlink, BLINK | STALK, 30));
            assertFalse(live(signal, 1, 1, BLINK, 40));
            assertEquals(0, signal.endedAt());
        }
        BlindShortTurnSignal signal = seeded();
        live(signal, 2, 1, STALK, 10);
        live(signal, 1, 1, STALK, 20);
        signal.observe(1, 1, TurnSignalTelemetryController.Source.RECONCILE, 0, false, 1000);
        live(signal, 1, 2, BLINK, 1500);
        assertFalse(live(signal, 1, 1, BLINK, 2000));
        live(signal, 4, 4, BLINK | STALK, 2100);
        signal.reset();
        assertFalse(live(signal, 1, 1, BLINK, 2200));
    }

    @Test public void softEligibilityChangesPreserveFullDeadlineIncludingAngleOnlyFrontCamera() {
        BlindCameraHold hold = new BlindCameraHold();
        hold.retainedMask(2, REAR_LEFT | FRONT_LEFT, ALL, 10);
        hold.acceptEnd(100, 2, 2, REAR_LEFT | FRONT_LEFT, 100);
        assertEquals(REAR_LEFT, hold.retainedMask(1, FRONT_LEFT, ALL, 100));
        assertEquals(REAR_LEFT | FRONT_LEFT, hold.retainedMask(1, 0, ALL, 200));
        assertEquals(REAR_LEFT, hold.retainedMask(1, FRONT_LEFT, ALL, 300));
        assertEquals(REAR_LEFT | FRONT_LEFT, hold.retainedMask(1, 0, ALL, 3099));
        assertEquals(0, hold.retainedMask(1, 0, ALL, 3100));
    }

    @Test public void sameSideTriggerReplacesOldHoldAndOldExpiryCannotHideNewDemand() {
        BlindCameraHold hold = new BlindCameraHold();
        hold.retainedMask(2, REAR_LEFT, ALL, 10);
        hold.acceptEnd(100, 2, 2, REAR_LEFT, 100);
        assertEquals(REAR_LEFT, hold.retainedMask(1, 0, ALL, 100));
        assertEquals(0, hold.retainedMask(2, REAR_LEFT, ALL, 500));
        assertEquals(0, hold.nextDeadline());
        hold.acceptEnd(700, 2, 2, REAR_LEFT, 700);
        assertEquals(REAR_LEFT, hold.retainedMask(1, 0, ALL, 3100));
        assertEquals(0, hold.retainedMask(1, 0, ALL, 3700));
        assertEquals(REAR_LEFT, REAR_LEFT | hold.retainedMask(2, REAR_LEFT, ALL, 4000));
    }

    @Test public void groupOffAndHardBlocksCancelWithoutResurrectionAndHiddenViewsNeverOpen() {
        BlindCameraHold hold = new BlindCameraHold();
        hold.retainedMask(2, ALL, ALL, 10);
        hold.acceptEnd(100, 2, 2, ALL, 100);
        // Rear sharp-turn companion is held; opposite angle-only front is not.
        assertEquals(REAR_LEFT | REAR_RIGHT | FRONT_LEFT, hold.retainedMask(1, 0, ALL, 100));
        assertEquals(FRONT_LEFT, hold.retainedMask(1, 0, FRONT_LEFT | FRONT_RIGHT, 200));
        assertEquals(FRONT_LEFT, hold.retainedMask(1, 0, ALL, 300));
        assertEquals(0, hold.retainedMask(1, 0, 0, 400));
        assertEquals(0, hold.retainedMask(1, 0, ALL, 500));
        hold.acceptEnd(100, 2, 2, ALL, 600); // Replayed end after a blocker is not a new hold.
        assertEquals(0, hold.retainedMask(1, 0, ALL, 600));
        hold.acceptEnd(700, 4, 4, 0, 700);
        assertEquals(0, hold.retainedMask(1, 0, ALL, 700));
        hold.acceptEnd(800, 4, 4, REAR_RIGHT, 800);
        hold.clear();
        hold.acceptEnd(800, 4, 4, REAR_RIGHT, 900);
        assertEquals(0, hold.retainedMask(1, 0, ALL, 900));
    }

    @Test public void lateUnknownOrMismatchedEndCannotGrantFreshThreeSeconds() {
        BlindCameraHold hold = new BlindCameraHold();
        hold.acceptEnd(0, 0, 2, ALL, 100);
        hold.acceptEnd(500, 2, 4, ALL, 600);
        hold.acceptEnd(1000, 4, 4, ALL, 4000);
        assertEquals(0, hold.retainedMask(1, 0, ALL, 4000));
        hold.acceptEnd(5000, 4, 4, REAR_RIGHT, 5100);
        assertEquals(REAR_RIGHT, hold.retainedMask(1, 0, ALL, 7999));
        assertEquals(0, hold.retainedMask(1, 0, ALL, 8000));
    }

    @Test public void lostSurfaceCancelsOnlyThatCameraEvenIfDisplayImmediatelyReturns() {
        BlindCameraHold hold = new BlindCameraHold();
        hold.retainedMask(2, REAR_LEFT | FRONT_LEFT, ALL, 10);
        hold.acceptEnd(100, 2, 2, REAR_LEFT | FRONT_LEFT, 100);
        assertEquals(REAR_LEFT | FRONT_LEFT, hold.retainedMask(1, 0, ALL, 100));
        hold.cancel(CameraProfile.REAR_LEFT);
        assertEquals(FRONT_LEFT, hold.retainedMask(1, 0, ALL, 101));
        assertEquals(FRONT_LEFT, hold.retainedMask(1, 0, ALL, 3099));
        assertEquals(0, hold.retainedMask(1, 0, ALL, 3100));
    }

    @Test public void oppositeSharpTurnCompanionCannotExtendEarlierHold() {
        BlindCameraHold hold = new BlindCameraHold();
        hold.retainedMask(2, REAR_LEFT, ALL, 10);
        hold.acceptEnd(100, 2, 2, REAR_LEFT, 100);
        assertEquals(REAR_LEFT, hold.retainedMask(1, 0, ALL, 100));
        // Right turn also requests the left camera as its sharp-turn companion.
        assertEquals(0, hold.retainedMask(4, REAR_LEFT | REAR_RIGHT, ALL, 200));
        hold.acceptEnd(500, 4, 4, REAR_LEFT | REAR_RIGHT, 500);
        assertEquals(REAR_LEFT | REAR_RIGHT, hold.retainedMask(1, 0, ALL, 500));
        assertEquals(3100, hold.nextDeadline());
        assertEquals(REAR_RIGHT, hold.retainedMask(1, 0, ALL, 3100));
        assertEquals(0, hold.retainedMask(1, 0, ALL, 3500));
    }
}
