package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OemCameraVisibilityRuntimeTest {
    @Test
    public void onlyOneAndZeroAreAcceptedPanoEdges() {
        assertEquals(1, OemCameraVisibilityRuntime.parseState("1"));
        assertEquals(0, OemCameraVisibilityRuntime.parseState(" 0 "));
        assertEquals(-1, OemCameraVisibilityRuntime.parseState("true"));
        assertEquals(-1, OemCameraVisibilityRuntime.parseState("2"));
        assertEquals(-1, OemCameraVisibilityRuntime.parseState(null));
    }

    @Test
    public void eventContractNamesTestedOEMSource() {
        assertEquals("byd.intent.action.pano", OemCameraVisibilityRuntime.ACTION_PANO);
        assertEquals("pano_state", OemCameraVisibilityRuntime.EXTRA_PANO_STATE);
        assertEquals("sys.byd.pano_start", OemCameraVisibilityRuntime.STARTUP_PROPERTY);
    }

    @Test
    public void invalidStateClearsRatherThanReplayingOldClosedValue() {
        OemCameraVisibilityRuntime.State state = new OemCameraVisibilityRuntime.State();
        assertTrue(state.accept("0"));
        assertTrue(state.known());
        assertFalse(state.visible());
        assertEquals(0, state.panoState());

        assertFalse(state.accept("bad"));
        assertFalse(state.known());
        assertFalse(state.visible());
        assertEquals(-1, state.panoState());
    }

    @Test
    public void currentValidStateCanBeReplayedWithoutAnotherRead() {
        OemCameraVisibilityRuntime.State state = new OemCameraVisibilityRuntime.State();
        assertTrue(state.accept("1"));
        assertTrue(state.known());
        assertTrue(state.visible());
        assertEquals(1, state.panoState());
        assertTrue(state.known());
        assertTrue(state.visible());
        assertEquals(1, state.panoState());
    }

    @Test
    public void stoppedStateCannotBeReplayedAsKnown() {
        OemCameraVisibilityRuntime.State state = new OemCameraVisibilityRuntime.State();
        assertTrue(state.accept("0"));
        state.invalidate();
        assertFalse(state.known());
        assertEquals(-1, state.panoState());
    }
}
