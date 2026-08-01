package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StartupTurnLatchCleanupTest {
    @Test
    public void awakeSessionParsesLegacyAndCleanupFormats() {
        TurnSignalShellMain.ShellBinder.AwakeSessionState five =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(
                        "12,4,1,100,200");
        assertNotNull(five);
        assertEquals(4, five.generation);
        assertTrue(five.interactive);
        assertFalse(five.unpairedInteractiveWake);
        assertEquals(100, five.lastWakeElapsedMs);
        assertEquals(200, five.lastObservedElapsedMs);
        assertEquals(0, five.cleanupAttemptedGeneration);

        TurnSignalShellMain.ShellBinder.AwakeSessionState six =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(
                        "12,4,1,1,100,200");
        assertNotNull(six);
        assertTrue(six.unpairedInteractiveWake);
        assertEquals(0, six.cleanupAttemptedGeneration);

        TurnSignalShellMain.ShellBinder.AwakeSessionState seven =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(
                        "12,4,1,0,100,200,4");
        assertNotNull(seven);
        assertEquals(4, seven.cleanupAttemptedGeneration);
        assertEquals(seven.encode(),
                TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(
                        seven.encode()).encode());

        assertNull(TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(
                "12,4,1,0,100,200,4,unexpected"));
        assertNull(TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(
                "bad,4,1,0,100,200,4"));
    }

    @Test
    public void attemptedMarkerSurvivesRestartAndNewWakeRearms() {
        TurnSignalShellMain.ShellBinder.AwakeSessionState state =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.reconcile(
                        null, 12, true, 1_000);
        assertEquals(1, state.generation);
        state.cleanupAttemptedGeneration = state.generation;

        TurnSignalShellMain.ShellBinder.AwakeSessionState restored =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.reconcile(
                        TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(state.encode()),
                        12, true, 2_000);
        assertEquals(1, restored.generation);
        assertEquals(1, restored.cleanupAttemptedGeneration);

        assertFalse(restored.update(false, false, 3_000));
        assertTrue(restored.update(true, false, 4_000));
        assertEquals(2, restored.generation);
        assertEquals(1, restored.cleanupAttemptedGeneration);
        assertTrue(canRun(restored.generation, restored.generation,
                restored.generation, restored.cleanupAttemptedGeneration,
                0, true, true, true, 1, 1, false, false, false));
    }

    @Test
    public void cleanupRequiresFreshNeutralHealthyIdleState() {
        assertTrue(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 1, false, false, false));

        assertFalse(canRun(7, 7, 0, 6, 0,
                true, true, true, 1, 1, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                false, true, true, 1, 1, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, false, true, 1, 1, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, false, 1, 1, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 2, 1, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 2, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 6, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 3, false, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 1, true, false, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 1, false, true, false));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 1, false, false, true));
        assertFalse(canRun(7, 7, 7, 6, 0,
                true, true, true, 1, 1, false, false, false, false));
    }

    @Test
    public void attemptedOrPersistenceFailedSessionCannotRunAgain() {
        assertFalse(canRun(9, 9, 9, 9, 0,
                true, true, true, 1, 1, false, false, false));
        assertFalse(canRun(9, 9, 9, 8, 9,
                true, true, true, 1, 1, false, false, false));
        assertFalse(canRun(9, 8, 9, 8, 0,
                true, true, true, 1, 1, false, false, false));
    }

    private static boolean canRun(
            long generation,
            long armedGeneration,
            long freshGeneration,
            long attemptedGeneration,
            long persistFailedGeneration,
            boolean interactive,
            boolean enabled,
            boolean telemetryReady,
            int stalk,
            int blink,
            boolean writeInFlight,
            boolean manualCommandPending,
            boolean confirmationPending) {
        return canRun(generation, armedGeneration, freshGeneration, attemptedGeneration,
                persistFailedGeneration, interactive, enabled, telemetryReady, stalk, blink,
                writeInFlight, manualCommandPending, confirmationPending, true);
    }

    private static boolean canRun(
            long generation,
            long armedGeneration,
            long freshGeneration,
            long attemptedGeneration,
            long persistFailedGeneration,
            boolean interactive,
            boolean enabled,
            boolean telemetryReady,
            int stalk,
            int blink,
            boolean writeInFlight,
            boolean manualCommandPending,
            boolean confirmationPending,
            boolean controlIdle) {
        return TurnSignalGuardRuntime.canRunStartupAwakeSessionCleanup(
                interactive,
                enabled,
                generation,
                armedGeneration,
                freshGeneration,
                attemptedGeneration,
                persistFailedGeneration,
                telemetryReady,
                stalk,
                blink,
                writeInFlight,
                manualCommandPending,
                confirmationPending,
                controlIdle);
    }
}
