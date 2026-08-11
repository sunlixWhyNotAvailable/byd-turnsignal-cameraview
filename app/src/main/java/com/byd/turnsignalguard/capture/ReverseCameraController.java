package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.Surface;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class ReverseCameraController {
    static final String PREF_ENABLED = "reverse_camera_enabled";
    static final boolean DEFAULT_ENABLED = false;
    private static final String PREF_PREFIX = "reverse_camera_";
    private static final long SURFACE_TIMEOUT_MS = 8_000;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3_000;
    private static final long RETRY_MS = 3_000;
    private static final long GEAR_FRESHNESS_MS = 12_000;

    private final Handler handler;
    private final SharedPreferences settings;
    private final BiConsumer<String, Object[]> eventSink;
    private final Consumer<Boolean> prioritySink;
    private final Runnable surfaceTimeout = () -> fail("surface_timeout");
    private final Runnable firstFrameTimeout = () -> fail("first_frame_timeout");
    private final Runnable gearFreshnessTimeout = () -> {
        gearValid = false;
        reverse = false;
        emit("reverse_camera_error", "stage", "gear_stale",
                "timeout_ms", GEAR_FRESHNESS_MS);
        stop("gear_stale", false);
    };
    private final Runnable retry = () -> {
        retryScheduled = false;
        evaluate();
    };
    private CameraHelperMain.HelperBinder helper;
    private boolean gearValid;
    private boolean reverse;
    private boolean stopping;
    private boolean retryScheduled;
    private boolean cleanupRetryScheduled;
    private boolean visible;
    private boolean shutdown;
    private int requestSequence;
    private int activeRequestId;
    private int[] generations = new int[0];
    private long cameraShellEpoch;
    private CleanupCloseCoordinator activeCleanup;
    private Runnable cleanupRetryTask;
    private final Runnable cleanupRetry = () -> {
        cleanupRetryScheduled = false;
        Runnable task = cleanupRetryTask;
        cleanupRetryTask = null;
        if (task != null) task.run();
    };

    ReverseCameraController(
            Context context, Handler handler,
            BiConsumer<String, Object[]> eventSink,
            Consumer<Boolean> prioritySink) {
        this.handler = handler;
        this.eventSink = eventSink;
        this.prioritySink = prioritySink;
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    void attachHelper(CameraHelperMain.HelperBinder value) {
        helper = value;
        resetRuntime("helper_attached");
        evaluate();
    }

    void settingsChanged() {
        if (!enabled()) {
            stop("disabled", false);
            return;
        }
        if (activeRequestId != 0 || stopping) stop("settings_changed", false);
        else evaluate();
    }

    void acceptEvent(String line) {
        if (line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            handler.post(() -> acceptEventOnMain(event));
        } catch (Throwable error) {
            emit("reverse_camera_error", "stage", "event_parse",
                    "error", summary(error));
        }
    }

    private void acceptEventOnMain(JSONObject event) {
        try {
            String kind = event.optString("kind");
            if ("reverse_gear_state".equals(kind)) {
                gearValid = event.optBoolean("valid", false)
                        && event.optBoolean("listener_ok", false);
                reverse = gearValid && event.optBoolean("reverse", false);
                handler.removeCallbacks(gearFreshnessTimeout);
                if (reverse) {
                    handler.postDelayed(gearFreshnessTimeout, GEAR_FRESHNESS_MS);
                }
                emit("reverse_camera_decision", "gear_valid", gearValid,
                        "reverse", reverse, "enabled", enabled(),
                        "gear_raw", event.optInt("raw", -1));
                evaluate();
            } else if ("reverse_gear_listener".equals(kind)
                    && !event.optBoolean("listener_ok", false)) {
                gearValid = false;
                reverse = false;
                handler.removeCallbacks(gearFreshnessTimeout);
                stop("gear_listener_unavailable", false);
            } else if ("helper_death".equals(kind)
                    || "helper_ping_failed".equals(kind)) {
                gearValid = false;
                reverse = false;
                handler.removeCallbacks(gearFreshnessTimeout);
                stop("gear_helper_unavailable", false);
            } else if ("camera_opened".equals(kind)
                    && "reverse".equals(event.optString("camera_owner"))) {
                cameraOpened(event.optInt("request_id", -1));
            } else if ("camera_error".equals(kind)
                    && "reverse".equals(event.optString("camera_owner"))) {
                int requestId = event.optInt("request_id", -1);
                if (matchesCameraOpenEvent(activeRequestId, requestId)) {
                    fail("camera_open_error");
                }
            } else if ("reverse_overlay_first_frames".equals(kind)) {
                framesReady(event.optInt("request_id", -1));
            } else if ("reverse_overlay_surface".equals(kind)
                    && "destroyed".equals(event.optString("state"))) {
                int requestId = event.optInt("request_id", -1);
                if (matchesCameraOpenEvent(activeRequestId, requestId)) {
                    fail("surface_destroyed");
                }
            } else if ("reverse_overlay_error".equals(kind)) {
                int requestId = event.optInt("request_id", -1);
                if (matchesCameraOpenEvent(activeRequestId, requestId)) {
                    fail("overlay_error");
                }
            } else if ("camera_shell_attached".equals(kind)) {
                long epoch = event.optLong("camera_shell_epoch", 0);
                if (epoch > cameraShellEpoch) cameraShellEpoch = epoch;
            } else if ("camera_shell_died".equals(kind)) {
                long epoch = event.optLong("camera_shell_epoch", 0);
                if (!TurnSignalController.isCurrentCameraShellEpoch(
                        cameraShellEpoch, epoch)) return;
                emit("reverse_camera_output_invalidated",
                        "request_id", activeRequestId,
                        "generations", Arrays.toString(generations),
                        "reason", "camera_shell_died");
                visible = false;
                fail("camera_shell_died");
            }
        } catch (Throwable error) {
            emit("reverse_camera_error", "stage", "event_parse",
                    "error", summary(error));
        }
    }

    void shutdown() {
        shutdown = true;
        cancelTimers();
        handler.removeCallbacks(gearFreshnessTimeout);
        clearCleanupRetry();
        gearValid = false;
        reverse = false;
        activeRequestId = 0;
        generations = new int[0];
        visible = false;
        stopping = false;
        activeCleanup = null;
        prioritySink.accept(false);
        helper = null;
        emit("reverse_camera_runtime_reset", "reason", "service_shutdown");
    }

    private void evaluate() {
        if (shutdown) return;
        if (!enabled() || !gearValid || !reverse || helper == null) {
            stop(!enabled() ? "disabled" : !gearValid ? "invalid_gear"
                    : !reverse ? "not_reverse" : "helper_unavailable", false);
            return;
        }
        if (stopping || activeRequestId != 0 || retryScheduled) return;
        start();
    }

    private void start() {
        CameraHelperMain.HelperBinder activeHelper = helper;
        if (activeHelper == null) return;
        int requestId = nextRequestId();
        activeRequestId = requestId;
        generations = new int[0];
        visible = false;
        emit("reverse_camera_start", "request_id", requestId);
        prioritySink.accept(true);
        CameraShellProtocol.ReverseOverlaySpec spec =
                new CameraShellProtocol.ReverseOverlaySpec(
                        requestId, loadLayout(settings), loadRawLayout(settings),
                        BlindSpotOverlayController.readCornerRadius(settings),
                        CameraDewarpConfig.load(settings, CameraDewarpConfig.LENS_REAR),
                        CameraDewarpConfig.load(settings, CameraDewarpConfig.LENS_LEFT),
                        CameraDewarpConfig.load(settings, CameraDewarpConfig.LENS_RIGHT));
        activeHelper.prepareReverseOverlayWindow(
                spec, this::surfacesAvailable, () -> overlayPrepared(requestId));
    }

    private void overlayPrepared(int requestId) {
        if (requestId != activeRequestId || stopping) return;
        handler.removeCallbacks(surfaceTimeout);
        handler.postDelayed(surfaceTimeout, SURFACE_TIMEOUT_MS);
    }

    private void surfacesAvailable(TurnSignalController.ReverseSurfaces value) {
        if (value == null || value.requestId != activeRequestId || stopping) {
            if (value != null) release(value.surfaces);
            return;
        }
        handler.removeCallbacks(surfaceTimeout);
        generations = value.generations.clone();
        CameraHelperMain.HelperBinder activeHelper = helper;
        if (activeHelper == null) {
            release(value.surfaces);
            fail("helper_unavailable");
            return;
        }
        try {
            activeHelper.openReverseCamera(value.surfaces, activeRequestId);
        } catch (Throwable error) {
            emit("reverse_camera_error", "stage", "open_surfaces",
                    "error", summary(error));
            fail("open_surfaces");
            return;
        }
        emit("reverse_camera_surfaces", "request_id", activeRequestId,
                "generations", Arrays.toString(generations));
    }

    private void cameraOpened(int requestId) {
        if (!matchesCameraOpenEvent(activeRequestId, requestId)
                || generations.length != 3 || stopping || helper == null) return;
        handler.removeCallbacks(firstFrameTimeout);
        handler.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
        helper.armReverseOverlayFrames(activeRequestId, generations);
        emit("reverse_camera_waiting_frames", "request_id", activeRequestId,
                "timeout_ms", FIRST_FRAME_TIMEOUT_MS);
    }

    private void framesReady(int requestId) {
        if (requestId != activeRequestId || generations.length != 3
                || stopping || helper == null) return;
        handler.removeCallbacks(firstFrameTimeout);
        helper.setReverseOverlayVisible(requestId, generations, true, null);
        visible = true;
        emit("reverse_camera_visible", "request_id", requestId,
                "generations", Arrays.toString(generations));
    }

    private void fail(String reason) {
        if (activeRequestId == 0 || stopping) return;
        emit("reverse_camera_error", "stage", reason, "request_id", activeRequestId);
        stop(reason, true);
    }

    private void stop(String reason, boolean retryAfter) {
        if (stopping) return;
        cancelTimers();
        int closingRequestId = activeRequestId;
        int[] closingGenerations = generations;
        boolean wasVisible = visible;
        activeRequestId = 0;
        generations = new int[0];
        visible = false;
        if (closingRequestId == 0) {
            prioritySink.accept(false);
            if (retryAfter) scheduleRetry(reason);
            return;
        }
        stopping = true;
        CameraHelperMain.HelperBinder activeHelper = helper;
        CleanupCloseCoordinator cleanup = new CleanupCloseCoordinator(
                closingRequestId,
                candidate -> !shutdown && stopping && activeCleanup == candidate,
                requestId -> activeHelper == null
                        || activeHelper.closeReverseCamera(reason, requestId),
                closed -> {
                    if (activeHelper == null) closed.accept(true);
                    else activeHelper.closeReverseOverlayWindow(reason, closed);
                },
                this::scheduleCleanupRetry,
                this::clearCleanupRetry,
                cameraClosed -> emit("reverse_camera_cleanup_retry",
                        "request_id", closingRequestId,
                        "reason", reason, "camera_closed", cameraClosed,
                        "delay_ms", RETRY_MS),
                error -> emit("reverse_camera_error", "stage", "close_camera",
                        "request_id", closingRequestId, "error", summary(error)),
                error -> emit("reverse_camera_error", "stage", "queue_close",
                        "error", summary(error)),
                state -> finishStop(reason, retryAfter, state));
        activeCleanup = cleanup;
        Runnable closeCamera = cleanup::startCameraThenWindow;
        if (activeHelper != null && wasVisible && closingGenerations.length == 3) {
            try {
                activeHelper.setReverseOverlayVisible(
                        closingRequestId, closingGenerations, false,
                        hidden -> {
                            if (hidden) closeCamera.run();
                            else cleanup.startWindowThenCamera();
                        });
            } catch (Throwable error) {
                emit("reverse_camera_error", "stage", "queue_hide",
                        "error", summary(error));
                cleanup.startWindowThenCamera();
            }
        } else {
            closeCamera.run();
        }
    }

    private void finishStop(
            String reason, boolean retryAfter, CleanupCloseCoordinator state) {
        if (shutdown || activeCleanup != state) return;
        activeCleanup = null;
        stopping = false;
        prioritySink.accept(false);
        emit("reverse_camera_stopped", "request_id", state.requestId,
                "reason", reason);
        if (retryAfter) scheduleRetry(reason);
        else evaluate();
    }

    private void scheduleCleanupRetry(Runnable task) {
        cleanupRetryTask = task;
        if (cleanupRetryScheduled) return;
        cleanupRetryScheduled = true;
        handler.postDelayed(cleanupRetry, RETRY_MS);
    }

    private void clearCleanupRetry() {
        if (cleanupRetryScheduled) handler.removeCallbacks(cleanupRetry);
        cleanupRetryScheduled = false;
        cleanupRetryTask = null;
    }

    private void scheduleRetry(String reason) {
        if (!enabled() || !gearValid || !reverse || helper == null || retryScheduled) return;
        retryScheduled = true;
        handler.postDelayed(retry, RETRY_MS);
        emit("reverse_camera_retry", "reason", reason, "delay_ms", RETRY_MS);
    }

    private void cancelTimers() {
        handler.removeCallbacks(surfaceTimeout);
        handler.removeCallbacks(firstFrameTimeout);
        if (retryScheduled) handler.removeCallbacks(retry);
        retryScheduled = false;
    }

    private void resetRuntime(String reason) {
        cancelTimers();
        handler.removeCallbacks(gearFreshnessTimeout);
        clearCleanupRetry();
        activeRequestId = 0;
        generations = new int[0];
        visible = false;
        stopping = false;
        activeCleanup = null;
        prioritySink.accept(false);
        emit("reverse_camera_runtime_reset", "reason", reason);
    }

    private boolean enabled() {
        return settings.getBoolean(PREF_ENABLED, DEFAULT_ENABLED);
    }

    private int nextRequestId() {
        requestSequence = requestSequence == Integer.MAX_VALUE ? 1 : requestSequence + 1;
        return requestSequence;
    }

    static boolean matchesCameraOpenEvent(int activeRequestId, int eventRequestId) {
        return activeRequestId > 0 && eventRequestId == activeRequestId;
    }

    interface CameraCloser {
        boolean close(int requestId) throws Throwable;
    }

    interface WindowCloser {
        void close(Consumer<Boolean> callback) throws Throwable;
    }

    static final class CleanupCloseCoordinator {
        final int requestId;
        private final Predicate<CleanupCloseCoordinator> current;
        private final CameraCloser cameraCloser;
        private final WindowCloser windowCloser;
        private final Consumer<Runnable> retryScheduler;
        private final Runnable retryCanceller;
        private final Consumer<Boolean> retrySink;
        private final Consumer<Throwable> cameraErrorSink;
        private final Consumer<Throwable> windowErrorSink;
        private final Consumer<CleanupCloseCoordinator> finishedSink;
        private boolean cameraClosed;
        private int closeAttempts;
        private boolean finished;

        CleanupCloseCoordinator(
                int requestId,
                Predicate<CleanupCloseCoordinator> current,
                CameraCloser cameraCloser,
                WindowCloser windowCloser,
                Consumer<Runnable> retryScheduler,
                Runnable retryCanceller,
                Consumer<Boolean> retrySink,
                Consumer<Throwable> cameraErrorSink,
                Consumer<Throwable> windowErrorSink,
                Consumer<CleanupCloseCoordinator> finishedSink) {
            this.requestId = requestId;
            this.current = current;
            this.cameraCloser = cameraCloser;
            this.windowCloser = windowCloser;
            this.retryScheduler = retryScheduler;
            this.retryCanceller = retryCanceller;
            this.retrySink = retrySink;
            this.cameraErrorSink = cameraErrorSink;
            this.windowErrorSink = windowErrorSink;
            this.finishedSink = finishedSink;
        }

        void startCameraThenWindow() {
            if (!isCurrent()) return;
            attemptCameraClose();
            closeWindow(true);
        }

        void startWindowThenCamera() {
            closeWindow(false);
        }

        private void closeWindow(boolean cameraAttemptedThisPass) {
            if (!isCurrent()) return;
            try {
                windowCloser.close(success ->
                        windowClosed(success, cameraAttemptedThisPass));
            } catch (Throwable error) {
                windowErrorSink.accept(error);
                windowClosed(false, cameraAttemptedThisPass);
            }
        }

        private void windowClosed(boolean success, boolean cameraAttemptedThisPass) {
            if (!isCurrent()) return;
            if (!success) {
                scheduleRetry();
                return;
            }
            retryCanceller.run();
            if (!cameraClosed && !cameraAttemptedThisPass) attemptCameraClose();
            if (!cameraClosed) {
                scheduleRetry();
                return;
            }
            finished = true;
            finishedSink.accept(this);
        }

        private void attemptCameraClose() {
            closeAttempts++;
            try {
                cameraClosed |= cameraCloser.close(requestId);
            } catch (Throwable error) {
                cameraErrorSink.accept(error);
            }
        }

        private void scheduleRetry() {
            retrySink.accept(cameraClosed);
            retryScheduler.accept(() -> closeWindow(false));
        }

        private boolean isCurrent() {
            return !finished && current.test(this);
        }

        int closeAttempts() {
            return closeAttempts;
        }

        boolean cameraClosed() {
            return cameraClosed;
        }
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    static ReverseCameraLayout loadLayout(SharedPreferences settings) {
        ReverseCameraLayout layout = readLayout(settings);
        for (ReverseCameraLayout.Pane pane : layout.panes()) {
            CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                    settings, CameraDewarpConfig.lensForReverseCamera(pane.cameraIndex));
            if (!dewarp.enabled) continue;
            layout = ReverseCameraLayout.withPane(layout, pane.cameraIndex,
                    pane.destination, loadCorrectedSourceCrop(
                            settings, pane.cameraIndex,
                            ReverseCameraLayout.centeredSourceCrop(pane.sourceCrop)),
                    pane.rotationDegrees);
        }
        return layout;
    }

    static ReverseCameraLayout loadRawLayout(SharedPreferences settings) {
        return readLayout(settings);
    }

    private static ReverseCameraLayout readLayout(SharedPreferences settings) {
        try {
            ReverseCameraLayout layout = ReverseCameraLayout.defaults();
            ReverseCameraLayout.Rect defaultBackground = layout.background;
            layout = ReverseCameraLayout.withBackground(layout,
                    ReverseCameraLayout.destination(
                            settings.getFloat(PREF_PREFIX + "background_left",
                                    defaultBackground.left),
                            settings.getFloat(PREF_PREFIX + "background_top",
                                    defaultBackground.top),
                            settings.getFloat(PREF_PREFIX + "background_width",
                                    defaultBackground.width),
                            settings.getFloat(PREF_PREFIX + "background_height",
                                    defaultBackground.height)));
            for (ReverseCameraLayout.Pane pane : layout.panes()) {
                String prefix = PREF_PREFIX + pane.cameraIndex + "_";
                ReverseCameraLayout.Rect destination = ReverseCameraLayout.destination(
                        settings.getFloat(prefix + "left", pane.destination.left),
                        settings.getFloat(prefix + "top", pane.destination.top),
                        settings.getFloat(prefix + "width", pane.destination.width),
                        settings.getFloat(prefix + "height", pane.destination.height));
                ReverseCameraLayout.Rect crop = loadSourceCrop(
                        settings, pane.cameraIndex, pane.sourceCrop);
                int rotationDegrees = CameraRotation.clamp(settings.getInt(
                        prefix + "rotation_degrees", pane.rotationDegrees));
                layout = ReverseCameraLayout.withPane(
                        layout, pane.cameraIndex, destination, crop, rotationDegrees);
                layout = ReverseCameraLayout.withDisplayMode(
                        layout, pane.cameraIndex, readDisplayMode(settings, pane.cameraIndex));
            }
            for (int z = 0; z < 3; z++) {
                int cameraIndex = settings.getInt(PREF_PREFIX + "z_" + z, z + 1);
                layout = ReverseCameraLayout.bringToFront(layout, cameraIndex);
            }
            return layout;
        } catch (Throwable ignored) {
            return ReverseCameraLayout.defaults();
        }
    }

    static void saveLayout(SharedPreferences settings, ReverseCameraLayout layout) {
        SharedPreferences.Editor editor = settings.edit()
                .putFloat(PREF_PREFIX + "background_left", layout.background.left)
                .putFloat(PREF_PREFIX + "background_top", layout.background.top)
                .putFloat(PREF_PREFIX + "background_width", layout.background.width)
                .putFloat(PREF_PREFIX + "background_height", layout.background.height);
        for (ReverseCameraLayout.Pane pane : layout.panes()) {
            String prefix = PREF_PREFIX + pane.cameraIndex + "_";
            editor.putFloat(prefix + "left", pane.destination.left)
                    .putFloat(prefix + "top", pane.destination.top)
                    .putFloat(prefix + "width", pane.destination.width)
                    .putFloat(prefix + "height", pane.destination.height)
                    .putInt(prefix + "rotation_degrees", pane.rotationDegrees)
                    .putInt(displayModeKey(pane.cameraIndex), pane.displayMode)
                    .putInt(PREF_PREFIX + "z_" + pane.zOrder, pane.cameraIndex);
            CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                    settings, CameraDewarpConfig.lensForReverseCamera(pane.cameraIndex));
            writeSourceCrop(editor, pane.cameraIndex, pane.sourceCrop, dewarp.enabled);
        }
        editor.apply();
    }

    static void resetLayout(SharedPreferences settings) {
        ReverseCameraLayout defaults = ReverseCameraLayout.defaults();
        SharedPreferences.Editor editor = settings.edit()
                .putFloat(PREF_PREFIX + "background_left", defaults.background.left)
                .putFloat(PREF_PREFIX + "background_top", defaults.background.top)
                .putFloat(PREF_PREFIX + "background_width", defaults.background.width)
                .putFloat(PREF_PREFIX + "background_height", defaults.background.height);
        for (ReverseCameraLayout.Pane pane : defaults.panes()) {
            String prefix = PREF_PREFIX + pane.cameraIndex + "_";
            editor.putFloat(prefix + "left", pane.destination.left)
                    .putFloat(prefix + "top", pane.destination.top)
                    .putFloat(prefix + "width", pane.destination.width)
                    .putFloat(prefix + "height", pane.destination.height)
                    .putInt(prefix + "rotation_degrees", pane.rotationDegrees)
                    .putInt(displayModeKey(pane.cameraIndex),
                            ReverseCameraLayout.DEFAULT_DISPLAY_MODE)
                    .putInt(PREF_PREFIX + "z_" + pane.zOrder, pane.cameraIndex);
            writeSourceCrop(editor, pane.cameraIndex, pane.sourceCrop);
            writeSourceCrop(editor, pane.cameraIndex,
                    ReverseCameraLayout.centeredSourceCrop(pane.sourceCrop), true);
        }
        editor.apply();
    }

    private static ReverseCameraLayout.Rect loadSourceCrop(
            SharedPreferences settings, int cameraIndex,
            ReverseCameraLayout.Rect fallback) {
        return loadActiveSourceCrop(settings, cameraIndex, false,
                settings.getFloat(sourceCropKey(cameraIndex, "left", false), fallback.left),
                settings.getFloat(sourceCropKey(cameraIndex, "top", false), fallback.top),
                settings.getFloat(sourceCropKey(cameraIndex, "width", false), fallback.width),
                settings.getFloat(sourceCropKey(cameraIndex, "height", false), fallback.height));
    }

    private static void writeSourceCrop(
            SharedPreferences.Editor editor, int cameraIndex,
            ReverseCameraLayout.Rect crop) {
        writeSourceCrop(editor, cameraIndex, crop, false);
    }

    static ReverseCameraLayout.Rect loadCorrectedSourceCrop(
            SharedPreferences settings, int cameraIndex,
            ReverseCameraLayout.Rect fallback) {
        String prefix = correctedSourceCropPrefix(cameraIndex);
        return loadActiveSourceCrop(settings, cameraIndex, true,
                settings.getFloat(prefix + "left", fallback.left),
                settings.getFloat(prefix + "top", fallback.top),
                settings.getFloat(prefix + "width", fallback.width),
                settings.getFloat(prefix + "height", fallback.height));
    }

    private static ReverseCameraLayout.Rect loadActiveSourceCrop(
            SharedPreferences settings, int cameraIndex, boolean corrected,
            float left, float top, float width, float height) {
        boolean migrate = SourceCropPolicy.needsMigration(width, height);
        float[] geometry = migrate
                ? SourceCropPolicy.migrate(left, top, width, height)
                : new float[]{left, top, width, height};
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(
                geometry[0], geometry[1], geometry[2], geometry[3]);
        if (migrate) saveSourceCrop(settings, cameraIndex, crop, corrected);
        return crop;
    }

    static void saveSourceCrop(SharedPreferences settings, int cameraIndex,
            ReverseCameraLayout.Rect crop, boolean corrected) {
        SharedPreferences.Editor editor = settings.edit();
        writeSourceCrop(editor, cameraIndex, crop, corrected);
        editor.apply();
    }

    static void writeSourceCrop(
            SharedPreferences.Editor editor, int cameraIndex,
            ReverseCameraLayout.Rect crop, boolean corrected) {
        String prefix = corrected ? correctedSourceCropPrefix(cameraIndex) : null;
        editor.putFloat(corrected ? prefix + "left"
                        : sourceCropKey(cameraIndex, "left", false), crop.left)
                .putFloat(corrected ? prefix + "top"
                        : sourceCropKey(cameraIndex, "top", false), crop.top)
                .putFloat(corrected ? prefix + "width"
                        : sourceCropKey(cameraIndex, "width", false), crop.width)
                .putFloat(corrected ? prefix + "height"
                        : sourceCropKey(cameraIndex, "height", false), crop.height);
    }

    private static String correctedSourceCropPrefix(int cameraIndex) {
        CameraDewarpConfig.lensForReverseCamera(cameraIndex);
        return PREF_PREFIX + cameraIndex + "_corrected_v3_crop_";
    }

    static String sourceCropKey(int cameraIndex, String field, boolean dewarped) {
        CameraDewarpConfig.lensForReverseCamera(cameraIndex);
        if (!"left".equals(field) && !"top".equals(field)
                && !"width".equals(field) && !"height".equals(field)) {
            throw new IllegalArgumentException("invalid reverse crop field");
        }
        return PREF_PREFIX + cameraIndex + "_"
                + (dewarped ? "dewarp_v2_crop_" : "crop_") + field;
    }

    static String displayModeKey(int cameraIndex) {
        switch (cameraIndex) {
            case ReverseCameraLayout.REAR_CAMERA_INDEX:
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                return PREF_PREFIX + cameraIndex + "_display_mode";
            default:
                throw new IllegalArgumentException("unsupported reverse camera index: "
                        + cameraIndex);
        }
    }

    static String paneSettingKey(int cameraIndex, String field) {
        CameraDewarpConfig.lensForReverseCamera(cameraIndex);
        if (!"left".equals(field) && !"top".equals(field)
                && !"width".equals(field) && !"height".equals(field)
                && !"rotation_degrees".equals(field)) {
            throw new IllegalArgumentException("invalid reverse pane field");
        }
        return PREF_PREFIX + cameraIndex + "_" + field;
    }

    private static int readDisplayMode(SharedPreferences settings, int cameraIndex) {
        try {
            return ReverseCameraLayout.normalizeDisplayMode(
                    settings.getInt(displayModeKey(cameraIndex),
                            ReverseCameraLayout.DEFAULT_DISPLAY_MODE));
        } catch (Throwable ignored) {
            return ReverseCameraLayout.DEFAULT_DISPLAY_MODE;
        }
    }

    private static void release(Surface[] surfaces) {
        if (surfaces == null) return;
        for (Surface surface : surfaces) if (surface != null) surface.release();
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
