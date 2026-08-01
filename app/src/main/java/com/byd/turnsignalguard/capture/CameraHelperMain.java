package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

final class CameraHelperMain {
    static final String PACKAGE_NAME = "com.byd.turnsignalguard.capture";
    static final String DESCRIPTOR = PACKAGE_NAME + ".ICameraProbeHelper";
    static final String CALLBACK_DESCRIPTOR = PACKAGE_NAME + ".ICameraProbeCallback";

    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_OPEN = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_SET_GUARD = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_DETACH_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_SET_TURN_STATE = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_RETRY_ADB_AUTH = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int TX_OPEN_STOCK_AVM = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int TX_OPEN_DIRECT = IBinder.FIRST_CALL_TRANSACTION + 8;
    static final int TX_OPEN_REVERSE_PREVIEW = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;
    static final int ADB_AUTH_MODE_AUTO_ONCE = 0;
    static final int ADB_AUTH_MODE_FORCE = 1;
    static final String CAMERA_OWNER_ACTIVITY = "activity";
    static final String CAMERA_OWNER_OVERLAY = "overlay";
    static final String CAMERA_OWNER_REVERSE = "reverse";

    private static final String TAG = "BydCameraProbe";
    private static final String COUNTER_PREFS = "lifetime_counters";
    private static final String ACTIVATION_COUNT = "activation_count";
    private static final String CORRECTION_COUNT = "correction_count";
    private static final String[] DIRECT_CAMERA_TAGS = {
            "pano_h", "pano_l", "apa", "byd_apa"
    };
    private CameraHelperMain() {}

    static final class HelperBinder extends Binder {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final TurnSignalController turnController;
        private final Consumer<String> logSink;
        private final SharedPreferences counters;
        private final ArrayDeque<String> musicJournal = new ArrayDeque<>();
        private IBinder callback;
        private int cameraId = -1;
        private String cameraTag = "none";
        private String discoveryError;
        private Object camera;
        private Object eventCallback;
        private Surface surface;
        private int previewIndex;
        private Surface[] multiSurfaces = new Surface[0];
        private int[] multiPreviewIndexes = new int[0];
        private String viewName;
        private int activeCameraId = -1;
        private String activeCameraTag = "none";
        private String activeCameraOwner = "none";
        private boolean stockCameraRequested;
        private Surface[] pendingReversePreviewSurfaces = new Surface[0];

        HelperBinder(Context context, Consumer<String> logSink) {
            this.logSink = logSink;
            counters = context.getSharedPreferences(COUNTER_PREFS, Context.MODE_PRIVATE);
            migrateLegacyCounters(context);
            turnController = new TurnSignalController(
                    context, mainHandler, this::acceptShellEvent, this::emit);
        }

        void startGuardRuntime() {
            turnController.start();
        }

        void configureGuard(
                boolean enabled, float outward, float center, int delayMs, int maxSpeedKph) {
            turnController.configure(enabled, outward, center, delayMs, maxSpeedKph);
        }

        void configureMusic(boolean enabled) {
            turnController.configureMusic(enabled);
        }

        void setRecoveryEnabled(boolean enabled) {
            turnController.setRecoveryEnabled(enabled);
        }

        void emitControllerEvent(String kind, Object... fields) {
            emit(kind, fields);
        }

        void shutdown(boolean terminateShells) {
            turnController.shutdown(terminateShells);
            closeCamera("service_destroyed");
            emit("helper_shutdown", "reason", "service_destroyed",
                    "terminate_shells", terminateShells);
        }

        @Override
        protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                data.enforceInterface(DESCRIPTOR);
                if (code == TX_REGISTER_CALLBACK) {
                    registerCallback(data.readStrongBinder());
                    reply.writeNoException();
                    reply.writeString(result("callback_registered", null));
                    return true;
                }
                if (code == TX_OPEN) {
                    Surface requestedSurface = Surface.CREATOR.createFromParcel(data);
                    int requestedIndex = data.readInt();
                    String requestedView = data.readString();
                    String result = openCamera(requestedSurface, requestedIndex, requestedView);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_CLOSE) {
                    String reason = data.readString();
                    String result = closeCameraForOwner(CAMERA_OWNER_ACTIVITY, reason);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_SET_GUARD) {
                    boolean requested = data.readInt() != 0;
                    float outward = data.readFloat();
                    float center = data.readFloat();
                    int correctionDelayMs = data.readInt();
                    int maxSpeedKph = data.readInt();
                    turnController.configure(
                            requested, outward, center, correctionDelayMs, maxSpeedKph);
                    reply.writeNoException();
                    reply.writeString(result("guard_configured", null));
                    return true;
                }
                if (code == TX_DETACH_CALLBACK) {
                    callback = null;
                    reply.writeNoException();
                    reply.writeString(result("callback_detached", null));
                    return true;
                }
                if (code == TX_SET_TURN_STATE) {
                    turnController.setManualState(data.readInt());
                    reply.writeNoException();
                    reply.writeString(result("manual_turn_state_queued", null));
                    return true;
                }
                if (code == TX_RETRY_ADB_AUTH) {
                    LocalAdbClient.PromptMode mode = adbAuthorizationMode(data.readInt());
                    TurnSignalController.AuthorizationRequestAction action =
                            turnController.requestAuthorization(mode);
                    reply.writeNoException();
                    reply.writeString(authorizationResult(mode, action));
                    return true;
                }
                if (code == TX_OPEN_STOCK_AVM) {
                    Surface requestedSurface = Surface.CREATOR.createFromParcel(data);
                    int viewpoint = data.readInt();
                    boolean horizontal = data.readInt() != 0;
                    String result = openStockAvm(requestedSurface, viewpoint, horizontal);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_OPEN_DIRECT) {
                    Surface requestedSurface = Surface.CREATOR.createFromParcel(data);
                    String requestedTag = data.readString();
                    int requestedIndex = data.readInt();
                    String result = openDirectCamera(
                            requestedSurface, requestedTag, requestedIndex);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_OPEN_REVERSE_PREVIEW) {
                    Surface[] requestedSurfaces = readReverseSurfaces(data);
                    String result = openReversePreview(requestedSurfaces);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable error) {
                reply.writeException(new IllegalStateException(summary(error)));
                emit("helper_transaction_error", "code", code, "error", summary(error));
                return true;
            }
        }

