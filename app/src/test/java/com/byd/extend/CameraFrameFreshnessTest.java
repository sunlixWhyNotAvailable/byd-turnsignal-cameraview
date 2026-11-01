package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraFrameFreshnessTest {
    @Test public void onlyAdvancingAcquiredFramesRenewReadiness() {
        CameraFrameFreshness frames = new CameraFrameFreshness();
        assertFalse(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 100));
        frames.acquired(10, 100);
        assertFalse(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 100));
        frames.acquired(20, 134);
        assertTrue(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 134));
        frames.acquired(20, 1_000);
        assertTrue(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_134));
        assertFalse(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_135));
        frames.acquired(30, 1_136);
        assertTrue(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_136));
    }

    @Test public void invalidOrResetTimestampsCannotReusePreviousEvidence() {
        CameraFrameFreshness frames = new CameraFrameFreshness();
        frames.acquired(100, 1_000);
        frames.acquired(200, 1_034);
        frames.acquired(0, 1_040);
        assertFalse(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_040));
        frames.acquired(300, 1_050);
        assertFalse(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_050));
        frames.acquired(400, 1_084);
        assertTrue(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_084));
        frames.acquired(10, 1_090);
        assertFalse(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_090));
        frames.acquired(20, 1_124);
        assertTrue(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_124));
        assertFalse(CameraFrameFreshness.fresh(new CameraFrameFreshness().lastAdvanceMs(), 1_124));
        assertFalse(CameraFrameFreshness.fresh(frames.lastAdvanceMs(), 1_123));
    }

    @Test public void liveFramesOverrideOffStateIdcGateWithoutAllowingUnknownPower() {
        CameraFrameFreshness frames = new CameraFrameFreshness();
        boolean idcZero = MotionCameraPreparation.sourceReady(0, 0, 1);
        assertFalse(idcZero);
        assertFalse(MotionCameraPreparation.remoteReady(0, idcZero, frames.lastAdvanceMs(), 100));
        frames.acquired(10, 100);
        frames.acquired(20, 134);
        assertTrue(MotionCameraPreparation.remoteReady(0, idcZero, frames.lastAdvanceMs(), 140));
        assertFalse(MotionCameraPreparation.remoteReady(0, idcZero, frames.lastAdvanceMs(), 1_135));
        assertFalse(MotionCameraPreparation.remoteReady(-1, true, frames.lastAdvanceMs(), 140));
        assertFalse(MotionCameraPreparation.remoteReady(3, true, frames.lastAdvanceMs(), 140));
    }

    @Test public void existingPreparationAndOnStateRulesStillWorkWithoutFrames() {
        assertTrue(MotionCameraPreparation.remoteReady(0,
                MotionCameraPreparation.sourceReady(0, 1, 1), -1, 100));
        assertFalse(MotionCameraPreparation.remoteReady(0,
                MotionCameraPreparation.sourceReady(0, 0, 1), -1, 100));
        assertTrue(MotionCameraPreparation.remoteReady(1, false, -1, 100));
        assertTrue(MotionCameraPreparation.remoteReady(2, false, -1, 100));
    }

    @Test public void oldCameraHostCannotBeRetainedWithoutTheFreshnessOperation() {
        assertEquals("protocol_mismatch",
                TurnSignalShellProtocol.compatibilityError(47, BuildConfig.VERSION_CODE));
        assertEquals("", TurnSignalShellProtocol.compatibilityError(
                TurnSignalShellProtocol.VERSION, BuildConfig.VERSION_CODE));
    }
}
