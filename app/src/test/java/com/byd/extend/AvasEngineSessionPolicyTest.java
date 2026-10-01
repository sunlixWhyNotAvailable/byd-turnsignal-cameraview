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
        assertEquals(START, p.observeReady(true));
    }

    @Test public void manualSuppressionSurvivesIndicatorsUntilOffOrManualStart() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(true);
        assertEquals(STOP_NOW, p.manualStop());
        p.observeReady(false);
        assertEquals(NONE, p.observeReady(true));
        assertEquals(START, p.manualStart());
        p.manualStop();
        p.observePower(0, false);
        p.observePower(2, false);
        assertEquals(START, p.observeReady(true));
    }

    @Test public void disablingOutputsRequiresFreshReadBeforeAutomaticRestore() {
        AvasEngineSessionPolicy p = enabled();
        p.observePower(2, true);
        p.observeReady(true);
        assertEquals(STOP_NOW, p.configure(true, false));
        assertEquals(NONE, p.manualStart());
        assertEquals(NONE, p.configure(true, true));
        assertEquals(RESTORE, p.observeReady(true));
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
