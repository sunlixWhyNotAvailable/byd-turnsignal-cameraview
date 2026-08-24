package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WindowlessOverlayHostTest {
    @Test
    public void transparencyMapsToOpaqueAlpha() {
        assertEquals(1.0f, WindowlessOverlayHost.alphaForTransparency(0), 0.0f);
        assertEquals(0.5f, WindowlessOverlayHost.alphaForTransparency(50), 0.0f);
        assertEquals(0.0f, WindowlessOverlayHost.alphaForTransparency(100), 0.0f);
    }

    @Test
    public void cameraLayersStayAboveReverse() {
        assertEquals(Integer.MAX_VALUE - 32, WindowlessOverlayHost.REVERSE_LAYER);
        assertEquals(Integer.MAX_VALUE - 16, WindowlessOverlayHost.cameraLayer(0));
        assertEquals(Integer.MAX_VALUE - 5, WindowlessOverlayHost.cameraLayer(11));
        assertTrue(WindowlessOverlayHost.cameraLayer(0) > WindowlessOverlayHost.REVERSE_LAYER);
    }

    @Test
    public void invalidTransparencyAndLayerAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.alphaForTransparency(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.alphaForTransparency(101));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.cameraLayer(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.cameraLayer(12));
    }
}
