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
    static final String ACTIVITY_RESUME_COLD_RESET = "activity_resume_cold_reset";
    static final String COLD_RESET_DEFERRED_REVERSE = "camera_close_deferred_reverse";

    private static final String TAG = "BydCameraProbe";
    private static final String COUNTER_PREFS = "lifetime_counters";
    private static final String ACTIVATION_COUNT = "activation_count";
    private static final String CORRECTION_COUNT = "correction_count";
    private static final String[] DIRECT_CAMERA_TAGS = {
            "pano_h", "pano_l", "apa", "byd_apa"
    };
    private CameraHelperMain() {}

    static class HelperBinder extends Binder {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final TurnSignalController turnController;
        private final Consumer<String> logSink;
        private final SharedPreferences counters;
        private final ArrayDeque<String> musicJournal = new ArrayDeque<>();
        private final CallbackSlot<IBinder> callbacks = new CallbackSlot<>();
        private int cameraId = -1;
        private String cameraTag = "none";
        private String discoveryError;
        private Object camera;
        private Object eventCallback;
        private boolean persistentPanoProducer;
        private int producerCameraId = -1;
        private int producerEpoch;
        private DirectCameraSourceHub rawSourceHub;
        private boolean unconfirmedOneShotOwner;
        private Surface surface;
        private int previewIndex;
        private Surface[] multiSurfaces = new Surface[0];
        private int[] multiPreviewIndexes = new int[0];
        private final ActivityPreviewState<Surface> activityPreview =
                new ActivityPreviewState<>();
        private final PersistentSession persistentSession = new PersistentSession();
        private final ConsumerGroup activityGroup = persistentSession.activityGroup;
        private final ConsumerGroup overlayGroup = persistentSession.overlayGroup;
        private final ConsumerGroup reverseGroup = persistentSession.reverseGroup;
        private String viewName;
        private int activeCameraId = -1;
        private String activeCameraTag = "none";
        private String activeCameraOwner = "none";
        private int activeCameraRequestId;
        private int activeReverseControllerRequestId;
        private boolean stockCameraRequested;
        private int pendingStockCameraRequestId;
        private Surface[] pendingReversePreviewSurfaces = new Surface[0];
        private int pendingReversePreviewRequestId;

        HelperBinder(Context context, Consumer<String> logSink) {
            this.logSink = logSink;
            counters = context.getSharedPreferences(COUNTER_PREFS, Context.MODE_PRIVATE);
            migrateLegacyCounters(context);
            turnController = new TurnSignalController(
                    context, mainHandler, this::acceptShellEvent, this::acceptControllerEvent);
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

        synchronized void emitControllerEvent(String kind, Object... fields) {
            activeReverseControllerRequestId = updateReverseControllerRequestId(
                    activeReverseControllerRequestId, kind, requestId(fields));
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
                    IBinder newCallback = data.readStrongBinder();
                    long registrationGeneration = data.readLong();
                    boolean accepted = registerCallback(
                            newCallback, registrationGeneration);
                    reply.writeNoException();
                    reply.writeString(result(accepted
                            ? "callback_registered" : "callback_registration_stale", null));
                    return true;
                }
                if (code == TX_OPEN) {
                    Surface requestedSurface = Surface.CREATOR.createFromParcel(data);
                    int requestedIndex = data.readInt();
                    String requestedView = data.readString();
                    int requestId = data.readInt();
                    requireActivityRequestId(requestId);
                    String result = openCamera(
                            requestedSurface, requestedIndex, requestedView, requestId);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_CLOSE) {
                    String reason = data.readString();
                    int requestId = data.readInt();
                    String result;
                    if (requestId == 0 && CameraTransition.reasonEquals(
                            reason, ACTIVITY_RESUME_COLD_RESET)) {
                        result = shouldDeferActivityColdReset(activeReverseControllerRequestId)
                                ? result(COLD_RESET_DEFERRED_REVERSE, null)
                                : closeCamera(reason);
                    } else {
                        requireActivityRequestId(requestId);
                        result = closeCameraForOwner(
                                CAMERA_OWNER_ACTIVITY, reason, requestId);
                    }
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
                    IBinder expectedCallback = data.readStrongBinder();
                    long registrationGeneration = data.readLong();
                    if (expectedCallback == null || registrationGeneration <= 0) {
                        throw new IllegalArgumentException(
                                "Expected callback identity is invalid");
                    }
                    callbacks.detach(expectedCallback, registrationGeneration);
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
                    boolean stockDewarp = data.readInt() != 0;
                    int requestId = data.readInt();
                    requireActivityRequestId(requestId);
                    String result = openStockAvm(
                            requestedSurface, viewpoint, horizontal, stockDewarp, requestId);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_OPEN_DIRECT) {
                    Surface requestedSurface = Surface.CREATOR.createFromParcel(data);
                    String requestedTag = data.readString();
                    int requestedIndex = data.readInt();
                    int requestId = data.readInt();
                    boolean exclusive = data.readInt() != 0;
                    requireActivityRequestId(requestId);
                    String result = openDirectCamera(
                            requestedSurface, requestedTag, requestedIndex, requestId, exclusive);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_OPEN_REVERSE_PREVIEW) {
                    Surface[] requestedSurfaces = readReverseSurfaces(data);
                    int requestId = data.readInt();
                    requireActivityRequestId(requestId);
                    String result = openReversePreview(requestedSurfaces, requestId);
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

        private boolean registerCallback(
                IBinder newCallback, long registrationGeneration) throws RemoteException {
            if (newCallback == null) throw new IllegalArgumentException("Callback is null");
            if (!callbacks.register(newCallback, registrationGeneration)) return false;
            try {
                newCallback.linkToDeath(
                        () -> mainHandler.post(() -> disconnectCallback(
                                newCallback, registrationGeneration)), 0);
            } catch (RemoteException error) {
                callbacks.detach(newCallback, registrationGeneration);
                throw error;
            }
            emit("helper_connected", "uid", Process.myUid());
            emitCounters();
            emitMusicJournalSnapshot();
            emit("reverse_camera_state",
                    "active", activeReverseControllerRequestId > 0,
                    "request_id", activeReverseControllerRequestId);
            discoverCamera();
            turnController.reportStatus();
            return true;
        }

        private static int requestId(Object... fields) {
            for (int i = 0; i + 1 < fields.length; i += 2) {
                if ("request_id".equals(String.valueOf(fields[i]))
                        && fields[i + 1] instanceof Number) {
                    return ((Number) fields[i + 1]).intValue();
                }
            }
            return 0;
        }

        static boolean shouldDeferActivityColdReset(int reverseRequestId) {
            return reverseRequestId > 0;
        }

        private synchronized void disconnectCallback(
                IBinder disconnected, long registrationGeneration) {
            if (!callbacks.detach(disconnected, registrationGeneration)) return;
            try {
                closeCameraForOwner(CAMERA_OWNER_ACTIVITY, "callback_died");
            } catch (Throwable error) {
                emit("camera_error", "stage", "callback_died_close",
                        "error", summary(error));
            }
            emit("helper_client_disconnected", "guard_kept_running", true);
        }

        private String openCamera(
                Surface requestedSurface, int requestedIndex, String requestedView, int requestId) {
            return openCamera(requestedSurface, requestedIndex, requestedView, true, false,
                    cameraId, cameraTag, CAMERA_OWNER_ACTIVITY, requestId);
        }

        private synchronized String openCamera(
                Surface requestedSurface,
                int requestedIndex,
                String requestedView,
                boolean closeExisting,
                boolean stockInput,
                int requestedCameraId,
                String requestedCameraTag,
                String requestedCameraOwner,
                int requestId) {
            if (requestedIndex < 0 || requestedIndex > 4) {
                requestedSurface.release();
                throw new IllegalArgumentException("Preview index must be 0..4");
            }
            if (!requestedSurface.isValid()) {
                requestedSurface.release();
                throw new IllegalArgumentException("Surface is invalid");
            }
            if ("pano_h".equals(requestedCameraTag)) {
                return attachPersistentGroup(
                        activityGroup, new Surface[]{requestedSurface},
                        new int[]{requestedIndex}, requestId,
                        requestedView, persistentActivityExclusive(stockInput),
                        stockInput, null, "open");
            }
            if (closeExisting && !canReplaceCamera(requestedCameraOwner)) {
                requestedSurface.release();
                emit("camera_open_rejected", "reason", "reverse_owner_active",
                        "requested_owner", requestedCameraOwner,
                        "active_owner", activeCameraOwner,
                        "request_id", requestId);
                emit("camera_error", "stage", "owner_busy",
                        "camera_owner", requestedCameraOwner,
                        "request_id", requestId,
                        "error", "reverse camera owns AVM");
                return result("camera_busy", "reverse camera owns AVM",
                        requestedCameraId, requestedCameraTag);
            }
            if (closeExisting) closeCamera("replace_preview");
            if (requestedCameraId < 0) {
                requestedSurface.release();
                String error = discoveryError == null ? "Camera was not discovered" : discoveryError;
                emit("camera_error", "stage", "discovery",
                        "camera_owner", requestedCameraOwner,
                        "request_id", requestId, "error", error);
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
                activeCameraRequestId = requestId;
                if (!stockInput) {
                    viewName = requestedView == null ? "unknown" : requestedView;
                }
                emit("camera_opened", "camera_id", requestedCameraId,
                        "camera_tag", requestedCameraTag,
                        "camera_owner", requestedCameraOwner,
                        "request_id", requestId,
                        "renderer", stockInput ? "stock_avm_shell" : "direct_avm",
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
                        "request_id", requestId,
                        "preview_index", requestedIndex, "error", message);
                return result("camera_error", message,
                        requestedCameraId, requestedCameraTag);
            }
        }

        String openDirectCamera(
                Surface requestedSurface, String requestedTag, int requestedIndex,
                int requestId) {
            return openDirectCamera(
                    requestedSurface, requestedTag, requestedIndex, requestId, false);
        }

        String openDirectCamera(
                Surface requestedSurface, String requestedTag, int requestedIndex,
                int requestId, boolean exclusive) {
            return openDirectCamera(
                    requestedSurface, requestedTag, requestedIndex,
                    CAMERA_OWNER_ACTIVITY, requestId, exclusive);
        }

        String openOverlayDirectCamera(
                Surface requestedSurface, String requestedTag, int requestedIndex) {
            return openDirectCamera(
                    requestedSurface, requestedTag, requestedIndex,
                    CAMERA_OWNER_OVERLAY, 0, false);
        }

        String openOverlayDirectCameras(
                Surface[] requestedSurfaces, int[] requestedIndexes, int[] cameraIds,
                int requestId) {
            if (requestId <= 0) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("overlay request id required");
            }
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
            return attachPersistentGroup(
                    overlayGroup, requestedSurfaces, requestedIndexes, requestId,
                    "side_camera_overlays", false, false, cameraIds, "overlay_open");
        }

        String openReverseCamera(Surface[] requestedSurfaces, int requestId) {
            if (requestId <= 0) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("reverse request id required");
            }
            return openReverseCamera(requestedSurfaces, CAMERA_OWNER_REVERSE, requestId);
        }

        private String openReversePreview(Surface[] requestedSurfaces, int requestId) {
            if (requestedSurfaces == null || requestedSurfaces.length != 4) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("Pano base plus three reverse Surfaces required");
            }
            Surface panoOutput = requestedSurfaces[0];
            Surface[] directSurfaces = Arrays.copyOfRange(requestedSurfaces, 1, 4);
            if (reverseGroup.has()) {
                panoOutput.release();
                releaseSurfaces(directSurfaces);
                emit("camera_error", "stage", "reverse_preview_owner_busy",
                        "camera_tag", "pano_h", "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "request_id", requestId,
                        "producer_epoch", producerEpoch,
                        "error", "reverse camera owns AVM");
                return result("camera_busy", "reverse camera owns AVM", -1, "pano_h");
            }
            releaseSurfaces(pendingReversePreviewSurfaces);
            pendingReversePreviewSurfaces = directSurfaces;
            pendingReversePreviewRequestId = requestId;
            pendingStockCameraRequestId = requestId;
            stockCameraRequested = true;
            viewName = "reverse_preview_with_stock_base";
            turnController.openStockAvm(panoOutput, StockAvmPreview.horizontalViewpoint(3), true,
                    false, requestId,
                    inputSurface -> attachReversePreviewInputSurface(inputSurface, requestId));
            emit("camera_shell_request", "action", "open_reverse_preview_base",
                    "view", "VIEW_2D_REAR", "request_id", requestId,
                    "preview_indexes", "[0, 1, 2, 3]");
            return result("reverse_preview_shell_open_queued", null);
        }

        private synchronized void attachReversePreviewInputSurface(
                Surface inputSurface, int callbackRequestId) {
            if (!matchesPendingCameraRequest(
                    pendingReversePreviewRequestId, callbackRequestId)
                    || !matchesPendingCameraRequest(
                            pendingStockCameraRequestId, callbackRequestId)) {
                inputSurface.release();
                emit("camera_input_surface_ignored", "view", "reverse_preview_with_stock_base",
                        "request_id", callbackRequestId,
                        "pending_request_id", pendingReversePreviewRequestId);
                return;
            }
            Surface[] direct = pendingReversePreviewSurfaces;
            pendingReversePreviewSurfaces = new Surface[0];
            int requestId = callbackRequestId;
            pendingReversePreviewRequestId = 0;
            pendingStockCameraRequestId = 0;
            if (!stockCameraRequested || direct.length != 3) {
                inputSurface.release();
                releaseSurfaces(direct);
                return;
            }
            Surface[] combined = {inputSurface, direct[0], direct[1], direct[2]};
            String openResult;
            try {
                openResult = attachPersistentGroup(
                        activityGroup, combined, new int[]{0, 1, 2, 3}, requestId,
                        "reverse_preview_with_stock_base", false, true,
                        null, "reverse_preview_open", true);
            } catch (Throwable error) {
                // attachPersistentGroup owns and releases the combined Surfaces on
                // validation failure. Consume the callback here so its wrapper
                // cannot release the stock input a second time.
                stockCameraRequested = false;
                emit("camera_error", "stage", "reverse_preview_open",
                        "camera_tag", "pano_h", "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "request_id", requestId, "error", summary(error));
                turnController.closeStockAvm("reverse_preview_direct_open_failed", requestId);
                return;
            }
            emit("camera_input_surface_attached", "view", viewName,
                    "result", openResult, "preview_indexes", "[0, 1, 2, 3]");
            if (openResult.contains("camera_error") || openResult.contains("camera_busy")) {
                stockCameraRequested = false;
                turnController.closeStockAvm("reverse_preview_direct_open_failed", requestId);
            }
        }

        private synchronized String openReverseCamera(
                Surface[] requestedSurfaces, String requestedOwner, int requestId) {
            return attachPersistentGroup(
                    reverseGroup, requestedSurfaces, new int[]{1, 2, 3}, requestId,
                    CAMERA_OWNER_REVERSE.equals(requestedOwner)
                            ? "reverse_overlay" : "reverse_preview",
                    true, false, null, "reverse_open");
        }

        private synchronized String openMultiCamera(
                Surface[] requestedSurfaces, int[] indexes, int[] profileIds,
                String requestedOwner, String requestedView, String errorStage) {
            return openMultiCamera(requestedSurfaces, indexes, profileIds,
                    requestedOwner, requestedView, errorStage, true, 0);
        }

        private synchronized String openMultiCamera(
                Surface[] requestedSurfaces, int[] indexes, int[] profileIds,
                String requestedOwner, String requestedView, String errorStage,
                int requestId) {
            return openMultiCamera(requestedSurfaces, indexes, profileIds,
                    requestedOwner, requestedView, errorStage, true, requestId);
        }

        private synchronized String openMultiCamera(
                Surface[] requestedSurfaces, int[] indexes, int[] profileIds,
                String requestedOwner, String requestedView, String errorStage,
                boolean closeExisting, int requestId) {
            ConsumerGroup persistentGroup = groupForOwner(requestedOwner);
            if (persistentGroup != null) {
                return attachPersistentGroup(
                        persistentGroup, requestedSurfaces, indexes, requestId,
                        requestedView,
                        CAMERA_OWNER_REVERSE.equals(requestedOwner)
                                || CAMERA_OWNER_ACTIVITY.equals(requestedOwner),
                        false, profileIds, errorStage);
            }
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
                        "active_owner", activeCameraOwner,
                        "request_id", requestId);
                emit("camera_error", "stage", errorStage + "_owner_busy",
                        "camera_tag", "pano_h", "camera_owner", requestedOwner,
                        "request_id", requestId,
                        "error", "reverse camera owns AVM");
                return result("camera_busy", "reverse camera owns AVM", -1, "pano_h");
            }
            if (!closeExisting && camera != null) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalStateException("combined preview camera already open");
            }
            if (closeExisting) {
                closeCamera("replace_with_multi_preview",
                        shouldPreserveActivityPreview(
                                activityPreview.has(), requestedOwner));
            }

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
                        "request_id", requestId,
                        "error", message);
                return result("camera_error", message, -1, "pano_h");
            }
            if (requestedCameraId < 0) {
                releaseSurfaces(requestedSurfaces);
                String message = "Camera tag is unavailable: pano_h";
                emit("camera_error", "stage", errorStage + "_discovery",
                        "camera_tag", "pano_h", "camera_owner", requestedOwner,
                        "request_id", requestId,
                        "error", message);
                return result("camera_error", message, requestedCameraId, "pano_h");
            }

            Object opened = null;
            boolean[] attached = new boolean[indexes.length];
            boolean activityAttached = false;
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
                if (CAMERA_OWNER_OVERLAY.equals(requestedOwner)
                        && activityPreview.has()) {
                    if (!activityPreview.value().isValid()) {
                        releaseActivityPreview();
                    } else {
                        activityAttached = invokeBoolean(avm, opened, "addPreviewSurface",
                                new Class<?>[]{Surface.class, int.class},
                                activityPreview.value(), activityPreview.index());
                        if (!activityAttached) {
                            throw new IllegalStateException(
                                    "addPreviewSurface returned false for Activity index "
                                            + activityPreview.index());
                        }
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
                activeCameraRequestId = requestId;
                viewName = requestedView;
                activityPreview.setAttached(activityAttached);
                emit("camera_opened", "camera_id", requestedCameraId,
                        "camera_tag", "pano_h", "camera_owner", requestedOwner,
                        "request_id", requestId,
                        "view", viewName,
                        "camera_profiles", profileIds == null
                                ? "[]" : Arrays.toString(profileIds),
                        "preview_indexes", Arrays.toString(indexes),
                        "start_preview", true);
                if (activityAttached) {
                    emit("camera_preview_attached", "camera_owner", CAMERA_OWNER_ACTIVITY,
                            "producer_owner", requestedOwner,
                            "camera_tag", "pano_h",
                            "preview_index", activityPreview.index(),
                            "path", "atomic_multi_surface");
                }
                return result("camera_opened", null, requestedCameraId, "pano_h");
            } catch (Throwable error) {
                if (opened != null) {
                    try {
                        if (activityAttached && activityPreview.has()) {
                            invokeBoolean(opened.getClass(), opened, "rmPreviewSurface",
                                    new Class<?>[]{Surface.class, int.class},
                                    activityPreview.value(), activityPreview.index());
                        }
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
                        "request_id", requestId,
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

        void armOverlayFirstFrame(OverlayFrameArm arm) {
            turnController.armCameraOverlayFrame(arm);
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
                String requestedOwner,
                int requestId,
                boolean exclusive) {
            if (!isAllowedDirectCameraTag(requestedTag)) {
                requestedSurface.release();
                throw new IllegalArgumentException("Camera tag is not allowed: " + requestedTag);
            }
            if ("pano_h".equals(requestedTag)) {
                ConsumerGroup group = CAMERA_OWNER_OVERLAY.equals(requestedOwner)
                        ? overlayGroup : activityGroup;
                return attachPersistentGroup(
                        group, new Surface[]{requestedSurface},
                        new int[]{requestedIndex}, requestId,
                        "direct_" + requestedTag + "_index_" + requestedIndex,
                        exclusive, false, null, "direct_open");
            }
            if (persistentPanoProducer) {
                requestedSurface.release();
                emit("camera_open_rejected", "reason", "persistent_pano_active",
                        "requested_owner", requestedOwner,
                        "requested_tag", requestedTag,
                        "request_id", requestId,
                        "producer_epoch", producerEpoch);
                emit("camera_error", "stage", "direct_owner_busy",
                        "camera_tag", requestedTag,
                        "camera_owner", requestedOwner,
                        "request_id", requestId,
                        "producer_epoch", producerEpoch,
                        "error", "persistent pano_h producer active");
                return result("camera_busy", "persistent pano_h producer active",
                        activeCameraId, activeCameraTag);
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
                        "request_id", requestId,
                        "error", message);
                return result("camera_error", message, -1, requestedTag);
            }
            if (requestedCameraId < 0) {
                requestedSurface.release();
                String message = "Camera tag is unavailable: " + requestedTag;
                emit("camera_error", "stage", "direct_discovery",
                        "camera_tag", requestedTag, "preview_index", requestedIndex,
                        "camera_owner", requestedOwner,
                        "request_id", requestId,
                        "error", message);
                return result("camera_error", message, requestedCameraId, requestedTag);
            }
            return openCamera(requestedSurface, requestedIndex,
                    "direct_" + requestedTag + "_index_" + requestedIndex,
                    true, false, requestedCameraId, requestedTag, requestedOwner, requestId);
        }

        synchronized String openStockAvm(Surface requestedSurface, int viewpoint) {
            return openStockAvm(requestedSurface, viewpoint, true, false);
        }

        synchronized String openStockAvm(
                Surface requestedSurface, int viewpoint, boolean horizontal) {
            return openStockAvm(requestedSurface, viewpoint, horizontal, false);
        }

        synchronized String openStockAvm(
                Surface requestedSurface, int viewpoint, boolean horizontal,
                boolean stockDewarp) {
            return openStockAvm(
                    requestedSurface, viewpoint, horizontal, stockDewarp, 0);
        }

        synchronized String openStockAvm(
                Surface requestedSurface, int viewpoint, boolean horizontal,
                boolean stockDewarp, int requestId) {
            if (!StockAvmPreview.isAllowedViewpoint(viewpoint)) {
                requestedSurface.release();
                throw new IllegalArgumentException("Unsupported stock AVM viewpoint: " + viewpoint);
            }
            if (!requestedSurface.isValid()) {
                requestedSurface.release();
                throw new IllegalArgumentException("Surface is invalid");
            }
            if (reverseGroup.has()) {
                requestedSurface.release();
                emit("camera_error", "stage", "stock_avm_owner_busy",
                        "camera_tag", "pano_h",
                        "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "request_id", requestId,
                        "producer_epoch", producerEpoch,
                        "error", "reverse camera owns AVM");
                return result("camera_busy", "reverse camera owns AVM",
                        activeCameraId, activeCameraTag);
            }
            String requestedView = StockAvmPreview.viewName(viewpoint);
            stockCameraRequested = true;
            pendingStockCameraRequestId = requestId;
            viewName = requestedView;
            previewIndex = -1;
            turnController.openStockAvm(requestedSurface, viewpoint, horizontal, stockDewarp,
                    requestId, inputSurface -> attachStockAvmInputSurface(inputSurface, requestId));
            emit("camera_shell_request", "action", "open", "view", requestedView,
                    "viewpoint", viewpoint, "orientation",
                    horizontal ? "horizontal" : "vertical",
                    "dewarp", stockDewarp, "request_id", requestId);
            return result("stock_avm_shell_open_queued", null);
        }

        private synchronized void attachStockAvmInputSurface(
                Surface inputSurface, int callbackRequestId) {
            if (!stockCameraRequested
                    || !matchesPendingCameraRequest(
                            pendingStockCameraRequestId, callbackRequestId)) {
                inputSurface.release();
                emit("camera_input_surface_ignored", "view", viewName,
                        "request_id", callbackRequestId,
                        "pending_request_id", pendingStockCameraRequestId);
                return;
            }
            int requestId = callbackRequestId;
            pendingStockCameraRequestId = 0;
            String attachResult = openCamera(
                    inputSurface, 0, "stock_avm_input", false, true,
                    cameraId, cameraTag, CAMERA_OWNER_ACTIVITY, requestId);
            emit("camera_input_surface_attached", "view", viewName,
                    "result", attachResult);
        }

        synchronized String closeCamera(String reason) {
            if (CameraTransition.reasonEquals(reason, ACTIVITY_RESUME_COLD_RESET)
                    || "service_destroyed".equals(reason)) {
                return persistentPanoProducer
                        ? tearDownPersistentProducer(reason, null)
                        : closeOneShotCamera(reason, false);
            }
            if (persistentPanoProducer) {
                ConsumerGroup active = reverseGroup.has()
                        ? reverseGroup : activityGroup.has() ? activityGroup : overlayGroup;
                return active.has()
                        ? closePersistentGroup(active, reason, active.requestId)
                        : result("already_closed", null);
            }
            return closeOneShotCamera(reason, false);
        }

        private synchronized String closeCamera(String reason, boolean preserveActivityPreview) {
            return persistentPanoProducer
                    ? closeCamera(reason)
                    : closeOneShotCamera(reason, preserveActivityPreview);
        }

        private synchronized String closeOneShotCamera(
                String reason, boolean preserveActivityPreview) {
            boolean closeStock = stockCameraRequested;
            int stockRequestId = pendingStockCameraRequestId > 0
                    ? pendingStockCameraRequestId : activeCameraRequestId;
            String activeView = viewName == null ? "unknown" : viewName;
            stockCameraRequested = false;
            pendingStockCameraRequestId = 0;
            Surface[] pendingReverseSurfaces = pendingReversePreviewSurfaces;
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            Object activeCamera = camera;
            Surface activeSurface = surface;
            int activeIndex = previewIndex;
            Surface[] activeMultiSurfaces = multiSurfaces;
            int[] activeMultiIndexes = multiPreviewIndexes;
            ActivityPreviewState.Snapshot<Surface> activeActivityPreview =
                    activityPreview.close(preserveActivityPreview);
            Surface activeActivitySurface = activeActivityPreview.value;
            int activeActivityIndex = activeActivityPreview.index;
            boolean activeActivityAttached = activeActivityPreview.attached;
            int closedCameraId = activeCameraId;
            String closedCameraTag = activeCameraTag;
            String closedCameraOwner = activeCameraOwner;
            int closedRequestId = activeCameraRequestId;
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
            activeCameraRequestId = 0;
            String error = null;
            releaseSurfaces(pendingReverseSurfaces);
            if (activeCamera != null) {
                Throwable first = null;
                if (activeActivityAttached && activeActivitySurface != null) {
                    try {
                        boolean removed = invokeBoolean(
                                activeCamera.getClass(), activeCamera, "rmPreviewSurface",
                                new Class<?>[]{Surface.class, int.class},
                                activeActivitySurface, activeActivityIndex);
                        if (!removed) {
                            throw new IllegalStateException(
                                    "rmPreviewSurface returned false for Activity preview");
                        }
                    } catch (Throwable failure) {
                        first = root(failure);
                    }
                }
                try {
                    if (activeMultiSurfaces.length > 0) {
                        tryClose(activeCamera, activeMultiSurfaces,
                                activeMultiIndexes, null);
                    } else {
                        tryClose(activeCamera, activeSurface, activeIndex);
                    }
                } catch (Throwable failure) {
                    if (first == null) first = root(failure);
                } finally {
                    if (activeSurface != null) activeSurface.release();
                    releaseSurfaces(activeMultiSurfaces);
                    if (activeActivityPreview.release && activeActivitySurface != null) {
                        activeActivitySurface.release();
                    }
                }
                if (first != null) {
                    error = summary(first);
                    unconfirmedOneShotOwner = true;
                }
                emit("camera_closed", "reason", reason == null ? "unknown" : reason,
                        "view", activeView, "preview_index", activeIndex,
                        "preview_indexes", Arrays.toString(activeMultiIndexes),
                        "camera_id", closedCameraId, "camera_tag", closedCameraTag,
                        "camera_owner", closedCameraOwner,
                        "request_id", closedRequestId,
                        "producer_epoch", producerEpoch,
                        "error", error == null ? "" : error);
            }
            if (activeCamera == null
                    && activeActivityPreview.release && activeActivitySurface != null) {
                activeActivitySurface.release();
            }
            if (closeStock) {
                turnController.closeStockAvm(reason, stockRequestId);
                emit("camera_shell_request", "action", "close", "view", activeView,
                        "reason", reason == null ? "unknown" : reason,
                        "request_id", stockRequestId);
                return result("stock_avm_shell_close_queued", error);
            }
            if (activeCamera == null) return result("already_closed", null);
            return result("camera_closed", error, closedCameraId, closedCameraTag);
        }

        synchronized String closeCameraForOwner(String expectedOwner, String reason) {
            return closeCameraForOwner(expectedOwner, reason, currentRequestId(expectedOwner));
        }

        synchronized String closeCameraForOwner(
                String expectedOwner, String reason, int expectedRequestId) {
            if (persistentPanoProducer) {
                ConsumerGroup group = groupForOwner(expectedOwner);
                if (group == null) return result("camera_close_ignored", null);
                if (!group.has()) {
                    if (CAMERA_OWNER_ACTIVITY.equals(expectedOwner)
                            && stockCameraRequested
                            && expectedRequestId == pendingStockCameraRequestId) {
                        boolean shellCloseQueued = cancelPendingStock(reason, expectedRequestId);
                        return result(persistentCloseResultKind(shellCloseQueued), null,
                                activeCameraId, activeCameraTag);
                    }
                    return result("already_closed", null);
                }
                return closePersistentGroup(group, reason, expectedRequestId);
            }
            if (CAMERA_OWNER_ACTIVITY.equals(expectedOwner)
                    && activityPreview.has()) {
                return detachActivityPreview(reason);
            }
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
            int closeRequestId = cameraRequestIdForClose(
                    stockCameraRequested, pendingStockCameraRequestId,
                    activeCameraRequestId);
            if (expectedRequestId > 0 && closeRequestId != expectedRequestId) {
                emit("camera_close_ignored", "reason", "request_mismatch",
                        "expected_owner", expectedOwner,
                        "expected_request_id", expectedRequestId,
                        "active_request_id", closeRequestId,
                        "request_reason", reason == null ? "unknown" : reason);
                return result("camera_close_ignored", null, activeCameraId, activeCameraTag);
            }
            return closeOneShotCamera(reason, false);
        }

        String closeOverlayCamera(String reason, int expectedRequestId) {
            return closeCameraForOwner(CAMERA_OWNER_OVERLAY, reason, expectedRequestId);
        }

        boolean closeReverseCamera(String reason, int expectedRequestId) {
            return isSuccessfulCameraCloseResult(closeCameraForOwner(
                    CAMERA_OWNER_REVERSE, reason, expectedRequestId));
        }

        private synchronized String attachPersistentGroup(
                ConsumerGroup target,
                Surface[] requestedSurfaces,
                int[] requestedIndexes,
                int requestId,
                String requestedView,
                boolean exclusive,
                boolean shellOwned,
                int[] profileIds,
                String errorStage) {
            return attachPersistentGroup(
                    target, requestedSurfaces, requestedIndexes, requestId,
                    requestedView, exclusive, shellOwned, profileIds,
                    errorStage, false);
        }

        private synchronized String attachPersistentGroup(
                ConsumerGroup target,
                Surface[] requestedSurfaces,
                int[] requestedIndexes,
                int requestId,
                String requestedView,
                boolean exclusive,
                boolean shellOwned,
                int[] profileIds,
                String errorStage,
                boolean firstSurfaceDirect) {
            String validation = validateBatch(requestedSurfaces, requestedIndexes);
            if (validation != null) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException(validation);
            }
            if ((target == activityGroup || target == reverseGroup) && requestId <= 0) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("camera request id required");
            }
            if (target == overlayGroup && (reverseGroup.has()
                    || activityGroup.has() && activityGroup.exclusive)) {
                releaseSurfaces(requestedSurfaces);
                return persistentBusy(target.owner, requestId, errorStage);
            }
            if (target == activityGroup && reverseGroup.has()) {
                releaseSurfaces(requestedSurfaces);
                return persistentBusy(target.owner, requestId, errorStage);
            }
            if (rejectUnconfirmedOwner(
                    unconfirmedOneShotOwner,
                    () -> releaseSurfaces(requestedSurfaces), this::emit,
                    target.owner, requestId, requestedIndexes)) {
                return result("camera_error", "prior camera owner close was not confirmed",
                        -1, "pano_h");
            }
            if (camera != null && !persistentPanoProducer) {
                String closeResult = closeOneShotCamera(
                        "replace_with_persistent_pano", false);
                if (!canOpenPersistentAfterOneShotClose(closeResult)) {
                    unconfirmedOneShotOwner = true;
                    rejectUnconfirmedOwner(true,
                            () -> releaseSurfaces(requestedSurfaces), this::emit,
                            target.owner, requestId, requestedIndexes);
                    return result("camera_error",
                            "prior camera owner close was not confirmed", -1, "pano_h");
                }
            }

            int requestedCameraId = panoCameraId();
            if (requestedCameraId < 0) {
                releaseSurfaces(requestedSurfaces);
                String error = "Camera tag is unavailable: pano_h";
                emit("camera_error", "stage", errorStage + "_discovery",
                        "camera_tag", "pano_h", "camera_owner", target.owner,
                        "request_id", requestId, "error", error);
                return result("camera_error", error, requestedCameraId, "pano_h");
            }

            if (shouldStartPersistentProducer(camera != null)) {
                return openPersistentProducer(
                        target, requestedSurfaces, requestedIndexes, requestId,
                        requestedView, exclusive, shellOwned, profileIds,
                        errorStage, requestedCameraId, firstSurfaceDirect);
            }

            try {
                persistentSession.attach(
                        new ReflectivePersistentCameraPort(camera), target,
                        requestedSurfaces, requestedIndexes, requestId,
                        requestedView, exclusive, shellOwned,
                        this::emit, this::cancelPendingStock,
                        requestedCameraId, producerEpoch, firstSurfaceDirect);
            } catch (PersistentSessionFailure error) {
                if (!error.requestedOwnedBySession) releaseSurfaces(requestedSurfaces);
                if (error.fatal) {
                    String message = error.getCause() == null
                            ? error.reason : summary(error.getCause());
                    if (shouldPreEmitFatalRequest(error.requestedOwnedBySession)) {
                        emit("camera_error", "stage", error.reason,
                                "camera_id", requestedCameraId,
                                "camera_tag", "pano_h", "camera_owner", target.owner,
                                "request_id", requestId,
                                "preview_indexes", Arrays.toString(requestedIndexes),
                                "producer_epoch", producerEpoch, "error", message);
                    }
                    return tearDownPersistentProducer(
                            error.reason, error.getCause(), error.shellCloseQueued);
                }
                String message = error.getCause() == null
                        ? error.reason : summary(error.getCause());
                emit("camera_error", "stage", errorStage,
                        "camera_id", requestedCameraId,
                        "camera_tag", "pano_h",
                        "camera_owner", target.owner,
                        "request_id", requestId,
                        "preview_indexes", Arrays.toString(requestedIndexes),
                        "producer_epoch", producerEpoch,
                        "error", message);
                return result("camera_error", message, requestedCameraId, "pano_h");
            }
            refreshPersistentLegacyState();
            emitConsumerOpened(target, profileIds, false);
            return result("camera_opened", null, activeCameraId, activeCameraTag);
        }

        private String openPersistentProducer(
                ConsumerGroup target,
                Surface[] requestedSurfaces,
                int[] requestedIndexes,
                int requestId,
                String requestedView,
                boolean exclusive,
                boolean shellOwned,
                int[] profileIds,
                String errorStage,
                int requestedCameraId,
                boolean firstSurfaceDirect) {
            Object opened = null;
            DirectCameraSourceHub openedHub = null;
            try {
                Class<?> avm = Class.forName("android.hardware.AVMCamera");
                opened = avm.getMethod("open", int.class).invoke(null, requestedCameraId);
                if (opened == null) opened = openWithConstructor(avm, requestedCameraId);
                if (opened == null) throw new IllegalStateException("AVMCamera.open returned null");
                Class<?> callbackType = Class.forName("android.hardware.AVMCamera$IEventCallback");
                Object callbackProxy = Proxy.newProxyInstance(
                        callbackType.getClassLoader(), new Class<?>[]{callbackType}, eventHandler());
                avm.getMethod("setEventCallback", callbackType).invoke(opened, callbackProxy);
                PersistentCameraPort port = new ReflectivePersistentCameraPort(opened);
                openedHub = DirectCameraSourceHub.create(new DirectCameraSourceHub.Listener() {
                    @Override
                    public void onConsumerFailure(
                            Surface failedSurface, int index, Throwable error) {
                        mainHandler.post(() -> acceptRawConsumerFailure(
                                failedSurface, index, error));
                    }

                    @Override
                    public void onSourceFailure(int index, Throwable error) {
                        mainHandler.post(() -> acceptRawSourceFailure(index, error));
                    }
                });
                rawSourceHub = openedHub;
                persistentSession.startProducer(
                        port, openedHub, target, requestedSurfaces, requestedIndexes,
                        requestId, requestedView, exclusive, shellOwned,
                        firstSurfaceDirect);

                camera = opened;
                eventCallback = callbackProxy;
                persistentPanoProducer = true;
                producerCameraId = requestedCameraId;
                producerEpoch++;
                refreshPersistentLegacyState();
                emit("camera_producer_opened", "camera_id", requestedCameraId,
                        "camera_tag", "pano_h", "producer_epoch", producerEpoch,
                        "start_preview", true);
                emitConsumerOpened(target, profileIds, true);
                return result("camera_opened", null, requestedCameraId, "pano_h");
            } catch (Throwable error) {
                if (rawSourceHub == openedHub) rawSourceHub = null;
                if (openedHub != null) {
                    try {
                        openedHub.close();
                    } catch (Throwable closeError) {
                        error.addSuppressed(closeError);
                    }
                }
                if (opened != null) {
                    try {
                        closeProducerObject(opened);
                    } catch (Throwable closeError) {
                        Log.e(TAG, "Persistent producer cleanup failed", root(closeError));
                    }
                }
                releaseSurfaces(requestedSurfaces);
                String message = summary(error);
                emit("camera_error", "stage", errorStage,
                        "camera_id", requestedCameraId,
                        "camera_tag", "pano_h", "camera_owner", target.owner,
                        "request_id", requestId,
                        "preview_indexes", Arrays.toString(requestedIndexes),
                        "error", message);
                return result("camera_error", message, requestedCameraId, "pano_h");
            }
        }

        private synchronized void acceptRawConsumerFailure(
                Surface failedSurface, int index, Throwable error) {
            if (rawSourceHub == null || !persistentPanoProducer) return;
            boolean handled;
            try {
                handled = persistentSession.failConsumer(
                        new ReflectivePersistentCameraPort(camera), failedSurface, index,
                        error, this::emit, this::cancelPendingStock,
                        producerCameraId, producerEpoch);
            } catch (PersistentSessionFailure failure) {
                tearDownPersistentProducer(
                        failure.reason, failure.getCause(), failure.shellCloseQueued);
                return;
            }
            if (!handled) {
                emit("camera_error", "stage", "raw_fanout_consumer_stale",
                        "camera_tag", "pano_h", "preview_index", index,
                        "producer_epoch", producerEpoch, "error", summary(error));
            }
            refreshPersistentLegacyState();
        }

        private synchronized void acceptRawSourceFailure(int index, Throwable error) {
            if (rawSourceHub == null || !persistentPanoProducer) return;
            tearDownPersistentProducer("raw_source_failed", error);
        }

        private synchronized String closePersistentGroup(
                ConsumerGroup group, String reason, int expectedRequestId) {
            CloseOutcome outcome;
            try {
                outcome = persistentSession.close(
                        new ReflectivePersistentCameraPort(camera), group,
                        reason, expectedRequestId,
                        this::emit, this::cancelPendingStock,
                        producerCameraId, producerEpoch);
            } catch (PersistentSessionFailure error) {
                if (error.fatal) {
                    return tearDownPersistentProducer(
                            error.reason, error.getCause(), error.shellCloseQueued);
                }
                String message = error.getCause() == null
                        ? error.reason : summary(error.getCause());
                emit("camera_error", "stage", error.reason,
                        "camera_id", producerCameraId,
                        "camera_tag", "pano_h", "camera_owner", group.owner,
                        "request_id", expectedRequestId,
                        "producer_epoch", producerEpoch, "error", message);
                return result("camera_error", message, producerCameraId, "pano_h");
            }
            if (outcome.decision == PersistentCloseDecision.ALREADY_CLOSED) {
                return result("already_closed", null);
            }
            if (outcome.decision == PersistentCloseDecision.STALE) {
                emit("camera_close_ignored", "reason", "request_mismatch",
                        "expected_owner", group.owner,
                        "expected_request_id", expectedRequestId,
                        "active_request_id", group.requestId,
                        "request_reason", reason == null ? "unknown" : reason,
                        "producer_epoch", producerEpoch);
                return result("camera_close_ignored", null, activeCameraId, activeCameraTag);
            }
            refreshPersistentLegacyState();
            return result(persistentCloseResultKind(outcome.shellCloseQueued), null,
                    activeCameraId, "pano_h");
        }

        private synchronized String tearDownPersistentProducer(
                String reason, Throwable failure) {
            return tearDownPersistentProducer(reason, failure, false);
        }

        private synchronized String tearDownPersistentProducer(
                String reason, Throwable failure, boolean shellCloseAlreadyQueued) {
            Object active = camera;
            int closedEpoch = producerEpoch;
            boolean closeStock = stockCameraRequested || activityGroup.shellOwned;
            int stockRequestId = pendingStockCameraRequestId > 0
                    ? pendingStockCameraRequestId : activityGroup.requestId;
            camera = null;
            eventCallback = null;
            persistentPanoProducer = false;
            producerCameraId = -1;
            rawSourceHub = null;
            stockCameraRequested = false;
            pendingStockCameraRequestId = 0;
            releaseSurfaces(pendingReversePreviewSurfaces);
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            releaseActivityPreview();
            TeardownOutcome outcome = persistentSession.tearDown(
                    active == null ? null : new ReflectivePersistentCameraPort(active),
                    reason, failure, closeStock, stockRequestId,
                    shellCloseAlreadyQueued, this::queueStockClose,
                    this::emit, closedEpoch);
            clearLegacyCameraState();
            String error = outcome.failure == null ? null : summary(outcome.failure);
            return result(persistentCloseResultKind(outcome.shellCloseQueued),
                    error, -1, "pano_h");
        }

        private static void closeProducerObject(Object active) throws Exception {
            PersistentCameraPort port = new ReflectivePersistentCameraPort(active);
            Throwable closeError = stopAndClosePersistentProducer(port);
            if (closeError != null) throw new Exception(closeError);
        }

        static Throwable stopAndClosePersistentProducer(PersistentCameraPort port) {
            Throwable first = null;
            try {
                port.stop();
            } catch (Throwable error) {
                first = root(error);
            }
            try {
                port.close();
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
            return first;
        }

        private void emitConsumerOpened(
                ConsumerGroup group, int[] profileIds, boolean openedProducer) {
            emit("camera_consumer_attached", "camera_owner", group.owner,
                    "request_id", group.requestId,
                    "view", group.view,
                    "preview_indexes", Arrays.toString(group.indexes),
                    "exclusive", group.exclusive,
                    "producer_epoch", producerEpoch,
                    "producer_opened", openedProducer);
            emit("camera_opened", "camera_id", activeCameraId,
                    "camera_tag", "pano_h", "camera_owner", group.owner,
                    "request_id", group.requestId,
                    "view", group.view,
                    "camera_profiles", profileIds == null
                            ? "[]" : Arrays.toString(profileIds),
                    "preview_indexes", Arrays.toString(group.indexes),
                    "producer_epoch", producerEpoch,
                    "start_preview", openedProducer);
        }

        static void emitConsumerClosed(
                PersistentEventSink sink, String owner, int requestId,
                String view, int[] indexes, String reason,
                int cameraId, int epoch) {
            String closeReason = reason == null ? "unknown" : reason;
            sink.emit("camera_consumer_detached", "camera_owner", owner,
                    "request_id", requestId,
                    "view", view == null ? "unknown" : view,
                    "preview_indexes", Arrays.toString(indexes),
                    "reason", closeReason,
                    "producer_epoch", epoch);
            sink.emit("camera_closed", "reason", closeReason,
                    "view", view == null ? "unknown" : view,
                    "preview_indexes", Arrays.toString(indexes),
                    "camera_id", cameraId, "camera_tag", "pano_h",
                    "camera_owner", owner,
                    "request_id", requestId,
                    "producer_epoch", epoch,
                    "error", "");
        }

        private void refreshPersistentLegacyState() {
            activeCameraId = producerCameraId;
            activeCameraTag = "pano_h";
            ConsumerGroup active = reverseGroup.has()
                    ? reverseGroup : activityGroup.has() ? activityGroup : overlayGroup;
            activeCameraOwner = active.has() ? active.owner : "none";
            activeCameraRequestId = active.has() ? active.requestId : 0;
            viewName = active.has() ? active.view : null;
            previewIndex = active.has() && active.indexes.length == 1
                    ? active.indexes[0] : -1;
        }

        private void clearLegacyCameraState() {
            surface = null;
            multiSurfaces = new Surface[0];
            multiPreviewIndexes = new int[0];
            viewName = null;
            previewIndex = 0;
            activeCameraId = -1;
            activeCameraTag = "none";
            activeCameraOwner = "none";
            activeCameraRequestId = 0;
        }

        private boolean cancelPendingStock(String reason, int requestId) {
            boolean closeStock = stockCameraRequested;
            stockCameraRequested = false;
            pendingStockCameraRequestId = 0;
            releaseSurfaces(pendingReversePreviewSurfaces);
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            if (closeStock) queueStockClose(reason, requestId);
            return closeStock;
        }

        private boolean queueStockClose(String reason, int requestId) {
            turnController.closeStockAvm(reason, requestId);
            emit("camera_shell_request", "action", "close",
                    "reason", reason == null ? "unknown" : reason,
                    "request_id", requestId);
            return true;
        }

        private int currentRequestId(String owner) {
            ConsumerGroup group = groupForOwner(owner);
            if (group != null && group.has()) return group.requestId;
            return CAMERA_OWNER_ACTIVITY.equals(owner) ? pendingStockCameraRequestId : 0;
        }

        private ConsumerGroup groupForOwner(String owner) {
            if (CAMERA_OWNER_ACTIVITY.equals(owner)) return activityGroup;
            if (CAMERA_OWNER_OVERLAY.equals(owner)) return overlayGroup;
            if (CAMERA_OWNER_REVERSE.equals(owner)) return reverseGroup;
            return null;
        }

        private int panoCameraId() {
            try {
                Class<?> info = Class.forName("android.hardware.BmmCameraInfo");
                return (Integer) info.getMethod("getCameraId", String.class)
                        .invoke(null, "pano_h");
            } catch (Throwable error) {
                discoveryError = summary(error);
                return -1;
            }
        }

        private String persistentBusy(
                String requestedOwner, int requestId, String errorStage) {
            emit("camera_open_rejected", "reason", "exclusive_consumer_active",
                    "requested_owner", requestedOwner,
                    "active_owner", activeCameraOwner,
                    "request_id", requestId,
                    "producer_epoch", producerEpoch);
            emitPersistentBusy(this::emit, requestedOwner, requestId,
                    errorStage + "_owner_busy", producerEpoch);
            return result("camera_busy", "exclusive camera consumer active",
                    activeCameraId, activeCameraTag);
        }

        static void emitPersistentBusy(
                PersistentEventSink sink, String requestedOwner,
                int requestId, String stage, int epoch) {
            sink.emit("camera_error", "stage", stage,
                    "camera_tag", "pano_h",
                    "camera_owner", requestedOwner,
                    "request_id", requestId,
                    "producer_epoch", epoch,
                    "error", "exclusive camera consumer active");
        }

        static String persistentCloseResultKind(boolean shellCloseQueued) {
            return shellCloseQueued ? "stock_avm_shell_close_queued" : "camera_closed";
        }

        private static String validateBatch(Surface[] surfaces, int[] indexes) {
            if (surfaces == null || indexes == null || surfaces.length == 0
                    || surfaces.length != indexes.length) {
                return "camera Surface/index count mismatch";
            }
            for (int i = 0; i < surfaces.length; i++) {
                if (surfaces[i] == null || !surfaces[i].isValid()) {
                    return "camera Surface is invalid";
                }
                if (indexes[i] < 0 || indexes[i] > 4) {
                    return "Preview index must be 0..4";
                }
            }
            return null;
        }

        private static void releaseAndClear(ConsumerGroup group) {
            ConsumerGroup.Snapshot snapshot = group.snapshot();
            group.clear();
            snapshot.release();
        }

        static boolean isSuccessfulCameraCloseResult(String result) {
            if (result == null) return false;
            try {
                JSONObject value = new JSONObject(result);
                if (!value.has("kind") || !value.has("error")) return false;
                return isSuccessfulCameraClose(
                        value.optString("kind"), value.optString("error"));
            } catch (Throwable ignored) {
                return false;
            }
        }

        static boolean canOpenPersistentAfterOneShotClose(String closeResult) {
            return isSuccessfulCameraCloseResult(closeResult);
        }

        static boolean persistentActivityExclusive(boolean stockInput) {
            return !stockInput;
        }

        static boolean shouldPreEmitFatalRequest(boolean requestedOwnedBySession) {
            return !requestedOwnedBySession;
        }

        static boolean rejectUnconfirmedOwner(
                boolean unconfirmed, Runnable release,
                PersistentEventSink events, String owner,
                int requestId, int[] indexes) {
            if (!unconfirmed) return false;
            release.run();
            events.emit("camera_error", "stage", "one_shot_owner_unconfirmed",
                    "camera_tag", "pano_h", "camera_owner", owner,
                    "request_id", requestId,
                    "preview_indexes", Arrays.toString(indexes),
                    "error", "prior camera owner close was not confirmed");
            return true;
        }

        static boolean isSuccessfulCameraClose(String kind, String error) {
            return ("camera_closed".equals(kind) || "already_closed".equals(kind))
                    && error != null && error.isEmpty();
        }

        private synchronized String attachActivityPreview(
                Surface requestedSurface, int requestedIndex, int requestId) {
            if (requestedIndex < 0 || requestedIndex > 4) {
                requestedSurface.release();
                throw new IllegalArgumentException("Preview index must be 0..4");
            }
            if (!requestedSurface.isValid()) {
                requestedSurface.release();
                throw new IllegalArgumentException("Surface is invalid");
            }
            if (activityPreview.has()) {
                detachActivityPreview("replace_activity_preview");
            }
            try {
                boolean added = invokeBoolean(camera.getClass(), camera, "addPreviewSurface",
                        new Class<?>[]{Surface.class, int.class},
                        requestedSurface, requestedIndex);
                if (!added) {
                    throw new IllegalStateException(
                            "addPreviewSurface returned false for Activity preview");
                }
                activityPreview.set(requestedSurface, requestedIndex, true);
                emit("camera_preview_attached", "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "producer_owner", activeCameraOwner,
                        "request_id", requestId,
                        "camera_tag", activeCameraTag,
                        "preview_index", requestedIndex,
                        "path", "hot_multi_surface");
                return result("camera_preview_attached", null,
                        activeCameraId, activeCameraTag);
            } catch (Throwable error) {
                requestedSurface.release();
                String message = summary(error);
                emit("camera_error", "stage", "attach_activity_preview",
                        "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "producer_owner", activeCameraOwner,
                        "request_id", requestId,
                        "camera_tag", activeCameraTag,
                        "preview_index", requestedIndex,
                        "error", message);
                return result("camera_error", message, activeCameraId, activeCameraTag);
            }
        }

        private synchronized String detachActivityPreview(String reason) {
            ActivityPreviewState.Snapshot<Surface> active = activityPreview.close(false);
            Surface activeSurface = active.value;
            int activeIndex = active.index;
            boolean attached = active.attached;
            String error = null;
            try {
                if (attached && camera != null) {
                    boolean removed = invokeBoolean(
                            camera.getClass(), camera, "rmPreviewSurface",
                            new Class<?>[]{Surface.class, int.class},
                            activeSurface, activeIndex);
                    if (!removed) {
                        throw new IllegalStateException(
                                "rmPreviewSurface returned false for Activity preview");
                    }
                }
            } catch (Throwable failure) {
                error = summary(failure);
            } finally {
                if (active.release && activeSurface != null) activeSurface.release();
            }
            emit("camera_preview_detached", "camera_owner", CAMERA_OWNER_ACTIVITY,
                    "producer_owner", activeCameraOwner,
                    "camera_tag", activeCameraTag,
                    "preview_index", activeIndex,
                    "reason", reason == null ? "unknown" : reason,
                    "error", error == null ? "" : error);
            return result("camera_preview_detached", error,
                    activeCameraId, activeCameraTag);
        }

        private void releaseActivityPreview() {
            ActivityPreviewState.Snapshot<Surface> active = activityPreview.close(false);
            if (active.release && active.value != null) active.value.release();
        }

        private void tryClose(Object activeCamera, Surface activeSurface, int activeIndex)
                throws Exception {
            Throwable first = closeOneShotPort(
                    new ReflectivePersistentCameraPort(activeCamera),
                    new Surface[]{activeSurface}, new int[]{activeIndex},
                    new boolean[]{activeSurface != null && activeIndex >= 0});
            if (first != null) throw new Exception(first);
        }

        private void tryClose(
                Object activeCamera, Surface[] activeSurfaces, int[] activeIndexes,
                boolean[] attached) throws Exception {
            Throwable first = closeOneShotPort(
                    new ReflectivePersistentCameraPort(activeCamera),
                    activeSurfaces, activeIndexes, attached);
            if (first != null) throw new Exception(first);
        }

        static Throwable closeOneShotPort(
                PersistentCameraPort port, Surface[] activeSurfaces,
                int[] activeIndexes, boolean[] attached) {
            Throwable first = null;
            for (int i = 0; i < activeSurfaces.length; i++) {
                boolean shouldRemove = attached == null
                        ? activeSurfaces[i] != null
                        : i < attached.length && attached[i];
                if (!shouldRemove || i >= activeIndexes.length || activeIndexes[i] < 0) {
                    continue;
                }
                try {
                    if (!port.remove(activeSurfaces[i], activeIndexes[i])) {
                        throw new IllegalStateException(
                                "rmPreviewSurface returned false for index "
                                        + activeIndexes[i]);
                    }
                } catch (Throwable error) {
                    if (first == null) first = root(error);
                }
            }
            try {
                port.stop();
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
            try {
                port.close();
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
            return first;
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

        static boolean matchesPendingCameraRequest(int pendingRequestId, int callbackRequestId) {
            return callbackRequestId > 0 && callbackRequestId == pendingRequestId;
        }

        static boolean matchesCurrentStockRequest(
                boolean stockRequested, int pendingRequestId,
                int activeRequestId, int eventRequestId) {
            int currentRequestId = pendingRequestId > 0
                    ? pendingRequestId : activeRequestId;
            return stockRequested && eventRequestId > 0
                    && eventRequestId == currentRequestId;
        }

        private static void requireActivityRequestId(int requestId) {
            if (requestId <= 0) throw new IllegalArgumentException("camera request id required");
        }

        static boolean shouldPreserveActivityPreview(
                boolean hasActivityPreview, String requestedOwner) {
            return hasActivityPreview
                    && (CAMERA_OWNER_OVERLAY.equals(requestedOwner)
                            || CAMERA_OWNER_REVERSE.equals(requestedOwner));
        }

        static boolean matchesConsumerClose(int activeRequestId, int expectedRequestId) {
            return activeRequestId == expectedRequestId
                    || activeRequestId == 0 && expectedRequestId == 0;
        }

        static int cameraRequestIdForClose(
                boolean stockRequested, int pendingStockRequestId,
                int activeRequestId) {
            return stockRequested && pendingStockRequestId > 0
                    ? pendingStockRequestId : activeRequestId;
        }

        static PersistentCloseDecision persistentCloseDecision(
                boolean hasConsumer, int activeRequestId, int expectedRequestId) {
            if (!hasConsumer) return PersistentCloseDecision.ALREADY_CLOSED;
            return matchesConsumerClose(activeRequestId, expectedRequestId)
                    ? PersistentCloseDecision.CLOSE : PersistentCloseDecision.STALE;
        }

        static boolean shellDeathInvalidatesConsumer(
                String owner, boolean shellOwned) {
            return CAMERA_OWNER_ACTIVITY.equals(owner) && shellOwned;
        }

        static boolean shouldStartPersistentProducer(boolean producerOpen) {
            return !producerOpen;
        }

        enum PersistentCloseDecision {
            ALREADY_CLOSED,
            STALE,
            CLOSE
        }

        interface PersistentEventSink {
            void emit(String kind, Object... fields);
        }

        interface PersistentCameraPort {
            boolean add(Surface value, int index) throws Exception;
            boolean remove(Surface value, int index) throws Exception;
            boolean start() throws Exception;
            void stop() throws Exception;
            void close() throws Exception;
        }

        interface PersistentSurfaceFanout {
            Surface source(int index) throws Exception;
            void attach(Surface[] surfaces, int[] indexes) throws Exception;
            void detach(Surface[] surfaces) throws Exception;
            void close();
        }

        interface PersistentShellCloseSink {
            boolean close(String reason, int requestId);
        }

        static final class PersistentSession {
            final ConsumerGroup activityGroup = new ConsumerGroup(CAMERA_OWNER_ACTIVITY);
            final ConsumerGroup overlayGroup = new ConsumerGroup(CAMERA_OWNER_OVERLAY);
            final ConsumerGroup reverseGroup = new ConsumerGroup(CAMERA_OWNER_REVERSE);
            final Surface[] sourceSurfaces = new Surface[5];
            final boolean[] sourceAttached = new boolean[5];
            boolean producerOpen;
            Throwable restoreFailure;
            PersistentSurfaceFanout fanout;

            void startProducer(
                    PersistentCameraPort port, PersistentSurfaceFanout requestedFanout,
                    ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned) throws Exception {
                startProducer(port, requestedFanout, target, surfaces, indexes, requestId,
                        view, exclusive, shellOwned, false);
            }

            void startProducer(
                    PersistentCameraPort port, PersistentSurfaceFanout requestedFanout,
                    ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    boolean firstSurfaceDirect) throws Exception {
                fanout = requestedFanout;
                boolean targetAttached = false;
                try {
                    attachGroup(port, surfaces, indexes, firstSurfaceDirect);
                    targetAttached = true;
                    ensureSources(port, fanoutIndexes(indexes, firstSurfaceDirect));
                    if (!port.start()) {
                        throw new IllegalStateException("startPreview returned false");
                    }
                    target.set(surfaces, indexes, requestId,
                            view, exclusive, shellOwned, true, firstSurfaceDirect);
                    producerOpen = true;
                    restoreFailure = null;
                } catch (Throwable error) {
                    if (targetAttached) {
                        try {
                            detachRequestedGroup(
                                    port, surfaces, indexes, firstSurfaceDirect);
                        } catch (Throwable ignored) {
                            // The failed hub is released below.
                        }
                    }
                    detachSources(port);
                    try {
                        fanout.close();
                    } catch (Throwable closeError) {
                        error.addSuppressed(closeError);
                    }
                    fanout = null;
                    clearSources();
                    throw error;
                }
            }

            void attach(
                    PersistentCameraPort port, ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) throws PersistentSessionFailure {
                attach(port, target, surfaces, indexes, requestId, view,
                        exclusive, shellOwned, events, shellClose, cameraId, epoch, false);
            }

            void attach(
                    PersistentCameraPort port, ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch,
                    boolean firstSurfaceDirect) throws PersistentSessionFailure {
                try {
                    ensureSources(port, fanoutIndexes(indexes, firstSurfaceDirect));
                } catch (BatchAttachException error) {
                    throw new PersistentSessionFailure(
                            "raw_source_attach_failed", root(error), true,
                            false, false);
                } catch (Throwable error) {
                    throw new PersistentSessionFailure(
                            "raw_source_attach_failed", root(error), false,
                            false, false);
                }
                ConsumerGroup.Snapshot previous = target.snapshot();
                ConsumerGroup[] detached = groupsToDetach(target, exclusive);
                for (ConsumerGroup group : detached) {
                    try {
                        detachGroup(port, group);
                    } catch (Throwable error) {
                        throw new PersistentSessionFailure(
                                "consumer_detach_failed", root(error), true,
                                false, false);
                    }
                }

                try {
                    attachGroup(port, surfaces, indexes, firstSurfaceDirect);
                } catch (Throwable error) {
                    restoreFailure = null;
                    boolean rollbackFailed = error instanceof BatchAttachException
                            && ((BatchAttachException) error).rollbackFailed;
                    if (!rollbackFailed) {
                        restoreGroups(port, detached, events, shellClose, cameraId, epoch);
                    }
                    throw new PersistentSessionFailure(
                            "consumer_attach_failed", root(error),
                            rollbackFailed, false, false);
                }

                target.set(surfaces, indexes, requestId,
                        view, exclusive, shellOwned, true, firstSurfaceDirect);
                boolean shellCloseQueued = false;
                if (target == reverseGroup && activityGroup.has()) {
                    ConsumerGroup.Snapshot preempted = activityGroup.snapshot();
                    emitConsumerClosed(events, preempted.owner, preempted.requestId,
                            preempted.view, preempted.indexes,
                            "replace_with_multi_preview", cameraId, epoch);
                    if (preempted.shellOwned) {
                        shellCloseQueued = shellClose.close(
                                "replace_with_multi_preview", preempted.requestId);
                    }
                    activityGroup.clear();
                    preempted.release();
                }
                if (!exclusive && !restoreCompatibleGroups(
                        port, events, shellClose, cameraId, epoch)) {
                    Throwable restoreError = restoreFailure;
                    if (!rollbackRequestedTarget(
                            port, target, previous, events, shellClose, cameraId, epoch)) {
                        throw new PersistentSessionFailure(
                                "consumer_restore_rollback_failed", restoreFailure, true,
                                true, shellCloseQueued);
                    }
                    throw new PersistentSessionFailure(
                            "consumer_restore_failed", restoreError, false,
                            true, shellCloseQueued);
                }
                previous.release();
            }

            CloseOutcome close(
                    PersistentCameraPort port, ConsumerGroup group,
                    String reason, int expectedRequestId,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) throws PersistentSessionFailure {
                PersistentCloseDecision decision = persistentCloseDecision(
                        group.has(), group.requestId, expectedRequestId);
                if (decision != PersistentCloseDecision.CLOSE) {
                    return new CloseOutcome(decision, false);
                }
                ConsumerGroup.Snapshot closed = group.snapshot();
                try {
                    detachGroup(port, group);
                } catch (Throwable error) {
                    throw new PersistentSessionFailure(
                            "consumer_detach_failed", root(error),
                            group.firstSurfaceDirect,
                            false, false);
                }
                group.clear();
                closed.release();
                boolean shellCloseQueued = closed.shellOwned
                        && shellClose.close(reason, closed.requestId);
                restoreCompatibleGroups(port, events, shellClose, cameraId, epoch);
                emitConsumerClosed(events, closed.owner, closed.requestId,
                        closed.view, closed.indexes, reason, cameraId, epoch);
                return new CloseOutcome(decision, shellCloseQueued);
            }

            TeardownOutcome tearDown(
                    PersistentCameraPort port, String reason, Throwable failure,
                    boolean closeStock, int stockRequestId,
                    boolean shellCloseAlreadyQueued,
                    PersistentShellCloseSink shellClose,
                    PersistentEventSink events, int epoch) {
                Throwable first = failure;
                ConsumerGroup.Snapshot[] abandoned = activeSnapshots();
                String terminalError = first == null ? reason : summary(first);
                for (ConsumerGroup.Snapshot consumer : abandoned) {
                    events.emit("camera_error", "stage", reason,
                            "camera_tag", "pano_h", "camera_owner", consumer.owner,
                            "request_id", consumer.requestId,
                            "view", consumer.view == null ? "unknown" : consumer.view,
                            "preview_indexes", Arrays.toString(consumer.indexes),
                            "producer_epoch", epoch, "error", terminalError);
                }
                if (port != null) {
                    Throwable directError = detachDirectInputs(port);
                    if (first == null) first = directError;
                    Throwable detachError = detachSources(port);
                    if (first == null) first = detachError;
                    Throwable closeError = stopAndClosePersistentProducer(port);
                    if (first == null) first = closeError;
                }
                if (fanout != null) {
                    try {
                        fanout.close();
                    } catch (Throwable closeError) {
                        if (first == null) first = root(closeError);
                    }
                }
                releaseAndClear(activityGroup);
                releaseAndClear(overlayGroup);
                releaseAndClear(reverseGroup);
                fanout = null;
                clearSources();
                producerOpen = false;
                restoreFailure = null;
                boolean shellCloseQueued = shellCloseAlreadyQueued;
                if (closeStock && !shellCloseQueued) {
                    shellCloseQueued = shellClose.close(reason, stockRequestId);
                }
                String error = first == null ? "" : summary(first);
                events.emit("camera_producer_closed", "reason", reason,
                        "camera_tag", "pano_h", "producer_epoch", epoch,
                        "error", error);
                return new TeardownOutcome(shellCloseQueued, first);
            }

            private ConsumerGroup.Snapshot[] activeSnapshots() {
                ArrayDeque<ConsumerGroup.Snapshot> result = new ArrayDeque<>();
                if (activityGroup.has()) result.add(activityGroup.snapshot());
                if (overlayGroup.has()) result.add(overlayGroup.snapshot());
                if (reverseGroup.has()) result.add(reverseGroup.snapshot());
                return result.toArray(new ConsumerGroup.Snapshot[0]);
            }

            private ConsumerGroup[] groupsToDetach(ConsumerGroup target, boolean exclusive) {
                ArrayDeque<ConsumerGroup> groups = new ArrayDeque<>();
                if (target.attached) groups.add(target);
                if (exclusive) {
                    if (overlayGroup != target && overlayGroup.attached) groups.add(overlayGroup);
                    if (activityGroup != target && activityGroup.attached) groups.add(activityGroup);
                    if (reverseGroup != target && reverseGroup.attached) groups.add(reverseGroup);
                }
                return groups.toArray(new ConsumerGroup[0]);
            }

            private boolean restoreCompatibleGroups(
                    PersistentCameraPort port,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) {
                restoreFailure = null;
                if (reverseGroup.has() || activityGroup.has() && activityGroup.exclusive) {
                    return true;
                }
                return restoreGroups(
                        port, new ConsumerGroup[]{overlayGroup, activityGroup},
                        events, shellClose, cameraId, epoch);
            }

            private boolean restoreGroups(
                    PersistentCameraPort port, ConsumerGroup[] groups,
                    PersistentEventSink events,
                    PersistentShellCloseSink shellClose, int cameraId, int epoch) {
                boolean restored = true;
                for (ConsumerGroup group : groups) {
                    if (!group.has() || group.attached) continue;
                    try {
                        attachGroup(port, group.surfaces, group.indexes,
                                group.firstSurfaceDirect);
                        group.attached = true;
                        group.directSurfaceAttached = group.firstSurfaceDirect;
                        events.emit("camera_consumer_attached",
                                "camera_owner", group.owner,
                                "request_id", group.requestId,
                                "preview_indexes", Arrays.toString(group.indexes),
                                "producer_epoch", epoch,
                                "restored", true);
                    } catch (Throwable error) {
                        restoreFailure = error.getCause() == null
                                ? root(error) : root(error.getCause());
                        terminalClear(group, "consumer_restore_failed", restoreFailure,
                                events, shellClose, cameraId, epoch);
                        restored = false;
                    }
                }
                return restored;
            }

            private boolean rollbackRequestedTarget(
                    PersistentCameraPort port, ConsumerGroup target,
                    ConsumerGroup.Snapshot previous,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) {
                ConsumerGroup.Snapshot requested = target.snapshot();
                try {
                    detachGroup(port, target);
                } catch (Throwable error) {
                    restoreFailure = root(error);
                    if (previous.surfaces.length > 0) {
                        terminalSnapshot(previous, "consumer_restore_failed", restoreFailure,
                                events, shellClose, cameraId, epoch);
                    }
                    return false;
                }
                target.clear();
                requested.release();
                if (previous.surfaces.length == 0) return true;
                if (!previous.attached) {
                    terminalSnapshot(previous, "consumer_restore_failed", restoreFailure,
                            events, shellClose, cameraId, epoch);
                    return true;
                }
                try {
                    attachGroup(port, previous.surfaces, previous.indexes,
                            previous.firstSurfaceDirect);
                    target.set(previous.surfaces, previous.indexes, previous.requestId,
                            previous.view, previous.exclusive, previous.shellOwned,
                            true, previous.firstSurfaceDirect);
                    events.emit("camera_consumer_attached",
                            "camera_owner", previous.owner,
                            "request_id", previous.requestId,
                            "preview_indexes", Arrays.toString(previous.indexes),
                            "producer_epoch", epoch, "restored", true);
                    return true;
                } catch (Throwable error) {
                    restoreFailure = root(error);
                    terminalSnapshot(previous, "consumer_restore_failed", restoreFailure,
                            events, shellClose, cameraId, epoch);
                    return true;
                }
            }

            private void terminalClear(
                    ConsumerGroup group, String reason, Throwable error,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) {
                ConsumerGroup.Snapshot failed = group.snapshot();
                group.clear();
                terminalSnapshot(failed, reason, error,
                        events, shellClose, cameraId, epoch);
            }

            private void terminalSnapshot(
                    ConsumerGroup.Snapshot failed, String reason, Throwable error,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) {
                if (failed.shellOwned) shellClose.close(reason, failed.requestId);
                events.emit("camera_error", "stage", reason,
                        "camera_tag", "pano_h", "camera_owner", failed.owner,
                        "request_id", failed.requestId,
                        "view", failed.view == null ? "unknown" : failed.view,
                        "preview_indexes", Arrays.toString(failed.indexes),
                        "producer_epoch", epoch, "error", summary(error));
                emitConsumerClosed(events, failed.owner, failed.requestId,
                        failed.view, failed.indexes, reason, cameraId, epoch);
                failed.release();
            }

            private void detachGroup(
                    PersistentCameraPort port, ConsumerGroup group) throws Exception {
                if (!group.attached) return;
                Exception first = null;
                if (group.directSurfaceAttached) {
                    try {
                        if (!port.remove(group.surfaces[0], group.indexes[0])) {
                            first = new IllegalStateException(
                                    "rmPreviewSurface returned false for direct index "
                                            + group.indexes[0]);
                        } else {
                            group.directSurfaceAttached = false;
                        }
                    } catch (Exception error) {
                        first = error;
                    }
                }
                try {
                    fanout.detach(fanoutSurfaces(
                            group.surfaces, group.firstSurfaceDirect));
                } catch (Exception error) {
                    if (first == null) first = error;
                }
                if (first != null) throw first;
                group.attached = false;
            }

            boolean failConsumer(
                    PersistentCameraPort port, Surface failedSurface,
                    int failedIndex, Throwable error,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) throws PersistentSessionFailure {
                ConsumerGroup group = groupContaining(failedSurface);
                if (group == null) return false;
                ConsumerGroup.Snapshot failed = group.snapshot();
                try {
                    detachGroup(port, group);
                } catch (Throwable cleanupError) {
                    throw new PersistentSessionFailure(
                            "consumer_detach_failed", root(cleanupError), true,
                            true, false);
                }
                group.clear();
                if (failed.shellOwned) {
                    shellClose.close("consumer_render_failed", failed.requestId);
                }
                events.emit("camera_error", "stage", "raw_fanout_consumer",
                        "camera_tag", "pano_h", "camera_owner", failed.owner,
                        "request_id", failed.requestId,
                        "view", failed.view == null ? "unknown" : failed.view,
                        "preview_index", failedIndex,
                        "producer_epoch", epoch, "error", summary(error));
                emitConsumerClosed(events, failed.owner, failed.requestId,
                        failed.view, failed.indexes, "consumer_render_failed",
                        cameraId, epoch);
                failed.release();
                restoreCompatibleGroups(port, events, shellClose, cameraId, epoch);
                return true;
            }

            private ConsumerGroup groupContaining(Surface target) {
                if (contains(activityGroup.surfaces, target)) return activityGroup;
                if (contains(overlayGroup.surfaces, target)) return overlayGroup;
                if (contains(reverseGroup.surfaces, target)) return reverseGroup;
                return null;
            }

            private static boolean contains(Surface[] surfaces, Surface target) {
                for (Surface surface : surfaces) if (surface == target) return true;
                return false;
            }

            private void attachGroup(
                    PersistentCameraPort port, Surface[] surfaces, int[] indexes,
                    boolean firstSurfaceDirect) throws Exception {
                Surface[] targets = fanoutSurfaces(surfaces, firstSurfaceDirect);
                int[] targetIndexes = fanoutIndexes(indexes, firstSurfaceDirect);
                boolean fanoutAttached = false;
                boolean directAttempted = false;
                try {
                    fanout.attach(targets, targetIndexes);
                    fanoutAttached = true;
                    if (firstSurfaceDirect) {
                        directAttempted = true;
                        if (!port.add(surfaces[0], indexes[0])) {
                            throw new IllegalStateException(
                                    "addPreviewSurface returned false for direct index "
                                            + indexes[0]);
                        }
                    }
                } catch (Exception error) {
                    Exception rollbackError = null;
                    if (directAttempted) {
                        try {
                            if (!port.remove(surfaces[0], indexes[0])) {
                                rollbackError = new IllegalStateException(
                                        "rmPreviewSurface returned false for direct index "
                                                + indexes[0]);
                            }
                        } catch (Exception cleanupError) {
                            rollbackError = cleanupError;
                        }
                    }
                    if (fanoutAttached) {
                        try {
                            fanout.detach(targets);
                        } catch (Exception cleanupError) {
                            if (rollbackError == null) rollbackError = cleanupError;
                        }
                    }
                    if (rollbackError != null) {
                        error.addSuppressed(rollbackError);
                        throw new BatchAttachException(error, true);
                    }
                    throw error;
                }
            }

            private void detachRequestedGroup(
                    PersistentCameraPort port, Surface[] surfaces, int[] indexes,
                    boolean firstSurfaceDirect) throws Exception {
                Exception first = null;
                if (firstSurfaceDirect) {
                    try {
                        if (!port.remove(surfaces[0], indexes[0])) {
                            first = new IllegalStateException(
                                    "rmPreviewSurface returned false for direct index "
                                            + indexes[0]);
                        }
                    } catch (Exception error) {
                        first = error;
                    }
                }
                try {
                    fanout.detach(fanoutSurfaces(surfaces, firstSurfaceDirect));
                } catch (Exception error) {
                    if (first == null) first = error;
                }
                if (first != null) throw first;
            }

            private Throwable detachDirectInputs(PersistentCameraPort port) {
                Throwable first = null;
                for (ConsumerGroup group : new ConsumerGroup[]{
                        activityGroup, overlayGroup, reverseGroup}) {
                    if (!group.directSurfaceAttached) continue;
                    try {
                        if (!port.remove(group.surfaces[0], group.indexes[0])) {
                            if (first == null) {
                                first = new IllegalStateException(
                                        "rmPreviewSurface returned false for direct index "
                                                + group.indexes[0]);
                            }
                        } else {
                            group.directSurfaceAttached = false;
                        }
                    } catch (Throwable error) {
                        if (first == null) first = root(error);
                    }
                }
                return first;
            }

            private static Surface[] fanoutSurfaces(
                    Surface[] surfaces, boolean firstSurfaceDirect) {
                if (!firstSurfaceDirect) return surfaces;
                if (surfaces.length < 2) {
                    throw new IllegalArgumentException(
                            "direct input plus at least one fanout Surface required");
                }
                return Arrays.copyOfRange(surfaces, 1, surfaces.length);
            }

            private static int[] fanoutIndexes(
                    int[] indexes, boolean firstSurfaceDirect) {
                if (!firstSurfaceDirect) return indexes;
                if (indexes.length < 2) {
                    throw new IllegalArgumentException(
                            "direct input plus at least one fanout index required");
                }
                return Arrays.copyOfRange(indexes, 1, indexes.length);
            }

            private void ensureSources(PersistentCameraPort port, int[] indexes)
                    throws Exception {
                try {
                    for (int index : indexes) {
                        if (sourceAttached[index]) continue;
                        Surface source = fanout.source(index);
                        // From this call onward AVMCamera ownership is ambiguous on failure.
                        sourceSurfaces[index] = source;
                        sourceAttached[index] = true;
                        if (!port.add(source, index)) {
                            throw new IllegalStateException(
                                    "addPreviewSurface returned false for RAW index " + index);
                        }
                    }
                } catch (Throwable error) {
                    // A source that reached AVMCamera stays pristine and attached for this epoch.
                    throw new BatchAttachException(root(error), false);
                }
            }

            private Throwable detachSources(PersistentCameraPort port) {
                Throwable first = null;
                for (int index = 0; index < sourceAttached.length; index++) {
                    if (!sourceAttached[index]) continue;
                    try {
                        if (!port.remove(sourceSurfaces[index], index) && first == null) {
                            first = new IllegalStateException(
                                    "rmPreviewSurface returned false for RAW index " + index);
                        }
                    } catch (Throwable error) {
                        if (first == null) first = root(error);
                    }
                }
                return first;
            }

            private void clearSources() {
                Arrays.fill(sourceSurfaces, null);
                Arrays.fill(sourceAttached, false);
            }
        }

        static final class CloseOutcome {
            final PersistentCloseDecision decision;
            final boolean shellCloseQueued;

            CloseOutcome(PersistentCloseDecision decision, boolean shellCloseQueued) {
                this.decision = decision;
                this.shellCloseQueued = shellCloseQueued;
            }
        }

        static final class TeardownOutcome {
            final boolean shellCloseQueued;
            final Throwable failure;

            TeardownOutcome(boolean shellCloseQueued, Throwable failure) {
                this.shellCloseQueued = shellCloseQueued;
                this.failure = failure;
            }
        }

        static final class PersistentSessionFailure extends Exception {
            final String reason;
            final boolean fatal;
            final boolean requestedOwnedBySession;
            final boolean shellCloseQueued;

            PersistentSessionFailure(
                    String reason, Throwable cause, boolean fatal,
                    boolean requestedOwnedBySession, boolean shellCloseQueued) {
                super(cause);
                this.reason = reason;
                this.fatal = fatal;
                this.requestedOwnedBySession = requestedOwnedBySession;
                this.shellCloseQueued = shellCloseQueued;
            }
        }

        private static final class ReflectivePersistentCameraPort
                implements PersistentCameraPort {
            private final Object target;
            private final Class<?> type;

            ReflectivePersistentCameraPort(Object target) {
                this.target = target;
                type = target.getClass();
            }

            @Override
            public boolean add(Surface value, int index) throws Exception {
                return invokeBoolean(type, target, "addPreviewSurface",
                        new Class<?>[]{Surface.class, int.class}, value, index);
            }

            @Override
            public boolean remove(Surface value, int index) throws Exception {
                return invokeBoolean(type, target, "rmPreviewSurface",
                        new Class<?>[]{Surface.class, int.class}, value, index);
            }

            @Override
            public boolean start() throws Exception {
                return invokeBoolean(type, target, "startPreview", new Class<?>[0]);
            }

            @Override
            public void stop() throws Exception {
                type.getMethod("stopPreview").invoke(target);
            }

            @Override
            public void close() throws Exception {
                type.getMethod("close").invoke(target);
            }
        }

        static final class BatchAttachException extends Exception {
            final boolean rollbackFailed;

            BatchAttachException(Throwable cause, boolean rollbackFailed) {
                super(cause);
                this.rollbackFailed = rollbackFailed;
            }
        }

        static final class ConsumerGroup {
            final String owner;
            Surface[] surfaces = new Surface[0];
            int[] indexes = new int[0];
            int requestId;
            String view;
            boolean exclusive;
            boolean shellOwned;
            boolean attached;
            boolean firstSurfaceDirect;
            boolean directSurfaceAttached;

            ConsumerGroup(String owner) {
                this.owner = owner;
            }

            boolean has() {
                return surfaces.length > 0;
            }

            void set(
                    Surface[] nextSurfaces, int[] nextIndexes, int nextRequestId,
                    String nextView, boolean nextExclusive,
                    boolean nextShellOwned, boolean nextAttached) {
                set(nextSurfaces, nextIndexes, nextRequestId, nextView,
                        nextExclusive, nextShellOwned, nextAttached, false);
            }

            void set(
                    Surface[] nextSurfaces, int[] nextIndexes, int nextRequestId,
                    String nextView, boolean nextExclusive,
                    boolean nextShellOwned, boolean nextAttached,
                    boolean nextFirstSurfaceDirect) {
                surfaces = nextSurfaces;
                indexes = nextIndexes.clone();
                requestId = nextRequestId;
                view = nextView;
                exclusive = nextExclusive;
                shellOwned = nextShellOwned;
                attached = nextAttached;
                firstSurfaceDirect = nextFirstSurfaceDirect;
                directSurfaceAttached = nextAttached && nextFirstSurfaceDirect;
            }

            Snapshot snapshot() {
                return new Snapshot(
                        owner, surfaces, indexes, requestId, view,
                        exclusive, shellOwned, attached, firstSurfaceDirect);
            }

            void clear() {
                surfaces = new Surface[0];
                indexes = new int[0];
                requestId = 0;
                view = null;
                exclusive = false;
                shellOwned = false;
                attached = false;
                firstSurfaceDirect = false;
                directSurfaceAttached = false;
            }

            static final class Snapshot {
                final String owner;
                final Surface[] surfaces;
                final int[] indexes;
                final int requestId;
                final String view;
                final boolean exclusive;
                final boolean shellOwned;
                final boolean attached;
                final boolean firstSurfaceDirect;

                Snapshot(
                        String owner, Surface[] surfaces, int[] indexes,
                        int requestId, String view, boolean exclusive,
                        boolean shellOwned, boolean attached,
                        boolean firstSurfaceDirect) {
                    this.owner = owner;
                    this.surfaces = surfaces;
                    this.indexes = indexes.clone();
                    this.requestId = requestId;
                    this.view = view;
                    this.exclusive = exclusive;
                    this.shellOwned = shellOwned;
                    this.attached = attached;
                    this.firstSurfaceDirect = firstSurfaceDirect;
                }

                void release() {
                    releaseSurfaces(surfaces);
                }
            }
        }

        static final class ActivityPreviewState<T> {
            private T value;
            private int index = -1;
            private boolean attached;

            boolean has() {
                return value != null;
            }

            T value() {
                return value;
            }

            int index() {
                return index;
            }

            boolean attached() {
                return attached;
            }

            void set(T nextValue, int nextIndex, boolean nextAttached) {
                if (nextValue == null) throw new IllegalArgumentException("preview required");
                value = nextValue;
                index = nextIndex;
                attached = nextAttached;
            }

            void setAttached(boolean nextAttached) {
                attached = value != null && nextAttached;
            }

            Snapshot<T> close(boolean preserve) {
                Snapshot<T> snapshot = new Snapshot<>(
                        value, index, attached, !preserve && value != null);
                attached = false;
                if (!preserve) {
                    value = null;
                    index = -1;
                }
                return snapshot;
            }

            static final class Snapshot<T> {
                final T value;
                final int index;
                final boolean attached;
                final boolean release;

                Snapshot(T value, int index, boolean attached, boolean release) {
                    this.value = value;
                    this.index = index;
                    this.attached = attached;
                    this.release = release;
                }
            }
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

        private void acceptControllerEvent(String kind, Object[] fields) {
            emit(kind, fields);
            if ("camera_shell_died".equals(kind)) {
                cameraShellDiedCleanup();
                turnController.recoverCameraHelper();
            }
        }

        private synchronized void cameraShellDiedCleanup() {
            stockCameraRequested = false;
            pendingStockCameraRequestId = 0;
            releaseSurfaces(pendingReversePreviewSurfaces);
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            if (persistentPanoProducer
                    && shellDeathInvalidatesConsumer(
                            activityGroup.owner, activityGroup.shellOwned)) {
                closePersistentGroup(
                        activityGroup, "camera_shell_died", activityGroup.requestId);
            } else if (!persistentPanoProducer) {
                closeCamera("camera_shell_died");
            }
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
                int requestId = event.optInt("request_id", 0);
                boolean terminal = "camera_error".equals(kind)
                        || "camera_closed".equals(kind);
                boolean pendingPreviewError;
                synchronized (this) {
                    boolean stockTerminal = terminal && matchesCurrentStockRequest(
                            stockCameraRequested, pendingStockCameraRequestId,
                            activeCameraRequestId, requestId);
                    pendingPreviewError = "camera_error".equals(kind)
                            && matchesPendingCameraRequest(
                                    pendingReversePreviewRequestId, requestId);
                    if (stockTerminal || pendingPreviewError) {
                        stockCameraRequested = false;
                        if (pendingStockCameraRequestId == requestId) {
                            pendingStockCameraRequestId = 0;
                        }
                        releaseSurfaces(pendingReversePreviewSurfaces);
                        pendingReversePreviewSurfaces = new Surface[0];
                        if (pendingReversePreviewRequestId == requestId) {
                            pendingReversePreviewRequestId = 0;
                        }
                    }
                }
                if (pendingPreviewError) {
                    turnController.closeStockAvm("reverse_preview_base_failed", requestId);
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
                    || "music_metadata_focus".equals(kind)
                    || "music_metadata_publish".equals(kind)
                    || "music_metadata_cleanup".equals(kind)
                    || "music_metadata_relinquished".equals(kind)
                    || "music_metadata_error".equals(kind)
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
                current = callbacks.current();
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

    static final class CallbackSlot<T> {
        private T current;
        private long currentGeneration;
        private long latestGeneration;

        synchronized boolean register(T value, long generation) {
            if (value == null) throw new IllegalArgumentException("callback is null");
            if (generation <= 0) {
                throw new IllegalArgumentException("callback generation is invalid");
            }
            if (generation <= latestGeneration) return false;
            latestGeneration = generation;
            current = value;
            currentGeneration = generation;
            return true;
        }

        synchronized boolean detach(T expected, long generation) {
            if (expected == null || current != expected
                    || currentGeneration != generation) return false;
            current = null;
            currentGeneration = 0;
            return true;
        }

        synchronized T current() {
            return current;
        }
    }

    static int updateReverseControllerRequestId(
            int activeRequestId, String kind, int eventRequestId) {
        if ("reverse_camera_start".equals(kind)) {
            return eventRequestId > 0 ? eventRequestId : activeRequestId;
        }
        if ("reverse_camera_stopped".equals(kind)
                && activeRequestId > 0 && eventRequestId == activeRequestId) {
            return 0;
        }
        return activeRequestId;
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
