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
        assertTrue(OemCameraVisibilityRuntime.ACTION_PANO.startsWith("byd.intent"));
        assertFalse(OemCameraVisibilityRuntime.EXTRA_PANO_STATE.isEmpty());
    }
}
