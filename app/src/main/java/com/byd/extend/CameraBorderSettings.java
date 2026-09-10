package com.byd.extend;

import android.content.SharedPreferences;

import java.util.Locale;

/** Independent persisted frame width/color for every configurable camera source. */
public final class CameraBorderSettings {
    public static final int MIN_DP = 0;
    public static final int MAX_DP = 16;
    public static final int DEFAULT_DP = 0;
    public static final int DEFAULT_ARGB = 0xFF000000;

    private CameraBorderSettings() {}

    public static final class Border {
        public final int borderDp;
        public final int borderArgb;

        public Border(int borderDp, int borderArgb) {
            requireValid(borderDp, borderArgb);
            this.borderDp = borderDp;
            this.borderArgb = borderArgb;
        }
    }

    public static Border defaults() {
        return new Border(DEFAULT_DP, DEFAULT_ARGB);
    }

    public static Border forBlind(SharedPreferences preferences, int profileId) {
        return read(preferences, blindPrefix(CameraProfile.of(profileId)), null);
    }

    static Border forBlind(SharedPreferences preferences, CameraProfile profile) {
        return read(preferences, blindPrefix(profile), null);
    }

    public static void writeBlind(
            SharedPreferences preferences, int profileId, Border border) {
        write(preferences, blindPrefix(CameraProfile.of(profileId)), border);
    }

    static void writeBlind(
            SharedPreferences preferences, CameraProfile profile, Border border) {
        write(preferences, blindPrefix(profile), border);
    }

    public static Border forParking(SharedPreferences preferences, int profileId) {
        return forParking(preferences, ParkingCameraProfile.of(profileId));
    }

    public static Border forParking(
            SharedPreferences preferences, ParkingCameraProfile profile) {
        return read(preferences, parkingPrefix(profile), null);
    }

    public static void writeParking(
            SharedPreferences preferences, int profileId, Border border) {
        writeParking(preferences, ParkingCameraProfile.of(profileId), border);
    }

    public static void writeParking(
            SharedPreferences preferences, ParkingCameraProfile profile, Border border) {
        write(preferences, parkingPrefix(profile), border);
    }

    public static Border forReverse(
            SharedPreferences preferences, int cameraIndex, boolean front) {
        return read(preferences, reversePrefix(cameraIndex, front), null);
    }

    public static void writeReverse(
            SharedPreferences preferences, int cameraIndex, boolean front, Border border) {
        write(preferences, reversePrefix(cameraIndex, front), border);
    }

    /** paneId must be ReverseCameraLayout.BACKGROUND_PANE_ID or WIDGET_PANE_ID. */
    public static Border forReverseElement(SharedPreferences preferences, int paneId) {
        return read(preferences, reverseElementPrefix(paneId), null);
    }

    public static void writeReverseElement(
            SharedPreferences preferences, int paneId, Border border) {
        write(preferences, reverseElementPrefix(paneId), border);
    }

    public static Border forMirror(SharedPreferences preferences, boolean front) {
        Border legacy = read(preferences, "mirror_", null);
        return read(preferences, mirrorPrefix(front), legacy);
    }

