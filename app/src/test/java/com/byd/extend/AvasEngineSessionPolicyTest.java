package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.byd.extend.AvasEngineSessionPolicy.Action.*;

public final class AvasEngineSessionPolicyTest {
    private AvasEngineSessionPolicy enabled() {
        AvasEngineSessionPolicy p = new AvasEngineSessionPolicy();
        p.configure(true, true);
        return p;
    }

    @Test public void samePowerTwoStaysSilentUntilOkAppears() {
        AvasEngineSessionPolicy p = enabled();
        assertEquals(NONE, p.observePower(2, true));
        assertEquals(NONE, p.observeReady(false));
        assertFalse(p.desiredActive());
        assertEquals(START, p.observeReady(true));
        assertEquals(NONE, p.observeReady(true));
        assertTrue(p.desiredActive());
    }

    @Test public void helperBaselineAndPlaybackRecoveryDoNotRepeatIgnition() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        assertEquals(RESTORE, p.observeReady(true));
        p.playbackFailed();
        assertEquals(RESTORE, p.observeReady(true));
        p.observeReady(null);
        p.playbackFailed();
        assertEquals(NONE, p.configure(true, true));
        assertEquals(RESTORE, p.observeReady(true));
    }

    @Test public void missingOkOrPowerDoesNotBecomeOffAndOnlyOffGetsTail() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(true);
        assertEquals(NONE, p.observeReady(null));
        assertEquals(NONE, p.observeReady(false));
        assertEquals(NONE, p.observePower(255, false));
        assertTrue(p.desiredActive());
        assertEquals(STOP_WITH_TAIL, p.observePower(0, false));
        assertEquals(NONE, p.observePower(0, false));
        assertEquals(NONE, p.observeReady(true));
        assertEquals(NONE, p.observePower(2, false));
        assertEquals(NONE, p.observeReady(true));
        assertEquals(START, p.playbackTailFinished());
    }

    @Test public void testOverridesLiveAndStopRestoresItWithoutAnotherIgnition() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(true);
        assertEquals(NONE, p.manualStop()); // never stops live
        assertTrue(p.desiredActive());
        assertEquals(START, p.manualStart()); // test replaces live output
        assertTrue(p.testActive());
        assertEquals(NONE, p.manualStart());
        p.observeReady(false);
        assertEquals(NONE, p.observeReady(true));
        assertEquals(RESTORE, p.manualStop());
        assertFalse(p.testActive());
        assertTrue(p.desiredActive());
        assertEquals(NONE, p.manualStop()); // duplicate Stop cannot stop resumed live
        p.observePower(0, false);
        p.observePower(2, false);
        assertEquals(NONE, p.observeReady(true));
        assertEquals(START, p.playbackTailFinished());
    }

    @Test public void testBeforeOkDoesNotStartLiveOrRequireAnotherManualStart() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(false);
        assertEquals(START, p.manualStart());
        assertFalse(p.liveRequested());
        assertEquals(STOP_NOW, p.manualStop());
        assertFalse(p.desiredActive());
        assertEquals(START, p.observeReady(true)); // live is independent of the stopped test
        p.observePower(0, false);
        p.observePower(2, false);
        assertEquals(NONE, p.playbackTailFinished());
        p.observeReady(false);
        p.manualStart();
        assertEquals(NONE, p.playbackFailed());
        assertEquals(START, p.observeReady(true)); // failed test must not suppress real ignition
    }

    @Test public void okDuringTestAndTestFailureReleaseToLive() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(false);
        p.manualStart();
        assertEquals(NONE, p.observeReady(true)); // no second output while testing
        assertTrue(p.liveRequested());
        assertEquals(RESTORE, p.manualStop());
        p.manualStart();
        assertEquals(RESTORE, p.playbackFailed());
        assertFalse(p.testActive());
        assertEquals(NONE, p.playbackFailed()); // no infinite live failure/restart loop
    }

    @Test public void disablingOutputsRequiresFreshReadBeforeAutomaticRestore() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(true);
        assertEquals(STOP_NOW, p.configure(true, false));
        assertFalse(p.liveRequested());
        assertEquals(NONE, p.manualStart());
        assertEquals(NONE, p.configure(true, true));
        assertEquals(RESTORE, p.observeReady(true));
    }

    @Test public void manualLiveStartBeforeOkDoesNotRepeatIgnitionOnOk() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        assertEquals(NONE, p.observeReady(false));
        assertEquals(START, p.toggleLive());
        assertTrue(p.liveRequested());
        assertFalse(p.testActive());
        assertEquals(NONE, p.observeReady(true));
        assertEquals(NONE, p.observeReady(true));
        assertTrue(p.desiredActive());
    }

    @Test public void liveToggleDuringTestChangesOnlyDemandAndStopRestoresItQuietly() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(false);
        assertEquals(START, p.manualStart());
        assertEquals(NONE, p.toggleLive());
        assertTrue(p.testActive());
        assertTrue(p.liveRequested());
        assertEquals(RESTORE, p.manualStop());
        assertFalse(p.testActive());

        assertEquals(START, p.manualStart());
        assertEquals(NONE, p.toggleLive());
        assertTrue(p.testActive());
        assertFalse(p.liveRequested());
        assertEquals(STOP_NOW, p.manualStop());
        assertFalse(p.desiredActive());
    }

    @Test public void failedTestRestoresLiveDemandSelectedDuringTest() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(false);
        assertEquals(START, p.manualStart());
        assertEquals(NONE, p.toggleLive());
        assertTrue(p.testActive());
        assertEquals(RESTORE, p.playbackFailed());
        assertFalse(p.testActive());
        assertTrue(p.liveRequested());
    }

    @Test public void repeatedOffDoesNotStopManualTestButNextOffEdgeDoes() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(0, true);
        assertEquals(START, p.manualStart());
        assertEquals(NONE, p.observePower(0, false));
        p.observePower(1, false);
        assertEquals(STOP_WITH_TAIL, p.observePower(0, false));
    }

    @Test public void okCannotStartWithoutKnownPowerAndPowerCannotStartWithoutOk() {
        AvasEngineSessionPolicy p = enabled();
        assertEquals(NONE, p.observeReady(true));
        assertEquals(NONE, p.observePower(255, true));
        assertEquals(RESTORE, p.observePower(2, true));
        for (int raw = 1; raw <= 4; raw++) {
            AvasEngineSessionPolicy other = enabled();
            assertEquals(NONE, other.observePower(raw, true));
            assertFalse(other.desiredActive());
        }
    }

    @Test public void confirmedOkRiseRestartsStoppedLiveButUnknownDoesNotCreateAnEdge() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        assertEquals(RESTORE, p.observeReady(true));
        assertEquals(STOP_WITH_TAIL, p.toggleLive());
        assertEquals(NONE, p.playbackTailFinished());
        assertEquals(NONE, p.observeReady(null));
        assertEquals(NONE, p.observeReady(true));
        assertFalse(p.liveRequested());

        assertEquals(NONE, p.observeReady(false));
        assertEquals(NONE, p.observeReady(null));
        assertEquals(START, p.observeReady(true));
        assertTrue(p.liveRequested());
    }

    @Test public void repeatedConfigurePreservesExplicitLiveStop() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        assertEquals(RESTORE, p.observeReady(true));
        assertEquals(STOP_WITH_TAIL, p.toggleLive());
        assertEquals(NONE, p.configure(true, true));
        assertEquals(NONE, p.observeReady(true));
        assertFalse(p.liveRequested());
    }

    @Test public void hotkeyTailDefersTogglesAndDisableStillCancelsImmediately() {
        AvasEngineSessionPolicy p = enabled();
        assertEquals(START, p.toggleLive());
        assertEquals(STOP_WITH_TAIL, p.toggleLive());
        assertEquals(NONE, p.toggleLive());
        assertEquals(NONE, p.toggleLive());
        assertEquals(NONE, p.playbackTailFinished());
        assertEquals(START, p.toggleLive());
        assertEquals(STOP_WITH_TAIL, p.toggleLive());
        assertEquals(NONE, p.toggleLive());
        assertEquals(STOP_NOW, p.configure(false, true));
        assertEquals(NONE, p.playbackTailFinished());
    }

    @Test public void tailDefersTogglesAndUsesOnlyTheLatestLiveDemand() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        assertEquals(RESTORE, p.observeReady(true));
        assertEquals(STOP_WITH_TAIL, p.observePower(0, false));
        assertEquals(NONE, p.toggleLive());
        assertEquals(NONE, p.toggleLive());
        assertEquals(NONE, p.toggleLive());
        assertTrue(p.liveRequested());
        assertEquals(START, p.playbackTailFinished());
        assertTrue(p.desiredActive());
    }

    @Test public void separateIndicatorEnumsPreserveUnknowns() {
        assertEquals(Boolean.TRUE, AvasEngineReadyMonitor.decode(1, false));
        assertEquals(Boolean.FALSE, AvasEngineReadyMonitor.decode(2, false));
        assertNull(AvasEngineReadyMonitor.decode(0, false));
        assertEquals(Boolean.TRUE, AvasEngineReadyMonitor.decode(1, true));
        assertEquals(Boolean.FALSE, AvasEngineReadyMonitor.decode(0, true));
        assertNull(AvasEngineReadyMonitor.decode(2, true));
        for (int raw : new int[]{3, 4, 5, 6, 7, 255, -10011}) {
            assertNull(AvasEngineReadyMonitor.decode(raw, false));
            assertNull(AvasEngineReadyMonitor.decode(raw, true));
        }
        assertNull(AvasEngineReadyMonitor.decode(null, false));
    }
}