        synchronized boolean discoverCamera() {
            cameraId = -1;
            cameraTag = "none";
            discoveryError = null;
            try {
                exemptHiddenApis();
                Class<?> info = Class.forName("android.hardware.BmmCameraInfo");
                int count = (Integer) info.getMethod("getCameraNumbers").invoke(null);
                Object tags = info.getMethod("getValidCameraTag").invoke(null);
                JSONObject candidateIds = new JSONObject();
                for (String candidate : DIRECT_CAMERA_TAGS) {
                    int id = (Integer) info.getMethod("getCameraId", String.class)
                            .invoke(null, candidate);
                    candidateIds.put(candidate, id);
                    if (id >= 0) {
                        if (cameraId < 0) {
                            cameraId = id;
                            cameraTag = candidate;
                        }
                    }
                }
                if (cameraId < 0) {
                    throw new IllegalStateException("No pano_h/pano_l/apa/byd_apa camera");
                }
                int width = optionalInt(info, "getDefaultPreviewWidth", cameraId);
                int height = optionalInt(info, "getDefaultPreviewHeight", cameraId);
                emit("camera_discovery", "ok", true, "camera_id", cameraId,
                        "camera_tag", cameraTag, "camera_count", count,
                        "valid_tags", String.valueOf(tags),
                        "candidate_ids", candidateIds.toString(),
                        "width", width, "height", height,
                        "cam_sort", systemProperty("vehicle.config.cam_sort"),
                        "cam_info_avm", systemProperty("vehicle.config.camInfo.avm"),
                        "vendor_cam_info_avm", systemProperty("vendor.vehicle.config.camInfo.avm"),
                        "autostudy_avm", systemProperty("persist.vendor.camera.autostudy.avm"));
                return true;
            } catch (Throwable error) {
                discoveryError = summary(error);
                emit("camera_discovery", "ok", false, "error", discoveryError);
                return false;
            }
        }

        private void registerCallback(IBinder newCallback) throws RemoteException {
            if (newCallback == null) throw new IllegalArgumentException("Callback is null");
            callback = newCallback;
            newCallback.linkToDeath(
                    () -> mainHandler.post(() -> disconnectCallback(newCallback)), 0);
            emit("helper_connected", "uid", Process.myUid());
            emitCounters();
            emitMusicJournalSnapshot();
            discoverCamera();
            turnController.reportStatus();
        }

        private synchronized void disconnectCallback(IBinder disconnected) {
            if (callback != disconnected) return;
            callback = null;
            try {
                closeCameraForOwner(CAMERA_OWNER_ACTIVITY, "callback_died");
            } catch (Throwable error) {
                emit("camera_error", "stage", "callback_died_close",
                        "error", summary(error));
            }
            emit("helper_client_disconnected", "guard_kept_running", true);
        }

        private String openCamera(Surface requestedSurface, int requestedIndex, String requestedView) {
            return openCamera(requestedSurface, requestedIndex, requestedView, true, false,
                    cameraId, cameraTag, CAMERA_OWNER_ACTIVITY);
        }

