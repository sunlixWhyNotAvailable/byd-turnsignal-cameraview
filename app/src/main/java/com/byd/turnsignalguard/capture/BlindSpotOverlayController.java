package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Parcel;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.util.function.BiConsumer;

final class BlindSpotOverlayController implements BlindSpotCameraView.Callback {
    static final String PREF_ENABLED = "camera_enabled";
    static final String PREF_MIN_SPEED = "camera_min_speed_kph";
    static final String PREF_SCALE = "camera_overlay_scale_percent";
    static final String PREF_LEFT_POSITION = "camera_left_position";
    static final String PREF_RIGHT_POSITION = "camera_right_position";
    static final String PREF_LEFT_X = "camera_left_x";
    static final String PREF_LEFT_Y = "camera_left_y";
    static final String PREF_RIGHT_X = "camera_right_x";
    static final String PREF_RIGHT_Y = "camera_right_y";
    static final int DEFAULT_MIN_SPEED_KPH = 20;
    static final int DEFAULT_SCALE_PERCENT = 36;
    static final int MIN_SCALE_PERCENT = 20;
    static final int MAX_SCALE_PERCENT = 60;
    static final int DEFAULT_LEFT_POSITION = 0;
    static final int DEFAULT_RIGHT_POSITION = 2;

    private static final int BLINK_LEFT = 2;
    private static final int BLINK_RIGHT = 4;
    private static final String DIRECT_CAMERA_TAG = "pano_h";
    private static final int LEFT_PREVIEW_INDEX = 2;
    private static final int RIGHT_PREVIEW_INDEX = 3;
    private static final long STATE_STALE_MS = 750;
    private static final long VIEWPOINT_SETTLE_MS = 150;
    private static final long CAMERA_RETRY_MS = 3_000;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences settings;
    private final WindowManager windows;
    private final BiConsumer<String, Object[]> eventSink;
    private final Runnable staleState = () -> {
        stateValid = false;
        hide("vehicle_state_stale");
    };
    private final Runnable settleViewpoint = this::finishPreviewSettle;
    private final Runnable retryCamera = this::retryCameraOpen;

    private CameraHelperMain.HelperBinder helper;
    private FrameLayout window;
    private BlindSpotCameraView preview;
    private WindowManager.LayoutParams layout;
    private boolean suspended;
    private boolean uiHidden;
    private boolean tearingDown;
    private boolean cameraReady;
    private boolean stateValid;
    private boolean visible;
    private boolean windowArmed;
    private final CameraRetryState cameraRetry = new CameraRetryState();
    private boolean shutdown;
    private int blink = -1;
    private float speedKph = Float.NaN;
    private int requestedPreviewIndex = -1;
    private int displayedPreviewIndex = -1;
    private int settlingPreviewIndex = -1;
    private int preparedDirection = -1;

    BlindSpotOverlayController(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.eventSink = eventSink;
        settings = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    static int directionToShow(boolean valid, int blink, float speedKph, int minSpeedKph) {
        if (!valid || !Float.isFinite(speedKph)
                || minSpeedKph < 0 || minSpeedKph > 300 || speedKph < minSpeedKph) {
            return 0;
        }
        return blink == BLINK_LEFT || blink == BLINK_RIGHT ? blink : 0;
    }

    static float readPosition(
            SharedPreferences settings, boolean right, boolean vertical) {
        String key = right
                ? (vertical ? PREF_RIGHT_Y : PREF_RIGHT_X)
                : (vertical ? PREF_LEFT_Y : PREF_LEFT_X);
        if (settings.contains(key)) return clamp(settings.getFloat(key, 0.0f), 0.0f, 1.0f);
        int legacy = settings.getInt(right ? PREF_RIGHT_POSITION : PREF_LEFT_POSITION,
                right ? DEFAULT_RIGHT_POSITION : DEFAULT_LEFT_POSITION);
        return legacyPosition(legacy, vertical);
    }

    static float legacyPosition(int position, boolean vertical) {
        int safe = clamp(position, 0, 8);
        return (vertical ? safe / 3 : safe % 3) / 2.0f;
    }

    static boolean isSettledViewpoint(int settling, int requested, int displayed) {
        return settling != -1 && settling == requested && settling == displayed;
    }

    static int previewIndexForDirection(int direction) {
        if (direction == BLINK_LEFT) return LEFT_PREVIEW_INDEX;
        if (direction == BLINK_RIGHT) return RIGHT_PREVIEW_INDEX;
        return -1;
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

    static int[] fitFourThree(int requestedWidth, int maxWidth, int maxHeight) {
        return fitAspect(requestedWidth, maxWidth, maxHeight,
                DirectCameraCrop.OUTPUT_ASPECT);
    }

    private void finishPreviewSettle() {
        if (!isSettledViewpoint(
                settlingPreviewIndex, requestedPreviewIndex, displayedPreviewIndex)) return;
        cameraReady = true;
        emit("overlay_camera_ready", "camera_tag", DIRECT_CAMERA_TAG,
                "preview_index", settlingPreviewIndex,
                "settle_ms", VIEWPOINT_SETTLE_MS);
        evaluate();
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
            if (value) destroyWindow("preview_handoff");
            else applySettingsOnMain();
        });
    }

