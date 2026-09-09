package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

final class BlindSpotOverlayController {
    static final String PREF_ENABLED = "camera_enabled";
    static final String PREF_REAR_SUPPRESS_WHILE_PANORAMA =
            "camera_rear_suppress_while_panorama";
    static final String PREF_MIN_SPEED = "camera_min_speed_kph";
    static final String PREF_MAX_SPEED = "camera_max_speed_kph";
    static final String PREF_FRONT_ENABLED = "camera_front_enabled";
    static final String PREF_FRONT_SUPPRESS_WHILE_PANORAMA =
            "camera_front_suppress_while_panorama";
    static final String PREF_FRONT_MIN_SPEED = "camera_front_min_speed_kph";
    static final String PREF_FRONT_MAX_SPEED = "camera_front_max_speed_kph";
    static final String PREF_FRONT_MIN_ANGLE = "camera_front_min_angle_deg";
    static final String PREF_FRONT_TURN_REQUIRED = "camera_front_turn_required";
    static final String PREF_REAR_SHARP_TURN_ENABLED = "camera_rear_sharp_turn_enabled";
    static final String PREF_REAR_SHARP_TURN_ANGLE = "camera_rear_sharp_turn_angle_deg";
    static final String PREF_REAR_BSD_ONLY = "camera_rear_bsd_only";
    static final String PREF_CORNER_RADIUS = "camera_corner_radius_dp";
    static final String PREF_TRANSPARENCY_PERCENT = "camera_transparency_percent";
    static final String PREF_SCALE = "camera_overlay_scale_percent";
    static final String PREF_LEFT_SCALE = "camera_left_scale_percent";
    static final String PREF_RIGHT_SCALE = "camera_right_scale_percent";
    static final String PREF_FRONT_LEFT_SCALE = "camera_front_left_scale_percent";
    static final String PREF_FRONT_RIGHT_SCALE = "camera_front_right_scale_percent";
    static final String PREF_LEFT_FRAME_ASPECT = "camera_left_frame_aspect";
    static final String PREF_RIGHT_FRAME_ASPECT = "camera_right_frame_aspect";
    static final String PREF_FRONT_LEFT_FRAME_ASPECT = "camera_front_left_frame_aspect";
    static final String PREF_FRONT_RIGHT_FRAME_ASPECT = "camera_front_right_frame_aspect";
    static final String PREF_LEFT_TARGET = "camera_left_display_target";
    static final String PREF_RIGHT_TARGET = "camera_right_display_target";
    static final String PREF_FRONT_LEFT_TARGET = "camera_front_left_display_target";
    static final String PREF_FRONT_RIGHT_TARGET = "camera_front_right_display_target";
    static final String PREF_LEFT_POSITION = "camera_left_position";
    static final String PREF_RIGHT_POSITION = "camera_right_position";
    static final String PREF_LEFT_X = "camera_left_x";
    static final String PREF_LEFT_Y = "camera_left_y";
    static final String PREF_RIGHT_X = "camera_right_x";
    static final String PREF_RIGHT_Y = "camera_right_y";
    static final String PREF_FRONT_LEFT_X = "camera_front_left_x";
    static final String PREF_FRONT_LEFT_Y = "camera_front_left_y";
    static final String PREF_FRONT_RIGHT_X = "camera_front_right_x";
    static final String PREF_FRONT_RIGHT_Y = "camera_front_right_y";
    // New independent whole-display placement.  X/Y remain readable for v1
    // migration; width/height being present is the unambiguous v2 marker.
    static final String PREF_LEFT_WIDTH = "camera_left_width";
    static final String PREF_LEFT_HEIGHT = "camera_left_height";
    static final String PREF_RIGHT_WIDTH = "camera_right_width";
    static final String PREF_RIGHT_HEIGHT = "camera_right_height";
    static final String PREF_FRONT_LEFT_WIDTH = "camera_front_left_width";
    static final String PREF_FRONT_LEFT_HEIGHT = "camera_front_left_height";
    static final String PREF_FRONT_RIGHT_WIDTH = "camera_front_right_width";
    static final String PREF_FRONT_RIGHT_HEIGHT = "camera_front_right_height";
    static final int PLACEMENT_X = 0;
    static final int PLACEMENT_Y = 1;
    static final int PLACEMENT_WIDTH = 2;
    static final int PLACEMENT_HEIGHT = 3;
    static final String PREF_WARNING_MODE = "camera_bsd_warning_mode";

    static final int DEFAULT_MIN_SPEED_KPH = 10;
    static final int DEFAULT_MAX_SPEED_KPH = 300;
    static final int DEFAULT_FRONT_MIN_SPEED_KPH = 0;
    static final int DEFAULT_FRONT_MAX_SPEED_KPH = 10;
    static final float DEFAULT_FRONT_MIN_ANGLE_DEG = 10.0f;
    static final float DEFAULT_REAR_SHARP_TURN_ANGLE_DEG = 135.0f;
    static final float MIN_REAR_SHARP_TURN_ANGLE_DEG = 0.0f;
    static final float MAX_REAR_SHARP_TURN_ANGLE_DEG = 780.0f;
    static final int DEFAULT_SCALE_PERCENT = 30;
    static final int MIN_SCALE_PERCENT = 5;
    static final int MAX_SCALE_PERCENT = 60;
    static final int DEFAULT_LEFT_POSITION = 0;
    static final int DEFAULT_RIGHT_POSITION = 2;
    static final int DEFAULT_WARNING_MODE = CameraShellProtocol.WARNING_MODE_PULSE;
    static final int DEFAULT_CORNER_RADIUS_DP = 10;
    static final int MAX_CORNER_RADIUS_DP = 48;
    static final int DEFAULT_TRANSPARENCY_PERCENT = 0;
    static final int MIN_TRANSPARENCY_PERCENT = 0;
    static final int MAX_TRANSPARENCY_PERCENT = 100;

    private static final int BLINK_OFF = 1;
    private static final int BLINK_LEFT = 2;
    private static final int BLINK_RIGHT = 4;
    private static final String DIRECT_CAMERA_TAG = "pano_h";
    private static final long STATE_STALE_MS = 750;
    private static final long SURFACE_TIMEOUT_MS = 8_000;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3_000;
    private static final long CAMERA_RETRY_MS = 3_000;
    static final int PREPARATION_WAIT = 0;
    static final int PREPARATION_OPEN = 1;
    static final int PREPARATION_RETRY = 2;
    static final int VISIBILITY_COMPLETION_STALE = 0;
    static final int VISIBILITY_COMPLETION_FAILED = 1;
    static final int VISIBILITY_COMPLETION_APPLY = 2;
    static final int VISIBILITY_COMPLETION_PAUSE = 3;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences settings;
    private final WindowManager windows;
    private final DisplayManager displays;
    private final BiConsumer<String, Object[]> eventSink;
    private final PaneState[] panes = new PaneState[CameraProfile.COUNT];
    private final CameraRetryState cameraRetry = new CameraRetryState();
    private final CameraShellRecoveryGate shellRecovery = new CameraShellRecoveryGate();
    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {
                    displayEvent(ClusterDisplayLifecycle.EVENT_ADDED, id);
                }

                @Override public void onDisplayRemoved(int id) {
                    displayEvent(ClusterDisplayLifecycle.EVENT_REMOVED, id);
                }