        private synchronized String openCamera(
                Surface requestedSurface,
                int requestedIndex,
                String requestedView,
                boolean closeExisting,
                boolean stockInput,
                int requestedCameraId,
                String requestedCameraTag,
                String requestedCameraOwner) {
            if (requestedIndex < 0 || requestedIndex > 4) {
                requestedSurface.release();
                throw new IllegalArgumentException("Preview index must be 0..4");
            }
            if (!requestedSurface.isValid()) {
                requestedSurface.release();
                throw new IllegalArgumentException("Surface is invalid");
            }
            if (closeExisting && !canReplaceCamera(requestedCameraOwner)) {
                requestedSurface.release();
                emit("camera_open_rejected", "reason", "reverse_owner_active",
                        "requested_owner", requestedCameraOwner,
                        "active_owner", activeCameraOwner);
                return result("camera_busy", "reverse camera owns AVM",
                        requestedCameraId, requestedCameraTag);
            }
            if (closeExisting) closeCamera("replace_preview");
            if (requestedCameraId < 0) {
                requestedSurface.release();
                String error = discoveryError == null ? "Camera was not discovered" : discoveryError;
                emit("camera_error", "stage", "discovery",
                        "camera_owner", requestedCameraOwner, "error", error);
                return result("camera_error", error, requestedCameraId, requestedCameraTag);
            }

            Object opened = null;
            try {
                Class<?> avm = Class.forName("android.hardware.AVMCamera");
                opened = avm.getMethod("open", int.class).invoke(null, requestedCameraId);
                if (opened == null) opened = openWithConstructor(avm, requestedCameraId);
                if (opened == null) throw new IllegalStateException("AVMCamera.open returned null");

                Class<?> callbackType = Class.forName("android.hardware.AVMCamera$IEventCallback");
                Object callbackProxy = Proxy.newProxyInstance(
                        callbackType.getClassLoader(), new Class<?>[]{callbackType}, eventHandler());
                avm.getMethod("setEventCallback", callbackType).invoke(opened, callbackProxy);

                boolean added = invokeBoolean(avm, opened, "addPreviewSurface",
                        new Class<?>[]{Surface.class, int.class}, requestedSurface, requestedIndex);
                boolean set = invokeBoolean(avm, opened, "setPreviewSurface",
                        new Class<?>[]{Surface.class, int.class}, requestedSurface, requestedIndex);
                boolean started = invokeBoolean(avm, opened, "startPreview", new Class<?>[0]);
                if (!set || !started) {
                    throw new IllegalStateException(
                            "Preview setup failed: add=" + added + ", set=" + set + ", start=" + started);
                }

                camera = opened;
                eventCallback = callbackProxy;
                surface = requestedSurface;
                previewIndex = requestedIndex;
                activeCameraId = requestedCameraId;
                activeCameraTag = requestedCameraTag;
                activeCameraOwner = requestedCameraOwner;
                if (!stockInput) {
                    viewName = requestedView == null ? "unknown" : requestedView;
                }
                emit("camera_opened", "camera_id", requestedCameraId,
                        "camera_tag", requestedCameraTag,
                        "camera_owner", requestedCameraOwner,
                        "view", viewName, "preview_index", previewIndex,
                        "input_for_stock_avm", stockInput,
                        "add_surface", added, "set_surface", set, "start_preview", started);
                return result("camera_opened", null, requestedCameraId, requestedCameraTag);
            } catch (Throwable error) {
                if (opened != null) {
                    try {
                        tryClose(opened, requestedSurface, requestedIndex);
                    } catch (Throwable closeError) {
                        Log.e(TAG, "Cleanup after failed open also failed", root(closeError));
                    }
                }
                requestedSurface.release();
                String message = summary(error);
                emit("camera_error", "stage", "open", "camera_id", requestedCameraId,
                        "camera_tag", requestedCameraTag, "view", requestedView,
                        "camera_owner", requestedCameraOwner,
                        "preview_index", requestedIndex, "error", message);
                return result("camera_error", message,
                        requestedCameraId, requestedCameraTag);
            }
        }

        String openDirectCamera(
                Surface requestedSurface, String requestedTag, int requestedIndex) {
            return openDirectCamera(
                    requestedSurface, requestedTag, requestedIndex, CAMERA_OWNER_ACTIVITY);
        }

        String openOverlayDirectCamera(
                Surface requestedSurface, String requestedTag, int requestedIndex) {
            return openDirectCamera(
                    requestedSurface, requestedTag, requestedIndex, CAMERA_OWNER_OVERLAY);
        }