    public static void writeMirror(
            SharedPreferences preferences, boolean front, Border border) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        SharedPreferences.Editor editor = preferences.edit();
        preserveLegacyMirrorSources(editor, preferences);
        write(editor, mirrorPrefix(front), border);
        editor.apply();
    }

    /** Saves the active source frame beside its existing Mirror calibration preset. */
    public static void saveMirrorPreset(SharedPreferences preferences, boolean front) {
        write(preferences, mirrorPresetPrefix(front), forMirror(preferences, front));
    }

    /** Restores a saved frame; old calibration-only presets preserve the active frame. */
    public static boolean loadMirrorPreset(SharedPreferences preferences, boolean front) {
        String prefix = mirrorPresetPrefix(front);
        if (!hasValidPair(preferences, prefix)) return false;
        Border saved = read(preferences, prefix, null);
        writeMirror(preferences, front, saved);
        return true;
    }

    static String widthKey(String prefix) { return prefix + "border_width"; }
    static String colorKey(String prefix) { return prefix + "border_color"; }

    static String blindPrefix(CameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        return "camera_" + profile.wireName + "_";
    }

    static String parkingPrefix(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        return "parking_camera_" + profile.wireName.toLowerCase(Locale.US) + "_";
    }

    static String reversePrefix(int cameraIndex, boolean front) {
        if (front) CameraDewarpConfig.lensForReverseFrontCamera(cameraIndex);
        else CameraDewarpConfig.lensForReverseCamera(cameraIndex);
        return front ? "reverse_camera_front_" + cameraIndex + "_"
                : "reverse_camera_" + cameraIndex + "_";
    }

    static String reverseElementPrefix(int paneId) {
        if (paneId == ReverseCameraLayout.BACKGROUND_PANE_ID) {
            return "reverse_camera_background_";
        }
        if (paneId == ReverseCameraLayout.WIDGET_PANE_ID) {
            return "reverse_camera_widget_";
        }
        throw new IllegalArgumentException("invalid reverse element pane id: " + paneId);
    }

    static String mirrorPrefix(boolean front) {
        return front ? "mirror_front_" : "mirror_rear_";
    }

    static String mirrorPresetPrefix(boolean front) {
        return front ? "mirror_front_preset_" : "mirror_preset_";
    }

    private static boolean hasPair(SharedPreferences preferences, String prefix) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        return preferences.contains(widthKey(prefix)) && preferences.contains(colorKey(prefix));
    }

    static void preserveLegacyMirrorSources(
            SharedPreferences.Editor editor, SharedPreferences preferences) {
        if (editor == null || preferences == null) {
            throw new IllegalArgumentException("mirror migration arguments required");
        }
        String rear = mirrorPrefix(false);
        String front = mirrorPrefix(true);
        if (hasValidPair(preferences, rear) && hasValidPair(preferences, front)) return;
        Border inherited = read(preferences, "mirror_", null);
        if (!hasValidPair(preferences, rear)) write(editor, rear, inherited);
        if (!hasValidPair(preferences, front)) write(editor, front, inherited);
    }

    private static boolean hasValidPair(SharedPreferences preferences, String prefix) {
        if (!hasPair(preferences, prefix)) return false;
        try {
            return valid(preferences.getInt(widthKey(prefix), -1),
                    preferences.getInt(colorKey(prefix), 0));
        } catch (RuntimeException invalidPreference) {
            return false;
        }
    }

    static Border read(SharedPreferences preferences, String prefix, Border fallback) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        Border safeFallback = fallback == null ? defaults() : fallback;
        String widthKey = widthKey(prefix);
        String colorKey = colorKey(prefix);
        if (!preferences.contains(widthKey) || !preferences.contains(colorKey)) return safeFallback;
        try {
            int width = preferences.getInt(widthKey, safeFallback.borderDp);
            int color = preferences.getInt(colorKey, safeFallback.borderArgb);
            return valid(width, color) ? new Border(width, color) : safeFallback;
        } catch (RuntimeException invalidPreference) {
            return safeFallback;
        }
    }

    static void write(SharedPreferences preferences, String prefix, Border border) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        SharedPreferences.Editor editor = preferences.edit();
        write(editor, prefix, border);
        editor.apply();
    }

    static void write(SharedPreferences.Editor editor, String prefix, Border border) {
        if (editor == null || prefix == null || border == null) {
            throw new IllegalArgumentException("border write arguments required");
        }
        requireValid(border.borderDp, border.borderArgb);
        editor.putInt(widthKey(prefix), border.borderDp)
                .putInt(colorKey(prefix), border.borderArgb);
    }

    static boolean valid(int borderDp, int borderArgb) {
        return borderDp >= MIN_DP && borderDp <= MAX_DP
                && (borderArgb >>> 24) == 0xFF;
    }

    static void requireValid(int borderDp, int borderArgb) {
        if (!valid(borderDp, borderArgb)) {
            throw new IllegalArgumentException("invalid camera border");
        }
    }
}
