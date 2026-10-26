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

    @Test public void providerMaskKeepsTheSavedVdoChoiceAndAllowsBothTunnelCameras() {
        for (int selected = RemoteCameraView.NONE; selected <= RemoteCameraView.BOTH; selected++) {
            assertEquals(selected, RemoteCameraView.effectiveInteriorMask("vdo", selected));
            assertEquals(RemoteCameraView.BOTH, RemoteCameraView.effectiveInteriorMask("tailscale", selected));
            assertEquals(RemoteCameraView.BOTH, RemoteCameraView.effectiveInteriorMask("zrok", selected));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCameraView.effectiveInteriorMask("unknown", RemoteCameraView.NONE));
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

    @Test public void retainedInteriorSourcesAccumulateSelectionsAndDropRevokedChannels() {
        int retained = RemoteCameraView.retainedInteriors(RemoteCameraView.NONE,
                RemoteCameraView.interiorMask(RemoteCameraView.CABIN, RemoteCameraView.BOTH),
                RemoteCameraView.BOTH);
        assertEquals(RemoteCameraView.ALLOW_CABIN, retained);

        retained = RemoteCameraView.retainedInteriors(retained,
                RemoteCameraView.interiorMask(RemoteCameraView.COMPOSITION, RemoteCameraView.BOTH),
                RemoteCameraView.BOTH);
        assertEquals(RemoteCameraView.ALLOW_CABIN, retained);

        retained = RemoteCameraView.retainedInteriors(retained,
                RemoteCameraView.interiorMask(RemoteCameraView.DRIVER, RemoteCameraView.BOTH),
                RemoteCameraView.BOTH);
        assertEquals(RemoteCameraView.BOTH, retained);

        retained = RemoteCameraView.retainedInteriors(retained,
                RemoteCameraView.interiorMask(RemoteCameraView.COMPOSITION, RemoteCameraView.ALLOW_DRIVER),
                RemoteCameraView.ALLOW_DRIVER);
        assertEquals(RemoteCameraView.ALLOW_DRIVER, retained);
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCameraView.retainedInteriors(RemoteCameraView.NONE,
                        RemoteCameraView.ALLOW_CABIN, RemoteCameraView.ALLOW_DRIVER));
    }
}
