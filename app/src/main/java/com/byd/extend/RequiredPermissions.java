package com.byd.extend;

import android.content.SharedPreferences;

/** Read-only requirements for enabled features and current explicit UI operations. */
final class RequiredPermissions {
    final boolean camera;
    final boolean overlay;
    final boolean accessibility;
    final boolean install;
    final boolean notificationAccess;
    final boolean writeSecureSettings;

    private RequiredPermissions(boolean camera, boolean overlay, boolean accessibility,
            boolean install, boolean notificationAccess, boolean writeSecureSettings) {
        this.camera = camera;
        this.overlay = overlay;
        this.accessibility = accessibility;
        this.install = install;
        this.notificationAccess = notificationAccess;
        this.writeSecureSettings = writeSecureSettings;
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
        boolean hint = preferences.getBoolean(UpdateHintRuntime.PREF_ENABLED, true);
        boolean adbRecovery = preferences.getBoolean("adb_recovery_enabled", true);
        boolean adbReminder = adbRecovery && preferences.getBoolean("adb_reminder_enabled", true);
        // These three accesses are provisioned and read back independently of runtime switches.
        return new RequiredPermissions(camera, mirror || hint || adbReminder, true, installRequested,
                true, true);
    }

    boolean satisfied(boolean cameraGranted, boolean overlayGranted,
            boolean accessibilityConnected, boolean installGranted) {
        return satisfied(cameraGranted, overlayGranted, accessibilityConnected, installGranted, true);
    }

    boolean satisfied(boolean cameraGranted, boolean overlayGranted,
            boolean accessibilityConnected, boolean installGranted,
            boolean notificationAccessGranted) {
        return satisfied(cameraGranted, overlayGranted, accessibilityConnected, installGranted,
                notificationAccessGranted, true);
    }

    boolean satisfied(boolean cameraGranted, boolean overlayGranted,
            boolean accessibilityConnected, boolean installGranted,
            boolean notificationAccessGranted, boolean writeSecureSettingsGranted) {
        return (!camera || cameraGranted) && (!overlay || overlayGranted)
                && (!accessibility || accessibilityConnected) && (!install || installGranted)
                && (!notificationAccess || notificationAccessGranted)
                && (!writeSecureSettings || writeSecureSettingsGranted);
    }

}
