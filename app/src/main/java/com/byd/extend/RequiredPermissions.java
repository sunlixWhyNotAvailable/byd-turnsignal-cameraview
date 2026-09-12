package com.byd.extend;

import android.content.SharedPreferences;

/** Read-only requirements for enabled features and current explicit UI operations. */
final class RequiredPermissions {
    final boolean camera;
    final boolean overlay;
    final boolean accessibility;
    final boolean install;

    private RequiredPermissions(boolean camera, boolean overlay, boolean accessibility, boolean install) {
        this.camera = camera;
        this.overlay = overlay;
        this.accessibility = accessibility;
        this.install = install;
    }

    static RequiredPermissions read(SharedPreferences preferences, boolean previewRequested,
            boolean learningRequested, boolean installRequested) {
        boolean mirror = RearviewMirrorSettings.enabled(preferences);
        boolean reverse = preferences.getBoolean(ReverseCameraController.PREF_ENABLED,
                ReverseCameraController.DEFAULT_ENABLED);
        boolean camera = previewRequested || mirror || reverse
                || preferences.getBoolean(BlindSpotOverlayController.PREF_ENABLED, false)
                || preferences.getBoolean(BlindSpotOverlayController.PREF_FRONT_ENABLED, false);
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            camera |= ParkingCameraSettings.readRule(preferences, profile).enabled;
        }
        boolean accessibility = learningRequested
                || preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false)
                || reverse && ReverseCameraController.hasAnyFrontIntegration(preferences)
                    && assigned(preferences, CameraButtonBindings.Action.ReverseSource)
                || mirror && (assigned(preferences, CameraButtonBindings.Action.MirrorVisibility)
                    || RearviewMirrorSettings.frontIntegrated(preferences)
                        && assigned(preferences, CameraButtonBindings.Action.MirrorSource));
        boolean hint = preferences.getBoolean(UpdateHintRuntime.PREF_ENABLED, true);
        return new RequiredPermissions(camera, mirror || hint, accessibility, installRequested);
    }

    boolean satisfied(boolean cameraGranted, boolean overlayGranted,
            boolean accessibilityConnected, boolean installGranted) {
        return (!camera || cameraGranted) && (!overlay || overlayGranted)
                && (!accessibility || accessibilityConnected) && (!install || installGranted);
    }

    private static boolean assigned(SharedPreferences preferences, CameraButtonBindings.Action action) {
        return CameraButtonBindings.load(preferences, action).isAssigned();
    }
}
