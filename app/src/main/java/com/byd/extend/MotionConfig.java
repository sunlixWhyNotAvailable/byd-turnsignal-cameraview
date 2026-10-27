package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/** Runtime settings shared by the native monitor, recorder and settings UI. */
public final class MotionConfig {
    public static final String SCREEN_PREFS = "motion_screen";
    public static final String RECORDING_PREFS = "motion_recording";
    public static final String KEY_RECORDING_SENSITIVITY = "recording_sensitivity";
    public static final int DEFAULT_RECORDING_SENSITIVITY = 1;
    public final boolean warningEnabled, recordingEnabled, audioEnabled, remoteViewingEnabled;
    public final int chargeSocThresholdPercent, chargeVoltageThresholdTenths;
    public final int recordingSensitivity;
    public final int lockCondition, beforeSeconds, afterSeconds, quality;
    public final boolean primarySd, fallbackEnabled, internalKeepFree, sdKeepFree, oemCleanup;
    public final int internalLimitGb, sdLimitGb, internalFreeGb, sdFreeGb, oemLimitGb;
    public final int[] cameraOrder;

    private MotionConfig(JSONObject json) {
        warningEnabled = json.optBoolean("warning", false);
        recordingEnabled = json.optBoolean("enabled", false);
        audioEnabled = json.optBoolean("audio", false);
        remoteViewingEnabled = json.optBoolean("remoteViewingEnabled", false);
        recordingSensitivity = bounded(json.optInt("recordingSensitivity",
                DEFAULT_RECORDING_SENSITIVITY), 0, 2);
        chargeSocThresholdPercent = bounded(json.optInt("chargeSocThreshold",
                CameraChargeSettings.DEFAULT_SOC_THRESHOLD_PERCENT),
                CameraChargeSettings.MIN_SOC_THRESHOLD_PERCENT,
                CameraChargeSettings.MAX_SOC_THRESHOLD_PERCENT);
        chargeVoltageThresholdTenths = bounded(json.optInt("chargeVoltageTenths",
                Math.round(CameraChargeSettings.DEFAULT_VOLTAGE_THRESHOLD_VOLTS * 10.0f)),
                CameraChargeSettings.MIN_VOLTAGE_THRESHOLD_TENTHS,
                CameraChargeSettings.MAX_VOLTAGE_THRESHOLD_TENTHS);
        lockCondition = normalizeLockMode(json.optInt("lock", 1));
        beforeSeconds = bounded(json.optInt("before", 10), 1, 30);
        afterSeconds = bounded(json.optInt("after", 10), 1, 30);
        quality = bounded(json.optInt("quality", 1), 0, 2);
        primarySd = json.optBoolean("sd", false);
        fallbackEnabled = json.optBoolean("fallback", false);
        internalLimitGb = Math.max(1, json.optInt("internalLimit", 10));
        sdLimitGb = Math.max(1, json.optInt("sdLimit", 10));
        internalKeepFree = json.optBoolean("internalKeepFree", false);
        sdKeepFree = json.optBoolean("sdKeepFree", false);
        internalFreeGb = Math.max(1, json.optInt("internalFreeGb", 1));
        sdFreeGb = Math.max(1, json.optInt("sdFreeGb", 1));
        oemCleanup = json.optBoolean("oemCleanup", false);
        // Zero is an absent user limit: use the selected volume's full byte capacity.
        oemLimitGb = json.has("oemQuota") ? Math.max(1, json.optInt("oemQuota", 1)) : 0;
        cameraOrder = order(json.optString("order", "0,1,2,3"));
    }

