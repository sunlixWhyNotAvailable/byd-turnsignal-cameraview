package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CameraShellProtocolValidationTest {
    @Test
    public void overlayBinderValidationUsesOnePercentSourceFloor() {
        CameraShellProtocol.OverlaySpec invalid = overlay(0.0099f, 0.01f);
        assertThrows(IllegalArgumentException.class,
                () -> invalid.validate(1920, 990));

        overlay(0.01f, 0.01f).validate(1920, 990);
    }

    @Test
    public void binderSpecsRejectUnknownBufferQuality() {
        DirectCameraCrop crop = DirectCameraCrop.defaultFor(false);
        CameraDewarpConfig left = CameraDewarpConfig.disabled(
                CameraDewarpConfig.LENS_LEFT);
        CameraShellProtocol.OverlaySpec overlay = new CameraShellProtocol.OverlaySpec(
                CameraProfile.REAR_LEFT, 1, CameraDisplayTarget.TABLET,
                400, 300, 0, 0,
                crop.left, crop.top, crop.width, crop.height, crop.aspectMode,
                crop.rotationDegrees, crop.rotationMode, 8, left, crop, 99);
        assertThrows(IllegalArgumentException.class,
                () -> overlay.validate(1920, 990));

        CameraShellProtocol.ReverseOverlaySpec reverse =
                new CameraShellProtocol.ReverseOverlaySpec(
                        1, ReverseCameraLayout.defaults(), ReverseCameraLayout.defaults(), 8,
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR), left,
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT), 99);
        assertThrows(IllegalArgumentException.class,
                () -> reverse.validate(1920, 990));
    }

    @Test
    public void reverseVisibilityMaskAllowsAllOffAndRejectsUnknownBits() {
        CameraShellProtocol.ReverseOverlaySpec allOff = reverse(
                0, CameraBufferQuality.DEFAULT);
        assertEquals(0, allOff.visibilityMask);
        allOff.validate(1920, 990);

        assertThrows(IllegalArgumentException.class, () -> reverse(
                ReverseCameraLayout.VISIBILITY_ALL + 1, CameraBufferQuality.DEFAULT));
    }

    private static CameraShellProtocol.ReverseOverlaySpec reverse(
            int visibilityMask, int bufferQuality) {
        CameraDewarpConfig left = CameraDewarpConfig.disabled(
                CameraDewarpConfig.LENS_LEFT);
        return new CameraShellProtocol.ReverseOverlaySpec(
                2, ReverseCameraLayout.defaults(), ReverseCameraLayout.defaults(), 8,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR), left,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT),
                bufferQuality, visibilityMask);
    }

    private static CameraShellProtocol.OverlaySpec overlay(
            float cropWidth, float cropHeight) {
        return new CameraShellProtocol.OverlaySpec(
                CameraProfile.REAR_LEFT, 1, CameraDisplayTarget.TABLET,
                400, 300, 0, 0,
                0.0f, 0.0f, cropWidth, cropHeight,
                DirectCameraCrop.ASPECT_FREE, 8);
    }
}
