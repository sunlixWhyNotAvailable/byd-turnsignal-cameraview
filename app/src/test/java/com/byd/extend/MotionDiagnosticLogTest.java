package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.*;

public class MotionDiagnosticLogTest {
    @Test public void ordinaryDetectionLogsOnlyActivityEdgesAndLevelChanges() {
        MotionDetectorShellMain.DetectionLogState state =
                new MotionDetectorShellMain.DetectionLogState();
        assertNull(state.observe(MotionEventPolicy.LEVEL_NONE, false));
        assertEquals("begin", state.observe(1, false));
        assertNull(state.observe(1, false));
        assertEquals("level_change", state.observe(2, false));
        assertEquals("end", state.observe(MotionEventPolicy.LEVEL_NONE, false));
        assertNull(state.observe(MotionEventPolicy.LEVEL_NONE, false));
    }

    @Test public void extendedDetectionKeepsRepeatedActivityAndResetStartsANewWindow() {
        MotionDetectorShellMain.DetectionLogState state =
                new MotionDetectorShellMain.DetectionLogState();
        assertNull(state.observe(MotionEventPolicy.LEVEL_NONE, true));
        assertEquals("begin", state.observe(1, true));
        assertEquals("active", state.observe(1, true));
        assertEquals("active", state.observe(1, true));
        assertEquals("end", state.observe(MotionEventPolicy.LEVEL_NONE, true));

        state.reset();
        assertEquals("state", state.observe(2, false));
        assertNull(state.observe(2, false));
    }
}