    public static MotionConfig read(Context context) {
        SharedPreferences screen = context.getSharedPreferences(SCREEN_PREFS, Context.MODE_PRIVATE);
        SharedPreferences recording = context.getSharedPreferences(RECORDING_PREFS, Context.MODE_PRIVATE);
        JSONObject json = new JSONObject(recording.getAll());
        int lock = normalizeLockMode(screen.getInt("lock", 1));
        try {
            json.put("warning", screen.getBoolean("enabled", false));
            json.put("recordingSensitivity", screen.getInt(KEY_RECORDING_SENSITIVITY,
                    DEFAULT_RECORDING_SENSITIVITY));
            json.put("lock", lock);
            json.put("remoteViewingEnabled", CameraChargeSettings.isRemoteViewingEnabled(context));
            json.put("chargeSocThreshold", CameraChargeSettings.getSocThresholdPercent(context));
            json.put("chargeVoltageTenths", CameraChargeSettings.getVoltageThresholdTenths(context));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return new MotionConfig(json);
    }

    public static MotionConfig parse(String json) throws JSONException {
        if (json == null || json.length() > 8192) throw new JSONException("invalid motion config");
        return new MotionConfig(new JSONObject(json));
    }

    public static int normalizeLockMode(int value) { return value == 0 ? 0 : 1; }
    public static int nativeSensitivityFor(int recordingSensitivity) {
        return 3 - bounded(recordingSensitivity, 0, 2);
    }
    public static int rThresholdMsFor(int recordingSensitivity) {
        switch (bounded(recordingSensitivity, 0, 2)) {
            case 0: return 6_000;
            case 1: return 3_000;
            default: return 0;
        }
    }

    public boolean motionEnabled() { return warningEnabled || recordingEnabled; }
    public boolean anyEnabled() { return motionEnabled() || remoteViewingEnabled; }
    boolean onlyMotionModesDifferFrom(MotionConfig other) {
        return other != null && (warningEnabled != other.warningEnabled
                || recordingEnabled != other.recordingEnabled)
                && audioEnabled == other.audioEnabled
                && remoteViewingEnabled == other.remoteViewingEnabled
                && chargeSocThresholdPercent == other.chargeSocThresholdPercent
                && chargeVoltageThresholdTenths == other.chargeVoltageThresholdTenths
                && recordingSensitivity == other.recordingSensitivity
                && lockCondition == other.lockCondition
                && beforeSeconds == other.beforeSeconds && afterSeconds == other.afterSeconds
                && quality == other.quality && primarySd == other.primarySd
                && fallbackEnabled == other.fallbackEnabled
                && internalKeepFree == other.internalKeepFree && sdKeepFree == other.sdKeepFree
                && oemCleanup == other.oemCleanup && internalLimitGb == other.internalLimitGb
                && sdLimitGb == other.sdLimitGb && internalFreeGb == other.internalFreeGb
                && sdFreeGb == other.sdFreeGb && oemLimitGb == other.oemLimitGb
                && Arrays.equals(cameraOrder, other.cameraOrder);
    }
    boolean eligible(int power, int lock) {
        return motionEnabled() && power == 0 && (lockCondition == 0 || lockCondition == 1 && lock == 2);
    }
    public int width() { return quality == 0 ? 1280 : quality == 1 ? 1920 : 2560; }
    public int height() { return width() * 9 / 16; }
    public int bitrate() { return quality == 0 ? 2_000_000 : quality == 1 ? 4_000_000 : 8_000_000; }

    public String toJson() {
        try {
            return new JSONObject().put("warning", warningEnabled).put("enabled", recordingEnabled)
                    .put("remoteViewingEnabled", remoteViewingEnabled)
                    .put("chargeSocThreshold", chargeSocThresholdPercent)
                    .put("chargeVoltageTenths", chargeVoltageThresholdTenths)
                    .put("recordingSensitivity", recordingSensitivity)
                    .put("audio", audioEnabled).put("lock", lockCondition)
                    .put("before", beforeSeconds).put("after", afterSeconds).put("quality", quality)
                    .put("sd", primarySd).put("fallback", fallbackEnabled)
                    .put("internalLimit", internalLimitGb).put("sdLimit", sdLimitGb)
                    .put("internalKeepFree", internalKeepFree).put("sdKeepFree", sdKeepFree)
                    .put("internalFreeGb", internalFreeGb).put("sdFreeGb", sdFreeGb)
                    .put("oemCleanup", oemCleanup).put("oemQuota", oemLimitGb == 0 ? null : oemLimitGb)
                    .put("order", cameraOrder[0] + "," + cameraOrder[1] + "," + cameraOrder[2] + "," + cameraOrder[3])
                    .toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    MotionConfig cameraConfig() {
        try {
            JSONObject json = new JSONObject(toJson());
            json.remove("oemCleanup");
            json.remove("oemQuota");
            return new MotionConfig(json);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static int[] order(String value) {
        String[] parts = value.split(",");
        if (parts.length != 4) return new int[]{0, 1, 2, 3};
        int[] result = new int[4];
        int seen = 0;
        try {
            for (int i = 0; i < 4; i++) {
                int direction = Integer.parseInt(parts[i].trim());
                if (direction < 0 || direction > 3 || (seen & (1 << direction)) != 0)
                    return new int[]{0, 1, 2, 3};
                result[i] = direction;
                seen |= 1 << direction;
            }
        } catch (NumberFormatException invalid) { return new int[]{0, 1, 2, 3}; }
        return result;
    }
}
