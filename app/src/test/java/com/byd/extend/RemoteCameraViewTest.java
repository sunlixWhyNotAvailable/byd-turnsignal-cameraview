package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RemoteCameraViewTest {
    @Test public void viewNamesRoundTrip() {
        for (int view = RemoteCameraView.COMPOSITION; view <= RemoteCameraView.COMBINED; view++) {
            assertEquals(view, RemoteCameraView.parse(RemoteCameraView.name(view)));
        }
        assertTrue(RemoteCameraView.valid(RemoteCameraView.COMPOSITION));
        assertFalse(RemoteCameraView.valid(RemoteCameraView.COMPOSITION - 1));
        assertFalse(RemoteCameraView.valid(RemoteCameraView.COMBINED + 1));
        assertThrows(IllegalArgumentException.class, () -> RemoteCameraView.parse("unknown"));
    }

    @Test public void maskControlsInteriorAvailabilityWithoutHidingExteriorViews() {
        for (int mask = RemoteCameraView.NONE; mask <= RemoteCameraView.BOTH; mask++) {
            RemoteCameraView.validateMask(mask);
            for (int view = RemoteCameraView.COMPOSITION; view <= RemoteCameraView.RIGHT; view++) {
                assertTrue(RemoteCameraView.allowed(view, mask));
            }
            assertEquals((mask & RemoteCameraView.ALLOW_CABIN) != 0,
                    RemoteCameraView.allowed(RemoteCameraView.CABIN, mask));
            assertEquals((mask & RemoteCameraView.ALLOW_DRIVER) != 0,
                    RemoteCameraView.allowed(RemoteCameraView.DRIVER, mask));
            assertEquals(mask != RemoteCameraView.NONE,
                    RemoteCameraView.allowed(RemoteCameraView.INTERIOR, mask));
            assertEquals(mask == RemoteCameraView.NONE ? RemoteCameraView.COMPOSITION : RemoteCameraView.COMBINED,
                    RemoteCameraView.defaultVdoView(mask));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCameraView.validateMask(RemoteCameraView.BOTH + 1));
    }

    @Test public void permittedInteriorsAndExteriorRequirementsFollowSelectedView() {
        assertEquals(RemoteCameraView.ALLOW_CABIN,
                RemoteCameraView.interiorMask(RemoteCameraView.CABIN, RemoteCameraView.BOTH));
        assertEquals(RemoteCameraView.ALLOW_DRIVER,
                RemoteCameraView.interiorMask(RemoteCameraView.DRIVER, RemoteCameraView.BOTH));
        assertEquals(RemoteCameraView.ALLOW_CABIN,
                RemoteCameraView.interiorMask(RemoteCameraView.COMBINED, RemoteCameraView.ALLOW_CABIN));
        assertEquals(RemoteCameraView.BOTH,
                RemoteCameraView.interiorMask(RemoteCameraView.INTERIOR, RemoteCameraView.BOTH));
        assertFalse(RemoteCameraView.needsExterior(RemoteCameraView.INTERIOR));
        assertTrue(RemoteCameraView.needsExterior(RemoteCameraView.COMBINED));
        assertEquals(RemoteCameraView.COMPOSITION,
                RemoteCameraView.retainAllowed(RemoteCameraView.CABIN, RemoteCameraView.NONE));
        assertEquals(RemoteCameraView.DRIVER,
                RemoteCameraView.retainAllowed(RemoteCameraView.DRIVER, RemoteCameraView.BOTH));
    }
}