    void setUiHidden(boolean value) {
        handler.post(() -> {
            if (uiHidden == value) return;
            uiHidden = value;
            if (value) hide("activity_visible");
            else evaluate();
        });
    }

    void applySettings() {
        handler.post(this::applySettingsOnMain);
    }

    void acceptEvent(String line) {
        if (line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            if ("vehicle_state".equals(kind)) {
                boolean valid = event.optBoolean("valid");
                int nextBlink = event.optInt("blink", -1);
                float nextSpeed = valid ? (float) event.optDouble("speed_kph", Double.NaN)
                        : Float.NaN;
                handler.post(() -> acceptVehicleState(valid, nextBlink, nextSpeed));
            } else if ("camera_discovery".equals(kind) && event.optBoolean("ok")) {
                handler.post(this::applySettingsOnMain);
            } else if ("camera_opened".equals(kind)
                    && "helper".equals(event.optString("source"))
                    && CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                            event.optString("camera_owner"))
                    && DIRECT_CAMERA_TAG.equals(event.optString("camera_tag"))) {
                int previewIndex = event.optInt("preview_index", -1);
                handler.post(() -> cameraOpened(previewIndex));
            } else if ("camera_error".equals(kind)
                    && "helper".equals(event.optString("source"))
                    && CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                            event.optString("camera_owner"))
                    && DIRECT_CAMERA_TAG.equals(event.optString("camera_tag"))) {
                int previewIndex = event.optInt("preview_index", -1);
                String stage = event.optString("stage", kind);
                handler.post(() -> cameraUnavailable(stage, previewIndex));
            }
        } catch (Throwable error) {
            emit("overlay_event_parse_error", "error", summary(error));
        }
    }

    void shutdown() {
        shutdown = true;
        handler.removeCallbacks(staleState);
        handler.removeCallbacks(settleViewpoint);
        cancelCameraRetry("overlay_shutdown");
        destroyWindow("overlay_shutdown");
        helper = null;
    }

    @Override
    public void onCameraSurfaceAvailable(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        emit("overlay_surface", "state", "created", "valid", surface.isValid(),
                "width", width, "height", height,
                "buffer_width", DirectCameraCrop.SOURCE_WIDTH,
                "buffer_height", DirectCameraCrop.SOURCE_HEIGHT);
        armWindowAfterSurfaceCreated();
        requestDirection(desiredDirection());
    }

    @Override
    public void onCameraSurfaceSizeChanged(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        emit("overlay_surface", "state", "changed", "width", width, "height", height);
    }

    @Override
    public void onCameraSurfaceDestroyed(BlindSpotCameraView view) {
        emit("overlay_surface", "state", "destroyed");
        cancelCameraRetry("surface_destroyed");
        if (!tearingDown && helper != null) helper.closeCamera("overlay_surface_destroyed");
        handler.removeCallbacks(settleViewpoint);
        cameraReady = false;
        windowArmed = false;
        requestedPreviewIndex = -1;
        displayedPreviewIndex = -1;
        settlingPreviewIndex = -1;
    }

    private void applySettingsOnMain() {
        boolean enabled = settings.getBoolean(PREF_ENABLED, false);
        if (shutdown || !enabled || suspended || helper == null
                || !Settings.canDrawOverlays(context)) {
            destroyWindow(!enabled ? "overlay_disabled"
                    : shutdown ? "overlay_shutdown"
                    : suspended ? "overlay_suspended"
                    : helper == null ? "helper_unavailable" : "overlay_permission_missing");
            return;
        }
        ensureWindow();
        configureSurfaceBuffer();
        preparedDirection = -1;
        boolean geometryChanged = prepareDirection(desiredDirection());
        if (preview != null && preview.isCameraSurfaceReady()) {
            requestDirection(desiredDirection());
        }
        if (geometryChanged && window != null) window.post(this::evaluate);
        else evaluate();
    }

    private void ensureWindow() {
        if (window != null || windows == null) return;
        window = new FrameLayout(context);
        window.setClipChildren(true);
        window.setBackgroundColor(Color.BLACK);
        preview = new BlindSpotCameraView(context);
        preview.setAlpha(1.0f);
        preview.setCallback(this);
        window.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        layout = new WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.RGBX_8888);
        layout.gravity = Gravity.TOP | Gravity.START;
        layout.alpha = 0.0f;
        layout.windowAnimations = 0;
        layout.setTitle("BYD blind-spot camera");
        int initialDirection = desiredDirection();
        setExpandedLayout(initialDirection);
        preparedDirection = initialDirection;
        configureSurfaceBuffer();
        try {
            windows.addView(window, layout);
            emit("overlay_window", "state", "added",
                    "width", layout.width, "height", layout.height,
                    "x", layout.x, "y", layout.y, "initial_alpha", layout.alpha);
        } catch (Throwable error) {
            window = null;
            preview = null;
            layout = null;
            preparedDirection = -1;
            emit("overlay_window_error", "error", summary(error));
        }
    }

    private void destroyWindow(String reason) {
        cancelCameraRetry(reason);
        handler.removeCallbacks(staleState);
        handler.removeCallbacks(settleViewpoint);
        hide(reason);
        if (helper != null && (cameraReady || requestedPreviewIndex != -1)) {
            helper.closeCamera(reason);
        }
        cameraReady = false;
        windowArmed = false;
        requestedPreviewIndex = -1;
        displayedPreviewIndex = -1;
        settlingPreviewIndex = -1;
        if (window == null || windows == null) return;
        tearingDown = true;
        try {
            windows.removeView(window);
        } catch (Throwable error) {
            emit("overlay_window_error", "error", summary(error));
        } finally {
            tearingDown = false;
            window = null;
            preview = null;
            layout = null;
            preparedDirection = -1;
        }
        emit("overlay_window", "state", "removed", "reason", reason);
    }

    private void acceptVehicleState(boolean valid, int nextBlink, float nextSpeed) {
        stateValid = valid;
        blink = nextBlink;
        speedKph = nextSpeed;
        handler.removeCallbacks(staleState);
        if (nextBlink == BLINK_LEFT || nextBlink == BLINK_RIGHT) {
            handler.postDelayed(staleState, STATE_STALE_MS);
        }
        evaluate();
    }

    private void evaluate() {
        if (window == null || suspended) {
            hide("overlay_unavailable");
            return;
        }
        int activeDirection = blink == BLINK_LEFT || blink == BLINK_RIGHT ? blink : 0;
        if (activeDirection != 0) requestDirection(activeDirection);
        int direction = directionToShow(stateValid, blink, speedKph,
                settings.getInt(PREF_MIN_SPEED, DEFAULT_MIN_SPEED_KPH));
        if (direction == 0) {
            hide("turn_or_speed_condition_false");
            return;
        }
        int previewIndex = previewIndexForDirection(direction);
        if (!cameraReady || displayedPreviewIndex != previewIndex) {
            requestDirection(direction);
            hide("direct_camera_switch_pending");
            return;
        }
        if (uiHidden) {
            hide("activity_visible");
            return;
        }
        show(direction);
    }

    private void requestDirection(int direction) {
        int previewIndex = previewIndexForDirection(direction);
        if (cameraRetry.active() || helper == null || preview == null || previewIndex < 0
                || !preview.isCameraSurfaceReady()) {
            return;
        }
        prepareDirection(direction);
        if (requestedPreviewIndex == previewIndex) return;
        Surface copy = duplicate(preview.getCameraSurface());
        if (copy == null || !copy.isValid()) {
            if (copy != null) copy.release();
            emit("overlay_camera_error", "reason", "surface_copy_invalid");
            return;
        }
        requestedPreviewIndex = previewIndex;
        cameraReady = false;
        settlingPreviewIndex = -1;
        handler.removeCallbacks(settleViewpoint);
        helper.openOverlayDirectCamera(copy, DIRECT_CAMERA_TAG, previewIndex);
        emit("overlay_camera_request", "camera_tag", DIRECT_CAMERA_TAG,
                "preview_index", previewIndex,
                "direction", direction == BLINK_RIGHT ? "right" : "left");
    }

    private int desiredDirection() {
        return blink == BLINK_RIGHT ? BLINK_RIGHT : BLINK_LEFT;
    }

    private void cameraOpened(int previewIndex) {
        if (previewIndex != requestedPreviewIndex) return;
        cancelCameraRetry("camera_opened");
        displayedPreviewIndex = previewIndex;
        cameraReady = false;
        settlingPreviewIndex = previewIndex;
        handler.removeCallbacks(settleViewpoint);
        emit("overlay_camera_settling", "camera_tag", DIRECT_CAMERA_TAG,
                "preview_index", previewIndex,
                "settle_ms", VIEWPOINT_SETTLE_MS);
        handler.postDelayed(settleViewpoint, VIEWPOINT_SETTLE_MS);
    }

    private void cameraUnavailable(String reason, int previewIndex) {
        if (previewIndex >= 0 && previewIndex != requestedPreviewIndex) {
            emit("overlay_camera_retry", "state", "ignored",
                    "reason", "preview_mismatch",
                    "preview_index", previewIndex,
                    "requested_preview_index", requestedPreviewIndex);
            return;
        }
        handler.removeCallbacks(settleViewpoint);
        cameraReady = false;
        requestedPreviewIndex = -1;
        displayedPreviewIndex = -1;
        settlingPreviewIndex = -1;
        hide(reason);
        scheduleCameraRetry(reason);
    }

    private void scheduleCameraRetry(String reason) {
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled", "reason", blocked,
                    "trigger", reason);
            return;
        }
        if (!cameraRetry.schedule(reason)) return;
        handler.postDelayed(retryCamera, CAMERA_RETRY_MS);
        emit("overlay_camera_retry", "state", "scheduled", "reason", reason,
                "delay_ms", CAMERA_RETRY_MS,
                "preview_index", previewIndexForDirection(desiredDirection()));
    }

    private void retryCameraOpen() {
        String trigger = cameraRetry.consume();
        if (trigger == null) return;
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled", "reason", blocked,
                    "trigger", trigger);
            return;
        }
        int direction = desiredDirection();
        emit("overlay_camera_retry", "state", "attempt",
                "reason", trigger,
                "preview_index", previewIndexForDirection(direction));
        requestDirection(direction);
    }

    private String cameraRetryBlockReason() {
        return cameraRetryBlockReason(
                shutdown,
                settings.getBoolean(PREF_ENABLED, false),
                suspended,
                helper != null,
                Settings.canDrawOverlays(context),
                preview != null && preview.isCameraSurfaceReady());
    }

    static String cameraRetryBlockReason(
            boolean shutdown,
            boolean enabled,
            boolean suspended,
            boolean helperAvailable,
            boolean overlayPermission,
            boolean surfaceReady) {
        if (shutdown) return "shutdown";
        if (!enabled) return "overlay_disabled";
        if (suspended) return "overlay_suspended";
        if (!helperAvailable) return "helper_unavailable";
        if (!overlayPermission) return "overlay_permission_missing";
        if (!surfaceReady) return "surface_unavailable";
        return null;
    }

    private void cancelCameraRetry(String reason) {
        String trigger = cameraRetry.cancel();
        if (trigger == null) return;
        handler.removeCallbacks(retryCamera);
        emit("overlay_camera_retry", "state", "cancelled", "reason", reason,
                "trigger", trigger);
    }

    static final class CameraRetryState {
        private String trigger;

        boolean schedule(String reason) {
            if (trigger != null) return false;
            trigger = reason == null ? "unknown" : reason;
            return true;
        }

        String consume() {
            return clear();
        }

        String cancel() {
            return clear();
        }

        boolean active() {
            return trigger != null;
        }

        private String clear() {
            String value = trigger;
            trigger = null;
            return value;
        }
    }

    private void show(int direction) {
        if (layout == null || window == null || windows == null || !windowArmed) return;
        boolean wasVisible = visible;
        if (preparedDirection != direction) {
            prepareDirection(direction);
            window.post(this::evaluate);
            return;
        }
        window.setVisibility(View.VISIBLE);
        visible = true;
        if (!wasVisible) {
            emit("overlay_visibility", "visible", true,
                    "direction", direction == BLINK_LEFT ? "left" : "right",
                    "speed_kph", speedKph);
        }
    }

    private void hide(String reason) {
        if (layout == null || window == null || windows == null) return;
        boolean wasVisible = visible;
        // Keep the initial alpha-zero view drawable until TextureView creates its Surface.
        if (!windowArmed) {
            visible = false;
            return;
        }
        if (!wasVisible && window.getVisibility() == View.INVISIBLE) return;
        window.setVisibility(View.INVISIBLE);
        visible = false;
        if (wasVisible) emit("overlay_visibility", "visible", false, "reason", reason);
    }

    private boolean prepareDirection(int direction) {
        if (layout == null || window == null || windows == null
                || preparedDirection == direction) return false;
        if (visible) hide("overlay_geometry_changed");
        setExpandedLayout(direction);
        try {
            windows.updateViewLayout(window, layout);
        } catch (Throwable error) {
            emit("overlay_window_error", "error", summary(error));
            return false;
        }
        preparedDirection = direction;
        emit("overlay_geometry", "direction", direction == BLINK_RIGHT ? "right" : "left",
                "width", layout.width, "height", layout.height,
                "x", layout.x, "y", layout.y);
        return true;
    }

    private void armWindowAfterSurfaceCreated() {
        if (windowArmed || window == null || layout == null || windows == null) return;
        window.setVisibility(View.INVISIBLE);
        layout.alpha = 1.0f;
        try {
            windows.updateViewLayout(window, layout);
            windowArmed = true;
            emit("overlay_window", "state", "armed",
                    "width", layout.width, "height", layout.height,
                    "x", layout.x, "y", layout.y);
        } catch (Throwable error) {
            emit("overlay_window_error", "error", summary(error));
        }
    }

    private void setExpandedLayout(int direction) {
        if (layout == null || windows == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        boolean right = direction == BLINK_RIGHT;
        DirectCameraCrop crop = loadDirectCrop(right);
        int[] size = overlaySize(metrics, crop.outputAspect());
        int width = size[0];
        int height = size[1];
        float x = readPosition(settings, right, false);
        float y = readPosition(settings, right, true);
        int marginX = dp(16);
        int topMargin = dp(36);
        int bottomMargin = dp(88);
        int availableX = Math.max(0, metrics.widthPixels - width - marginX * 2);
        int availableY = Math.max(0, metrics.heightPixels - height - topMargin - bottomMargin);
        layout.width = width;
        layout.height = height;
        layout.x = marginX + Math.round(availableX * x);
        layout.y = topMargin + Math.round(availableY * y);
        if (preview != null) preview.post(() -> preview.applyDirectCameraCrop(crop));
    }

    private void configureSurfaceBuffer() {
        if (preview != null) {
            preview.applyDirectCameraCrop(loadDirectCrop(desiredDirection() == BLINK_RIGHT));
        }
    }

    private int[] overlaySize(DisplayMetrics metrics, float aspect) {
        int scale = clamp(settings.getInt(PREF_SCALE, DEFAULT_SCALE_PERCENT),
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
        int requestedWidth = metrics.widthPixels * scale / 100;
        int availableHeight = Math.max(1, metrics.heightPixels - dp(36) - dp(88));
        return fitAspect(requestedWidth, metrics.widthPixels, availableHeight, aspect);
    }

    private DirectCameraCrop loadDirectCrop(boolean right) {
        DirectCameraCrop fallback = DirectCameraCrop.defaultFor(right);
        return DirectCameraCrop.of(
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_X : DirectCameraCrop.PREF_LEFT_X,
                        fallback.left),
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_Y : DirectCameraCrop.PREF_LEFT_Y,
                        fallback.top),
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_WIDTH : DirectCameraCrop.PREF_LEFT_WIDTH,
                        fallback.width),
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_HEIGHT : DirectCameraCrop.PREF_LEFT_HEIGHT,
                        fallback.height),
                settings.getInt(right
                        ? DirectCameraCrop.PREF_RIGHT_ASPECT : DirectCameraCrop.PREF_LEFT_ASPECT,
                        DirectCameraCrop.ASPECT_FOUR_THREE));
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Surface duplicate(Surface source) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Surface.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
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
}
