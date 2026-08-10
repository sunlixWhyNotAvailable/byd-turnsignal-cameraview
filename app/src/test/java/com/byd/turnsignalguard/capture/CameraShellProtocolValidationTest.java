package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

public final class CameraShellProtocolValidationTest {
    @Test
    public void overlayBinderValidationUsesOnePercentSourceFloor() {
        CameraShellProtocol.OverlaySpec invalid = overlay(0.0099f, 0.01f);
        assertThrows(IllegalArgumentException.class,
                () -> invalid.validate(1920, 990));

        overlay(0.01f, 0.01f).validate(1920, 990);
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