        String openOverlayDirectCameras(
                Surface[] requestedSurfaces, int[] requestedIndexes, int[] cameraIds) {
            if (cameraIds == null || requestedIndexes == null
                    || cameraIds.length != requestedIndexes.length) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("overlay camera mappings required");
            }
            for (int i = 0; i < cameraIds.length; i++) {
                CameraProfile profile = CameraProfile.of(cameraIds[i]);
                if (requestedIndexes[i] != profile.previewIndex) {
                    releaseSurfaces(requestedSurfaces);
                    throw new IllegalArgumentException("overlay camera/index mismatch");
                }
            }
            return openMultiCamera(requestedSurfaces, requestedIndexes, cameraIds,
                    CAMERA_OWNER_OVERLAY, "side_camera_overlays", "overlay_open");
        }

        String openReverseCamera(Surface[] requestedSurfaces) {
            return openReverseCamera(requestedSurfaces, CAMERA_OWNER_REVERSE);
        }

        private String openReversePreview(Surface[] requestedSurfaces) {
            if (requestedSurfaces == null || requestedSurfaces.length != 4) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("Pano base plus three reverse Surfaces required");
            }
            Surface panoOutput = requestedSurfaces[0];
            Surface[] directSurfaces = Arrays.copyOfRange(requestedSurfaces, 1, 4);
            if (camera != null && !canReplaceCamera(CAMERA_OWNER_ACTIVITY)) {
                panoOutput.release();
                releaseSurfaces(directSurfaces);
                return result("camera_busy", "reverse camera owns AVM", -1, "pano_h");
            }
            closeCamera("replace_with_reverse_preview");
            pendingReversePreviewSurfaces = directSurfaces;
            stockCameraRequested = true;
            viewName = "reverse_preview_with_stock_base";
            turnController.openStockAvm(panoOutput, StockAvmPreview.horizontalViewpoint(3), true,
                    this::attachReversePreviewInputSurface);
            emit("camera_shell_request", "action", "open_reverse_preview_base",
                    "view", "VIEW_2D_REAR", "preview_indexes", "[0, 1, 2, 3]");
            return result("reverse_preview_shell_open_queued", null);
        }

        private synchronized void attachReversePreviewInputSurface(Surface inputSurface) {
            Surface[] direct = pendingReversePreviewSurfaces;
            pendingReversePreviewSurfaces = new Surface[0];
            if (!stockCameraRequested || direct.length != 3) {
                inputSurface.release();
                releaseSurfaces(direct);
                return;
            }
            Surface[] combined = {inputSurface, direct[0], direct[1], direct[2]};
            String openResult = openMultiCamera(combined, new int[]{0, 1, 2, 3}, null,
                    CAMERA_OWNER_ACTIVITY, "reverse_preview_with_stock_base",
                    "reverse_preview_open", false);
            emit("camera_input_surface_attached", "view", viewName,
                    "result", openResult, "preview_indexes", "[0, 1, 2, 3]");
            if (openResult.contains("camera_error") || openResult.contains("camera_busy")) {
                stockCameraRequested = false;
                turnController.closeStockAvm("reverse_preview_direct_open_failed");
            }
        }

        private synchronized String openReverseCamera(
                Surface[] requestedSurfaces, String requestedOwner) {
            return openMultiCamera(requestedSurfaces, new int[]{1, 2, 3}, null,
                    requestedOwner,
                    CAMERA_OWNER_REVERSE.equals(requestedOwner)
                            ? "reverse_overlay" : "reverse_preview",
                    "reverse_open");
        }

        private synchronized String openMultiCamera(
                Surface[] requestedSurfaces, int[] indexes, int[] profileIds,
                String requestedOwner, String requestedView, String errorStage) {
            return openMultiCamera(requestedSurfaces, indexes, profileIds,
                    requestedOwner, requestedView, errorStage, true);
        }

        private synchronized String openMultiCamera(
                Surface[] requestedSurfaces, int[] indexes, int[] profileIds,
                String requestedOwner, String requestedView, String errorStage,
                boolean closeExisting) {
            if (requestedSurfaces == null || requestedSurfaces.length != indexes.length) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("camera Surface/index count mismatch");
            }
            for (Surface requestedSurface : requestedSurfaces) {
                if (requestedSurface == null || !requestedSurface.isValid()) {
                    releaseSurfaces(requestedSurfaces);
                    throw new IllegalArgumentException("reverse camera Surface is invalid");
                }
            }
            if (!canReplaceCamera(requestedOwner)) {
                releaseSurfaces(requestedSurfaces);
                emit("camera_open_rejected", "reason", "reverse_owner_active",
                        "requested_owner", requestedOwner,
                        "active_owner", activeCameraOwner);
                return result("camera_busy", "reverse camera owns AVM", -1, "pano_h");
            }
            if (!closeExisting && camera != null) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalStateException("combined preview camera already open");
            }
            if (closeExisting) closeCamera("replace_with_multi_preview");

            int requestedCameraId;
            try {
                Class<?> info = Class.forName("android.hardware.BmmCameraInfo");
                requestedCameraId = (Integer) info.getMethod("getCameraId", String.class)
                        .invoke(null, "pano_h");
            } catch (Throwable error) {
                releaseSurfaces(requestedSurfaces);
                String message = summary(error);
                emit("camera_error", "stage", errorStage + "_discovery",
                        "camera_tag", "pano_h", "camera_owner", requestedOwner,
                        "error", message);
                return result("camera_error", message, -1, "pano_h");
            }
            if (requestedCameraId < 0) {
                releaseSurfaces(requestedSurfaces);
                String message = "Camera tag is unavailable: pano_h";
                emit("camera_error", "stage", errorStage + "_discovery",
                        "camera_tag", "pano_h", "camera_owner", requestedOwner,
                        "error", message);
                return result("camera_error", message, requestedCameraId, "pano_h");
            }

            Object opened = null;
            boolean[] attached = new boolean[indexes.length];
            try {
                Class<?> avm = Class.forName("android.hardware.AVMCamera");
                opened = avm.getMethod("open", int.class).invoke(null, requestedCameraId);
                if (opened == null) opened = openWithConstructor(avm, requestedCameraId);
                if (opened == null) throw new IllegalStateException("AVMCamera.open returned null");

                Class<?> callbackType = Class.forName("android.hardware.AVMCamera$IEventCallback");
                Object callbackProxy = Proxy.newProxyInstance(
                        callbackType.getClassLoader(), new Class<?>[]{callbackType}, eventHandler());
                avm.getMethod("setEventCallback", callbackType).invoke(opened, callbackProxy);

                for (int i = 0; i < indexes.length; i++) {
                    attached[i] = invokeBoolean(avm, opened, "addPreviewSurface",
                            new Class<?>[]{Surface.class, int.class},
                            requestedSurfaces[i], indexes[i]);
                    if (!attached[i]) {
                        throw new IllegalStateException(
                                "addPreviewSurface returned false for index " + indexes[i]);
                    }
                }
                boolean started = invokeBoolean(avm, opened,
                        "startPreview", new Class<?>[0]);
                if (!started) throw new IllegalStateException("startPreview returned false");

                camera = opened;
                eventCallback = callbackProxy;
                multiSurfaces = requestedSurfaces;
                multiPreviewIndexes = indexes.clone();
                activeCameraId = requestedCameraId;
                activeCameraTag = "pano_h";
                activeCameraOwner = requestedOwner;
                viewName = requestedView;
                emit("camera_opened", "camera_id", requestedCameraId,
                        "camera_tag", "pano_h", "camera_owner", requestedOwner,
                        "view", viewName,
                        "camera_profiles", profileIds == null
                                ? "[]" : Arrays.toString(profileIds),
                        "preview_indexes", Arrays.toString(indexes),
                        "start_preview", true);
                return result("camera_opened", null, requestedCameraId, "pano_h");
            } catch (Throwable error) {
                if (opened != null) {
                    try {
                        tryClose(opened, requestedSurfaces, indexes, attached);
                    } catch (Throwable closeError) {
                        Log.e(TAG, "Multi-Surface cleanup after failed open also failed",
                                root(closeError));
                    }
                }
                releaseSurfaces(requestedSurfaces);
                String message = summary(error);
                emit("camera_error", "stage", errorStage,
                        "camera_id", requestedCameraId, "camera_tag", "pano_h",
                        "camera_owner", requestedOwner,
                        "camera_profiles", profileIds == null
                                ? "[]" : Arrays.toString(profileIds),
                        "preview_indexes", Arrays.toString(indexes),
                        "error", message);
                return result("camera_error", message, requestedCameraId, "pano_h");
            }
        }

        void prepareOverlayWindow(
                CameraShellProtocol.OverlaySpec spec,
                Consumer<TurnSignalController.OverlaySurface> surfaceSink,
                Runnable preparedSink) {
            turnController.prepareCameraOverlay(spec, surfaceSink, preparedSink);
        }

        void armOverlayFirstFrame(int cameraId, int requestId, int surfaceGeneration) {
            turnController.armCameraOverlayFrame(cameraId, requestId, surfaceGeneration);
        }

        void setOverlayWindowVisible(
                int cameraId, int requestId, int surfaceGeneration, boolean visible) {
            turnController.setCameraOverlayVisible(
                    cameraId, requestId, surfaceGeneration, visible);
        }

        void setOverlayWindowVisible(
                int cameraId, int requestId, int surfaceGeneration, boolean visible,
                Runnable completion) {
            turnController.setCameraOverlayVisible(
                    cameraId, requestId, surfaceGeneration, visible, completion);
        }

        void setOverlayWindowWarning(
                int cameraId, int requestId, int surfaceGeneration, int edge, int mode) {
            turnController.setCameraOverlayWarning(
                    cameraId, requestId, surfaceGeneration, edge, mode);
        }

        void closeOverlayWindow(int cameraId, String reason) {
            turnController.closeCameraOverlay(cameraId, reason);
        }

        void closeOverlayWindows(String reason) {
            turnController.closeCameraOverlays(reason);
        }

        void prepareReverseOverlayWindow(
                CameraShellProtocol.ReverseOverlaySpec spec,
                Consumer<TurnSignalController.ReverseSurfaces> surfaceSink,
                Runnable preparedSink) {
            turnController.prepareReverseOverlay(spec, surfaceSink, preparedSink);
        }

        void armReverseOverlayFrames(int requestId, int[] generations) {
            turnController.armReverseOverlayFrames(requestId, generations);
        }

        void setReverseOverlayVisible(
                int requestId, int[] generations, boolean visible,
                Consumer<Boolean> completion) {
            turnController.setReverseOverlayVisible(
                    requestId, generations, visible, completion);
        }

        void closeReverseOverlayWindow(String reason, Consumer<Boolean> completion) {
            turnController.closeReverseOverlay(reason, completion);
        }

        private String openDirectCamera(
                Surface requestedSurface,
                String requestedTag,
                int requestedIndex,
                String requestedOwner) {
            if (!isAllowedDirectCameraTag(requestedTag)) {
                requestedSurface.release();
                throw new IllegalArgumentException("Camera tag is not allowed: " + requestedTag);
            }
            int requestedCameraId;
            try {
                Class<?> info = Class.forName("android.hardware.BmmCameraInfo");
                requestedCameraId = (Integer) info.getMethod("getCameraId", String.class)
                        .invoke(null, requestedTag);
            } catch (Throwable error) {
                requestedSurface.release();
                String message = summary(error);
                emit("camera_error", "stage", "direct_discovery",
                        "camera_tag", requestedTag, "preview_index", requestedIndex,
                        "camera_owner", requestedOwner,
                        "error", message);
                return result("camera_error", message, -1, requestedTag);
            }
            if (requestedCameraId < 0) {
                requestedSurface.release();
                String message = "Camera tag is unavailable: " + requestedTag;
                emit("camera_error", "stage", "direct_discovery",
                        "camera_tag", requestedTag, "preview_index", requestedIndex,
                        "camera_owner", requestedOwner,
                        "error", message);
                return result("camera_error", message, requestedCameraId, requestedTag);
            }
            return openCamera(requestedSurface, requestedIndex,
                    "direct_" + requestedTag + "_index_" + requestedIndex,
                    true, false, requestedCameraId, requestedTag, requestedOwner);
        }

        synchronized String openStockAvm(Surface requestedSurface, int viewpoint) {
            return openStockAvm(requestedSurface, viewpoint, true);
        }

        synchronized String openStockAvm(
                Surface requestedSurface, int viewpoint, boolean horizontal) {
            if (!StockAvmPreview.isAllowedViewpoint(viewpoint)) {
                requestedSurface.release();
                throw new IllegalArgumentException("Unsupported stock AVM viewpoint: " + viewpoint);
            }
            if (!requestedSurface.isValid()) {
                requestedSurface.release();
                throw new IllegalArgumentException("Surface is invalid");
            }
            if (camera != null && !stockCameraRequested) {
                if (!canReplaceCamera(CAMERA_OWNER_ACTIVITY)) {
                    requestedSurface.release();
                    return result("camera_busy", "reverse camera owns AVM",
                            activeCameraId, activeCameraTag);
                }
                closeCamera("replace_with_stock_avm");
            }
            String requestedView = StockAvmPreview.viewName(viewpoint);
            stockCameraRequested = true;
            viewName = requestedView;
            previewIndex = -1;
            turnController.openStockAvm(requestedSurface, viewpoint, horizontal,
                    this::attachStockAvmInputSurface);
            emit("camera_shell_request", "action", "open", "view", requestedView,
                    "viewpoint", viewpoint, "orientation",
                    horizontal ? "horizontal" : "vertical");
            return result("stock_avm_shell_open_queued", null);
        }

        private synchronized void attachStockAvmInputSurface(Surface inputSurface) {
            if (!stockCameraRequested) {
                inputSurface.release();
                return;
            }
            if (camera != null) {
                inputSurface.release();
                emit("camera_input_surface_reused", "view", viewName);
                return;
            }
            String attachResult = openCamera(
                    inputSurface, 0, "stock_avm_input", false, true,
                    cameraId, cameraTag, CAMERA_OWNER_ACTIVITY);
            emit("camera_input_surface_attached", "view", viewName,
                    "result", attachResult);
        }

        synchronized String closeCamera(String reason) {
            boolean closeStock = stockCameraRequested;
            String activeView = viewName == null ? "unknown" : viewName;
            stockCameraRequested = false;
            Surface[] pendingReverseSurfaces = pendingReversePreviewSurfaces;
            pendingReversePreviewSurfaces = new Surface[0];
            Object activeCamera = camera;
            Surface activeSurface = surface;
            int activeIndex = previewIndex;
            Surface[] activeMultiSurfaces = multiSurfaces;
            int[] activeMultiIndexes = multiPreviewIndexes;
            int closedCameraId = activeCameraId;
            String closedCameraTag = activeCameraTag;
            String closedCameraOwner = activeCameraOwner;
            camera = null;
            eventCallback = null;
            surface = null;
            previewIndex = 0;
            multiSurfaces = new Surface[0];
            multiPreviewIndexes = new int[0];
            viewName = null;
            activeCameraId = -1;
            activeCameraTag = "none";
            activeCameraOwner = "none";
            String error = null;
            releaseSurfaces(pendingReverseSurfaces);
            if (activeCamera != null) {
                try {
                    if (activeMultiSurfaces.length > 0) {
                        tryClose(activeCamera, activeMultiSurfaces,
                                activeMultiIndexes, null);
                    } else {
                        tryClose(activeCamera, activeSurface, activeIndex);
                    }
                } catch (Throwable failure) {
                    error = summary(failure);
                } finally {
                    if (activeSurface != null) activeSurface.release();
                    releaseSurfaces(activeMultiSurfaces);
                }
                emit("camera_closed", "reason", reason == null ? "unknown" : reason,
                        "view", activeView, "preview_index", activeIndex,
                        "preview_indexes", Arrays.toString(activeMultiIndexes),
                        "camera_id", closedCameraId, "camera_tag", closedCameraTag,
                        "camera_owner", closedCameraOwner,
                        "error", error == null ? "" : error);
            }
            if (closeStock) {
                turnController.closeStockAvm(reason);
                emit("camera_shell_request", "action", "close", "view", activeView,
                        "reason", reason == null ? "unknown" : reason);
                return result("stock_avm_shell_close_queued", error);
            }
            if (activeCamera == null) return result("already_closed", null);
            return result("camera_closed", error, closedCameraId, closedCameraTag);
        }

        synchronized String closeCameraForOwner(String expectedOwner, String reason) {
            if (camera == null && !stockCameraRequested) {
                return result("already_closed", null);
            }
            String owner = stockCameraRequested && camera == null
                    ? CAMERA_OWNER_ACTIVITY : activeCameraOwner;
            if (!expectedOwner.equals(owner)) {
                emit("camera_close_ignored", "reason", "owner_mismatch",
                        "expected_owner", expectedOwner, "active_owner", owner,
                        "request_reason", reason == null ? "unknown" : reason);
                return result("camera_close_ignored", null, activeCameraId, activeCameraTag);
            }
            return closeCamera(reason);
        }

        String closeOverlayCamera(String reason) {
            return closeCameraForOwner(CAMERA_OWNER_OVERLAY, reason);
        }

        String closeReverseCamera(String reason) {
            return closeCameraForOwner(CAMERA_OWNER_REVERSE, reason);
        }

        private void tryClose(Object activeCamera, Surface activeSurface, int activeIndex)
                throws Exception {
            Class<?> avm = activeCamera.getClass();
            Throwable first = null;
            try {
                if (activeSurface != null && activeIndex >= 0) {
                    avm.getMethod("rmPreviewSurface", Surface.class, int.class)
                            .invoke(activeCamera, activeSurface, activeIndex);
                }
            } catch (Throwable error) {
                first = root(error);
            }
            try {
                avm.getMethod("stopPreview").invoke(activeCamera);
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
            try {
                avm.getMethod("close").invoke(activeCamera);
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
            if (first != null) throw new Exception(first);
        }

        private void tryClose(
                Object activeCamera, Surface[] activeSurfaces, int[] activeIndexes,
                boolean[] attached) throws Exception {
            Class<?> avm = activeCamera.getClass();
            Throwable first = null;
            for (int i = 0; i < activeSurfaces.length; i++) {
                if (activeSurfaces[i] == null
                        || attached != null && (i >= attached.length || !attached[i])) {
                    continue;
                }
                try {
                    avm.getMethod("rmPreviewSurface", Surface.class, int.class)
                            .invoke(activeCamera, activeSurfaces[i], activeIndexes[i]);
                } catch (Throwable error) {
                    if (first == null) first = root(error);
                }
            }
            try {
                avm.getMethod("stopPreview").invoke(activeCamera);
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
            try {
                avm.getMethod("close").invoke(activeCamera);
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
            if (first != null) throw new Exception(first);
        }

        private boolean canReplaceCamera(String requestedOwner) {
            return canReplaceCamera(camera != null, activeCameraOwner, requestedOwner);
        }

        static boolean canReplaceCamera(
                boolean cameraOpen, String activeOwner, String requestedOwner) {
            return !cameraOpen
                    || !CAMERA_OWNER_REVERSE.equals(activeOwner)
                    || CAMERA_OWNER_REVERSE.equals(requestedOwner);
        }

        private static Surface[] readReverseSurfaces(Parcel data) {
            int count = data.readInt();
            if (count != 4) {
                throw new IllegalArgumentException("four reverse preview Surfaces required");
            }
            Surface[] values = new Surface[count];
            try {
                for (int i = 0; i < values.length; i++) {
                    values[i] = Surface.CREATOR.createFromParcel(data);
                }
                return values;
            } catch (Throwable error) {
                releaseSurfaces(values);
                throw error;
            }
        }

        private static void releaseSurfaces(Surface[] values) {
            if (values == null) return;
            for (Surface value : values) {
                if (value != null) value.release();
            }
        }

        private InvocationHandler eventHandler() {
            return (proxy, method, args) -> {
                if ("onEvent".equals(method.getName()) && args != null && args.length >= 4) {
                    emit("avm_event", "type", args[1], "arg1", args[2], "arg2", args[3]);
                    return null;
                }
                if ("toString".equals(method.getName())) return "CameraProbeEventCallback";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                return null;
            };
        }

        private void emit(String kind, Object... fields) {
            String line;
            try {
                JSONObject json = new JSONObject();
                json.put("kind", kind);
                json.put("source", "helper");
                json.put("wall_time", new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
                json.put("t_ms", SystemClock.elapsedRealtime());
                for (int i = 0; i + 1 < fields.length; i += 2) {
                    json.put(String.valueOf(fields[i]), fields[i + 1]);
                }
                line = json.toString();
            } catch (Throwable error) {
                line = "{\"kind\":\"helper_json_error\"}";
            }
            forwardLine(line);
        }

        private void acceptShellEvent(String line) {
            if (line == null) return;
            String key = null;
            try {
                JSONObject event = new JSONObject(line);
                String kind = event.optString("kind");
                key = lifetimeCounterKey(kind);
                if (isMusicJournalEvent(kind)) {
                    synchronized (musicJournal) {
                        appendBounded(musicJournal, line, 20);
                    }
                }
                boolean shellTerminal = "camera_shell_helper".equals(
                        event.optString("source"))
                        && ("camera_error".equals(kind) || "camera_closed".equals(kind));
                boolean pendingPreviewError = "camera_error".equals(kind)
                        && pendingReversePreviewSurfaces.length > 0;
                if (shellTerminal || pendingPreviewError) {
                    synchronized (this) {
                        stockCameraRequested = false;
                        releaseSurfaces(pendingReversePreviewSurfaces);
                        pendingReversePreviewSurfaces = new Surface[0];
                    }
                    if (pendingPreviewError) {
                        turnController.closeStockAvm("reverse_preview_base_failed");
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "Counter event parse failed", error);
            }
            if (key != null) incrementCounter(key);
            forwardLine(line);
            if (key != null) emitCounters();
        }

        private void emitMusicJournalSnapshot() {
            JSONArray events = new JSONArray();
            synchronized (musicJournal) {
                for (String line : musicJournal) events.put(line);
            }
            emit("music_journal_snapshot", "events", events);
        }

        static void appendBounded(ArrayDeque<String> journal, String line, int limit) {
            if (journal == null || line == null || limit <= 0) return;
            while (journal.size() >= limit) journal.removeFirst();
            journal.addLast(line);
        }

        static boolean isMusicJournalEvent(String kind) {
            return "music_runtime_config".equals(kind)
                    || "music_playback_state".equals(kind)
                    || "music_visualizer_start".equals(kind)
                    || "music_visualizer_stop_pending".equals(kind)
                    || "music_visualizer_stop".equals(kind)
                    || "music_runtime_error".equals(kind);
        }

        private synchronized void incrementCounter(String key) {
            counters.edit().putLong(key, counters.getLong(key, 0) + 1).commit();
        }

        private void emitCounters() {
            emit("lifetime_counters",
                    ACTIVATION_COUNT, counters.getLong(ACTIVATION_COUNT, 0),
                    CORRECTION_COUNT, counters.getLong(CORRECTION_COUNT, 0));
        }

        private void migrateLegacyCounters(Context context) {
            if (counters.getBoolean("initialized", false)) return;
            SharedPreferences legacy = context.getSharedPreferences(
                    "settings", Context.MODE_PRIVATE);
            counters.edit()
                    .putLong(ACTIVATION_COUNT, legacy.getLong(ACTIVATION_COUNT, 0))
                    .putLong(CORRECTION_COUNT, legacy.getLong(CORRECTION_COUNT, 0))
                    .putBoolean("initialized", true)
                    .commit();
        }

        private void forwardLine(String line) {
            Log.i(TAG, line);
            logSink.accept(line);
            IBinder current;
            synchronized (this) {
                current = callback;
            }
            if (current == null) return;
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
                data.writeString(line);
                current.transact(CB_EVENT, data, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable error) {
                Log.e(TAG, "Callback failed", error);
            } finally {
                data.recycle();
            }
        }

        private String result(String kind, String error) {
            return result(kind, error, cameraId, cameraTag);
        }

        private String result(String kind, String error, int resultCameraId, String resultCameraTag) {
            try {
                return new JSONObject()
                        .put("kind", kind)
                        .put("camera_id", resultCameraId)
                        .put("camera_tag", resultCameraTag)
                        .put("error", error == null ? "" : error)
                        .toString();
            } catch (Throwable ignored) {
                return kind;
            }
        }

        private String authorizationResult(
                LocalAdbClient.PromptMode mode,
                TurnSignalController.AuthorizationRequestAction action) {
            try {
                return new JSONObject()
                        .put("kind", "adb_authorization_request")
                        .put("mode", mode.name())
                        .put("result", action.wireName())
                        .put("replaced_auto", action.replacedAuto())
                        .toString();
            } catch (Throwable ignored) {
                return "{\"kind\":\"adb_authorization_request\",\"result\":\"error\"}";
            }
        }
    }

    static String lifetimeCounterKey(String kind) {
        if ("driver_activation".equals(kind)) return ACTIVATION_COUNT;
        if ("correction_confirmed".equals(kind)) return CORRECTION_COUNT;
        return null;
    }

    static LocalAdbClient.PromptMode adbAuthorizationMode(int value) {
        if (value == ADB_AUTH_MODE_AUTO_ONCE) return LocalAdbClient.PromptMode.AUTO_ONCE;
        if (value == ADB_AUTH_MODE_FORCE) return LocalAdbClient.PromptMode.FORCE;
        throw new IllegalArgumentException("Unsupported ADB authorization mode: " + value);
    }

    static boolean isAllowedDirectCameraTag(String tag) {
        if (tag == null) return false;
        for (String allowed : DIRECT_CAMERA_TAGS) {
            if (allowed.equals(tag)) return true;
        }
        return false;
    }

    static String[] directCameraTags() {
        return DIRECT_CAMERA_TAGS.clone();
    }

    private static Object openWithConstructor(Class<?> avm, int cameraId) throws Exception {
        Constructor<?> constructor = avm.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object camera = constructor.newInstance(cameraId);
        Method open = avm.getDeclaredMethod("open");
        open.setAccessible(true);
        return Boolean.TRUE.equals(open.invoke(camera)) ? camera : null;
    }

    private static boolean invokeBoolean(
            Class<?> type, Object target, String name, Class<?>[] parameters, Object... args)
            throws Exception {
        return Boolean.TRUE.equals(type.getMethod(name, parameters).invoke(target, args));
    }

    private static int optionalInt(Class<?> type, String name, int cameraId) {
        try {
            return (Integer) type.getMethod(name, int.class).invoke(null, cameraId);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String systemProperty(String name) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return String.valueOf(properties.getMethod("get", String.class).invoke(null, name));
        } catch (Throwable error) {
            return "<unavailable:" + summary(error) + ">";
        }
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = runtime.getDeclaredMethod("getRuntime");
            Method exemptions = runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            Object instance = getRuntime.invoke(null);
            exemptions.invoke(instance, (Object) new String[]{"Landroid/hardware/", "Landroid/os/SystemProperties;"});
        } catch (Throwable error) {
            Log.w(TAG, "Hidden API exemption unavailable", root(error));
        }
    }

    private static String summary(Throwable error) {
        Throwable root = root(error);
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while ((current instanceof InvocationTargetException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
