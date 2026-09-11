package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TurnSignalListenerRecoveryTest {
    @Test
    public void rawZeroIsNeverAValidNeutralOrActionSample() {
        assertFalse(TurnSignalListenerRecovery.validSample(
                TurnSignalListenerRecovery.STALK, 0));
        assertFalse(TurnSignalListenerRecovery.validSample(
                TurnSignalListenerRecovery.BLINK, 0));
        assertTrue(TurnSignalListenerRecovery.validSample(
                TurnSignalListenerRecovery.STALK, 1));
        assertTrue(TurnSignalListenerRecovery.validSample(
                TurnSignalListenerRecovery.STALK, 5));
        assertTrue(TurnSignalListenerRecovery.validSample(
                TurnSignalListenerRecovery.BLINK, 9));
    }

    @Test
    public void invalidStalkNeedsItsCallbackThenFreshCompletePoll() {
        TurnSignalListenerRecovery recovery = new TurnSignalListenerRecovery();

        assertTrue(recovery.invalidate(TurnSignalListenerRecovery.STALK));
        assertFalse(recovery.ready());
        recovery.validCallback(TurnSignalListenerRecovery.BLINK);
        assertFalse(recovery.validPoll(true, 1));
        recovery.validCallback(TurnSignalListenerRecovery.STALK);
        assertFalse(recovery.validPoll(TurnSignalGuardRuntime.pollFresh(1_001L, 750L), 3));
        assertTrue(TurnSignalGuardRuntime.pollFresh(1_000L, 750L));
        assertTrue(recovery.validPoll(true, 3));
        assertTrue(recovery.ready());
        assertFalse(recovery.acceptStalkObservation());
    }

    @Test
    public void recoveryRequiresNeutralBeforeAnewStalkCycle() {
        TurnSignalListenerRecovery recovery = new TurnSignalListenerRecovery();
        recovery.invalidate(TurnSignalListenerRecovery.BLINK);
        recovery.validCallback(TurnSignalListenerRecovery.BLINK);

        assertTrue(recovery.validPoll(true, 2));
        assertFalse(recovery.acceptStalkObservation());
        assertFalse(recovery.validPoll(true, 1));
        assertTrue(recovery.acceptStalkObservation());
    }

    @Test
    public void neutralBeforeCompleteRecoveryCannotResurrectAnInterruptedCycle() {
        TurnSignalListenerRecovery recovery = new TurnSignalListenerRecovery();
        recovery.invalidate(TurnSignalListenerRecovery.STALK);
        recovery.invalidate(TurnSignalListenerRecovery.BLINK);
        recovery.validCallback(TurnSignalListenerRecovery.STALK);
        assertFalse(recovery.validPoll(true, 1));
        recovery.validCallback(TurnSignalListenerRecovery.BLINK);

        assertTrue(recovery.validPoll(true, 3));
        assertFalse(recovery.acceptStalkObservation());
        recovery.validPoll(true, 1);
        assertTrue(recovery.acceptStalkObservation());
    }

    @Test
    public void multipleAndRepeatedInvalidSignalsRecoverInOrder() {
        TurnSignalListenerRecovery recovery = new TurnSignalListenerRecovery();
        recovery.invalidate(TurnSignalListenerRecovery.STALK);
        assertTrue(recovery.invalidate(TurnSignalListenerRecovery.BLINK));
        assertFalse(recovery.invalidate(TurnSignalListenerRecovery.BLINK));
        recovery.validCallback(TurnSignalListenerRecovery.STALK);
        assertFalse(recovery.validPoll(true, 1));
        recovery.validCallback(TurnSignalListenerRecovery.BLINK);
        recovery.invalidate(TurnSignalListenerRecovery.STALK);
        recovery.validCallback(TurnSignalListenerRecovery.STALK);

        assertTrue(recovery.validPoll(true, 1));
        assertTrue(recovery.acceptStalkObservation());
    }

    @Test
    public void pollingAndCallbacksCannotRecoverHardListenerFailure() {
        TurnSignalListenerRecovery recovery = new TurnSignalListenerRecovery();
        recovery.invalidate(TurnSignalListenerRecovery.STALK);
        recovery.validCallback(TurnSignalListenerRecovery.STALK);
        assertTrue(recovery.validPoll(true, 1));

        assertFalse(TurnSignalGuardRuntime.telemetryReady(false, true, true, recovery));
        assertTrue(TurnSignalGuardRuntime.telemetryReady(true, true, true, recovery));
    }

    @Test
    public void subscriptionReinitializationResetsOnlyRecoveryState() {
        TurnSignalListenerRecovery recovery = new TurnSignalListenerRecovery();
        recovery.invalidate(TurnSignalListenerRecovery.BLINK);
        assertFalse(recovery.ready());

        recovery.reset();

        assertTrue(recovery.ready());
        assertTrue(recovery.acceptStalkObservation());
    }
}