                @Override public void onDisplayChanged(int id) {
                    displayEvent(ClusterDisplayLifecycle.EVENT_CHANGED, id);
                }
            };
    private final Runnable staleState = () -> {
        stateValid = false;
        evaluate();
    };
    private final Runnable retryCamera = () -> {
        String trigger = cameraRetry.consume();
        if (trigger == null) return;
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled",
                    "reason", blocked, "trigger", trigger);
            return;
        }
        emit("overlay_camera_retry", "state", "attempt", "reason", trigger);
        rebuild("camera_retry");
    };

    private CameraHelperMain.HelperBinder helper;
    private boolean suspended;
    private boolean reversePriority;
    private boolean hardBlocked;
    private long cameraShellEpoch;
    private boolean uiHidden;
    private boolean shutdown;
    private boolean stateValid;
    private boolean oemPanoramaKnown;
    private boolean oemPanoramaVisible;
    private boolean cameraOpenPending;
    private boolean cameraSessionOpen;
    private int cameraOpenRequestId;
    private int blink = -1;
    private float speedKph = Float.NaN;
    private float steeringAngle = Float.NaN;
    private int requestSequence;
    private int clusterDisplayId = -1;
    private boolean clusterChangePending;
    private boolean leftBsdValid;
    private boolean rightBsdValid;
    private int leftBsdRaw = -1;
    private int rightBsdRaw = -1;

    BlindSpotOverlayController(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.eventSink = eventSink;
        settings = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        migrateOverlayPreferences(settings);
        windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        displays = this.context.getSystemService(DisplayManager.class);
        for (CameraProfile profile : CameraProfile.values()) {
            panes[profile.id] = new PaneState(profile);
        }
        if (displays != null) displays.registerDisplayListener(displayListener, handler);
    }

    static int desiredCameraMask(
            boolean valid, int blink, float speedKph, float steeringAngle,
            boolean rearEnabled, int rearMinSpeed, int rearMaxSpeed,
            boolean frontEnabled, int frontMinSpeed, int frontMaxSpeed,
            boolean frontTurnRequired, float frontMinAngle) {
        return desiredCameraMask(valid, blink, speedKph, steeringAngle,
                rearEnabled, rearMinSpeed, rearMaxSpeed,
                frontEnabled, frontMinSpeed, frontMaxSpeed,
                frontTurnRequired, frontMinAngle,
                false, DEFAULT_REAR_SHARP_TURN_ANGLE_DEG, false,
                false, -1, false, -1);
    }

    static int desiredCameraMask(
            boolean valid, int blink, float speedKph, float steeringAngle,
            boolean rearEnabled, int rearMinSpeed, int rearMaxSpeed,
            boolean frontEnabled, int frontMinSpeed, int frontMaxSpeed,
            boolean frontTurnRequired, float frontMinAngle,
            boolean rearSharpTurnEnabled, float rearSharpTurnAngle,
            boolean rearBsdOnly,
            boolean leftBsdValid, int leftBsdRaw,
            boolean rightBsdValid, int rightBsdRaw) {
        int mask = CameraProfile.desiredMask(valid, blink, speedKph, steeringAngle,
                rearEnabled, rearMinSpeed, rearMaxSpeed,
                frontEnabled, frontMinSpeed, frontMaxSpeed,
                frontTurnRequired, frontMinAngle);
        int normalRear = blink == BLINK_LEFT
                ? CameraProfile.of(CameraProfile.REAR_LEFT).bit()
                : blink == BLINK_RIGHT
                        ? CameraProfile.of(CameraProfile.REAR_RIGHT).bit() : 0;
        if (normalRear == 0 || (mask & normalRear) == 0) return mask;

        boolean matchingBsdValid = blink == BLINK_LEFT ? leftBsdValid : rightBsdValid;
        int matchingBsdRaw = blink == BLINK_LEFT ? leftBsdRaw : rightBsdRaw;
        if (rearBsdOnly && matchingBsdValid
                && BlindSpotWarningRuntime.isValidRaw(matchingBsdRaw)
                && !BlindSpotWarningRuntime.isActiveRaw(true, matchingBsdRaw)) {
            mask &= ~normalRear;
        }

        if (!rearSharpTurnEnabled || !Float.isFinite(steeringAngle)
                || !Float.isFinite(rearSharpTurnAngle)
                || rearSharpTurnAngle < MIN_REAR_SHARP_TURN_ANGLE_DEG
                || rearSharpTurnAngle > MAX_REAR_SHARP_TURN_ANGLE_DEG) return mask;
        if (blink == BLINK_LEFT && steeringAngle > 0.0f
                && steeringAngle >= rearSharpTurnAngle) {
            mask |= CameraProfile.of(CameraProfile.REAR_RIGHT).bit();
        } else if (blink == BLINK_RIGHT && steeringAngle < 0.0f
                && steeringAngle <= -rearSharpTurnAngle) {
            mask |= CameraProfile.of(CameraProfile.REAR_LEFT).bit();
        }
        return mask;
    }

    static float readPosition(
            SharedPreferences settings, boolean right, boolean vertical) {
        return readPosition(settings,
                CameraProfile.of(right ? CameraProfile.REAR_RIGHT : CameraProfile.REAR_LEFT),
                vertical);
    }

    static float readPosition(
            SharedPreferences settings, CameraProfile profile, boolean vertical) {
        String key = positionKey(profile, vertical);
        if (settings.contains(key)) return clamp(settings.getFloat(key, 0.0f), 0.0f, 1.0f);
        if (profile.rear()) {
            String legacyKey = profile.right() ? PREF_RIGHT_POSITION : PREF_LEFT_POSITION;
            if (settings.contains(legacyKey)) {
                int legacy = settings.getInt(legacyKey,
                        profile.right() ? DEFAULT_RIGHT_POSITION : DEFAULT_LEFT_POSITION);
                return legacyPosition(legacy, vertical);
            }
        }
        return defaultPosition(profile, vertical);
    }

    /**
     * Reads a whole-display placement.  Existing v1 anchor/scale values are
     * converted through the exact effective pixel rectangle (including the
     * target insets), so migration does not move or resize an overlay on its
     * first render.  New geometry is kept in normalized complete-display
     * coordinates and is independent of the remaining-space anchor model.
     */
    static CameraPlacement readPlacement(
            SharedPreferences settings, CameraProfile profile,
            int displayWidth, int displayHeight,
            int marginX, int topMargin, int bottomMargin) {
        return readPlacement(settings, profile, readTarget(settings, profile),
                displayWidth, displayHeight, marginX, topMargin, bottomMargin);
    }

    static CameraPlacement readPlacement(
            SharedPreferences settings, CameraProfile profile, int target,
            int displayWidth, int displayHeight,
            int marginX, int topMargin, int bottomMargin) {
        if (settings == null || profile == null) {
            throw new IllegalArgumentException("camera placement arguments required");
        }
        String xKey = placementKey(profile, target, PLACEMENT_X);
        String yKey = placementKey(profile, target, PLACEMENT_Y);
        String widthKey = placementKey(profile, target, PLACEMENT_WIDTH);
        String heightKey = placementKey(profile, target, PLACEMENT_HEIGHT);
        try {
            if (settings.contains(widthKey) && settings.contains(heightKey)
                    && settings.contains(xKey) && settings.contains(yKey)) {
                return CameraPlacement.of(
                        settings.getFloat(xKey, 0.0f),
                        settings.getFloat(yKey, 0.0f),
                        settings.getFloat(widthKey, 0.0f),
                        settings.getFloat(heightKey, 0.0f));
            }
        } catch (RuntimeException invalidPlacement) {
            // Malformed v2 geometry falls back to the read-only v1 conversion.
        }
        Float legacyWidth = null;
        Float legacyHeight = null;
        try {
            if (settings.contains(placementWidthKey(profile))
                    && settings.contains(placementHeightKey(profile))
                    && settings.contains(positionKey(profile, false))
                    && settings.contains(positionKey(profile, true))) {
                legacyWidth = settings.getFloat(placementWidthKey(profile), 0.0f);
                legacyHeight = settings.getFloat(placementHeightKey(profile), 0.0f);
            }
        } catch (RuntimeException invalidLegacyPlacement) {
            // Malformed shared v2 geometry retains its historical anchor/scale fallback.
        }
        return legacyPlacement(profile, displayWidth, displayHeight,
                marginX, topMargin, bottomMargin, readScale(settings, profile),
                readFrameAspect(settings, profile),
                readPosition(settings, profile, false),
                readPosition(settings, profile, true), legacyWidth, legacyHeight);
    }

    /** Saves an explicit destination edit in normalized complete-display units. */
    static void writePlacement(
            SharedPreferences settings, CameraProfile profile, CameraPlacement placement) {
        writePlacement(settings, profile, readTarget(settings, profile), placement);
    }

    static void writePlacement(SharedPreferences settings, CameraProfile profile, int target,
            CameraPlacement placement) {
        if (settings == null || profile == null || placement == null) {
            throw new IllegalArgumentException("camera placement arguments required");
        }
        SharedPreferences.Editor editor = settings.edit();
        writePlacement(editor, profile, target, placement);
        editor.apply();
    }

    static void writePlacement(SharedPreferences.Editor editor, CameraProfile profile, int target,
            CameraPlacement placement) {
        if (editor == null || profile == null || placement == null) {
            throw new IllegalArgumentException("camera placement arguments required");
        }
        CameraPlacement safe = CameraPlacement.of(
                placement.x, placement.y, placement.width, placement.height);
        editor.putFloat(placementKey(profile, target, PLACEMENT_X), safe.x)
                .putFloat(placementKey(profile, target, PLACEMENT_Y), safe.y)
                .putFloat(placementKey(profile, target, PLACEMENT_WIDTH), safe.width)
                .putFloat(placementKey(profile, target, PLACEMENT_HEIGHT), safe.height);
    }

    static void writePlacement(
            SharedPreferences settings, CameraProfile profile,
            float x, float y, float width, float height) {
        writePlacement(settings, profile, CameraPlacement.bounded(x, y, width, height));
    }

    static void writePlacement(
            SharedPreferences settings, CameraProfile profile, int target,
            float x, float y, float width, float height) {
        writePlacement(settings, profile, target, CameraPlacement.bounded(x, y, width, height));
    }

    static CameraPlacement defaultPlacement(
            CameraProfile profile, int target,
            int tabletWidth, int tabletHeight,
            int tabletMarginX, int tabletTopMargin, int tabletBottomMargin) {
        CameraPlacement tablet = CameraPlacement.fromLegacy(
                tabletWidth, tabletHeight, defaultScale(profile), defaultFrameAspect(profile),
                defaultPosition(profile, false), defaultPosition(profile, true),
                tabletMarginX, tabletTopMargin, tabletBottomMargin);
        if (target != CameraDisplayTarget.CLUSTER) return tablet;
        return CameraPlacement.of((1.0f - tablet.width) / 2.0f,
                (1.0f - tablet.height) / 2.0f, tablet.width, tablet.height);
    }

    /** Pure v1/v2 import seam matching readPlacement's explicit-rect then anchor fallback. */
    static CameraPlacement legacyPlacement(
            CameraProfile profile, int displayWidth, int displayHeight,
            int marginX, int topMargin, int bottomMargin,
            int scale, float frameAspect, float x, float y,
            Float width, Float height) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        if (width != null && height != null) {
            try {
                return CameraPlacement.of(x, y, width, height);
            } catch (RuntimeException invalidExplicitPlacement) {
                // Old malformed explicit geometry had the same anchor/scale fallback at runtime.
            }
        }
        float aspect = isValidFrameAspect(frameAspect)
                ? frameAspect : defaultFrameAspect(profile);
        return CameraPlacement.fromLegacy(displayWidth, displayHeight, scale, aspect,
                clamp(x, 0.0f, 1.0f), clamp(y, 0.0f, 1.0f),
                marginX, topMargin, bottomMargin);
    }

    static float defaultPosition(CameraProfile profile, boolean vertical) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        return CameraDefaults.blindPosition(profile, vertical);
    }

    static float legacyPosition(int position, boolean vertical) {
        int safe = clamp(position, 0, 8);
        return (vertical ? safe / 3 : safe % 3) / 2.0f;
    }

    static void migrateOverlayPreferences(SharedPreferences settings) {
        int sharedScale = migratedScale(false, 0,
                settings.getInt(PREF_SCALE, DEFAULT_SCALE_PERCENT));
        SharedPreferences.Editor editor = settings.edit();
        boolean changed = false;
        for (CameraProfile profile : CameraProfile.values()) {
            String scaleKey = scaleKey(profile);
            if (!settings.contains(scaleKey)) {
                editor.putInt(scaleKey, profile.rear()
                        ? sharedScale : defaultScale(profile));
                changed = true;
            }
            String targetKey = targetKey(profile);
            if (!settings.contains(targetKey)) {
                editor.putInt(targetKey, defaultTarget(profile));
                changed = true;
            }
        }
        if (!settings.contains(PREF_FRONT_ENABLED)) {
            editor.putBoolean(PREF_FRONT_ENABLED, false);
            changed = true;
        }
        if (!settings.contains(PREF_REAR_SUPPRESS_WHILE_PANORAMA)) {
            editor.putBoolean(PREF_REAR_SUPPRESS_WHILE_PANORAMA, true);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_SUPPRESS_WHILE_PANORAMA)) {
            editor.putBoolean(PREF_FRONT_SUPPRESS_WHILE_PANORAMA, true);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_MIN_SPEED)) {
            editor.putInt(PREF_FRONT_MIN_SPEED, DEFAULT_FRONT_MIN_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_MIN_SPEED)) {
            editor.putInt(PREF_MIN_SPEED, DEFAULT_MIN_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_MAX_SPEED)) {
            editor.putInt(PREF_MAX_SPEED, DEFAULT_MAX_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_MAX_SPEED)) {
            editor.putInt(PREF_FRONT_MAX_SPEED, DEFAULT_FRONT_MAX_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_MIN_ANGLE)) {
            editor.putFloat(PREF_FRONT_MIN_ANGLE, DEFAULT_FRONT_MIN_ANGLE_DEG);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_TURN_REQUIRED)) {
            editor.putBoolean(PREF_FRONT_TURN_REQUIRED, true);
            changed = true;
        }
        if (!settings.contains(PREF_CORNER_RADIUS)) {
            editor.putInt(PREF_CORNER_RADIUS, DEFAULT_CORNER_RADIUS_DP);
            changed = true;
        }
        if (!settings.contains(PREF_TRANSPARENCY_PERCENT)) {
            editor.putInt(PREF_TRANSPARENCY_PERCENT, DEFAULT_TRANSPARENCY_PERCENT);
            changed = true;
        }
        if (changed) editor.apply();
    }

    static int migratedScale(boolean sidePresent, int sideScale, int sharedScale) {
        return clamp(sidePresent ? sideScale : sharedScale,
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
    }

    static int readScale(SharedPreferences settings, boolean right) {
        return readScale(settings,
                CameraProfile.of(right ? CameraProfile.REAR_RIGHT : CameraProfile.REAR_LEFT));
    }

    static int readScale(SharedPreferences settings, CameraProfile profile) {
        int fallback = profile.rear()
                ? settings.getInt(PREF_SCALE, defaultScale(profile))
                : defaultScale(profile);
        return clamp(settings.getInt(scaleKey(profile), fallback),
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
    }

    static int defaultScale(CameraProfile profile) {
        if (profile == null || !CameraProfile.isValid(profile.id)) {
            throw new IllegalArgumentException("camera profile required");
        }
        return DEFAULT_SCALE_PERCENT;
    }

    static int defaultTarget(CameraProfile profile) {
        if (profile == null || !CameraProfile.isValid(profile.id)) {
            throw new IllegalArgumentException("camera profile required");
        }
        return CameraDisplayTarget.TABLET;
    }

    static float defaultFrameAspect(CameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        return CameraDefaults.blindFrameAspect(profile);
    }

    /** One destination fallback for preview, export and production, independent of live drafts. */
    static float readFrameAspect(SharedPreferences settings, CameraProfile profile) {
        DirectCameraCrop raw = DirectCameraCrop.load(settings, profile);
        DirectCameraCrop active = CameraDewarpConfig.loadForProfile(settings, profile).enabled
                ? DirectCameraCrop.loadCorrected(settings, profile, raw) : raw;
        return readFrameAspect(settings, profile, active.outputAspect());
    }

    /** Reads the stored aspect or its effective fallback without changing preferences. */
    static float readFrameAspect(
            SharedPreferences settings, CameraProfile profile, float fallback) {
        float safeFallback = isValidFrameAspect(fallback)
                ? fallback : DirectCameraCrop.OUTPUT_ASPECT;
        String key = frameAspectKey(profile);
        if (settings.contains(key)) {
            try {
                float stored = settings.getFloat(key, safeFallback);
                if (isValidFrameAspect(stored)) return stored;
            } catch (ClassCastException ignored) {
                // A malformed key uses the same read-only fallback as an absent key.
            }
        }
        return hasRawPreferences(settings, profile) ? safeFallback : defaultFrameAspect(profile);
    }

    /** Pins the pre-edit destination in the caller's geometry transaction, never on read. */
    static void pinFrameAspect(SharedPreferences.Editor editor,
            SharedPreferences settings, CameraProfile profile) {
        String key = frameAspectKey(profile);
        try {
            if (settings.contains(key) && isValidFrameAspect(settings.getFloat(key, 0.0f))) {
                return;
            }
        } catch (ClassCastException ignored) {
            // An explicit edit may replace the malformed key with its prior effective value.
        }
        editor.putFloat(key, readFrameAspect(settings, profile));
    }

    static boolean isValidFrameAspect(float aspect) {
        return Float.isFinite(aspect) && aspect > 0.0f;
    }

    static int readTarget(SharedPreferences settings, boolean right) {
        return readTarget(settings,
                CameraProfile.of(right ? CameraProfile.REAR_RIGHT : CameraProfile.REAR_LEFT));
    }

    static int readTarget(SharedPreferences settings, CameraProfile profile) {
        int target = settings.getInt(targetKey(profile), defaultTarget(profile));
        return CameraDisplayTarget.isValid(target) ? target : CameraDisplayTarget.TABLET;
    }

    static int readCornerRadius(SharedPreferences settings) {
        return clamp(settings.getInt(PREF_CORNER_RADIUS, DEFAULT_CORNER_RADIUS_DP),
                0, MAX_CORNER_RADIUS_DP);
    }

    static int readTransparencyPercent(SharedPreferences settings) {
        try {
            return clamp(settings.getInt(PREF_TRANSPARENCY_PERCENT,
                            DEFAULT_TRANSPARENCY_PERCENT),
                    MIN_TRANSPARENCY_PERCENT, MAX_TRANSPARENCY_PERCENT);
        } catch (ClassCastException ignored) {
            return DEFAULT_TRANSPARENCY_PERCENT;
        }
    }

    static int[] fitAspect(
            int requestedWidth, int maxWidth, int maxHeight, float aspect) {
        float safeAspect = Float.isFinite(aspect) && aspect > 0.0f
                ? aspect : DirectCameraCrop.OUTPUT_ASPECT;
        int width = Math.max(1, Math.min(Math.max(1, requestedWidth),
                Math.min(Math.max(1, maxWidth),
                        Math.round(Math.max(1, maxHeight) * safeAspect))));
        return new int[]{width, Math.max(1, Math.round(width / safeAspect))};
    }

    void attachHelper(CameraHelperMain.HelperBinder value) {
        handler.post(() -> {
            helper = value;
            applySettingsOnMain();
        });
    }

    void setSuspended(boolean value) {
        handler.post(() -> {
            if (suspended == value) return;
            suspended = value;
            updateHardBlock("preview_handoff");
        });
    }

    void setReversePriority(boolean value) {
        handler.post(() -> {
            if (reversePriority == value) return;
            reversePriority = value;
            updateHardBlock("reverse_priority");
        });
    }

    static boolean isHardBlocked(
            boolean uiHidden, boolean suspended, boolean reversePriority) {
        return uiHidden || suspended || reversePriority;
    }

    private boolean isHardBlocked() {
        return isHardBlocked(uiHidden, suspended, reversePriority);
    }

    static int hardBlockEdge(boolean previous, boolean next) {
        return previous == next ? 0 : next ? 1 : -1;
    }

    private void updateHardBlock(String reason) {
        boolean next = isHardBlocked();
        int edge = hardBlockEdge(hardBlocked, next);
        if (edge == 0) return;
        hardBlocked = next;
        if (edge > 0) {
            cancelCameraRetry("overlay_hard_blocked");
            destroyAll(reason);
        } else {
            applySettingsOnMain();
        }
    }

    void setUiHidden(boolean value) {
        handler.post(() -> {
            if (uiHidden == value) return;
            uiHidden = value;
            updateHardBlock("ui_hidden");
        });
    }

    void applySettings() {
        handler.post(this::applySettingsOnMain);
    }

    void applyWarningSettings() {
        handler.post(this::applyWarnings);
    }

    void applyTriggerSettings() {
        handler.post(this::evaluate);
    }

    void oemVisibility(boolean known, boolean visible) {
        handler.post(() -> {
            oemPanoramaKnown = known;
            oemPanoramaVisible = known && visible;
            evaluate();
        });
    }

    static boolean panoramaSuppresses(
            boolean known, boolean visible, boolean suppressWhilePanorama) {
        return suppressWhilePanorama && known && visible;
    }

    static boolean readPanoramaSuppression(
            SharedPreferences settings, boolean front) {
        try {
            return settings.getBoolean(front
                    ? PREF_FRONT_SUPPRESS_WHILE_PANORAMA
                    : PREF_REAR_SUPPRESS_WHILE_PANORAMA, true);
        } catch (RuntimeException invalidPreference) {
            return true;
        }
    }

    void acceptEvent(String line) {
        if (line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            if ("vehicle_state".equals(kind)) {
                boolean valid = event.optBoolean("valid");
                int nextBlink = event.optInt("blink", -1);
                float nextSpeed = valid
                        ? (float) event.optDouble("speed_kph", Double.NaN) : Float.NaN;
                double angleValue = event.has("steering_angle_deg")
                        ? event.optDouble("steering_angle_deg", Double.NaN)
                        : event.optDouble("angle_deg", Double.NaN);
                float nextAngle = valid ? (float) angleValue : Float.NaN;
                handler.post(() -> acceptVehicleState(
                        valid, nextBlink, nextSpeed, nextAngle));
            } else if ("bsd_state".equals(kind)) {
                boolean listenerOk = event.optBoolean("listener_ok");
                boolean nextLeftValid = listenerOk && event.optBoolean("left_valid");
                boolean nextRightValid = listenerOk && event.optBoolean("right_valid");
                int nextLeftRaw = nextLeftValid ? event.optInt("left_raw", -1) : -1;
                int nextRightRaw = nextRightValid ? event.optInt("right_raw", -1) : -1;
                handler.post(() -> acceptBsdState(
                        nextLeftValid, nextLeftRaw, nextRightValid, nextRightRaw));
            } else if ("camera_discovery".equals(kind) && event.optBoolean("ok")) {
                handler.post(() -> {
                    if (!isHardBlocked()
                            && shouldRebuildAfterCameraDiscovery(hasPreparedOverlay())) {
                        applySettingsOnMain();
                    }
                });
            } else if ("camera_opened".equals(kind)
                    && "helper".equals(event.optString("source"))
                    && CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                            event.optString("camera_owner"))
                    && DIRECT_CAMERA_TAG.equals(event.optString("camera_tag"))) {
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> cameraOpened(requestId));
            } else if ("camera_error".equals(kind)
                    && "helper".equals(event.optString("source"))
                    && CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                            event.optString("camera_owner"))) {
                String stage = event.optString("stage", kind);
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> {
                    if (matchesCameraOpenEvent(
                            cameraOpenPending || cameraSessionOpen,
                            cameraOpenRequestId, requestId)) {
                        cameraUnavailable(stage);
                    }
                });
            } else if ("camera_overlay_first_frame".equals(kind)) {
                OverlayFrameArm arm = OverlayFrameArm.fromEvent(event);
                handler.post(() -> firstFrameAvailable(arm));
            } else if ("camera_overlay_surface".equals(kind)
                    && "destroyed".equals(event.optString("state"))) {
                int cameraId = event.optInt("camera_id", -1);
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> paneSurfaceDestroyed(cameraId, requestId));
            } else if (ClusterDisplayLifecycle.isUnavailableError(event)) {
                int cameraId = event.optInt("camera_id", -1);
                int requestId = event.optInt("request_id", -1);
                int generation = event.optInt("surface_generation", -1);
                handler.post(() -> clusterDestinationUnavailable(
                        event, cameraId, requestId, generation));
            } else if ("camera_overlay_error".equals(kind)) {
                String stage = event.optString("stage", kind);
                if ("arm_first_frame".equals(stage)) {
                    OverlayFrameArm arm = OverlayFrameArm.fromEvent(event);
                    handler.post(() -> {
                        PaneState pane = pane(arm.cameraId);
                        if (pane == null || pane.requestId != arm.requestId
                                || pane.generation != arm.surfaceGeneration
                                || !pane.freshness.current(arm.frameArmEpoch)) return;
                        paneUnavailable(arm.cameraId, arm.requestId, stage);
                    });
                } else {
                    int cameraId = event.optInt("camera_id", -1);
                    int requestId = event.optInt("request_id", -1);
                    handler.post(() -> paneUnavailable(cameraId, requestId, stage));
                }
            } else if ("camera_shell_attached".equals(kind)) {
                long epoch = event.optLong("camera_shell_epoch", 0);
                handler.post(() -> cameraShellAttached(epoch));
            } else if ("camera_shell_died".equals(kind)) {
                long epoch = event.optLong("camera_shell_epoch", 0);
                handler.post(() -> cameraShellDied(epoch));
            } else if ("camera_shell_recovery_failed".equals(kind)) {
                handler.post(() -> {
                    if (!shellRecovery.pending()) return;
                    shellRecovery.clear();
                    emit("overlay_camera_epoch_recovery", "state", "failed");
                });
            }
        } catch (Throwable error) {
            emit("overlay_event_parse_error", "error", summary(error));
        }
    }

    void shutdown() {
        shutdown = true;
        handler.removeCallbacks(staleState);
        cancelCameraRetry("overlay_shutdown");
        shellRecovery.clear();
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        destroyAll("overlay_shutdown");
        helper = null;
    }

    private void applySettingsOnMain() {
        if (isHardBlocked()) return;
        migrateOverlayPreferences(settings);
        if (cameraUnavailableReason() != null) {
            destroyAll(cameraUnavailableReason());
            return;
        }
        rebuild("settings_changed");
    }

    private String cameraUnavailableReason() {
        if (shutdown) return "overlay_shutdown";
        if (!anyLaneEnabled()) return "overlay_disabled";
        if (isHardBlocked()) return "overlay_hard_blocked";
        if (helper == null) return "helper_unavailable";
        if (windows == null) return "window_manager_unavailable";
        return null;
    }

    private boolean anyLaneEnabled() {
        return settings.getBoolean(PREF_ENABLED, false)
                || settings.getBoolean(PREF_FRONT_ENABLED, false);
    }

    private boolean clusterTargetRequired() {
        boolean rearEnabled = settings.getBoolean(PREF_ENABLED, false);
        boolean frontEnabled = settings.getBoolean(PREF_FRONT_ENABLED, false);
        for (PaneState pane : panes) {
            if ((pane.profile.rear() ? rearEnabled : frontEnabled)
                    && readTarget(settings, pane.profile) == CameraDisplayTarget.CLUSTER) {
                return true;
            }
        }
        return false;
    }

    private void displayEvent(int event, int displayId) {
        boolean clusterRequired = clusterTargetRequired();
        if (!clusterRequired) return;
        Display selected = CameraDisplayTarget.resolve(context, CameraDisplayTarget.CLUSTER);
        int action = ClusterDisplayLifecycle.action(CameraDisplayTarget.CLUSTER,
                clusterDisplayId, selected == null ? -1 : selected.getDisplayId(),
                displayId, event,
                cameraUnavailableReason() == null && !shellRecovery.pending());
        if (action == ClusterDisplayLifecycle.INVALIDATE) {
            rebuild("cluster_display_removed");
        } else if (action == ClusterDisplayLifecycle.WAKE) {
            applySettingsOnMain();
        } else if (action == ClusterDisplayLifecycle.NOTE_CHANGE) {
            clusterChangePending = true;
        }
    }

    private void rebuild(String reason) {
        cancelCameraRetry(reason);
        destroyAll(reason);
        if (cameraUnavailableReason() != null) return;
        if (clusterTargetRequired()) {
            Display cluster = CameraDisplayTarget.resolve(
                    context, CameraDisplayTarget.CLUSTER);
            if (cluster == null) {
                emit("overlay_camera_output_unavailable",
                        "reason", ClusterDisplayLifecycle.UNAVAILABLE_STAGE);
                return;
            }
            clusterDisplayId = cluster.getDisplayId();
        }
        boolean rearEnabled = settings.getBoolean(PREF_ENABLED, false);
        boolean frontEnabled = settings.getBoolean(PREF_FRONT_ENABLED, false);
        for (PaneState pane : panes) {
            if (pane.profile.rear() ? rearEnabled : frontEnabled) preparePane(pane);
        }
    }

    private void preparePane(PaneState pane) {
        int requestId = nextRequestId();
        CameraShellProtocol.OverlaySpec spec = buildOverlaySpec(pane.profile, requestId);
        pane.expected = true;
        pane.requestId = requestId;
        pane.target = spec.target;
        helper.prepareOverlayWindow(spec,
                this::overlaySurfaceAvailable,
                () -> overlayPrepared(pane.profile.id, requestId));
        emit("overlay_geometry", "camera_id", pane.profile.id,
                "camera_profile", pane.profile.wireName,
                "request_id", requestId,
                "target", CameraDisplayTarget.name(spec.target),
                "width", spec.width, "height", spec.height,
                "x", spec.x, "y", spec.y);
    }

    private void overlayPrepared(int cameraId, int requestId) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId || pane.resolved) return;
        handler.postDelayed(() -> surfaceTimedOut(cameraId, requestId), SURFACE_TIMEOUT_MS);
    }

    private void surfaceTimedOut(int cameraId, int requestId) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId || pane.resolved) return;
        paneUnavailable(cameraId, requestId, "overlay_surface_timeout");
    }

    private void overlaySurfaceAvailable(TurnSignalController.OverlaySurface value) {
        PaneState pane = pane(value.cameraId);
        if (pane == null || !pane.expected || pane.requestId != value.requestId
                || value.surface == null || !value.surface.isValid()) {
            if (value.surface != null) value.surface.release();
            return;
        }
        releaseSurface(pane.pendingSurface);
        pane.pendingSurface = value.surface;
        pane.generation = value.surfaceGeneration;
        pane.resolved = true;
        maybeOpenCamera();
    }

    private void maybeOpenCamera() {
        if (isHardBlocked() || cameraOpenPending || cameraSessionOpen || helper == null) return;
        List<PaneState> ready = new ArrayList<>();
        int expected = 0;
        int resolved = 0;
        int failed = 0;
        for (PaneState pane : panes) {
            if (!pane.expected) continue;
            expected++;
            if (pane.resolved) resolved++;
            if (pane.failed) failed++;
            if (!pane.failed && pane.pendingSurface != null) ready.add(pane);
        }
        int decision = preparationDecision(expected, resolved, failed);
        if (decision == PREPARATION_WAIT) return;
        if (decision == PREPARATION_RETRY) {
            destroyAll(failed > 0 ? "incomplete_overlay_set" : "no_overlay_surfaces");
            scheduleCameraRetry(failed > 0
                    ? "incomplete_overlay_set" : "no_overlay_surfaces");
            return;
        }
        Surface[] surfaces = new Surface[ready.size()];
        int[] indexes = new int[ready.size()];
        int[] cameraIds = new int[ready.size()];
        for (int i = 0; i < ready.size(); i++) {
            PaneState pane = ready.get(i);
            surfaces[i] = pane.pendingSurface;
            pane.surface = pane.pendingSurface;
            pane.pendingSurface = null;
            indexes[i] = pane.profile.previewIndex;
            cameraIds[i] = pane.profile.id;
        }
        cameraOpenPending = true;
        cameraOpenRequestId = nextRequestId();
        try {
            helper.openOverlayDirectCameras(
                    surfaces, indexes, cameraIds, cameraOpenRequestId);
            emit("overlay_camera_request", "camera_ids", java.util.Arrays.toString(cameraIds),
                    "preview_indexes", java.util.Arrays.toString(indexes),
                    "request_id", cameraOpenRequestId);
        } catch (Throwable error) {
            for (int i = 0; i < surfaces.length; i++) {
                releaseSurface(surfaces[i]);
                PaneState pane = pane(cameraIds[i]);
                if (pane != null && pane.surface == surfaces[i]) pane.surface = null;
            }
            cameraUnavailable("open_direct_camera");
        }
    }

    private void cameraOpened(int cameraRequestId) {
        if (!matchesCameraOpenEvent(
                cameraOpenPending, cameraOpenRequestId, cameraRequestId)) return;
        cameraOpenPending = false;
        cameraSessionOpen = true;
        cancelCameraRetry("camera_opened");
        for (PaneState pane : panes) {
            if (!pane.expected || pane.failed || pane.generation <= 0) continue;
            pane.targetActive = true;
            pane.requestedVisible = false;
            pane.activationPending = true;
            int frameArmEpoch = pane.freshness.arm();
            OverlayFrameArm arm = OverlayFrameArm.create(
                    pane.profile.id, pane.requestId, pane.generation, frameArmEpoch);
            try {
                helper.setOverlayTargetActive(pane.surface, true);
            } catch (Exception error) {
                cameraUnavailable("overlay_target_resume_" + pane.profile.wireName);
                return;
            }
            helper.armOverlayFirstFrame(arm);
            handler.postDelayed(() -> firstFrameTimedOut(arm),
                    FIRST_FRAME_TIMEOUT_MS);
        }
    }

    private void firstFrameTimedOut(OverlayFrameArm arm) {
        PaneState pane = pane(arm.cameraId);
        if (pane == null || pane.requestId != arm.requestId
                || pane.generation != arm.surfaceGeneration
                || !pane.freshness.shouldTimeout(arm.frameArmEpoch)
                || isHardBlocked()) return;
        cameraUnavailable("first_frame_timeout_" + pane.profile.wireName);
    }

    private void firstFrameAvailable(OverlayFrameArm arm) {
        PaneState pane = pane(arm.cameraId);
        if (pane == null || pane.requestId != arm.requestId
                || pane.generation != arm.surfaceGeneration
                || !cameraSessionOpen || !pane.freshness.accept(arm.frameArmEpoch)) return;
        emit("overlay_camera_ready", "camera_id", arm.cameraId,
                "camera_profile", pane.profile.wireName,
                "request_id", arm.requestId,
                "surface_generation", arm.surfaceGeneration,
                "frame_arm_epoch", arm.frameArmEpoch,
                "readiness", "first_frame");
        pane.activationPending = false;
        evaluate();
    }

    private void paneSurfaceDestroyed(int cameraId, int requestId) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId) return;
        if (cameraOpenPending || cameraSessionOpen) {
            cameraUnavailable("overlay_surface_destroyed_" + pane.profile.wireName);
        } else {
            paneUnavailable(cameraId, requestId, "overlay_surface_destroyed");
        }
    }

    private void paneUnavailable(int cameraId, int requestId, String reason) {
        PaneState pane = pane(cameraId);
        if (pane == null || !pane.expected
                || requestId > 0 && pane.requestId != requestId) return;
        if (cameraOpenPending || cameraSessionOpen) {
            cameraUnavailable(reason + "_" + pane.profile.wireName);
            return;
        }
        pane.failed = true;
        pane.resolved = true;
        releaseSurface(pane.pendingSurface);
        pane.pendingSurface = null;
        emit("overlay_camera_output_unavailable", "camera_id", cameraId,
                "camera_profile", pane.profile.wireName, "reason", reason);
        maybeOpenCamera();
    }

    private void clusterDestinationUnavailable(JSONObject event, int cameraId,
            int requestId, int generation) {
        PaneState pane = pane(cameraId);
        if (pane == null || !ClusterDisplayLifecycle.matchesUnavailableError(event,
                cameraId, pane.requestId, pane.generation)
                || pane.requestId != requestId || pane.generation != generation) return;
        boolean reconcile = clusterChangePending;
        emit("overlay_camera_output_invalidated", "camera_id", cameraId,
                "camera_profile", pane.profile.wireName, "request_id", requestId,
                "surface_generation", generation,
                "reason", ClusterDisplayLifecycle.UNAVAILABLE_STAGE);
        cancelCameraRetry(ClusterDisplayLifecycle.UNAVAILABLE_STAGE);
        destroyAll(ClusterDisplayLifecycle.UNAVAILABLE_STAGE);
        if (reconcile && cameraUnavailableReason() == null
                && CameraDisplayTarget.resolve(context, CameraDisplayTarget.CLUSTER) != null) {
            rebuild("cluster_display_changed");
        }
    }

    static int preparationDecision(int expected, int resolved, int failed) {
        if (failed > 0 || expected <= 0) return PREPARATION_RETRY;
        return resolved < expected ? PREPARATION_WAIT : PREPARATION_OPEN;
    }

    private void cameraUnavailable(String reason) {
        for (PaneState pane : panes) {
            pane.visibilityToken++;
            pane.visibilityPending = false;
            pane.activationPending = false;
            pane.requestedVisible = false;
        }
        hideAll(reason);
        if (helper != null) helper.closeOverlayCamera(reason, cameraOpenRequestId);
        cameraOpenPending = false;
        cameraSessionOpen = false;
        cameraOpenRequestId = 0;
        for (PaneState pane : panes) {
            pane.freshness.invalidate();
            pane.visible = false;
            pane.targetActive = false;
        }
        scheduleCameraRetry(reason);
    }

    private void cameraShellAttached(long epoch) {
        if (epoch <= cameraShellEpoch) return;
        cameraShellEpoch = epoch;
        boolean pending = shellRecovery.pending();
        boolean recover = shellRecovery.claim(epoch, cameraRetryBlockReason() == null);
        if (!pending) return;
        emit("overlay_camera_epoch_recovery",
                "camera_shell_epoch", epoch,
                "state", recover ? "attempt" : "cancelled");
        if (recover) rebuild("camera_shell_epoch_recovery");
    }

    private void cameraShellDied(long epoch) {
        if (!TurnSignalController.isCurrentCameraShellEpoch(cameraShellEpoch, epoch)
                || !shellRecovery.isNewDeath(epoch)) return;
        cancelCameraRetry("camera_shell_died");
        boolean pending = shellRecovery.onDeath(epoch, cameraRetryBlockReason() == null);
        for (PaneState pane : panes) {
            if (!pane.expected && pane.requestId <= 0 && pane.generation <= 0) continue;
            emit("overlay_camera_output_invalidated",
                    "camera_id", pane.profile.id,
                    "camera_profile", pane.profile.wireName,
                    "request_id", pane.requestId,
                    "surface_generation", pane.generation,
                    "reason", "camera_shell_died");
        }
        cameraOpenPending = false;
        cameraSessionOpen = false;
        cameraOpenRequestId = 0;
        clusterDisplayId = -1;
        clusterChangePending = false;
        for (PaneState pane : panes) pane.reset();
        emit("overlay_camera_epoch_recovery",
                "camera_shell_epoch", epoch,
                "state", pending ? "pending" : "cancelled");
    }

    private void acceptVehicleState(
            boolean valid, int nextBlink, float nextSpeed, float nextAngle) {
        stateValid = valid && validBlink(nextBlink) && Float.isFinite(nextSpeed);
        blink = nextBlink;
        speedKph = nextSpeed;
        steeringAngle = nextAngle;
        handler.removeCallbacks(staleState);
        if (stateValid) handler.postDelayed(staleState, STATE_STALE_MS);
        evaluate();
    }

    private static boolean validBlink(int value) {
        return value == BLINK_OFF || value == BLINK_LEFT || value == BLINK_RIGHT;
    }

    private void acceptBsdState(
            boolean nextLeftValid, int nextLeftRaw,
            boolean nextRightValid, int nextRightRaw) {
        leftBsdValid = nextLeftValid && BlindSpotWarningRuntime.isValidRaw(nextLeftRaw);
        rightBsdValid = nextRightValid && BlindSpotWarningRuntime.isValidRaw(nextRightRaw);
        leftBsdRaw = leftBsdValid ? nextLeftRaw : -1;
        rightBsdRaw = rightBsdValid ? nextRightRaw : -1;
        evaluate();
    }

    private void evaluate() {
        boolean rearSuppressed = panoramaSuppresses(
                oemPanoramaKnown, oemPanoramaVisible,
                readPanoramaSuppression(settings, false));
        boolean frontSuppressed = panoramaSuppresses(
                oemPanoramaKnown, oemPanoramaVisible,
                readPanoramaSuppression(settings, true));
        int desired = desiredCameraMask(
                stateValid, blink, speedKph, steeringAngle,
                settings.getBoolean(PREF_ENABLED, false) && !rearSuppressed,
                settings.getInt(PREF_MIN_SPEED, DEFAULT_MIN_SPEED_KPH),
                settings.getInt(PREF_MAX_SPEED, DEFAULT_MAX_SPEED_KPH),
                settings.getBoolean(PREF_FRONT_ENABLED, false) && !frontSuppressed,
                settings.getInt(PREF_FRONT_MIN_SPEED, DEFAULT_FRONT_MIN_SPEED_KPH),
                settings.getInt(PREF_FRONT_MAX_SPEED, DEFAULT_FRONT_MAX_SPEED_KPH),
                settings.getBoolean(PREF_FRONT_TURN_REQUIRED, true),
                settings.getFloat(PREF_FRONT_MIN_ANGLE, DEFAULT_FRONT_MIN_ANGLE_DEG),
                settings.getBoolean(PREF_REAR_SHARP_TURN_ENABLED, false),
                settings.getFloat(PREF_REAR_SHARP_TURN_ANGLE,
                        DEFAULT_REAR_SHARP_TURN_ANGLE_DEG),
                settings.getBoolean(PREF_REAR_BSD_ONLY, false),
                leftBsdValid, leftBsdRaw, rightBsdValid, rightBsdRaw);
        if (isHardBlocked()) desired = 0;
        for (PaneState pane : panes) {
            boolean requested = (desired & pane.profile.bit()) != 0
                    && pane.expected && !pane.failed;
            setVisible(pane, requested,
                    requested ? "trigger_active" : "trigger_inactive");
        }
        applyWarnings();
    }

    private void setVisible(PaneState pane, boolean visible, String reason) {
        if (pane.requestedVisible == visible && pane.visibilityPending) return;
        pane.requestedVisible = visible;
        if (visible) {
            if (!cameraSessionOpen) return;
            if (pane.visible || pane.activationPending) return;
            if (!pane.targetActive) {
                try {
                    if (helper == null || pane.surface == null) return;
                    helper.setOverlayTargetActive(pane.surface, true);
                    pane.targetActive = true;
                    emit("overlay_target_state", "camera_id", pane.profile.id,
                            "camera_profile", pane.profile.wireName, "active", true);
                } catch (Throwable error) {
                    cameraUnavailable("overlay_target_resume_" + pane.profile.wireName);
                    return;
                }
            }
            if (needsFreshFrameArm(
                    pane.requestedVisible, pane.targetActive,
                    pane.freshness.ready(), pane.activationPending)) {
                int frameArmEpoch = pane.freshness.arm();
                pane.activationPending = true;
                OverlayFrameArm arm = OverlayFrameArm.create(
                        pane.profile.id, pane.requestId, pane.generation, frameArmEpoch);
                helper.armOverlayFirstFrame(arm);
                handler.postDelayed(() -> firstFrameTimedOut(arm), FIRST_FRAME_TIMEOUT_MS);
                return;
            }
            requestVisibility(pane, true, reason);
            return;
        }

        pane.activationPending = false;
        pane.freshness.invalidate();
        if (!pane.targetActive && !pane.visible) return;
        requestVisibility(pane, false, reason);
    }

    static boolean needsFreshFrameArm(
            boolean requestedVisible, boolean targetActive,
            boolean frameReady, boolean activationPending) {
        return requestedVisible && (!targetActive || !frameReady) && !activationPending;
    }

    static int visibilityCompletionAction(
            long currentToken, long callbackToken, boolean success,
            boolean completedVisible, boolean requestedVisible, boolean targetActive) {
        if (currentToken != callbackToken) return VISIBILITY_COMPLETION_STALE;
        if (!success) return VISIBILITY_COMPLETION_FAILED;
        if (!completedVisible && !requestedVisible && targetActive) {
            return VISIBILITY_COMPLETION_PAUSE;
        }
        return VISIBILITY_COMPLETION_APPLY;
    }

    private void requestVisibility(PaneState pane, boolean visible, String reason) {
        if (helper == null || pane.requestId <= 0 || pane.generation <= 0) return;
        long token = ++pane.visibilityToken;
        pane.visibilityPending = true;
        helper.setOverlayWindowVisible(pane.profile.id,
                pane.requestId, pane.generation, visible,
                success -> visibilityCompleted(
                        pane, token, visible, reason, success));
    }

    private void visibilityCompleted(
            PaneState pane, long token, boolean visible, String reason, boolean success) {
        int action = visibilityCompletionAction(
                pane.visibilityToken, token, success, visible,
                pane.requestedVisible, pane.targetActive);
        if (action == VISIBILITY_COMPLETION_STALE) return;
        pane.visibilityPending = false;
        if (!cameraSessionOpen) {
            pane.targetActive = false;
            return;
        }
        if (action == VISIBILITY_COMPLETION_FAILED) {
            cameraUnavailable("overlay_visibility_"
                    + (visible ? "show_" : "hide_") + pane.profile.wireName);
            return;
        }
        pane.visible = visible;
        emit("overlay_visibility", "camera_id", pane.profile.id,
                "camera_profile", pane.profile.wireName,
                "visible", visible, "reason", reason,
                "speed_kph", Float.isFinite(speedKph) ? speedKph : "unknown");
        if (action == VISIBILITY_COMPLETION_PAUSE) {
            try {
                helper.setOverlayTargetActive(pane.surface, false);
                pane.targetActive = false;
                emit("overlay_target_state", "camera_id", pane.profile.id,
                        "camera_profile", pane.profile.wireName, "active", false);
            } catch (Throwable error) {
                cameraUnavailable("overlay_target_pause_" + pane.profile.wireName);
                return;
            }
        }
        evaluate();
    }

    private void hideAll(String reason) {
        for (PaneState pane : panes) setVisible(pane, false, reason);
    }

    private void applyWarnings() {
        int mode = readWarningMode(settings);
        for (PaneState pane : panes) {
            if (!pane.profile.rear() || !pane.freshness.ready()
                    || pane.generation <= 0) continue;
            int edge = warningEdge(mode, pane.visible,
                    pane.profile.right() ? BLINK_RIGHT : BLINK_LEFT,
                    leftBsdValid, leftBsdRaw, rightBsdValid, rightBsdRaw);
            int appliedMode = edge == CameraShellProtocol.WARNING_EDGE_NONE
                    ? CameraShellProtocol.WARNING_MODE_OFF : mode;
            if (pane.warningEdge == edge && pane.warningMode == appliedMode) continue;
            helper.setOverlayWindowWarning(pane.profile.id,
                    pane.requestId, pane.generation, edge, appliedMode);
            pane.warningEdge = edge;
            pane.warningMode = appliedMode;
        }
    }

    private void destroyAll(String reason) {
        handler.removeCallbacks(staleState);
        hideAll(reason);
        if (helper != null) {
            if (cameraOpenPending || cameraSessionOpen || hasPendingSurfaces()) {
                helper.closeOverlayCamera(reason, cameraOpenRequestId);
            }
            helper.closeOverlayWindows(reason);
        }
        cameraOpenPending = false;
        cameraSessionOpen = false;
        cameraOpenRequestId = 0;
        clusterDisplayId = -1;
        clusterChangePending = false;
        for (PaneState pane : panes) pane.reset();
    }

    static boolean matchesCameraOpenEvent(
            boolean pending, int expectedRequestId, int eventRequestId) {
        return pending && expectedRequestId > 0 && eventRequestId == expectedRequestId;
    }

    static boolean shouldRebuildAfterCameraDiscovery(boolean overlayPrepared) {
        return !overlayPrepared;
    }

    private boolean hasPreparedOverlay() {
        if (cameraOpenPending || cameraSessionOpen) return true;
        for (PaneState pane : panes) if (pane.expected) return true;
        return false;
    }

    private boolean hasPendingSurfaces() {
        for (PaneState pane : panes) if (pane.pendingSurface != null) return true;
        return false;
    }

    private void scheduleCameraRetry(String reason) {
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled",
                    "reason", blocked, "trigger", reason);
            return;
        }
        if (!cameraRetry.schedule(reason)) return;
        handler.postDelayed(retryCamera, CAMERA_RETRY_MS);
        emit("overlay_camera_retry", "state", "scheduled",
                "reason", reason, "delay_ms", CAMERA_RETRY_MS);
    }

    private String cameraRetryBlockReason() {
        return cameraRetryBlockReason(
                shutdown, anyLaneEnabled(), isHardBlocked(), helper != null);
    }

    static String cameraRetryBlockReason(
            boolean shutdown, boolean enabled, boolean hardBlocked, boolean helperAvailable) {
        if (shutdown) return "shutdown";
        if (!enabled) return "overlay_disabled";
        if (hardBlocked) return "overlay_hard_blocked";
        if (!helperAvailable) return "helper_unavailable";
        return null;
    }

    private void cancelCameraRetry(String reason) {
        String trigger = cameraRetry.cancel();
        if (trigger == null) return;
        handler.removeCallbacks(retryCamera);
        emit("overlay_camera_retry", "state", "cancelled",
                "reason", reason, "trigger", trigger);
    }

    static final class CameraRetryState {
        private String trigger;

        boolean schedule(String reason) {
            if (trigger != null) return false;
            trigger = reason == null ? "unknown" : reason;
            return true;
        }

        String consume() { return clear(); }
        String cancel() { return clear(); }
        boolean active() { return trigger != null; }

        private String clear() {
            String value = trigger;
            trigger = null;
            return value;
        }
    }

    static int readWarningMode(SharedPreferences settings) {
        boolean present = settings.contains(PREF_WARNING_MODE);
        int mode = settings.getInt(PREF_WARNING_MODE, DEFAULT_WARNING_MODE);
        return normalizeWarningMode(present, mode);
    }

    static int normalizeWarningMode(boolean present, int mode) {
        if (!present) return DEFAULT_WARNING_MODE;
        return isWarningMode(mode) ? mode : CameraShellProtocol.WARNING_MODE_OFF;
    }

    static boolean isWarningMode(int mode) {
        return mode >= CameraShellProtocol.WARNING_MODE_OFF
                && mode <= CameraShellProtocol.WARNING_MODE_PULSE;
    }

    static int warningEdge(
            int mode, boolean overlayVisible, int direction,
            boolean leftValid, int leftRaw, boolean rightValid, int rightRaw) {
        if (!isWarningMode(mode) || mode == CameraShellProtocol.WARNING_MODE_OFF
                || !overlayVisible) return CameraShellProtocol.WARNING_EDGE_NONE;
        if (direction == BLINK_LEFT
                && BlindSpotWarningRuntime.isActiveRaw(leftValid, leftRaw)) {
            return CameraShellProtocol.WARNING_EDGE_LEFT;
        }
        if (direction == BLINK_RIGHT
                && BlindSpotWarningRuntime.isActiveRaw(rightValid, rightRaw)) {
            return CameraShellProtocol.WARNING_EDGE_RIGHT;
        }
        return CameraShellProtocol.WARNING_EDGE_NONE;
    }

    private CameraShellProtocol.OverlaySpec buildOverlaySpec(
            CameraProfile profile, int requestId) {
        int target = readTarget(settings, profile);
        int[] displaySize = CameraDisplayTarget.displaySize(context, target);
        int marginX = target == CameraDisplayTarget.TABLET ? dp(16) : 0;
        int topMargin = target == CameraDisplayTarget.TABLET ? dp(36) : 0;
        int bottomMargin = target == CameraDisplayTarget.TABLET ? dp(88) : 0;
        return buildOverlaySpec(settings, profile, requestId, target,
                displaySize[0], displaySize[1], marginX, topMargin, bottomMargin);
    }

    static CameraShellProtocol.OverlaySpec buildOverlaySpec(
            SharedPreferences settings, CameraProfile profile, int requestId, int target,
            int displayWidth, int displayHeight,
            int marginX, int topMargin, int bottomMargin) {
        CameraDewarpConfig dewarp = CameraDewarpConfig.loadForProfile(settings, profile);
        DirectCameraCrop rawCrop = DirectCameraCrop.load(settings, profile);
        DirectCameraCrop crop = dewarp.enabled
                ? DirectCameraCrop.loadCorrected(settings, profile, rawCrop) : rawCrop;
        CameraPlacement placement = readPlacement(settings, profile, target,
                displayWidth, displayHeight, marginX, topMargin, bottomMargin);
        int[] geometry = placement.toPixelRect(displayWidth, displayHeight);
        return new CameraShellProtocol.OverlaySpec(
                profile.id, requestId, target,
                geometry[2], geometry[3], geometry[0], geometry[1],
                crop.left, crop.top, crop.width, crop.height, crop.aspectMode,
                crop.rotationDegrees, crop.rotationMode, readCornerRadius(settings),
                dewarp, rawCrop, CameraBufferQuality.load(settings),
                crop.mirrorHorizontally, readTransparencyPercent(settings));
    }

    static int[] overlayGeometry(
            int displayWidth, int displayHeight, int scalePercent, float aspect,
            float normalizedX, float normalizedY,
            int marginX, int topMargin, int bottomMargin) {
        int safeWidth = Math.max(1, displayWidth);
        int safeHeight = Math.max(1, displayHeight);
        int scale = clamp(scalePercent, MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
        int maxWidth = Math.max(1, safeWidth - Math.max(0, marginX) * 2);
        int maxHeight = Math.max(1, safeHeight - Math.max(0, topMargin)
                - Math.max(0, bottomMargin));
        int[] size = fitAspect(safeWidth * scale / 100, maxWidth, maxHeight, aspect);
        int availableX = Math.max(0, maxWidth - size[0]);
        int availableY = Math.max(0, maxHeight - size[1]);
        return new int[]{
                Math.max(0, marginX) + Math.round(availableX
                        * clamp(normalizedX, 0.0f, 1.0f)),
                Math.max(0, topMargin) + Math.round(availableY
                        * clamp(normalizedY, 0.0f, 1.0f)),
                size[0], size[1]
        };
    }

    static String scaleKey(CameraProfile profile) {
        if (profile.id == CameraProfile.REAR_LEFT) return PREF_LEFT_SCALE;
        if (profile.id == CameraProfile.REAR_RIGHT) return PREF_RIGHT_SCALE;
        if (profile.id == CameraProfile.FRONT_LEFT) return PREF_FRONT_LEFT_SCALE;
        return PREF_FRONT_RIGHT_SCALE;
    }

    static String frameAspectKey(CameraProfile profile) {
        if (profile.id == CameraProfile.REAR_LEFT) return PREF_LEFT_FRAME_ASPECT;
        if (profile.id == CameraProfile.REAR_RIGHT) return PREF_RIGHT_FRAME_ASPECT;
        if (profile.id == CameraProfile.FRONT_LEFT) return PREF_FRONT_LEFT_FRAME_ASPECT;
        return PREF_FRONT_RIGHT_FRAME_ASPECT;
    }

    private static boolean hasRawPreferences(
            SharedPreferences settings, CameraProfile profile) {
        for (int field = 0; field < 4; field++) {
            if (settings.contains(DirectCameraCrop.preferenceKey(profile, field))) return true;
        }
        return false;
    }

    static String targetKey(CameraProfile profile) {
        if (profile.id == CameraProfile.REAR_LEFT) return PREF_LEFT_TARGET;
        if (profile.id == CameraProfile.REAR_RIGHT) return PREF_RIGHT_TARGET;
        if (profile.id == CameraProfile.FRONT_LEFT) return PREF_FRONT_LEFT_TARGET;
        return PREF_FRONT_RIGHT_TARGET;
    }

    static String positionKey(CameraProfile profile, boolean vertical) {
        if (profile.id == CameraProfile.REAR_LEFT) return vertical ? PREF_LEFT_Y : PREF_LEFT_X;
        if (profile.id == CameraProfile.REAR_RIGHT) return vertical ? PREF_RIGHT_Y : PREF_RIGHT_X;
        if (profile.id == CameraProfile.FRONT_LEFT) {
            return vertical ? PREF_FRONT_LEFT_Y : PREF_FRONT_LEFT_X;
        }
        return vertical ? PREF_FRONT_RIGHT_Y : PREF_FRONT_RIGHT_X;
    }

    static String placementWidthKey(CameraProfile profile) {
        if (profile.id == CameraProfile.REAR_LEFT) return PREF_LEFT_WIDTH;
        if (profile.id == CameraProfile.REAR_RIGHT) return PREF_RIGHT_WIDTH;
        if (profile.id == CameraProfile.FRONT_LEFT) return PREF_FRONT_LEFT_WIDTH;
        return PREF_FRONT_RIGHT_WIDTH;
    }

    static String placementHeightKey(CameraProfile profile) {
        if (profile.id == CameraProfile.REAR_LEFT) return PREF_LEFT_HEIGHT;
        if (profile.id == CameraProfile.REAR_RIGHT) return PREF_RIGHT_HEIGHT;
        if (profile.id == CameraProfile.FRONT_LEFT) return PREF_FRONT_LEFT_HEIGHT;
        return PREF_FRONT_RIGHT_HEIGHT;
    }

    static String placementKey(CameraProfile profile, int target, int field) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        String legacy;
        switch (field) {
            case PLACEMENT_X: legacy = positionKey(profile, false); break;
            case PLACEMENT_Y: legacy = positionKey(profile, true); break;
            case PLACEMENT_WIDTH: legacy = placementWidthKey(profile); break;
            case PLACEMENT_HEIGHT: legacy = placementHeightKey(profile); break;
            default: throw new IllegalArgumentException("invalid placement field");
        }
        int separator = legacy.lastIndexOf('_');
        String display = target == CameraDisplayTarget.CLUSTER ? "cluster" : "tablet";
        return legacy.substring(0, separator + 1) + display + legacy.substring(separator);
    }

    private int nextRequestId() {
        requestSequence = requestSequence == Integer.MAX_VALUE ? 1 : requestSequence + 1;
        return requestSequence;
    }

    private PaneState pane(int cameraId) {
        return CameraProfile.isValid(cameraId) ? panes[cameraId] : null;
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void releaseSurface(Surface surface) {
        if (surface != null) surface.release();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    static final class FrameFreshness {
        private int epoch;
        private boolean ready;

        int arm() {
            epoch = epoch == Integer.MAX_VALUE ? 1 : epoch + 1;
            ready = false;
            return epoch;
        }

        boolean accept(int eventEpoch) {
            if (eventEpoch <= 0 || eventEpoch != epoch || ready) return false;
            ready = true;
            return true;
        }

        boolean shouldTimeout(int deadlineEpoch) {
            return deadlineEpoch > 0 && deadlineEpoch == epoch && !ready;
        }

        boolean current(int candidateEpoch) {
            return candidateEpoch > 0 && candidateEpoch == epoch;
        }

        boolean ready() {
            return ready;
        }

        void invalidate() {
            epoch = epoch == Integer.MAX_VALUE ? 1 : epoch + 1;
            ready = false;
        }
    }

    private static final class PaneState {
        final CameraProfile profile;
        final FrameFreshness freshness = new FrameFreshness();
        boolean expected;
        boolean resolved;
        boolean failed;
        boolean visible;
        boolean requestedVisible;
        boolean targetActive;
        boolean activationPending;
        boolean visibilityPending;
        long visibilityToken;
        int requestId;
        int generation;
        int target = -1;
        int warningEdge = CameraShellProtocol.WARNING_EDGE_NONE;
        int warningMode = CameraShellProtocol.WARNING_MODE_OFF;
        Surface pendingSurface;
        Surface surface;

        PaneState(CameraProfile profile) {
            this.profile = profile;
        }

        void reset() {
            releaseSurface(pendingSurface);
            pendingSurface = null;
            surface = null;
            expected = false;
            resolved = false;
            failed = false;
            freshness.invalidate();
            visible = false;
            requestedVisible = false;
            targetActive = false;
            activationPending = false;
            visibilityPending = false;
            visibilityToken++;
            requestId = 0;
            generation = 0;
            target = -1;
            warningEdge = CameraShellProtocol.WARNING_EDGE_NONE;
            warningMode = CameraShellProtocol.WARNING_MODE_OFF;
        }
    }
}
