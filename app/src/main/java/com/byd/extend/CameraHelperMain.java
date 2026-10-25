package com.byd.extend;

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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

final class CameraHelperMain {
    enum DiscoveryResult { READY, RETRYABLE_FAILURE, TERMINAL_UNSUPPORTED_ID }

    static final String PACKAGE_NAME = "com.byd.extend";
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
    static final int TX_UPDATE_VISUALS = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int TX_UPDATE_REVERSE_VISIBILITY = IBinder.FIRST_CALL_TRANSACTION + 11;
    static final int TX_OPEN_REVERSE_PREVIEW_TARGETED = IBinder.FIRST_CALL_TRANSACTION + 12;
    static final int TX_OPEN_MOTION_PREVIEW = IBinder.FIRST_CALL_TRANSACTION + 13;
    static final int TX_CLOSE_MOTION_PREVIEW = IBinder.FIRST_CALL_TRANSACTION + 14;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;
    static final int ADB_AUTH_MODE_AUTO_ONCE = 0;
    static final int ADB_AUTH_MODE_FORCE = 1;
    static final String CAMERA_OWNER_ACTIVITY = "activity";
    static final String CAMERA_OWNER_OVERLAY = "overlay";
    static final String CAMERA_OWNER_PARKING = "parking";
    static final String CAMERA_OWNER_MIRROR = "mirror";
    static final String CAMERA_OWNER_REVERSE = "reverse";
    static final String ACTIVITY_RESUME_COLD_RESET = "activity_resume_cold_reset";
    static final String VEHICLE_WAKE_RECOVERY = "vehicle_wake_recovery";
    static final String NATIVE_SOURCE_RECOVERY = "native_source_recovery";
    static boolean isSourceRecovery(String stage) {
        return VEHICLE_WAKE_RECOVERY.equals(stage) || NATIVE_SOURCE_RECOVERY.equals(stage);
    }
    static boolean nativeSourceDied(int event) {
        // AVMCameraEvent: daemon, server and HAL death; not frame/sensor/preemption notices.
        return event == 8 || event == 1002 || event == 1010;
    }
    static final String COLD_RESET_DEFERRED_REVERSE = "camera_close_deferred_reverse";

    private static final String TAG = "BydCameraProbe";
    private static final String COUNTER_PREFS = "lifetime_counters";
    private static final String ACTIVATION_COUNT = "activation_count";
    private static final String CORRECTION_COUNT = "correction_count";
    private static final String[] DIRECT_CAMERA_TAGS = {
            "pano_h", "pano_l", "apa", "byd_apa"
    };
    private CameraHelperMain() {}

    static class HelperBinder extends Binder
            implements CameraHelperService.RuntimeSettingsSink {
        private final Handler callbackHandler;
        private final TurnSignalController turnController;
        private final CameraRuntimeClient cameraRuntime;
        private final Consumer<String> logSink;
        private final SharedPreferences counters;
        private final SharedPreferences settings;
        private final ArrayDeque<String> musicJournal = new ArrayDeque<>();
        private final CallbackSlot<IBinder> callbacks = new CallbackSlot<>();
        private int cameraId = -1;
        private String cameraTag = "none";
        private String discoveryError;
        private boolean terminalUnsupportedDiscovery;
        private Object camera;
        private Object eventCallback;
        private boolean persistentPanoProducer;
        private int producerCameraId = -1;
        private int producerEpoch;
        private int sourceHubGeneration;
        private DirectCameraSourceHub rawSourceHub;
        private boolean unconfirmedOneShotOwner;
        private Surface surface;
        private int previewIndex;
        private Surface[] multiSurfaces = new Surface[0];
        private int[] multiPreviewIndexes = new int[0];
        private final ActivityPreviewState<Surface> activityPreview =
                new ActivityPreviewState<>();
        private final PersistentSession persistentSession = new PersistentSession();
        private Runnable reverseWarmExpiry;
        private long reverseWarmExpiryGeneration;
        private long reverseWarmExpiryAtMs;
        private long reverseWarmLeaseGeneration;
        private int reverseWarmLeaseRequestId;
        private int reverseStockRetainedCallbackEpoch;
        private MotionRecordingOutput motionRecordingOutput;
        private MotionRecordingOutput viewingOutput;
        private final ConsumerGroup activityGroup = persistentSession.activityGroup;
        private final ConsumerGroup overlayGroup = persistentSession.overlayGroup;
        private final ConsumerGroup parkingGroup = persistentSession.parkingGroup;
        private final ConsumerGroup mirrorGroup = persistentSession.mirrorGroup;
        private final ConsumerGroup reverseGroup = persistentSession.reverseGroup;
        private String viewName;
        private int activeCameraId = -1;
        private String activeCameraTag = "none";
        private String activeCameraOwner = "none";
        private int activeCameraRequestId;
        private int activeReverseControllerRequestId;
        private final StockAvmRequestState stockRequest = new StockAvmRequestState();
        private boolean reverseStockActive;
        private boolean reverseStockInputAttached;
        private int reverseStockRequestId;
        private int reverseStockProducerEpoch;
        private long reverseStockAvmShellEpoch;
        private int reverseStockRetiredRequestId;
        private int reverseStockRetiredProducerEpoch;
        private boolean reverseStockClosePending;
        private int reverseStockCloseRequestId;
        private int reverseStockCloseProducerEpoch;
        private Surface[] pendingReversePreviewSurfaces = new Surface[0];
        private int pendingReversePreviewRequestId;

        private final Context renderingContext;

        HelperBinder(Context context, Consumer<String> logSink) {
            this(context, new Handler(Looper.getMainLooper()), logSink);
        }

        HelperBinder(Context context, Handler callbackHandler, Consumer<String> logSink) {
            if (callbackHandler == null) {
                throw new IllegalArgumentException("callbackHandler is null");
            }
            this.callbackHandler = callbackHandler;
            this.renderingContext = context;
            this.logSink = logSink;
            counters = context.getSharedPreferences(COUNTER_PREFS, Context.MODE_PRIVATE);
            settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            if (!(context instanceof CameraRuntimeContext)) migrateLegacyCounters(context);
            turnController = new TurnSignalController(
                    context, callbackHandler, this::acceptShellEvent, this::acceptControllerEvent);
            cameraRuntime = context instanceof CameraRuntimeContext ? null
                    : new CameraRuntimeClient(context, callbackHandler, this::forwardLine);
        }

        CameraRuntimeClient cameraRuntime() { return cameraRuntime; }

        void foregroundAdbFinished(boolean authorized) {
            turnController.foregroundAdbFinished(authorized);
        }

        synchronized void reportRetainedCameraState() {
            emit("camera_vehicle_power_gate", "off", persistentSession.vehiclePowerOff);
            turnController.reportCameraShellState();
            emit("reverse_camera_state", "active", activeReverseControllerRequestId > 0,
                    "request_id", activeReverseControllerRequestId);
            discoverCamera();
        }

        void startGuardRuntime() {
            turnController.start();
        }

        void configureLogging() {
            turnController.configureLogging();
        }

        @Override
        public void applyGuard(
                boolean enabled, float outward, float center, int delayMs, int maxSpeedKph) {
            turnController.applyGuard(enabled, outward, center, delayMs, maxSpeedKph);
        }

        void configureMusic(boolean enabled) {
            turnController.configureMusic(enabled);
        }

        void configureMusic(boolean enabled, boolean captureFocusOnOpen,
                boolean engineVisualizationEnabled) {
            turnController.configureMusic(enabled, captureFocusOnOpen,
                    engineVisualizationEnabled);
        }

        @Override
        public void applyMusic(boolean enabled) {
            turnController.applyMusic(enabled);
        }

        @Override
        public void applyMusic(boolean enabled, boolean captureFocusOnOpen,
                boolean engineVisualizationEnabled) {
            turnController.applyMusic(enabled, captureFocusOnOpen,
                    engineVisualizationEnabled);
        }

        void noteMusicForegroundPackage(String packageName) {
            turnController.noteMusicForegroundPackage(packageName);
        }

        boolean dispatchMusicMediaKey(String foregroundPackage, int rawKeyCode,
                int action, int repeatCount, long downTime, long eventTime) {
            return turnController.dispatchMusicMediaKey(foregroundPackage, rawKeyCode,
                    action, repeatCount, downTime, eventTime);
        }

        boolean isHealthy() {
            return turnController.isHealthy();
        }

        void configureParkingRadar(boolean anyEnabled) {
            turnController.configureParkingRadar(anyEnabled);
        }

        @Override
        public void applyParkingRadar(boolean anyEnabled) {
            turnController.applyParkingRadar(anyEnabled);
        }

        public void configureAvas() {
            turnController.configureAvas();
        }

        public void configureCalls(boolean needed) {
            turnController.configureCalls(needed);
        }

        public void startAvasManual(String profileId) {
            turnController.startAvasManual(profileId);
        }

        public void stopAvasManual(String profileId) {
            turnController.stopAvasManual(profileId);
        }

        public void startAvasEngine() {
            turnController.startAvasEngine();
        }

        public void stopAvasEngine() {
            turnController.stopAvasEngine();
        }

        public void toggleAvasEngine() {
            turnController.toggleAvasEngine();
        }

        public void startAvasAudition(String profileId, String assetId, String sessionId) {
            turnController.startAvasAudition(profileId, assetId, sessionId);
        }

        public void stopAvasAudition(String sessionId) {
            turnController.stopAvasAudition(sessionId);
        }

        public void reportAvasStatus() {
            turnController.reportAvasStatus();
        }

        void startAvasMicrophone(String sessionId, android.os.ParcelFileDescriptor pcm,
                int volume) {
            turnController.startAvasMicrophone(sessionId, pcm,
                    TurnSignalShellProtocol.AVAS_MIC_FORMAT_PCM_S16LE_MONO_16KHZ, volume);
        }

        void stopAvasMicrophone(String sessionId) {
            turnController.stopAvasMicrophone(sessionId);
        }

        void setAvasMicrophoneVolume(String sessionId, int volume) {
            turnController.setAvasMicrophoneVolume(sessionId, volume);
        }

        void setRecoveryEnabled(boolean enabled) {
            turnController.setRecoveryEnabled(enabled);
        }

        /** Sends one current Reverse selector toggle through the cached camera shell only. */
        synchronized void toggleReverseSideMode(int requestId, long ownerEpoch) {
            if (requestId <= 0 || ownerEpoch < 0
                    || activeReverseControllerRequestId != requestId) return;
            turnController.toggleReverseSideMode(requestId, ownerEpoch);
        }

        /** Sends one automatic Reverse direction request through the current camera shell. */
        synchronized boolean setReverseSideMode(int requestId, int mode) {
            if (requestId <= 0 || activeReverseControllerRequestId != requestId) return false;
            return turnController.setReverseSideMode(requestId, mode);
        }

        synchronized void updateReverseSteering(int requestId, ReverseSteeringSample sample) {
            if (requestId <= 0 || activeReverseControllerRequestId != requestId) return;
            turnController.updateReverseSteering(requestId, sample);
        }

        synchronized void emitControllerEvent(String kind, Object... fields) {
            activeReverseControllerRequestId = updateReverseControllerRequestId(
                    activeReverseControllerRequestId, kind, requestId(fields));
            emit(kind, fields);
        }

        void shutdown(boolean terminateShells) {
            if (cameraRuntime != null) cameraRuntime.detach();
            turnController.shutdown(terminateShells);
            closeCamera("service_destroyed");
            emit("helper_shutdown", "reason", "service_destroyed",
                    "terminate_shells", terminateShells);
        }

        void shutdownKeepingAvas() {
            if (cameraRuntime != null) cameraRuntime.detach();
            turnController.shutdownKeepingAvas();
            closeCamera("service_destroyed");
            emit("helper_shutdown", "reason", "service_destroyed",
                    "terminate_shells", true, "avas_retained", true);
        }

        @Override
        protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (cameraRuntime != null && CameraRuntimeClient.isCameraTransaction(code))
                return cameraRuntime.transactCamera(code, data, reply, flags);
            return transactCameraAs(code, data, reply, flags, Binder.getCallingUid());
        }

        synchronized boolean transactCameraAs(int code, Parcel data, Parcel reply, int flags,
                int callerUid) throws RemoteException {
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
                                : closeCameraForOwner(CAMERA_OWNER_ACTIVITY, reason);
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
                    Surface requestedSurface = CameraGpuOutput.readSurface(data, callerUid);
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
                    Surface requestedSurface = CameraGpuOutput.readSurface(data, callerUid);
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
                if (code == TX_OPEN_REVERSE_PREVIEW_TARGETED) {
                    int requestId = data.readInt();
                    requireActivityRequestId(requestId);
                    ReversePreviewRequest requested = readTargetedReversePreview(data, callerUid);
                    String result = openReversePreview(
                            requested.panoOutput, requested.directSurfaces,
                            requested.directIndexes, requestId);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_OPEN_MOTION_PREVIEW) {
                    Surface requestedSurface = CameraGpuOutput.readSurface(data, callerUid);
                    int direction = data.readInt();
                    int requestId = data.readInt();
                    requireActivityRequestId(requestId);
                    String result = openMotionCompositionPreview(
                            requestedSurface, direction, requestId);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_CLOSE_MOTION_PREVIEW) {
                    int direction = data.readInt();
                    int requestId = data.readInt();
                    requireActivityRequestId(requestId);
                    String result = closeMotionCompositionPreview(
                            direction, requestId, "motion_preview_closed");
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == TX_UPDATE_VISUALS) {
                    if (!CameraShellProtocol.isCallerAllowed(
                            Binder.getCallingUid(), Process.myUid())) {
                        reply.writeException(new SecurityException("caller uid denied"));
                        return true;
                    }
                    int cornerRadiusDp = data.readInt();
                    int transparencyPercent = data.readInt();
                    CameraShellProtocol.validateVisualStyle(
                            cornerRadiusDp, transparencyPercent);
                    turnController.updateCameraVisuals(
                            cornerRadiusDp, transparencyPercent);
                    reply.writeNoException();
                    reply.writeString(result("camera_visuals_update_queued", null));
                    return true;
                }
                if (code == TX_UPDATE_REVERSE_VISIBILITY) {
                    if (!CameraShellProtocol.isCallerAllowed(
                            Binder.getCallingUid(), Process.myUid())) {
                        reply.writeException(new SecurityException("caller uid denied"));
                        return true;
                    }
                    int requestId = data.readInt();
                    requireActivityRequestId(requestId);
                    int count = data.readInt();
                    if (count < 1 || count > 4) {
                        throw new IllegalArgumentException(
                                "one to four reverse generations required");
                    }
                    int[] generations = new int[count];
                    for (int i = 0; i < count; i++) {
                        generations[i] = data.readInt();
                        if (generations[i] <= 0) {
                            throw new IllegalArgumentException(
                                    "invalid reverse Surface generation");
                        }
                    }
                    int visibilityMask = data.readInt();
                    boolean widgetVisible = data.readInt() != 0;
                    ReverseCameraLayout.requireVisibilityMask(visibilityMask);
                    // Activity Reverse owns the combined stock-base + direct group.  Route its
                    // mask to those attached fan-out targets instead of sending the Activity
                    // request through the automatic shell owner.  The optional central Front
                    // target shares the center visibility bit with Rear and is intentionally
                    // left to the local selector state.
                    if (updateActivityReverseVisibility(
                            requestId, generations, visibilityMask, widgetVisible)) {
                        reply.writeNoException();
                        reply.writeString(result("reverse_visibility_update_queued", null));
                        return true;
                    }
                    synchronized (this) {
                        if (activityGroup.has()) {
                            throw new IllegalStateException(
                                    "reverse visibility request is stale");
                        }
                        if (activeReverseControllerRequestId > 0
                                && activeReverseControllerRequestId != requestId) {
                            throw new IllegalStateException(
                                    "reverse visibility request is stale");
                        }
                        if (!reverseGroup.attached || reverseGroup.requestId != requestId) {
                            throw new IllegalStateException(
                                    "reverse visibility request is stale");
                        }
                    }
                    // Automatic Reverse remains shell-owned; its existing exact request and
                    // generation checks run unchanged in TurnSignalController/CameraShellMain.
                    turnController.updateReverseOverlayVisibility(
                            requestId, generations, visibilityMask, widgetVisible, null);
                    reply.writeNoException();
                    reply.writeString(result("reverse_visibility_update_queued", null));
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
            return discoverCameraWithResult() == DiscoveryResult.READY;
        }

        synchronized DiscoveryResult discoverCameraWithResult() {
            terminalUnsupportedDiscovery = false;
            return discoverCameraInternal();
        }

        synchronized DiscoveryResult retryCameraDiscovery() {
            return terminalUnsupportedDiscovery
                    ? DiscoveryResult.TERMINAL_UNSUPPORTED_ID : discoverCameraInternal();
        }

        private DiscoveryResult discoverCameraInternal() {
            if (cameraRuntime != null) {
                return cameraRuntime.connect()
                        ? DiscoveryResult.READY : DiscoveryResult.RETRYABLE_FAILURE;
            }
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
                if (isUnsafeBmmCameraId(cameraId)) {
                    // Leopard 5 / DiLink 5.1 (MT8673, Huawei MDC AVM): pano_h maps to 10000.
                    // bmmcameraserver routes 10000 to NormalHal3CamDevice::open(), which
                    // sprintf()s the id into a 4-byte buffer -> FORTIFY abort, killing the
                    // service and every other client (DVR, DMS). Never open it.
                    int rejectedId = cameraId;
                    String rejectedTag = cameraTag;
                    cameraId = -1;
                    cameraTag = "none";
                    IllegalStateException error = new IllegalStateException(
                            "Unsupported AVM camera " + rejectedTag
                            + " id " + rejectedId + " (MDC/IP-camera platform; opening it"
                            + " crashes bmmcameraserver)");
                    terminalUnsupportedDiscovery = true;
                    discoveryError = summary(error);
                    emit("camera_discovery", "ok", false, "error", discoveryError);
                    return DiscoveryResult.TERMINAL_UNSUPPORTED_ID;
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
                return DiscoveryResult.READY;
            } catch (Throwable error) {
                discoveryError = summary(error);
                emit("camera_discovery", "ok", false, "error", discoveryError);
                return DiscoveryResult.RETRYABLE_FAILURE;
            }
        }

        private boolean registerCallback(
                IBinder newCallback, long registrationGeneration) throws RemoteException {
            if (newCallback == null) throw new IllegalArgumentException("Callback is null");
            if (!callbacks.register(newCallback, registrationGeneration)) return false;
            try {
                newCallback.linkToDeath(
                        () -> callbackHandler.post(() -> disconnectCallback(
                                newCallback, registrationGeneration)), 0);
            } catch (RemoteException error) {
                callbacks.detach(newCallback, registrationGeneration);
                throw error;
            }
            emit("helper_connected", "uid", Process.myUid());
            if (cameraRuntime != null) cameraRuntime.reportStatus();
            // onTransact holds this callback lock until the snapshot is sent.
            turnController.reportCameraShellState();
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
            if (persistentSession.vehiclePowerOff) {
                releaseSurface(requestedSurface);
                return result("camera_error", "vehicle_power_off");
            }
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
                        stockInput, null, "open", stockInput);
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
                opened = openAvmCamera(avm, requestedCameraId);

                Class<?> callbackType = Class.forName("android.hardware.AVMCamera$IEventCallback");
                Object callbackProxy = Proxy.newProxyInstance(
                        callbackType.getClassLoader(), new Class<?>[]{callbackType}, eventHandler(opened));
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
            return openReverseCamera(requestedSurfaces,
                    reverseIndexes(requestedSurfaces == null ? 0 : requestedSurfaces.length),
                    requestId);
        }

        String openReverseCamera(
                Surface[] requestedSurfaces, int[] requestedIndexes, int requestId) {
            return openReverseCamera(requestedSurfaces, requestedIndexes, requestId, null);
        }

        String openReverseCamera(Surface[] requestedSurfaces, int[] requestedIndexes,
                int requestId, boolean[] initialActive) {
            if (requestId <= 0) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("reverse request id required");
            }
            return openReverseCamera(
                    requestedSurfaces, requestedIndexes, CAMERA_OWNER_REVERSE, requestId, initialActive);
        }

        private synchronized String openReversePreview(Surface[] requestedSurfaces, int requestId) {
            if (requestedSurfaces == null
                    || (requestedSurfaces.length != 4 && requestedSurfaces.length != 5)) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException(
                        "Pano base plus three or four reverse Surfaces required");
            }
            Surface panoOutput = requestedSurfaces[0];
            int directCount = requestedSurfaces.length - 1;
            Surface[] directSurfaces = Arrays.copyOfRange(
                    requestedSurfaces, 1, requestedSurfaces.length);
            return openReversePreview(
                    panoOutput, directSurfaces, reverseIndexes(directCount), requestId);
        }

        private synchronized String openReversePreview(
                Surface panoOutput, Surface[] directSurfaces,
                int[] directIndexes, int requestId) {
            String validation = validateReversePreviewTargets(
                    panoOutput, directSurfaces, directIndexes);
            if (requestId <= 0 || validation != null) {
                releaseSurface(panoOutput);
                releaseSurfaces(directSurfaces);
                throw new IllegalArgumentException(validation == null
                        ? "reverse request id required" : validation);
            }
            if (reverseGroup.has()) {
                releaseSurface(panoOutput);
                releaseSurfaces(directSurfaces);
                emit("camera_error", "stage", "reverse_preview_owner_busy",
                        "camera_tag", "pano_h", "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "request_id", requestId,
                        "producer_epoch", producerEpoch,
                        "error", "reverse camera owns AVM");
                return result("camera_busy", "reverse camera owns AVM", -1, "pano_h");
            }
            if (panoOutput == null) {
                return attachPersistentGroup(
                        activityGroup, directSurfaces, directIndexes, requestId,
                        "reverse_preview_direct", false, false,
                        null, "reverse_preview_direct_open");
            }
            // A prior stock shell failure is terminal for that request only.  Do not let a
            // delayed error from it be mistaken for a newly opened background request.
            reverseStockRetiredRequestId = 0;
            reverseStockRetiredProducerEpoch = 0;
            reverseStockAvmShellEpoch = 0L;
            // The direct panes own the persistent producer.  The stock background is an
            // optional second consumer and must not gate these panes on its config callback.
            String directResult;
            try {
                directResult = attachPersistentGroup(
                        activityGroup, directSurfaces, directIndexes, requestId,
                        "reverse_preview_with_stock_base", false, false,
                        null, "reverse_preview_direct_open");
            } catch (RuntimeException | Error error) {
                // attachPersistentGroup owns the direct array on validation/open failure;
                // panoOutput is intentionally separate until the stock request is queued.
                releaseSurface(panoOutput);
                throw error;
            }
            if (isCameraErrorResult(directResult) || isCameraBusyResult(directResult)) {
                releaseSurface(panoOutput);
                return directResult;
            }
            reverseStockActive = true;
            reverseStockInputAttached = false;
            reverseStockRequestId = requestId;
            reverseStockProducerEpoch = producerEpoch;
            stockRequest.begin(requestId);
            pendingReversePreviewRequestId = requestId;
            pendingReversePreviewSurfaces = new Surface[0];
            viewName = "reverse_preview_with_stock_base";
            final int callbackProducerEpoch = producerEpoch;
            turnController.openStockAvm(panoOutput, StockAvmPreview.horizontalViewpoint(3), true,
                    false, requestId,
                    inputSurface -> attachReversePreviewInputSurface(
                            inputSurface, requestId, callbackProducerEpoch));
            emit("camera_shell_request", "action", "open_reverse_preview_base",
                    "view", "VIEW_2D_REAR", "request_id", requestId,
                    "preview_indexes", previewIndexes(true, directIndexes),
                    "component", "reverse_preview_background",
                    "producer_epoch", callbackProducerEpoch);
            return result("reverse_preview_shell_open_queued", null);
        }

        private static String validateReversePreviewTargets(
                Surface panoOutput, Surface[] directSurfaces, int[] directIndexes) {
            if (directSurfaces == null || directIndexes == null
                    || directSurfaces.length < 1 || directSurfaces.length > 4
                    || directSurfaces.length != directIndexes.length) {
                return "one to four direct reverse Surface/index pairs required";
            }
            if (panoOutput != null && !panoOutput.isValid()) {
                return "Pano base Surface is invalid";
            }
            boolean[] seen = new boolean[5];
            for (int i = 0; i < directIndexes.length; i++) {
                int sourceIndex = directIndexes[i];
                Surface surface = directSurfaces[i];
                if (sourceIndex < 1 || sourceIndex > 4 || seen[sourceIndex]) {
                    return "direct reverse source indexes must be a unique subset of 1..4";
                }
                if (surface == null || !surface.isValid()) {
                    return "direct reverse Surface is invalid";
                }
                seen[sourceIndex] = true;
            }
            return null;
        }

        private static String previewIndexes(boolean hasPano, int[] directIndexes) {
            int[] indexes = new int[directIndexes.length + (hasPano ? 1 : 0)];
            int offset = 0;
            if (hasPano) indexes[offset++] = 0;
            System.arraycopy(directIndexes, 0, indexes, offset, directIndexes.length);
            return Arrays.toString(indexes);
        }

        private synchronized void attachReversePreviewInputSurface(
                Surface inputSurface, int callbackRequestId, int callbackProducerEpoch) {
            if (!isCurrentReverseStockCallback(callbackRequestId, callbackProducerEpoch)) {
                inputSurface.release();
                emit("camera_input_surface_ignored", "component",
                        "reverse_preview_background", "view", "reverse_preview_with_stock_base",
                        "request_id", callbackRequestId,
                        "pending_request_id", reverseStockRequestId,
                        "producer_epoch", callbackProducerEpoch,
                        "active_producer_epoch", producerEpoch);
                return;
            }
            int requestId = callbackRequestId;
            int producerEpoch = this.producerEpoch;
            pendingReversePreviewRequestId = 0;
            stockRequest.takeInput(requestId);
            String attachResult;
            try {
                boolean attached = persistentSession.attachStockInput(
                        new ReflectivePersistentCameraPort(camera), activityGroup, inputSurface,
                        requestId, this::emit, producerCameraId, producerEpoch);
                if (!attached) {
                    inputSurface.release();
                    reverseStockFailed(
                            "reverse_preview_stock_attach", requestId, producerEpoch,
                            "stock input addPreviewSurface returned false");
                    return;
                }
                reverseStockInputAttached = true;
                reverseStockRetainedCallbackEpoch = 0;
                attachResult = result("camera_opened", null, activeCameraId, "pano_h");
                emit("stock_avm_input_attached", "component", "reverse_preview_background",
                        "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "request_id", requestId, "producer_epoch", producerEpoch,
                        "callback_producer_epoch", callbackProducerEpoch,
                        "preview_index", 0, "view", activityGroup.view,
                        "preview_indexes", Arrays.toString(activityGroup.indexes));
            } catch (PersistentSessionFailure failure) {
                inputSurface.release();
                if (failure.fatal) {
                    tearDownPersistentProducer(
                            failure.reason, failure.getCause(), failure.shellCloseQueued);
                    return;
                }
                reverseStockFailed(
                        failure.reason, requestId, producerEpoch,
                        failure.getCause() == null ? failure.reason : summary(failure.getCause()));
                return;
            }
            emit("camera_input_surface_attached", "component", "reverse_preview_background",
                    "view", viewName, "result", attachResult,
                    "request_id", requestId, "producer_epoch", producerEpoch,
                    "preview_indexes", Arrays.toString(activityGroup.indexes));
        }

        private synchronized String openReverseCamera(
                Surface[] requestedSurfaces, int[] requestedIndexes,
                String requestedOwner, int requestId, boolean[] initialActive) {
            if (requestedSurfaces == null || requestedIndexes == null
                    || requestedSurfaces.length == 0
                    || requestedSurfaces.length != requestedIndexes.length) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("reverse Surface mappings required");
            }
            for (int index : requestedIndexes) {
                if (index < ReverseCameraLayout.REAR_CAMERA_INDEX
                        || index > 4) {
                    releaseSurfaces(requestedSurfaces);
                    throw new IllegalArgumentException("invalid reverse source index");
                }
            }
            return attachPersistentGroup(
                    reverseGroup, requestedSurfaces, requestedIndexes, requestId,
                    CAMERA_OWNER_REVERSE.equals(requestedOwner)
                            ? "reverse_overlay" : "reverse_preview",
                    true, false, null, "reverse_open", false, initialActive);
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
            if (persistentSession.vehiclePowerOff) {
                releaseSurfaces(requestedSurfaces);
                return result("camera_error", "vehicle_power_off");
            }
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
                opened = openAvmCamera(avm, requestedCameraId);

                Class<?> callbackType = Class.forName("android.hardware.AVMCamera$IEventCallback");
                Object callbackProxy = Proxy.newProxyInstance(
                        callbackType.getClassLoader(), new Class<?>[]{callbackType}, eventHandler(opened));
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
                        ActivityPreviewState.Snapshot<Surface> attachedActivityPreview =
                                activityAttached
                                        ? new ActivityPreviewState.Snapshot<>(
                                                activityPreview.value(),
                                                activityPreview.index(), true, false)
                                        : null;
                        tryClose(opened, requestedSurfaces, indexes, attached,
                                attachedActivityPreview);
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
                Consumer<Integer> prepareResultSink) {
            turnController.prepareCameraOverlay(spec, surfaceSink, prepareResultSink);
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
                Consumer<Boolean> completion) {
            turnController.setCameraOverlayVisible(
                    cameraId, requestId, surfaceGeneration, visible, completion);
        }

        synchronized void setVehiclePowerOff(boolean off) {
            if (persistentSession.vehiclePowerOff == off) return;
            persistentSession.vehiclePowerOff = off;
            persistentSession.clearVehiclePowerOffReconcileFailure();
            emit("camera_vehicle_power_gate", "off", off,
                    "motion_retained", persistentSession.motionDetectionWanted);
            ArrayList<String> retryDirectDetachOwners = new ArrayList<>();
            if (off) {
                cancelReverseWarmExpiry();
                persistentSession.reverseWarmLease.clear();
                // Close only ordinary consumers; Motion owns its detector/recorder independently.
                for (String owner : new String[]{CAMERA_OWNER_ACTIVITY, CAMERA_OWNER_OVERLAY,
                        CAMERA_OWNER_REVERSE, CAMERA_OWNER_PARKING, CAMERA_OWNER_MIRROR}) {
                    String closeResult = closeCameraForOwner(owner, "vehicle_power_off");
                    if (!isSuccessfulCameraCloseResult(closeResult)) {
                        emit("camera_vehicle_power_off_close_unconfirmed",
                                "camera_owner", owner,
                                "producer_epoch", producerEpoch,
                                "close_result", closeResult);
                        if (persistentSession.hasUnconfirmedOffDirectDetach(owner)) {
                            retryDirectDetachOwners.add(owner);
                        }
                    }
                }
            }
            updateSourceDemand("vehicle_power", () -> {
                for (String owner : retryDirectDetachOwners) {
                    String retryResult = closeCameraForOwner(
                            owner, "vehicle_power_off_reconcile");
                    if (!isSuccessfulCameraCloseResult(retryResult)) {
                        emit("camera_vehicle_power_off_close_unconfirmed",
                                "camera_owner", owner,
                                "producer_epoch", producerEpoch,
                                "close_result", retryResult,
                                "retry", true);
                    }
                }
                persistentSession.reconcileIfOpen(persistentPort());
            });
        }

        synchronized void setBlindSourceDemand(boolean rearEnabled, boolean frontEnabled) {
            updateSourceDemand("blind_standby", () -> persistentSession.setBlindStandbyDemand(
                    persistentPort(), CameraSourceDemand.blindStandby(
                            rearEnabled, frontEnabled)));
        }

        synchronized void setMirrorSourceDemand(boolean visible, boolean frontIntegrated) {
            updateSourceDemand("mirror", () -> persistentSession.setMirrorDemand(
                    persistentPort(), CameraSourceDemand.mirror(visible, frontIntegrated)));
        }

        synchronized void clearReverseSourceDemand() {
            cancelReverseWarmExpiry();
            updateSourceDemand("reverse_disabled", () ->
                    persistentSession.disableReverseDemand(persistentPort()));
        }

        private PersistentCameraPort persistentPort() {
            return camera == null || !persistentPanoProducer
                    ? null : new ReflectivePersistentCameraPort(camera);
        }

        synchronized void attachMotionDetector(Surface surface) throws Exception {
            if (surface == null || !surface.isValid()) throw new IllegalArgumentException("motion Surface missing");
            if (persistentSession.motionDetectionSurface != null) {
                surface.release();
                throw new IllegalStateException("motion detector already attached");
            }
            if ((camera != null && !persistentPanoProducer) || unconfirmedOneShotOwner) {
                surface.release();
                throw new IllegalStateException("camera owner busy");
            }
            persistentSession.motionDetectionSurface = surface;
            persistentSession.motionDetectionWanted = true;
            try {
                if (persistentPanoProducer) {
                    requireMotionDimensions(camera);
                    persistentSession.attachMotionInput(persistentPort());
                } else {
                    int id = panoCameraId();
                    if (id < 0) throw new IllegalStateException("pano_h unavailable");
                    String opened = openPersistentProducer(persistentSession.motionGroup,
                            new Surface[0], new int[0], 1, "motion_monitor", false, false,
                            null, "motion_open", id, false, 0, null);
                    if (!persistentPanoProducer) throw new IllegalStateException(opened);
                }
                emit("motion_camera_attached", "preview_index", 4123, "producer_epoch", producerEpoch);
            } catch (Exception failure) {
                if (persistentPanoProducer) tearDownPersistentProducer("motion_attach_failed", failure);
                else persistentSession.clearMotionInput();
                throw failure;
            }
        }

        synchronized int motionProducerEpoch() {
            return persistentPanoProducer && persistentSession.motionDetectionAttached ? producerEpoch : -1;
        }

        synchronized void detachMotionDetector() throws Exception {
            if (persistentSession.motionDetectionSurface == null) return;
            persistentSession.motionDetectionWanted = false;
            if (closePersistentProducerIfIdle("motion_detector_idle") != null) return;
            try {
                if (persistentSession.motionDetectionAttached && persistentPort() != null
                        && !persistentPort().remove(persistentSession.motionDetectionSurface, 4123))
                    throw new IllegalStateException("motion rmPreviewSurface returned false");
                persistentSession.clearMotionInput();
                emit("motion_camera_detached", "producer_epoch", producerEpoch);
            } catch (Exception failure) {
                tearDownPersistentProducer("motion_detach_failed", failure);
                throw failure;
            }
        }

        synchronized void attachMotionRecording(Surface[] surfaces, int requestId) throws Exception {
            if (!persistentPanoProducer || !persistentSession.motionDetectionWanted) {
                releaseSurfaces(surfaces);
                throw new IllegalStateException("motion camera session unavailable");
            }
            MotionRecordingOutput requestedOutput = surfaces == null || surfaces.length == 0
                    ? null : MotionRecordingOutput.find(surfaces[0]);
            String result = attachPersistentGroup(persistentSession.motionGroup, surfaces,
                    new int[]{4, 1, 2, 3}, requestId, "motion_recording", false, false,
                    null, "motion_recording_attach", false);
            if (!persistentSession.motionGroup.has()) throw new IllegalStateException(result);
            motionRecordingOutput = requestedOutput;
        }

        synchronized void detachMotionRecording() {
            if (persistentSession.motionGroup.has()) closePersistentGroup(
                    persistentSession.motionGroup, "motion_recording_stop",
                    persistentSession.motionGroup.requestId);
            if (persistentSession.motionGroup.has())
                throw new IllegalStateException("Motion recording outputs did not detach");
            motionRecordingOutput = null;
        }

        synchronized void attachViewing(Surface[] surfaces, int requestId) throws Exception {
            if (persistentSession.viewingGroup.has()) {
                releaseSurfaces(surfaces);
                throw new IllegalStateException("camera viewer already attached");
            }
            if (camera != null && !persistentPanoProducer || unconfirmedOneShotOwner) {
                releaseSurfaces(surfaces);
                throw new IllegalStateException("camera owner busy");
            }
            MotionRecordingOutput output = MotionRecordingOutput.find(surfaces[0]);
            String result = attachPersistentGroup(persistentSession.viewingGroup, surfaces,
                    new int[]{4, 1, 2, 3}, requestId, "camera_viewing", false, false,
                    null, "camera_viewing_attach", false);
            if (!persistentSession.viewingGroup.has()) throw new IllegalStateException(result);
            viewingOutput = output;
        }

        synchronized void updateViewingOutput(Surface surface, int direction) throws Exception {
            if (viewingOutput == null || !persistentSession.viewingGroup.has()) {
                if (surface != null) surface.release();
                throw new IllegalStateException("camera viewer not attached");
            }
            viewingOutput.updateViewingOutput(surface, direction);
        }

        synchronized void detachViewing() {
            if (persistentSession.viewingGroup.has()) closePersistentGroup(
                    persistentSession.viewingGroup, "camera_viewing_stop",
                    persistentSession.viewingGroup.requestId);
            if (persistentSession.viewingGroup.has())
                throw new IllegalStateException("Camera viewer did not detach");
            viewingOutput = null;
        }

        private synchronized String openMotionCompositionPreview(
                Surface surface, int direction, int requestId) {
            final int sourceIndex;
            final ConsumerGroup group;
            try {
                sourceIndex = MotionDirectionIcon.sourceIndexFor(direction);
                group = persistentSession.motionPreviewGroup(direction);
            } catch (IllegalArgumentException invalidDirection) {
                releaseSurfaces(new Surface[]{surface});
                throw invalidDirection;
            }
            if (surface == null || !surface.isValid()) {
                releaseSurfaces(new Surface[]{surface});
                throw new IllegalArgumentException("motion preview Surface missing");
            }
            // Do not take over an unrelated one-shot owner to start this passive tile.
            if (camera != null && !persistentPanoProducer || unconfirmedOneShotOwner) {
                releaseSurfaces(new Surface[]{surface});
                return result("camera_error", "camera owner busy", -1, "pano_h");
            }
            if (group.has()) {
                if (group.requestId == requestId) {
                    releaseSurfaces(new Surface[]{surface});
                    return result("camera_opened", null, activeCameraId, "pano_h");
                }
                closePersistentGroup(group, "motion_preview_replaced", group.requestId);
                if (group.has()) {
                    releaseSurfaces(new Surface[]{surface});
                    return result("camera_error", "previous motion preview still attached",
                            activeCameraId, "pano_h");
                }
            }
            String opened = attachPersistentGroup(group, new Surface[]{surface},
                    new int[]{sourceIndex}, requestId, "motion_composition_preview", false,
                    false, null, "motion_preview_attach", false);
            if (!group.has()) return opened;
            emit("motion_preview_attached", "direction", direction,
                    "preview_index", sourceIndex, "request_id", requestId,
                    "producer_epoch", producerEpoch);
            return opened;
        }

        private synchronized String closeMotionCompositionPreview(
                int direction, int requestId, String reason) {
            ConsumerGroup group = persistentSession.motionPreviewGroup(direction);
            if (!group.has()) return result("already_closed", null);
            if (group.requestId != requestId) {
                return result("camera_close_ignored", null, activeCameraId, "pano_h");
            }
            String closed = closePersistentGroup(group, reason, requestId);
            emit("motion_preview_detached", "direction", direction,
                    "request_id", requestId, "producer_epoch", producerEpoch);
            return closed;
        }

        /** Binder-owner death cleanup detaches only the four editor surfaces. */
        synchronized void detachMotionCompositionPreviews() {
            for (int direction = 0; direction < 4; direction++) {
                ConsumerGroup group = persistentSession.motionPreviewGroup(direction);
                if (group.has()) closeMotionCompositionPreview(
                        direction, group.requestId, "motion_preview_client_died");
            }
        }

        private static void requireMotionDimensions(Object target) throws Exception {
            Class<?> type = target.getClass();
            int width = (Integer) type.getMethod("getPreviewWidth").invoke(target);
            int height = (Integer) type.getMethod("getPreviewHeight").invoke(target);
            if (width != 7680 || height != 1300)
                throw new IllegalStateException("unverified motion source " + width + "x" + height);
        }

        private void updateSourceDemand(String owner, DemandUpdate update) {
            try {
                update.run();
                closePersistentProducerIfIdle("source_demand_idle");
            } catch (Throwable error) {
                emit("camera_error", "stage", "source_demand_reconcile_failed",
                        "camera_owner", owner, "producer_epoch", producerEpoch,
                        "error", summary(error));
                if (persistentSession.vehiclePowerOff) {
                    emit("camera_error", "stage", "vehicle_power_off_source_reconcile_failed",
                            "camera_owner", owner, "producer_epoch", producerEpoch,
                            "error_stack", android.util.Log.getStackTraceString(error));
                } else {
                    tearDownPersistentProducer("source_demand_reconcile_failed", error);
                }
            }
            if (persistentSession.vehiclePowerOff) {
                Throwable powerOffFailure =
                        persistentSession.takeVehiclePowerOffReconcileFailure();
                if (powerOffFailure != null) {
                    emit("camera_error", "stage", "vehicle_power_off_source_reconcile_failed",
                            "camera_owner", owner, "producer_epoch", producerEpoch,
                            "error", summary(powerOffFailure),
                            "error_stack", android.util.Log.getStackTraceString(powerOffFailure));
                }
            }
        }

        private interface DemandUpdate {
            void run() throws Exception;
        }

        synchronized void setOverlayTargetActive(Surface target, boolean active) throws Exception {
            persistentSession.setActive(overlayGroup, target, active);
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

        void prepareParkingOverlayWindow(
                CameraShellProtocol.OverlaySpec spec,
                Consumer<TurnSignalController.OverlaySurface> surfaceSink,
                Consumer<Integer> prepareResultSink) {
            turnController.prepareCameraOverlay(spec, surfaceSink, prepareResultSink);
        }

        void armParkingOverlayFirstFrame(OverlayFrameArm arm) {
            turnController.armCameraOverlayFrame(arm);
        }

        void setParkingOverlayWindowVisible(
                int cameraId, int requestId, int surfaceGeneration, boolean visible,
                Consumer<Boolean> completion) {
            turnController.setCameraOverlayVisible(
                    cameraId, requestId, surfaceGeneration, visible, completion);
        }

        void closeParkingOverlayWindows(String reason) {
            for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
                turnController.closeCameraOverlay(
                        CameraOverlayProfile.overlayIdForParking(profile.id), reason);
            }
        }

        synchronized void setParkingTargetActive(Surface target, boolean active) throws Exception {
            persistentSession.setActive(parkingGroup, target, active);
        }

        synchronized void setReverseTargetActive(Surface target, boolean active) throws Exception {
            persistentSession.setActive(reverseGroup, target, active);
        }

        /** Applies an Activity-owned Reverse visibility mask to its currently attached group. */
        private synchronized boolean updateActivityReverseVisibility(
                int requestId, int[] generations, int visibilityMask,
                boolean widgetVisible) throws Exception {
            if (!activityGroup.attached || !activityGroup.has()) return false;
            if (activityGroup.requestId != requestId
                    || !isActivityReversePreviewView(activityGroup.view)
                    || activityGroup.indexes.length != activityGroup.surfaces.length
                    || generations == null || generations.length < 1
                    || generations.length > 4) {
                throw new IllegalStateException("reverse visibility request is stale");
            }
            boolean hasPanoBase =
                    "reverse_preview_with_stock_base".equals(activityGroup.view);
            boolean[] seen = new boolean[5];
            boolean stockInput = false;
            int directCount = 0;
            for (int sourceIndex : activityGroup.indexes) {
                if (sourceIndex == 0) {
                    if (!hasPanoBase || stockInput) throw new IllegalStateException(
                            "reverse visibility request is stale");
                    stockInput = true;
                    continue;
                }
                if (sourceIndex < 1 || sourceIndex > 4 || seen[sourceIndex]) {
                    throw new IllegalStateException("reverse visibility request is stale");
                }
                seen[sourceIndex] = true;
                directCount++;
            }
            if (directCount != generations.length) {
                throw new IllegalStateException("reverse visibility request is stale");
            }
            for (int generation : generations) {
                if (generation <= 0) {
                    throw new IllegalStateException("reverse visibility request is stale");
                }
            }
            // Source indexes are stable whether the optional stock input 0 is attached.  Only
            // the three mask-backed direct targets change here; optional Front (index 4) keeps
            // its selector-owned state.
            for (int sourceIndex : activityGroup.indexes) {
                if (sourceIndex < ReverseCameraLayout.REAR_CAMERA_INDEX
                        || sourceIndex > ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) continue;
                int position = activityGroup.indexOfIndex(sourceIndex);
                if (position < 0 || activityGroup.surfaces[position] == null
                        || !activityGroup.surfaces[position].isValid()) {
                    throw new IllegalStateException("reverse visibility request is stale");
                }
                persistentSession.setActive(
                        activityGroup, activityGroup.surfaces[position],
                        ReverseCameraLayout.isVisible(visibilityMask, sourceIndex));
            }
            int configuredDemand = CameraSourceDemand.reverse(
                    visibilityMask,
                    ReverseCameraController.loadCentralFrontIntegrated(settings),
                    widgetVisible,
                    ReverseCameraController.switchByGear(settings));
            try {
                persistentSession.setGroupSourceDemand(
                        persistentPort(), activityGroup, configuredDemand);
            } catch (Throwable error) {
                emit("camera_error", "stage", "source_demand_reconcile_failed",
                        "camera_owner", CAMERA_OWNER_ACTIVITY,
                        "request_id", requestId, "producer_epoch", producerEpoch,
                        "error", summary(error));
                tearDownPersistentProducer("source_demand_reconcile_failed", error);
                throw error instanceof Exception
                        ? (Exception) error : new Exception(error);
            }
            closePersistentProducerIfIdle("source_demand_idle");
            // The widget itself has no fan-out target; widget visibility only affects whether
            // central Front can be selected and therefore whether source 4 remains demanded.
            return true;
        }

        synchronized String openParkingCameras(
                Surface[] requestedSurfaces, int[] indexes, int requestId) {
            if (requestedSurfaces == null || indexes == null
                    || requestedSurfaces.length != indexes.length
                    || requestedSurfaces.length == 0) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("parking Surface/index count mismatch");
            }
            return attachPersistentGroup(
                    parkingGroup, requestedSurfaces, indexes, requestId,
                    "parking_overlay", false, false, null, "parking_open");
        }

        synchronized String closeParkingCameras(String reason, int expectedRequestId) {
            return closeCameraForOwner(CAMERA_OWNER_PARKING, reason, expectedRequestId);
        }

        synchronized String openMirrorCamera(Surface surface, int cameraIndex, int requestId) {
            if (cameraIndex != RearviewMirrorSettings.REAR_CAMERA_INDEX
                    && cameraIndex != RearviewMirrorSettings.FRONT_CAMERA_INDEX) {
                throw new IllegalArgumentException("invalid mirror camera index");
            }
            return attachPersistentGroup(mirrorGroup, new Surface[]{surface},
                    new int[]{cameraIndex}, requestId,
                    "rearview_mirror", false, false, null, "mirror_open");
        }

        synchronized String closeMirrorCamera(String reason, int expectedRequestId) {
            return closeCameraForOwner(CAMERA_OWNER_MIRROR, reason, expectedRequestId);
        }

        void prepareReverseOverlayWindow(
                CameraShellProtocol.ReverseOverlaySpec spec,
                Consumer<TurnSignalController.ReverseSurfaces> surfaceSink,
                Runnable preparedSink) {
            turnController.prepareReverseOverlay(spec, surfaceSink, preparedSink);
        }

        void acquireReverseSurfacesForFallback(int requestId) {
            turnController.acquireReverseSurfacesForFallback(requestId);
        }

        void armReverseOverlayFrames(int requestId, int[] generations) {
            armReverseOverlayFrames(requestId, CameraDisplayTarget.TABLET, generations);
        }

        void armReverseOverlayFrames(
                int requestId, int displayTarget, int[] generations) {
            turnController.armReverseOverlayFrames(requestId, displayTarget, generations);
        }

        void setReverseOverlayVisible(
                int requestId, int[] generations, boolean visible,
                Consumer<Boolean> completion) {
            setReverseOverlayVisible(requestId, CameraDisplayTarget.TABLET,
                    generations, visible, completion);
        }

        void setReverseOverlayVisible(
                int requestId, int displayTarget, int[] generations, boolean visible,
                Consumer<Boolean> completion) {
            turnController.setReverseOverlayVisible(
                    requestId, displayTarget, generations, visible, completion);
        }

        void setReverseOverlayVisible(
                int requestId, int displayTarget, int[] generations, boolean visible,
                long expectedHelperEpoch, Consumer<Boolean> completion) {
            turnController.setReverseOverlayVisible(
                    requestId, displayTarget, generations, visible,
                    expectedHelperEpoch, completion);
        }

        void invalidateReverseOverlayShow(int requestId, int displayTarget, String reason) {
            turnController.invalidateReverseOverlayShow(requestId, displayTarget, reason);
        }

        void invalidateReverseOverlayShows(int requestId, String reason) {
            turnController.invalidateReverseOverlayShows(requestId, reason);
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
            if (persistentSession.vehiclePowerOff) {
                releaseSurface(requestedSurface);
                return result("camera_error", "vehicle_power_off");
            }
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
            if (requestId <= 0) {
                requestedSurface.release();
                throw new IllegalArgumentException("camera request id required");
            }
            stockRequest.begin(requestId);
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
            if (!stockRequest.takeInput(callbackRequestId)) {
                inputSurface.release();
                emit("camera_input_surface_ignored", "view", viewName,
                        "request_id", callbackRequestId,
                        "pending_request_id", stockRequest.pendingId());
                return;
            }
            int requestId = callbackRequestId;
            boolean attached = false;
            try {
                String attachResult = openCamera(
                        inputSurface, 0, "stock_avm_input", false, true,
                        cameraId, cameraTag, CAMERA_OWNER_ACTIVITY, requestId);
                attached = !isCameraErrorResult(attachResult) && !isCameraBusyResult(attachResult);
                emit("camera_input_surface_attached", "view", viewName,
                        "result", attachResult);
            } finally {
                // The SDK is already open even when its input cannot join our producer.
                if (!attached) cancelPendingStock("stock_avm_input_attach_failed", requestId);
            }
        }

        synchronized void recoverCameraSourceAfterWake() {
            if (persistentPanoProducer) tearDownPersistentProducer(VEHICLE_WAKE_RECOVERY, null);
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
                        ? reverseGroup : activityGroup.has() ? activityGroup
                        : overlayGroup.has() ? overlayGroup
                        : parkingGroup.has() ? parkingGroup : mirrorGroup;
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
            boolean closeStock = stockRequest.isActive();
            int stockRequestId = stockRequest.isActive()
                    ? stockRequest.id() : activeCameraRequestId;
            String activeView = viewName == null ? "unknown" : viewName;
            stockRequest.clear();
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
            if (activeCamera != null) {
                Throwable first = null;
                try {
                    if (activeMultiSurfaces.length > 0) {
                        tryClose(activeCamera, activeMultiSurfaces,
                                activeMultiIndexes, null, activeActivityPreview);
                    } else {
                        tryClose(activeCamera, activeSurface, activeIndex,
                                activeActivityPreview);
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
            releaseSurfaces(pendingReverseSurfaces);
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
                            && stockRequest.matches(expectedRequestId)) {
                        boolean shellCloseQueued = cancelPendingStock(reason, expectedRequestId);
                        return result(persistentCloseResultKind(shellCloseQueued), null,
                                activeCameraId, activeCameraTag);
                    }
                    return result("already_closed", null);
                }
                if (shouldRetireSourceForTimeout(group.requestId, expectedRequestId, reason)) {
                    emit("camera_source_retired", "reason", reason,
                            "owner", expectedOwner, "request_id", expectedRequestId);
                    return tearDownPersistentProducer(NATIVE_SOURCE_RECOVERY, null);
                }
                return closePersistentGroup(group, reason, expectedRequestId);
            }
            if (CAMERA_OWNER_ACTIVITY.equals(expectedOwner)
                    && activityPreview.has()) {
                return detachActivityPreview(reason);
            }
            if (camera == null && !stockRequest.isActive()) {
                return result("already_closed", null);
            }
            String owner = stockRequest.isActive() && camera == null
                    ? CAMERA_OWNER_ACTIVITY : activeCameraOwner;
            if (!expectedOwner.equals(owner)) {
                emit("camera_close_ignored", "reason", "owner_mismatch",
                        "expected_owner", expectedOwner, "active_owner", owner,
                        "request_reason", reason == null ? "unknown" : reason);
                return result("camera_close_ignored", null, activeCameraId, activeCameraTag);
            }
            int closeRequestId = cameraRequestIdForClose(
                    stockRequest.isActive(), stockRequest.id(),
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

        static boolean shouldRetireSourceForTimeout(int activeRequest, int requested, String reason) {
            return activeRequest > 0 && activeRequest == requested && reason != null
                    && (reason.startsWith("first_frame_timeout")
                    || reason.startsWith("production_first_frame_timeout"));
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
            return attachPersistentGroup(target, requestedSurfaces, requestedIndexes, requestId,
                    requestedView, exclusive, shellOwned, profileIds, errorStage,
                    firstSurfaceDirect, null);
        }

        private synchronized String attachPersistentGroup(
                ConsumerGroup target, Surface[] requestedSurfaces, int[] requestedIndexes,
                int requestId, String requestedView, boolean exclusive, boolean shellOwned,
                int[] profileIds, String errorStage, boolean firstSurfaceDirect,
                boolean[] initialActive) {
            if (persistentSession.vehiclePowerOff && !persistentSession.isMotionGroup(target)
                    && target != persistentSession.viewingGroup) {
                releaseSurfaces(requestedSurfaces);
                return result("camera_error", "vehicle_power_off");
            }
            String validation = validateBatch(requestedSurfaces, requestedIndexes);
            if (initialActive != null && (requestedSurfaces == null
                    || initialActive.length != requestedSurfaces.length)) {
                validation = "initial activity/Surface batch length mismatch";
            }
            if (validation != null) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException(validation);
            }
            if ((target == activityGroup || target == reverseGroup || target == parkingGroup
                    || target == mirrorGroup)
                    && requestId <= 0) {
                releaseSurfaces(requestedSurfaces);
                throw new IllegalArgumentException("camera request id required");
            }
            if (persistentAttachBlocked(
                    target == overlayGroup, target == parkingGroup || target == mirrorGroup,
                    reverseGroup.has(), activityGroup.has() && activityGroup.exclusive)) {
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

            int sourceDemandMask = sourceDemandMaskFor(
                    target, requestedView, requestedIndexes, firstSurfaceDirect);

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
                        errorStage, requestedCameraId, firstSurfaceDirect,
                        sourceDemandMask, initialActive);
            }

            try {
                if (target == reverseGroup && rawSourceHub != null) {
                    // Reused producers also need a bounded post-open measurement window.
                    rawSourceHub.collectStatsForWindow(0);
                }
                persistentSession.attach(
                        new ReflectivePersistentCameraPort(camera), target,
                        requestedSurfaces, requestedIndexes, requestId,
                        requestedView, exclusive, shellOwned,
                        this::emit, this::cancelPendingStock,
                        requestedCameraId, producerEpoch, firstSurfaceDirect,
                        sourceDemandMask, initialActive);
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
                String restoredCleanup = null;
                if (target == activityGroup
                        && "consumer_attach_failed".equals(error.reason)
                        && activityGroup.has() && activityGroup.requestId != requestId) {
                    int restoredRequestId = activityGroup.requestId;
                    restoredCleanup = closePersistentGroup(
                            activityGroup, "failed_activity_switch_cleanup",
                            restoredRequestId);
                    String cleanupKind;
                    try {
                        cleanupKind = new JSONObject(restoredCleanup).optString("kind");
                    } catch (Throwable invalidCleanupResult) {
                        cleanupKind = restoredCleanup;
                    }
                    if (!"camera_closed".equals(cleanupKind)
                            && !"stock_avm_shell_close_queued".equals(cleanupKind)
                            && !"already_closed".equals(cleanupKind)) {
                        String forcedCleanup = tearDownPersistentProducer(
                                "failed_activity_switch_cleanup", error.getCause());
                        restoredCleanup = restoredCleanup + "; forced=" + forcedCleanup;
                    }
                    emit("camera_switch_cleanup",
                            "camera_owner", target.owner,
                            "failed_request_id", requestId,
                            "restored_request_id", restoredRequestId,
                            "result", restoredCleanup);
                }
                emit("camera_error", "stage", errorStage,
                        "camera_id", requestedCameraId,
                        "camera_tag", "pano_h",
                        "camera_owner", target.owner,
                        "request_id", requestId,
                        "preview_indexes", Arrays.toString(requestedIndexes),
                        "producer_epoch", producerEpoch,
                        "restored_cleanup", restoredCleanup == null ? "" : restoredCleanup,
                        "error", message);
                return result("camera_error", message, requestedCameraId, "pano_h");
            }
            if (isReverseGroup(target, requestedView)) cancelReverseWarmExpiry();
            refreshPersistentLegacyState();
            emitConsumerOpened(target, profileIds, false);
            return result("camera_opened", null, activeCameraId, activeCameraTag);
        }

        private int sourceDemandMaskFor(
                ConsumerGroup target, String requestedView,
                int[] requestedIndexes, boolean firstSurfaceDirect) {
            int available = CameraSourceDemand.fromIndexes(
                    requestedIndexes, firstSurfaceDirect);
            if (!isReverseGroup(target, requestedView)) return available;
            int configured = CameraSourceDemand.reverse(
                    ReverseCameraController.loadVisibilityMask(settings),
                    ReverseCameraController.loadCentralFrontIntegrated(settings),
                    ReverseCameraController.loadWidgetVisible(settings),
                    ReverseCameraController.switchByGear(settings));
            return available & configured;
        }

        private static boolean isReverseGroup(ConsumerGroup target, String view) {
            return target != null && (CAMERA_OWNER_REVERSE.equals(target.owner)
                    || CAMERA_OWNER_ACTIVITY.equals(target.owner)
                    && isActivityReversePreviewView(view));
        }

        private static boolean isActivityReversePreviewView(String view) {
            return "reverse_preview_with_stock_base".equals(view)
                    || "reverse_preview_direct".equals(view);
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
                boolean firstSurfaceDirect,
                int sourceDemandMask, boolean[] initialActive) {
            Object opened = null;
            PersistentCameraPort openedPort = null;
            DirectCameraSourceHub openedHub = null;
            int openedProducerEpoch = producerEpoch + 1;
            try {
                Class<?> avm = Class.forName("android.hardware.AVMCamera");
                opened = openAvmCamera(avm, requestedCameraId);
                if (persistentSession.motionDetectionWanted) requireMotionDimensions(opened);
                Class<?> callbackType = Class.forName("android.hardware.AVMCamera$IEventCallback");
                Object callbackProxy = Proxy.newProxyInstance(
                        callbackType.getClassLoader(), new Class<?>[]{callbackType}, eventHandler(opened));
                avm.getMethod("setEventCallback", callbackType).invoke(opened, callbackProxy);
                PersistentCameraPort port = new ReflectivePersistentCameraPort(opened);
                openedPort = port;
                int openedHubGeneration = ++sourceHubGeneration;
                openedHub = DirectCameraSourceHub.create(renderingContext, new DirectCameraSourceHub.Listener() {
                    @Override
                    public void onConsumerFailure(
                            Surface failedSurface, int index, int failedRequestId,
                            int failedProducerEpoch, Throwable error) {
                        callbackHandler.post(() -> acceptRawConsumerFailure(
                                openedHubGeneration, failedSurface, index,
                                failedRequestId, failedProducerEpoch, error));
                    }

                    @Override
                    public void onSourceFailure(int index, Throwable error) {
                        callbackHandler.post(() -> acceptRawSourceFailure(
                                openedHubGeneration, index, error));
                    }

                    @Override
                    public void onStats(int index, DirectCameraSourceHub.Stats stats) {
                        long observedAt = SystemClock.elapsedRealtime();
                        long processCpuMs = Process.getElapsedCpuTime();
                        Runnable report = () -> {
                            stats.sourceRetired |= sourceHubGeneration != openedHubGeneration
                                    || !persistentPanoProducer;
                            if (stats.acquisitionMetrics == null
                                    && (sourceHubGeneration != openedHubGeneration
                                    || !persistentPanoProducer)) return;
                            boolean extended = DiagnosticLogPolicy.extended();
                            if (!extended && !stats.fpsWindow && !stats.sourceClosed) return;
                            double measuredNewBufferFps = ratePerSecond(stats.callbacks
                                    - stats.producerTimestampRepeated
                                    - stats.producerTimestampInvalid, stats.intervalNs);
                            if ((stats.fpsWindow || stats.sourceClosed) && !stats.outputStage) {
                                Object[] fields = withAcquisitionMetrics(new Object[]{
                                        "producer_epoch", openedProducerEpoch,
                                        "fps_window", stats.fpsWindow,
                                        "measured_new_buffer_fps", measuredNewBufferFps,
                                        "new_buffer_fps_valid", stats.producerTimestampInvalid == 0,
                                        "fps_window_id", stats.fpsWindowId,
                                        "fps_requested_elapsed_ns", stats.fpsRequestedNs,
                                        "fps_first_frame_elapsed_ns", stats.fpsFirstFrameNs,
                                        "sample_elapsed_ms", observedAt,
                                        "interval_ms", milliseconds(stats.intervalNs),
                                        "sample_interval_ms", milliseconds(stats.intervalNs),
                                        "preview_index", index,
                                        "callbacks", stats.callbacks,
                                        "frame_signals", stats.frameSignals,
                                        "coalesced_frames", stats.coalescedFrames,
                                        "producer_timestamp_repeated", stats.producerTimestampRepeated,
                                        "producer_timestamp_invalid", stats.producerTimestampInvalid,
                                        "callback_gap_avg_ms", averageMs(
                                                stats.callbackGapTotalNs, stats.callbackGaps),
                                        "callback_gap_max_ms", milliseconds(stats.callbackGapMaxNs),
                                        "processed_fps", ratePerSecond(
                                                stats.callbacks, stats.intervalNs)},
                                        stats, openedHubGeneration);
                                emit("camera_fps_window_stats", fields);
                            }
                            if (extended || (stats.outputStage && stats.fpsWindow)) {
                                Object[] fields = withAcquisitionMetrics(new Object[]{
                                    "stage", stats.outputStage ? "output" : "acquisition",
                                    "producer_epoch", openedProducerEpoch,
                                    "fps_window", stats.fpsWindow,
                                    "fps_window_id", stats.fpsWindowId,
                                    "dropped_snapshots", stats.droppedSnapshots,
                                    "output_age_max_ms", milliseconds(stats.outputAgeMaxNs),
                                    "sample_elapsed_ms", observedAt,
                                    "sample_interval_ms", milliseconds(stats.intervalNs),
                                    "process_cpu_time_ms", processCpuMs,
                                    "process_pid", Process.myPid(),
                                    "new_buffer_fps", measuredNewBufferFps,
                                    "output_fps", stats.outputStage
                                            ? ratePerSecond(stats.renderedFrames, stats.intervalNs) : 0.0d,
                                    "new_buffer_fps_valid", stats.producerTimestampInvalid == 0,
                                    "preview_index", index,
                                    "source_width", stats.sourceWidth,
                                    "source_height", stats.sourceHeight,
                                    "interval_ms", milliseconds(stats.intervalNs),
                                    "worker", stats.workerName,
                                    "callbacks", stats.callbacks,
                                    "frame_signals", stats.frameSignals,
                                    "rendered_frames", stats.renderedFrames,
                                    "coalesced_frames", stats.coalescedFrames,
                                    "queue_delay_avg_ms", averageMs(
                                            stats.queueDelayTotalNs, stats.renderedFrames),
                                    "queue_delay_max_ms", milliseconds(
                                            stats.queueDelayMaxNs),
                                    "processed_fps", ratePerSecond(
                                            stats.callbacks, stats.intervalNs),
                                    "callback_gap_avg_ms", averageMs(
                                            stats.callbackGapTotalNs, stats.callbackGaps),
                                    "callback_gap_max_ms", milliseconds(
                                            stats.callbackGapMaxNs),
                                    "producer_timestamp_clock", "unverified",
                                    "producer_timestamp_repeated", stats.producerTimestampRepeated,
                                    "producer_timestamp_invalid", stats.producerTimestampInvalid,
                                    "update_tex_image_avg_ms", averageMs(
                                            stats.updateTotalNs, stats.callbacks),
                                    "update_tex_image_max_ms", milliseconds(
                                            stats.updateMaxNs),
                                    "draw_swaps", stats.swaps,
                                    "pre_swap_avg_ms", averageMs(
                                            stats.preSwapTotalNs, stats.swaps),
                                    "pre_swap_max_ms", milliseconds(stats.preSwapMaxNs),
                                    "swap_wait_avg_ms", averageMs(
                                            stats.swapWaitTotalNs, stats.swaps),
                                    "swap_wait_max_ms", milliseconds(stats.swapWaitMaxNs),
                                    "draw_swap_avg_ms", averageMs(
                                            stats.preSwapTotalNs + stats.swapWaitTotalNs,
                                            stats.swaps),
                                    "draw_swap_max_ms", milliseconds(stats.drawMaxNs),
                                    "render_avg_ms", averageMs(
                                            stats.renderTotalNs, stats.callbacks),
                                    "render_max_ms", milliseconds(stats.renderMaxNs),
                                    "targets_current", stats.targetsCurrent,
                                    "targets_max", stats.targetsMax,
                                    "target_pixels_current", stats.targetPixelsCurrent,
                                    "target_pixels_max", stats.targetPixelsMax,
                                    "target_width_max", stats.targetWidthMax,
                                    "target_height_max", stats.targetHeightMax,
                                    "target_dimensions", stats.targetDimensions},
                                        stats, openedHubGeneration);
                                emit(stats.outputStage ? "camera_source_output_stats"
                                        : "camera_source_hub_stats", fields);
                            }
                        };
                        if (!callbackHandler.post(report) && stats.sourceClosed) {
                            Log.w(TAG, "camera acquisition close stats handler rejected: producer_epoch="
                                    + openedProducerEpoch + ", source_hub_generation="
                                    + openedHubGeneration + ", preview_index=" + index);
                        }
                    }

                    @Override
                    public void onCommandStats(CameraCommandMetrics.Report report) {
                        long observedAt = SystemClock.elapsedRealtime();
                        Runnable emitReport = () -> {
                            CameraCommandMetrics.Context last = report.lastContext;
                            CameraCommandMetrics.Context worst = report.worstContext;
                            CameraCommandMetrics.Context failed = report.lastFailureContext;
                            ArrayList<Object> fields = new ArrayList<>();
                            fields.add("operation"); fields.add(report.operation.key);
                            fields.add("worker"); fields.add(report.workerIndex < 0 ? "coordinator"
                                    : DirectCameraSourceHub.workerThreadName(report.workerIndex));
                            fields.add("worker_index"); fields.add(report.workerIndex);
                            fields.add("report_reason"); fields.add(report.reason);
                            fields.add("sample_elapsed_ms"); fields.add(observedAt);
                            fields.add("source_hub_generation"); fields.add(openedHubGeneration);
                            fields.add("producer_epoch"); fields.add(last.producerEpoch >= 0
                                    ? last.producerEpoch : openedProducerEpoch);
                            fields.add("commands"); fields.add(report.commands);
                            fields.add("success"); fields.add(report.successes);
                            fields.add("failure"); fields.add(report.failures);
                            fields.add("queued_cancel"); fields.add(report.queuedCancels);
                            fields.add("post_reject"); fields.add(report.postRejects);
                            fields.add("inline_commands"); fields.add(report.inlineCommands);
                            fields.add("queue_samples"); fields.add(report.queueSamples);
                            fields.add("queue_total_ms"); fields.add(milliseconds(report.queueTotalNs));
                            fields.add("queue_avg_ms"); fields.add(averageMs(
                                    report.queueTotalNs, report.queueSamples));
                            fields.add("queue_max_ms"); fields.add(milliseconds(report.queueMaxNs));
                            fields.add("execution_samples"); fields.add(report.executionSamples);
                            fields.add("execution_total_ms"); fields.add(milliseconds(report.executionTotalNs));
                            fields.add("execution_avg_ms"); fields.add(averageMs(
                                    report.executionTotalNs, report.executionSamples));
                            fields.add("execution_max_ms"); fields.add(milliseconds(report.executionMaxNs));
                            fields.add("command_total_ms"); fields.add(milliseconds(report.totalNs));
                            fields.add("command_avg_ms"); fields.add(averageMs(
                                    report.totalNs, report.commands));
                            fields.add("command_max_ms"); fields.add(milliseconds(report.totalMaxNs));
                            fields.add("last_command_ms"); fields.add(milliseconds(report.lastTotalNs));
                            fields.add("worst_command_ms"); fields.add(milliseconds(report.worstTotalNs));
                            appendCommandContext(fields, "last", last);
                            appendCommandContext(fields, "worst", worst);
                            appendCommandContext(fields, "last_failure", failed);
                            fields.add("failed_phase"); fields.add(report.failedPhase == null
                                    ? "" : report.failedPhase.key);
                            if (report.operation == CameraCommandMetrics.Operation.WORKER_ATTACH) {
                                fields.add("egl_create_skipped_recording_output");
                                fields.add(last.recordingOutput);
                            }
                            for (CameraCommandMetrics.PhaseStats phase : report.phases) {
                                if (phase == null) continue;
                                String key = "phase_" + phase.phase.key;
                                fields.add(key + "_count"); fields.add(phase.count);
                                fields.add(key + "_total_ms"); fields.add(milliseconds(phase.totalNs));
                                fields.add(key + "_avg_ms"); fields.add(averageMs(
                                        phase.totalNs, phase.count));
                                fields.add(key + "_max_ms"); fields.add(milliseconds(phase.maxNs));
                                fields.add(key + "_failures"); fields.add(phase.failures);
                            }
                            if (report.firstFailure != null) {
                                appendCommandFailure(fields, "first_failure", report.firstFailure);
                            }
                            if (report.lastFailure != null
                                    && report.lastFailure != report.firstFailure) {
                                appendCommandFailure(fields, "last_failure", report.lastFailure);
                            }
                            emit("camera_source_command_stats", fields.toArray());
                        };
                        if (!callbackHandler.post(emitReport)) {
                            String rejected = "camera command stats handler rejected: operation="
                                    + report.operation.key + ", worker=" + report.workerIndex
                                    + ", source_hub_generation=" + openedHubGeneration;
                            if (report.firstFailure != null) {
                                Log.w(TAG, rejected + ", first failure", report.firstFailure);
                            }
                            if (report.lastFailure != null
                                    && report.lastFailure != report.firstFailure) {
                                Log.w(TAG, rejected + ", last failure", report.lastFailure);
                            }
                            if (report.firstFailure == null && report.lastFailure == null) {
                                Log.w(TAG, rejected);
                            }
                        }
                    }

                    @Override
                    public void onTargetStall(
                            Surface surface, int index, long swapWaitNs) {
                        callbackHandler.post(() -> {
                            if (sourceHubGeneration != openedHubGeneration
                                    || !persistentPanoProducer) return;
                            emit("camera_source_target_stall",
                                    "producer_epoch", producerEpoch,
                                    "preview_index", index,
                                    "worker", DirectCameraSourceHub.workerThreadName(index),
                                    "target_surface_id", System.identityHashCode(surface),
                                    "swap_wait_ms", milliseconds(swapWaitNs));
                        });
                    }
                });
                rawSourceHub = openedHub;
                final DirectCameraSourceHub hubForStart = openedHub;
                Runnable beforeStart = () -> hubForStart.collectStatsForWindow(0);
                persistentSession.startProducer(
                        port, openedHub, target, requestedSurfaces, requestedIndexes,
                        requestId, requestedView, exclusive, shellOwned,
                        firstSurfaceDirect, sourceDemandMask, openedProducerEpoch,
                        initialActive, beforeStart);
                if (isReverseGroup(target, requestedView)) cancelReverseWarmExpiry();

                camera = opened;
                eventCallback = callbackProxy;
                persistentPanoProducer = true;
                producerCameraId = requestedCameraId;
                producerEpoch = openedProducerEpoch;
                refreshPersistentLegacyState();
                emit("camera_producer_opened", "camera_id", requestedCameraId,
                        "camera_tag", "pano_h", "producer_epoch", producerEpoch,
                        "start_preview", true);
                emitConsumerOpened(target, profileIds, true);
                return result("camera_opened", null, requestedCameraId, "pano_h");
            } catch (Throwable error) {
                if (rawSourceHub == openedHub) rawSourceHub = null;
                if (openedPort != null) {
                    Throwable closeError = closeFailedPersistentProducer(
                            openedPort, openedHub, persistentSession);
                    if (closeError != null) error.addSuppressed(closeError);
                } else {
                    if (opened != null) {
                        try {
                            closeProducerObject(opened);
                        } catch (Throwable closeError) {
                            error.addSuppressed(root(closeError));
                        }
                    }
                    if (openedHub != null) {
                        try {
                            openedHub.close();
                        } catch (Throwable closeError) {
                            error.addSuppressed(root(closeError));
                        }
                    }
                }
                try {
                    PersistentSession.releaseTeardownSurfaces(requestedSurfaces);
                } catch (Throwable cleanupError) {
                    error.addSuppressed(root(cleanupError));
                }
                String message = summary(error);
                emit("camera_error", "stage", errorStage,
                        "camera_id", requestedCameraId,
                        "camera_tag", "pano_h", "camera_owner",
                        target == null ? "camera_runtime" : target.owner,
                        "request_id", requestId,
                        "preview_indexes", Arrays.toString(requestedIndexes),
                        "error", message);
                return result("camera_error", message, requestedCameraId, "pano_h");
            }
        }


        private synchronized void acceptRawConsumerFailure(
                int hubGeneration, Surface failedSurface, int index,
                int failedRequestId, int failedProducerEpoch, Throwable error) {
            if (!matchesSourceHubGeneration(sourceHubGeneration, hubGeneration)
                    || rawSourceHub == null || !persistentPanoProducer) return;
            ConsumerGroup current = persistentSession.groupContaining(failedSurface);
            if (failedProducerEpoch != producerEpoch
                    || current != null && current.requestId != failedRequestId) {
                emit("camera_consumer_event_ignored", "reason", "retired_target",
                        "request_id", failedRequestId, "producer_epoch", failedProducerEpoch,
                        "active_producer_epoch", producerEpoch);
                return;
            }
            boolean handled;
            boolean[] shellCloseQueued = {false};
            try {
                handled = persistentSession.failConsumer(
                        new ReflectivePersistentCameraPort(camera), failedSurface, index,
                        error, this::emit, (reason, requestId) -> {
                            boolean queued = cancelPendingStock(reason, requestId);
                            shellCloseQueued[0] |= queued;
                            return queued;
                        },
                        producerCameraId, producerEpoch);
            } catch (PersistentSessionFailure failure) {
                tearDownPersistentProducer(
                        failure.reason, failure.getCause(),
                        failure.shellCloseQueued || shellCloseQueued[0]);
                return;
            }
            if (!handled) {
                PersistentSession.DetachedConsumerIdentity identity =
                        persistentSession.detachedConsumerIdentity(failedSurface, producerEpoch);
                emit("camera_consumer_event_ignored", "reason", "raw_fanout_consumer_stale",
                        "camera_id", producerCameraId,
                        "camera_tag", "pano_h",
                        "camera_owner", identity == null ? "unknown" : identity.owner,
                        "request_id", identity == null ? 0 : identity.requestId,
                        "index", index, "preview_index", index,
                        "target_surface_id", System.identityHashCode(failedSurface),
                        "producer_epoch", producerEpoch,
                        "generation", hubGeneration,
                        "source_hub_generation", hubGeneration,
                        "error", summary(error));
            }
            refreshPersistentLegacyState();
            if (handled) {
                closePersistentProducerIfIdle("consumer_failure_idle", shellCloseQueued[0]);
            }
        }

        private synchronized void acceptRawSourceFailure(
                int hubGeneration, int index, Throwable error) {
            if (!matchesSourceHubGeneration(sourceHubGeneration, hubGeneration)
                    || rawSourceHub == null || !persistentPanoProducer) return;
            tearDownPersistentProducer("raw_source_failed", error);
        }

        private synchronized String closePersistentGroup(
                ConsumerGroup group, String reason, int expectedRequestId) {
            CloseOutcome outcome;
            int closingRequestId = group.requestId;
            int closingProducerEpoch = producerEpoch;
            boolean stockClosePending = group == activityGroup
                    && (stockRequest.matches(group.requestId)
                    || (reverseStockActive
                    && reverseStockRequestMatches(group.requestId)
                    && reverseStockProducerEpoch == producerEpoch)
                    || (reverseStockClosePending
                    && reverseStockCloseRequestId == group.requestId
                    && reverseStockCloseProducerEpoch == producerEpoch));
            try {
                outcome = persistentSession.close(
                        new ReflectivePersistentCameraPort(camera), group,
                        reason, expectedRequestId,
                        this::emit, this::cancelPendingStock,
                        producerCameraId, producerEpoch, stockClosePending,
                        !persistentSession.vehiclePowerOff
                                && settings.getBoolean(ReverseCameraController.PREF_ENABLED,
                                ReverseCameraController.DEFAULT_ENABLED));
            } catch (PersistentSessionFailure error) {
                boolean preserveProducerForOffDirectDetach = error.fatal
                        && persistentSession.hasUnconfirmedOffDirectDetach(group);
                if (error.fatal && !preserveProducerForOffDirectDetach) {
                    return tearDownPersistentProducer(
                            error.reason, error.getCause(), error.shellCloseQueued);
                }
                String message = error.getCause() == null
                        ? error.reason : summary(error.getCause());
                if (preserveProducerForOffDirectDetach) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    emit("camera_error", "stage", error.reason,
                            "camera_id", producerCameraId,
                            "camera_tag", "pano_h", "camera_owner", group.owner,
                            "request_id", expectedRequestId,
                            "producer_epoch", producerEpoch, "error", message,
                            "error_stack", Log.getStackTraceString(cause));
                } else {
                    emit("camera_error", "stage", error.reason,
                            "camera_id", producerCameraId,
                            "camera_tag", "pano_h", "camera_owner", group.owner,
                            "request_id", expectedRequestId,
                            "producer_epoch", producerEpoch, "error", message);
                }
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
            if (outcome.reverseWarmArmed) {
                scheduleReverseWarmExpiry(outcome.reverseWarmGeneration,
                        closingRequestId, closingProducerEpoch);
            } else if (isReverseGroup(group, group.view)
                    && !settings.getBoolean(ReverseCameraController.PREF_ENABLED,
                            ReverseCameraController.DEFAULT_ENABLED)) {
                cancelReverseWarmExpiry();
            }
            refreshPersistentLegacyState();
            String idleClose = closePersistentProducerIfIdle(
                    "source_demand_idle", outcome.shellCloseQueued);
            if (idleClose != null) return idleClose;
            return result(persistentCloseResultKind(outcome.shellCloseQueued), null,
                    activeCameraId, "pano_h");
        }

        private void scheduleReverseWarmExpiry(
                long leaseGeneration, int requestId, int epoch) {
            scheduleReverseWarmExpiry(leaseGeneration, requestId, epoch, 60_000L);
        }

        private void scheduleReverseWarmExpiry(
                long leaseGeneration, int requestId, int epoch, long delayMs) {
            cancelReverseWarmExpiry();
            long expiryGeneration = ++reverseWarmExpiryGeneration;
            reverseWarmLeaseGeneration = leaseGeneration;
            reverseWarmLeaseRequestId = requestId;
            reverseWarmExpiryAtMs = SystemClock.elapsedRealtime() + Math.max(0L, delayMs);
            Runnable expiry = new Runnable() {
                @Override public void run() {
                    synchronized (HelperBinder.this) {
                        if (reverseWarmExpiry != this
                                || reverseWarmExpiryGeneration != expiryGeneration
                                || producerEpoch != epoch) return;
                        reverseWarmExpiry = null;
                        reverseWarmExpiryAtMs = 0L;
                        reverseWarmLeaseGeneration = 0L;
                        reverseWarmLeaseRequestId = 0;
                        try {
                            boolean expired = persistentSession.expireReverseDemand(
                                    persistentPort(), leaseGeneration, requestId, epoch);
                            if (!expired) return;
                            emit("camera_source_demand_expired", "camera_owner", "reverse",
                                    "request_id", requestId, "producer_epoch", epoch,
                                    "retained_ms", 60_000);
                            closePersistentProducerIfIdle("reverse_source_demand_expired");
                        } catch (Throwable error) {
                            emit("camera_error", "stage", "reverse_source_demand_expiry",
                                    "camera_owner", "reverse", "request_id", requestId,
                                    "producer_epoch", epoch, "error", summary(error));
                            tearDownPersistentProducer(
                                    "source_demand_reconcile_failed", error);
                        }
                    }
                }
            };
            reverseWarmExpiry = expiry;
            callbackHandler.postDelayed(expiry, Math.max(0L, delayMs));
        }


        private void cancelReverseWarmExpiry() {
            reverseWarmExpiryGeneration++;
            if (reverseWarmExpiry != null) {
                callbackHandler.removeCallbacks(reverseWarmExpiry);
                reverseWarmExpiry = null;
            }
            reverseWarmExpiryAtMs = 0L;
            reverseWarmLeaseGeneration = 0L;
            reverseWarmLeaseRequestId = 0;
        }

        private String closePersistentProducerIfIdle(String reason) {
            return closePersistentProducerIfIdle(reason, false);
        }

        private String closePersistentProducerIfIdle(
                String reason, boolean shellCloseAlreadyQueued) {
            if (!persistentPanoProducer || !persistentSession.isIdle()
                    || activityPreview.has()) return null;
            return tearDownPersistentProducer(reason, null, shellCloseAlreadyQueued);
        }

        private synchronized String tearDownPersistentProducer(
                String reason, Throwable failure) {
            return tearDownPersistentProducer(reason, failure, false);
        }

        private synchronized String tearDownPersistentProducer(
                String reason, Throwable failure, boolean shellCloseAlreadyQueued) {
            cancelReverseWarmExpiry();
            Object active = camera;
            int closedEpoch = producerEpoch;
            boolean closeStock = stockRequest.isActive() || activityGroup.shellOwned;
            int stockRequestId = stockRequest.isActive()
                    ? stockRequest.id()
                    : reverseStockRequestId > 0 ? reverseStockRequestId : activityGroup.requestId;
            if (reverseStockActive && closeStock) {
                reverseStockClosePending = true;
                reverseStockCloseRequestId = stockRequestId;
                reverseStockCloseProducerEpoch = reverseStockProducerEpoch;
            }
            camera = null;
            eventCallback = null;
            persistentPanoProducer = false;
            producerCameraId = -1;
            rawSourceHub = null;
            stockRequest.clear();
            reverseStockActive = false;
            reverseStockInputAttached = false;
            reverseStockRequestId = 0;
            reverseStockProducerEpoch = 0;
            reverseStockRetainedCallbackEpoch = 0;
            reverseStockAvmShellEpoch = 0L;
            Surface[] retiringPendingReverseSurfaces = pendingReversePreviewSurfaces;
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            ActivityPreviewState.Snapshot<Surface> retiringActivityPreview =
                    activityPreview.close(false);
            TeardownOutcome outcome = persistentSession.tearDown(
                    active == null ? null : new ReflectivePersistentCameraPort(active),
                    reason, failure, closeStock, stockRequestId,
                    shellCloseAlreadyQueued, this::queueStockClose,
                    this::emit, closedEpoch, retiringActivityPreview,
                    retiringPendingReverseSurfaces);
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
            Throwable first = stopPersistentProducer(port);
            try {
                port.close();
            } catch (Throwable error) {
                first = mergeFailure(first, error);
            }
            return first;
        }

        private static Throwable stopPersistentProducer(PersistentCameraPort port) {
            try {
                return port.stop() ? null
                        : new IllegalStateException("stopPreview returned false");
            } catch (Throwable error) {
                return root(error);
            }
        }

        private static Throwable mergeFailure(Throwable first, Throwable next) {
            if (next == null) return first;
            Throwable failure = root(next);
            if (first == null) return failure;
            if (first != failure) first.addSuppressed(failure);
            return first;
        }

        private static double averageMs(long totalNs, int count) {
            return count <= 0 ? 0.0d : totalNs / 1_000_000.0d / count;
        }

        private static double milliseconds(long nanoseconds) {
            return nanoseconds / 1_000_000.0d;
        }

        private static double ratePerSecond(long count, long intervalNs) {
            return count <= 0 || intervalNs <= 0L
                    ? 0.0d : count * 1_000_000_000.0d / intervalNs;
        }

        private static Object[] withAcquisitionMetrics(
                Object[] fields, DirectCameraSourceHub.Stats stats, int sourceHubGeneration) {
            DirectCameraSourceHub.AcquisitionMetrics metrics = stats.acquisitionMetrics;
            if (metrics == null) return fields;
            ArrayList<Object> result = new ArrayList<>(Arrays.asList(fields));
            result.add("source_closed"); result.add(stats.sourceClosed);
            result.add("source_retired"); result.add(stats.sourceRetired);
            result.add("source_hub_generation"); result.add(sourceHubGeneration);
            result.add("input_calls"); result.add(metrics.inputCalls);
            result.add("input_signals"); result.add(metrics.signals);
            result.add("snapshot_allocations"); result.add(metrics.allocations);
            result.add("snapshot_copies"); result.add(metrics.snapshotCopies);
            result.add("input_nonpositive_timestamps"); result.add(metrics.invalidTimestamps);
            result.add("input_repeated_timestamps"); result.add(metrics.repeatedTimestamps);
            result.add("snapshot_skip_no_prepared_buffer");
            result.add(metrics.skippedNoPreparedBuffer);
            result.add("snapshot_skip_gpu_busy"); result.add(metrics.skippedGpuBusy);
            result.add("snapshot_mailbox_busy"); result.add(metrics.skippedMailboxBusy);
            appendStageMetrics(result, "input_queue", metrics.queueTotalNs,
                    metrics.queueMaxNs, metrics.inputCalls);
            appendStageMetrics(result, "input_preparation", metrics.preparationTotalNs,
                    metrics.preparationMaxNs, metrics.inputCalls);
            appendStageMetrics(result, "input_update", metrics.updateTotalNs,
                    metrics.updateMaxNs, metrics.inputCalls);
            appendStageMetrics(result, "snapshot_copy", metrics.copyTotalNs,
                    metrics.copyMaxNs, metrics.snapshotCopies);
            appendStageMetrics(result, "snapshot_fence", metrics.fenceTotalNs,
                    metrics.fenceMaxNs, metrics.snapshotCopies);
            appendStageMetrics(result, "input_release", metrics.releaseTotalNs,
                    metrics.releaseMaxNs, metrics.inputCalls);
            appendStageMetrics(result, "input_cycle", metrics.cycleTotalNs,
                    metrics.cycleMaxNs, metrics.inputCalls);
            return result.toArray();
        }

        private static void appendStageMetrics(
                ArrayList<Object> fields, String name, long totalNs, long maxNs, int count) {
            fields.add(name + "_total_ms"); fields.add(milliseconds(totalNs));
            fields.add(name + "_avg_ms"); fields.add(averageMs(totalNs, count));
            fields.add(name + "_max_ms"); fields.add(milliseconds(maxNs));
        }

        private static void appendCommandContext(ArrayList<Object> fields, String prefix,
                CameraCommandMetrics.Context context) {
            fields.add(prefix + "_source_index"); fields.add(context.sourceIndex);
            fields.add(prefix + "_request_id"); fields.add(context.requestId);
            fields.add(prefix + "_producer_epoch"); fields.add(context.producerEpoch);
            fields.add(prefix + "_surface_id"); fields.add(context.surfaceIdentity);
            fields.add(prefix + "_recording_output"); fields.add(context.recordingOutput);
        }

        private static void appendCommandFailure(
                ArrayList<Object> fields, String prefix, Throwable failure) {
            fields.add(prefix + "_class"); fields.add(failure.getClass().getName());
            fields.add(prefix + "_message");
            fields.add(failure.getMessage() == null ? "" : failure.getMessage());
            fields.add(prefix + "_stack_trace"); fields.add(commandStackTrace(failure));
        }

        private static String commandStackTrace(Throwable failure) {
            StringWriter text = new StringWriter();
            try (PrintWriter writer = new PrintWriter(text)) {
                failure.printStackTrace(writer);
            }
            return text.toString();
        }

        static Throwable closeFailedPersistentProducer(
                PersistentCameraPort port, PersistentSurfaceFanout fanout) {
            return closeFailedPersistentProducer(port, fanout, null);
        }

        static Throwable closeFailedPersistentProducer(
                PersistentCameraPort port, PersistentSurfaceFanout fanout,
                PersistentSession failedSession) {
            Throwable first;
            boolean stopped;
            if (failedSession != null && failedSession.failedOpenStopAttempted) {
                first = failedSession.failedOpenStopFailure;
                stopped = first == null;
            } else {
                first = stopPersistentProducer(port);
                stopped = first == null;
            }
            if (failedSession != null && stopped
                    && !failedSession.failedOpenStopAttempted) {
                first = mergeFailure(first, failedSession.detachDirectInputs(port));
                first = mergeFailure(first, failedSession.detachSources(port));
            }
            try {
                port.close();
            } catch (Throwable error) {
                first = mergeFailure(first, error);
            }
            if (fanout != null) {
                try {
                    fanout.close();
                } catch (Throwable error) {
                    first = mergeFailure(first, error);
                }
            }
            if (failedSession != null) failedSession.clearFailedOpenState();
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
            emitConsumerClosed(sink, owner, requestId, view, indexes, reason,
                    cameraId, epoch, false);
        }

        static void emitConsumerClosed(
                PersistentEventSink sink, String owner, int requestId,
                String view, int[] indexes, String reason,
                int cameraId, int epoch, boolean stockClosePending) {
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
                    "stock_close_pending", stockClosePending,
                    "error", "");
        }

        private void refreshPersistentLegacyState() {
            activeCameraId = producerCameraId;
            activeCameraTag = "pano_h";
            ConsumerGroup active = reverseGroup.has()
                    ? reverseGroup : activityGroup.has() ? activityGroup
                    : overlayGroup.has() ? overlayGroup
                    : parkingGroup.has() ? parkingGroup : mirrorGroup;
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
            boolean closeStock = stockRequest.isActive()
                    && (requestId <= 0 || stockRequest.matches(requestId));
            boolean queuedClose = reverseStockClosePending
                    && requestId > 0 && reverseStockCloseRequestId == requestId;
            // A late close/failure for another request must not cancel the current stock
            // callback or mark its direct group as closed.
            if (!closeStock && !queuedClose) return false;
            if (!closeStock) {
                // The stock close was already queued by a background failure. Re-issue it
                // with this overall transition's reason so the Activity can settle this close.
                queueStockClose(reason, requestId);
                return true;
            }
            boolean reverseClose = reverseStockActive
                    && (requestId <= 0 || reverseStockRequestMatches(requestId));
            int closeRequestId = requestId > 0 ? requestId
                    : stockRequest.isActive() ? stockRequest.id()
                    : reverseStockRequestId;
            int closeEpoch = reverseStockProducerEpoch;
            stockRequest.clear();
            reverseStockActive = false;
            reverseStockInputAttached = false;
            reverseStockRequestId = 0;
            reverseStockProducerEpoch = 0;
            reverseStockRetainedCallbackEpoch = 0;
            reverseStockAvmShellEpoch = 0L;
            if (reverseClose) {
                reverseStockClosePending = true;
                reverseStockCloseRequestId = closeRequestId;
                reverseStockCloseProducerEpoch = closeEpoch;
            }
            // Direct Reverse surfaces are attached immediately; this array is retained only
            // for a stale pre-direct-first callback and is empty for the current path.
            releaseSurfaces(pendingReversePreviewSurfaces);
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            if (closeStock) queueStockClose(reason, closeRequestId);
            return closeStock;
        }

        private boolean reverseStockRequestMatches(int requestId) {
            return requestId > 0 && reverseStockRequestId == requestId;
        }

        private boolean isCurrentReverseStockCallback(int requestId, int epoch) {
            boolean currentEpoch = epoch > 0 && epoch == reverseStockProducerEpoch
                    && epoch == producerEpoch;
            boolean retainedEpoch = epoch > 0
                    && epoch == reverseStockRetainedCallbackEpoch
                    && producerEpoch == reverseStockProducerEpoch;
            return reverseStockActive && stockRequest.isActive()
                    && requestId > 0 && requestId == reverseStockRequestId
                    && requestId == stockRequest.pendingId()
                    && (currentEpoch || retainedEpoch) && persistentPanoProducer
                    && activityGroup.has() && activityGroup.attached
                    && activityGroup.requestId == requestId;
        }

        private void reverseStockFailed(
                String stage, int requestId, int epoch, String error) {
            // This helper is called after the stock shell handed us its input Surface, so the
            // shell is live even if attaching input 0 to AVMCamera itself failed.
            boolean live = true;
            emit("camera_error", "component", "reverse_preview_background",
                    "renderer", "stock_avm_shell", "stage", stage,
                    "camera_owner", CAMERA_OWNER_ACTIVITY,
                    "request_id", requestId, "producer_epoch", epoch,
                    "stock_close_pending", live,
                    "error", error == null ? "stock background failed" : error);
            if (reverseStockActive && reverseStockRequestMatches(requestId)) {
                // A callback proves a live stock shell; a config/open failure before callback
                // has no shell to close and is settled by the controller's failure event.
                if (live) cancelPendingStock("reverse_preview_stock_failed", requestId);
                else {
                    stockRequest.clear();
                    reverseStockActive = false;
                    reverseStockInputAttached = false;
                    reverseStockRequestId = 0;
                    reverseStockProducerEpoch = 0;
                    reverseStockRetainedCallbackEpoch = 0;
                    reverseStockAvmShellEpoch = 0L;
                    pendingReversePreviewRequestId = 0;
                }
            }
        }

        private boolean queueStockClose(String reason, int requestId) {
            turnController.closeStockAvm(reason, requestId);
            emit("camera_shell_request", "action", "close",
                    "reason", reason == null ? "unknown" : reason,
                    "request_id", requestId,
                    "component", reverseStockClosePending
                            && reverseStockCloseRequestId == requestId
                            ? "reverse_preview_background" : "stock_avm");
            return true;
        }

        private int currentRequestId(String owner) {
            ConsumerGroup group = groupForOwner(owner);
            if (group != null && group.has()) return group.requestId;
            return CAMERA_OWNER_ACTIVITY.equals(owner) ? stockRequest.id() : 0;
        }

        private ConsumerGroup groupForOwner(String owner) {
            if (CAMERA_OWNER_ACTIVITY.equals(owner)) return activityGroup;
            if (CAMERA_OWNER_OVERLAY.equals(owner)) return overlayGroup;
            if (CAMERA_OWNER_PARKING.equals(owner)) return parkingGroup;
            if (CAMERA_OWNER_MIRROR.equals(owner)) return mirrorGroup;
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

        static boolean persistentAttachBlocked(
                boolean targetOverlay, boolean targetParking,
                boolean reverseActive, boolean activityExclusive) {
            return (targetOverlay && reverseActive)
                    || ((targetOverlay || targetParking) && activityExclusive);
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
            PersistentSession.releaseTeardownSurfaces(snapshot.surfaces);
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
            tryClose(activeCamera, activeSurface, activeIndex, null);
        }

        private void tryClose(
                Object activeCamera, Surface activeSurface, int activeIndex,
                ActivityPreviewState.Snapshot<Surface> activityPreview) throws Exception {
            Throwable first = closeOneShotPort(
                    new ReflectivePersistentCameraPort(activeCamera),
                    new Surface[]{activeSurface}, new int[]{activeIndex},
                    new boolean[]{activeSurface != null && activeIndex >= 0},
                    activityPreview);
            if (first != null) throw new Exception(first);
        }

        private void tryClose(
                Object activeCamera, Surface[] activeSurfaces, int[] activeIndexes,
                boolean[] attached) throws Exception {
            tryClose(activeCamera, activeSurfaces, activeIndexes, attached, null);
        }

        private void tryClose(
                Object activeCamera, Surface[] activeSurfaces, int[] activeIndexes,
                boolean[] attached,
                ActivityPreviewState.Snapshot<Surface> activityPreview) throws Exception {
            Throwable first = closeOneShotPort(
                    new ReflectivePersistentCameraPort(activeCamera),
                    activeSurfaces, activeIndexes, attached, activityPreview);
            if (first != null) throw new Exception(first);
        }

        static Throwable closeOneShotPort(
                PersistentCameraPort port, Surface[] activeSurfaces,
                int[] activeIndexes, boolean[] attached) {
            return closeOneShotPort(port, activeSurfaces, activeIndexes, attached, null);
        }

        static Throwable closeOneShotPort(
                PersistentCameraPort port, Surface[] activeSurfaces,
                int[] activeIndexes, boolean[] attached,
                ActivityPreviewState.Snapshot<Surface> activityPreview) {
            Throwable first = stopPersistentProducer(port);
            if (first == null) {
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
                        first = mergeFailure(first, error);
                    }
                }
                if (activityPreview != null && activityPreview.attached
                        && activityPreview.value != null) {
                    try {
                        if (!port.remove(activityPreview.value, activityPreview.index)) {
                            throw new IllegalStateException(
                                    "rmPreviewSurface returned false for Activity preview");
                        }
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
            }
            try {
                port.close();
            } catch (Throwable error) {
                first = mergeFailure(first, error);
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

        static boolean matchesSourceHubGeneration(int current, int callback) {
            return callback > 0 && callback == current;
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
            boolean stop() throws Exception;
            void close() throws Exception;
        }

        interface PersistentSurfaceFanout {
            Surface source(int index) throws Exception;
            void attach(Surface[] surfaces, int[] indexes,
                    int requestId, int producerEpoch) throws Exception;
            void attach(Surface[] surfaces, int[] indexes, int requestId,
                    int producerEpoch, boolean[] initialActive) throws Exception;
            void detach(Surface[] surfaces) throws Exception;
            void setActive(Surface surface, boolean active) throws Exception;
            void releaseSource(int index) throws Exception;
            void close();
        }

        interface PersistentShellCloseSink {
            boolean close(String reason, int requestId);
        }

        static final class PersistentSession {
            final ConsumerGroup activityGroup = new ConsumerGroup(CAMERA_OWNER_ACTIVITY);
            final ConsumerGroup overlayGroup = new ConsumerGroup(CAMERA_OWNER_OVERLAY);
            final ConsumerGroup parkingGroup = new ConsumerGroup(CAMERA_OWNER_PARKING);
            final ConsumerGroup mirrorGroup = new ConsumerGroup(CAMERA_OWNER_MIRROR);
            final ConsumerGroup reverseGroup = new ConsumerGroup(CAMERA_OWNER_REVERSE);
            final ConsumerGroup motionGroup = new ConsumerGroup("motion_recording");
            final ConsumerGroup viewingGroup = new ConsumerGroup("camera_viewing");
            final ConsumerGroup[] motionPreviewGroups = {
                    new ConsumerGroup("motion_preview_front"),
                    new ConsumerGroup("motion_preview_rear"),
                    new ConsumerGroup("motion_preview_left"),
                    new ConsumerGroup("motion_preview_right")
            };
            Surface motionDetectionSurface;
            boolean motionDetectionWanted, motionDetectionAttached;
            private final IdentityHashMap<Surface, DetachedConsumerIdentity>
                    detachedConsumerIdentities = new IdentityHashMap<>();
            final Surface[] sourceSurfaces = new Surface[5];
            final boolean[] sourceAttached = new boolean[5];
            boolean producerOpen;
            boolean vehiclePowerOff;
            private Throwable vehiclePowerOffReconcileFailure;
            boolean failedOpenStopAttempted;
            Throwable failedOpenStopFailure;
            int activeProducerEpoch;
            Throwable restoreFailure;
            boolean restoreFailureFatal;
            PersistentSurfaceFanout fanout;
            int blindStandbyDemandMask;
            int mirrorDemandMask;
            final CameraSourceDemand.ReverseWarmLease reverseWarmLease =
                    new CameraSourceDemand.ReverseWarmLease();

            ConsumerGroup motionPreviewGroup(int direction) {
                if (direction < 0 || direction >= motionPreviewGroups.length) {
                    throw new IllegalArgumentException(
                            "Motion direction must be in range 0..3");
                }
                return motionPreviewGroups[direction];
            }

            boolean isMotionGroup(ConsumerGroup group) {
                if (group == motionGroup) return true;
                for (ConsumerGroup preview : motionPreviewGroups) if (group == preview) return true;
                return false;
            }

            static int effectiveGroupSourceDemandMask(
                    boolean vehiclePowerOff, String owner, int sourceDemandMask) {
                return vehiclePowerOff && isOrdinaryCameraGroupOwner(owner)
                        ? 0 : sourceDemandMask;
            }

            static boolean shouldPreserveSharedProducerForOffDirectDetach(
                    boolean vehiclePowerOff, String owner, boolean directSurfaceAttached) {
                return vehiclePowerOff && directSurfaceAttached
                        && isOrdinaryCameraGroupOwner(owner);
            }

            boolean hasUnconfirmedOffDirectDetach(String owner) {
                for (ConsumerGroup group : consumerGroups()) {
                    if (owner.equals(group.owner) && hasUnconfirmedOffDirectDetach(group)) {
                        return true;
                    }
                }
                return false;
            }

            boolean hasUnconfirmedOffDirectDetach(ConsumerGroup group) {
                return shouldPreserveSharedProducerForOffDirectDetach(
                        vehiclePowerOff, group.owner,
                        group.firstSurfaceDirect && group.directSurfaceAttached);
            }

            private static boolean isOrdinaryCameraGroupOwner(String owner) {
                return CAMERA_OWNER_ACTIVITY.equals(owner)
                        || CAMERA_OWNER_OVERLAY.equals(owner)
                        || CAMERA_OWNER_REVERSE.equals(owner)
                        || CAMERA_OWNER_PARKING.equals(owner)
                        || CAMERA_OWNER_MIRROR.equals(owner);
            }

            void clearVehiclePowerOffReconcileFailure() {
                vehiclePowerOffReconcileFailure = null;
            }

            Throwable takeVehiclePowerOffReconcileFailure() {
                Throwable failure = vehiclePowerOffReconcileFailure;
                vehiclePowerOffReconcileFailure = null;
                return failure;
            }

            private void recordVehiclePowerOffReconcileFailure(Throwable error) {
                vehiclePowerOffReconcileFailure = mergeFailure(
                        vehiclePowerOffReconcileFailure, error);
            }

            private ConsumerGroup[] consumerGroups() {
                ConsumerGroup[] groups = new ConsumerGroup[7 + motionPreviewGroups.length];
                groups[0] = activityGroup;
                groups[1] = overlayGroup;
                groups[2] = parkingGroup;
                groups[3] = mirrorGroup;
                groups[4] = reverseGroup;
                groups[5] = motionGroup;
                groups[6] = viewingGroup;
                System.arraycopy(motionPreviewGroups, 0, groups, 7,
                        motionPreviewGroups.length);
                return groups;
            }

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
                startProducer(port, requestedFanout, target, surfaces, indexes, requestId,
                        view, exclusive, shellOwned, firstSurfaceDirect,
                        CameraSourceDemand.fromIndexes(indexes, firstSurfaceDirect));
            }

            void startProducer(
                    PersistentCameraPort port, PersistentSurfaceFanout requestedFanout,
                    ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    boolean firstSurfaceDirect, int sourceDemandMask) throws Exception {
                startProducer(port, requestedFanout, target, surfaces, indexes, requestId,
                        view, exclusive, shellOwned, firstSurfaceDirect, sourceDemandMask, 0);
            }

            void startProducer(
                    PersistentCameraPort port, PersistentSurfaceFanout requestedFanout,
                    ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    boolean firstSurfaceDirect, int sourceDemandMask, int producerEpoch)
                    throws Exception {
                startProducer(port, requestedFanout, target, surfaces, indexes, requestId,
                        view, exclusive, shellOwned, firstSurfaceDirect, sourceDemandMask,
                        producerEpoch, null);
            }

            void startProducer(
                    PersistentCameraPort port, PersistentSurfaceFanout requestedFanout,
                    ConsumerGroup target, Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    boolean firstSurfaceDirect, int sourceDemandMask, int producerEpoch,
                    boolean[] initialActive) throws Exception {
                startProducer(port, requestedFanout, target, surfaces, indexes, requestId,
                        view, exclusive, shellOwned, firstSurfaceDirect, sourceDemandMask,
                        producerEpoch, initialActive, null);
            }

            void startProducer(
                    PersistentCameraPort port, PersistentSurfaceFanout requestedFanout,
                    ConsumerGroup target, Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    boolean firstSurfaceDirect, int sourceDemandMask, int producerEpoch,
                    boolean[] initialActive, Runnable beforeStart) throws Exception {
                clearDetachedConsumerIdentities();
                fanout = requestedFanout;
                boolean[] directAttempted = {false};
                failedOpenStopAttempted = false;
                failedOpenStopFailure = null;
                activeProducerEpoch = producerEpoch;
                try {
                    int demanded = sourceDemandMask | retainedSourceDemandMask();
                    ensureSources(port, indexesFromMask(demanded));
                    attachMotionInput(port);
                    if (beforeStart != null) beforeStart.run();
                    if (!port.start()) {
                        throw new IllegalStateException("startPreview returned false");
                    }
                    boolean[] attachedTargets = attachGroup(
                            port, surfaces, indexes, firstSurfaceDirect, sourceDemandMask,
                            requestId, producerEpoch, true, directAttempted, initialActive);
                    target.set(surfaces, indexes, requestId,
                            view, exclusive, shellOwned, true, firstSurfaceDirect,
                            sourceDemandMask);
                    target.fanoutAttached = attachedTargets;
                    if (initialActive != null) target.restoreActive(initialActive);
                    producerOpen = true;
                    restoreFailure = null;
                    if (isReverseOwner(target)) reverseWarmLease.clear();
                    reconcileSourceDemand(port);
                } catch (Throwable error) {
                    failedOpenStopAttempted = true;
                    failedOpenStopFailure = stopPersistentProducer(port);
                    if (failedOpenStopFailure != null) {
                        error.addSuppressed(failedOpenStopFailure);
                    } else {
                        Throwable detachError = null;
                        try {
                            if (target.attached) detachGroup(port, target);
                            else detachRequestedGroup(port, surfaces, indexes,
                                    firstSurfaceDirect, directAttempted[0], sourceDemandMask);
                        } catch (Throwable cleanupError) {
                            detachError = root(cleanupError);
                        }
                        detachError = mergeFailure(detachError, detachSources(port));
                        if (detachError != null) error.addSuppressed(detachError);
                    }
                    target.clear();
                    fanout = null;
                    clearSources();
                    producerOpen = false;
                    activeProducerEpoch = 0;
                    throw error;
                }
            }


            private void clearFailedOpenState() {
                failedOpenStopAttempted = false;
                failedOpenStopFailure = null;
                for (ConsumerGroup group : consumerGroups()) group.clear();
                clearSources();
                producerOpen = false;
                activeProducerEpoch = 0;
                restoreFailure = null;
                restoreFailureFatal = false;
                fanout = null;
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
                attach(port, target, surfaces, indexes, requestId, view,
                        exclusive, shellOwned, events, shellClose, cameraId, epoch,
                        firstSurfaceDirect,
                        CameraSourceDemand.fromIndexes(indexes, firstSurfaceDirect));
            }

            void attach(
                    PersistentCameraPort port, ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId,
                    String view, boolean exclusive, boolean shellOwned,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch,
                    boolean firstSurfaceDirect, int sourceDemandMask)
                    throws PersistentSessionFailure {
                attach(port, target, surfaces, indexes, requestId, view, exclusive, shellOwned,
                        events, shellClose, cameraId, epoch, firstSurfaceDirect, sourceDemandMask, null);
            }

            void attach(PersistentCameraPort port, ConsumerGroup target,
                    Surface[] surfaces, int[] indexes, int requestId, String view,
                    boolean exclusive, boolean shellOwned, PersistentEventSink events,
                    PersistentShellCloseSink shellClose, int cameraId, int epoch,
                    boolean firstSurfaceDirect, int sourceDemandMask, boolean[] initialActive)
                    throws PersistentSessionFailure {
                activeProducerEpoch = epoch;
                try {
                    ensureSources(port, indexesFromMask(
                            sourceDemandMask | retainedSourceDemandMask()));
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
                    boolean[] attachedTargets = attachGroup(
                            port, surfaces, indexes, firstSurfaceDirect, sourceDemandMask,
                            requestId, epoch, false, null, initialActive);
                    target.set(surfaces, indexes, requestId,
                            view, exclusive, shellOwned, true, firstSurfaceDirect,
                            sourceDemandMask);
                    target.fanoutAttached = attachedTargets;
                    if (initialActive != null) target.restoreActive(initialActive);
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

                rememberDetached(previous, epoch);
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
                    rememberDetached(preempted, epoch);
                    activityGroup.clear();
                    preempted.release();
                }
                if (!exclusive && !restoreCompatibleGroups(
                        port, events, shellClose, cameraId, epoch)) {
                    Throwable restoreError = restoreFailure;
                    if (restoreFailureFatal) {
                        throw new PersistentSessionFailure(
                                "consumer_restore_failed", restoreError, true,
                                true, shellCloseQueued);
                    }
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
                previous.releaseExcept(surfaces);
                if (isReverseOwner(target)) reverseWarmLease.clear();
                try {
                    reconcileSourceDemand(port);
                } catch (Throwable error) {
                    throw new PersistentSessionFailure(
                            "source_demand_reconcile_failed", root(error), true,
                            true, shellCloseQueued);
                }
            }

            /** Adds optional stock input 0 without detaching or restarting direct targets. */
            boolean attachStockInput(
                    PersistentCameraPort port, ConsumerGroup target, Surface stockInput,
                    int requestId, PersistentEventSink events,
                    int cameraId, int epoch) throws PersistentSessionFailure {
                if (target != activityGroup || !target.has() || !target.attached
                        || target.requestId != requestId || target.firstSurfaceDirect) {
                    throw new PersistentSessionFailure(
                            "stock_input_request_stale", null, false, false, false);
                }
                if (stockInput == null || !stockInput.isValid()) {
                    throw new PersistentSessionFailure(
                            "stock_input_invalid", null, false, false, false);
                }
                for (int index : target.indexes) {
                    if (index == 0) {
                        throw new PersistentSessionFailure(
                                "stock_input_already_attached", null, false, false, false);
                    }
                }
                boolean added;
                try {
                    added = port.add(stockInput, 0);
                } catch (Throwable error) {
                    Throwable cleanup = null;
                    try {
                        if (!port.remove(stockInput, 0)) {
                            cleanup = new IllegalStateException(
                                    "rmPreviewSurface returned false for stock input 0");
                        }
                    } catch (Throwable removeError) {
                        cleanup = root(removeError);
                    }
                    if (cleanup != null) error.addSuppressed(cleanup);
                    throw new PersistentSessionFailure(
                            "stock_input_attach_failed", root(error), cleanup != null,
                            false, false);
                }
                if (!added) return false;

                Surface[] directSurfaces = target.surfaces;
                int[] directIndexes = target.indexes;
                boolean[] directActive = target.active;
                boolean[] directFanoutAttached = target.fanoutAttached;
                Surface[] combinedSurfaces = new Surface[directSurfaces.length + 1];
                int[] combinedIndexes = new int[directIndexes.length + 1];
                boolean[] combinedActive = new boolean[directActive.length + 1];
                boolean[] combinedFanoutAttached =
                        new boolean[directFanoutAttached.length + 1];
                combinedSurfaces[0] = stockInput;
                combinedIndexes[0] = 0;
                combinedActive[0] = true;
                System.arraycopy(directSurfaces, 0, combinedSurfaces, 1, directSurfaces.length);
                System.arraycopy(directIndexes, 0, combinedIndexes, 1, directIndexes.length);
                System.arraycopy(directActive, 0, combinedActive, 1, directActive.length);
                System.arraycopy(directFanoutAttached, 0,
                        combinedFanoutAttached, 1, directFanoutAttached.length);
                target.set(combinedSurfaces, combinedIndexes, requestId,
                        target.view, target.exclusive, true, true, true,
                        target.sourceDemandMask);
                target.restoreActive(combinedActive);
                target.fanoutAttached = combinedFanoutAttached;
                return true;
            }

            CloseOutcome close(
                    PersistentCameraPort port, ConsumerGroup group,
                    String reason, int expectedRequestId,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch) throws PersistentSessionFailure {
                return close(port, group, reason, expectedRequestId,
                        events, shellClose, cameraId, epoch, false);
            }

            CloseOutcome close(
                    PersistentCameraPort port, ConsumerGroup group,
                    String reason, int expectedRequestId,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch, boolean stockClosePending)
                    throws PersistentSessionFailure {
                return close(port, group, reason, expectedRequestId, events,
                        shellClose, cameraId, epoch, stockClosePending, true);
            }

            CloseOutcome close(
                    PersistentCameraPort port, ConsumerGroup group,
                    String reason, int expectedRequestId,
                    PersistentEventSink events, PersistentShellCloseSink shellClose,
                    int cameraId, int epoch, boolean stockClosePending,
                    boolean retainReverseWarm)
                    throws PersistentSessionFailure {
                PersistentCloseDecision decision = persistentCloseDecision(
                        group.has(), group.requestId, expectedRequestId);
                if (decision != PersistentCloseDecision.CLOSE) {
                    return new CloseOutcome(decision, false);
                }
                ConsumerGroup.Snapshot closed = group.snapshot();
                boolean closingReverse = isReverseOwner(group);
                try {
                    detachGroup(port, group);
                } catch (Throwable error) {
                    throw new PersistentSessionFailure(
                            "consumer_detach_failed", root(error),
                            group.firstSurfaceDirect,
                            false, false);
                }
                rememberDetached(closed, epoch);
                group.clear();
                long warmGeneration = 0L;
                if (closingReverse && retainReverseWarm && closed.sourceDemandMask != 0) {
                    warmGeneration = reverseWarmLease.arm(
                            closed.requestId, epoch, closed.sourceDemandMask);
                } else if (closingReverse && !retainReverseWarm) {
                    reverseWarmLease.clear();
                }
                closed.release();
                boolean shellCloseQueued = closed.shellOwned
                        && shellClose.close(reason, closed.requestId);
                if (stockClosePending && !shellCloseQueued) {
                    shellCloseQueued = shellClose.close(reason, closed.requestId);
                }
                restoreCompatibleGroups(port, events, shellClose, cameraId, epoch);
                try {
                    reconcileSourceDemand(port);
                } catch (Throwable error) {
                    throw new PersistentSessionFailure(
                            "source_demand_reconcile_failed", root(error), true,
                            true, shellCloseQueued);
                }
                emitConsumerClosed(events, closed.owner, closed.requestId,
                        closed.view, closed.indexes, reason, cameraId, epoch,
                        stockClosePending && shellCloseQueued);
                return new CloseOutcome(decision, shellCloseQueued,
                        warmGeneration, closingReverse && warmGeneration != 0L);
            }

            void invalidateCameraShellGroups(
                    PersistentCameraPort port, String reason,
                    PersistentEventSink events, int cameraId, int epoch)
                    throws PersistentSessionFailure {
                for (ConsumerGroup group : new ConsumerGroup[]{
                        activityGroup, overlayGroup, parkingGroup, reverseGroup, mirrorGroup}) {
                    if (!group.has() || group == activityGroup && !group.shellOwned) continue;
                    ConsumerGroup.Snapshot invalid = group.snapshot();
                    try {
                        detachGroup(port, group);
                    } catch (Throwable error) {
                        throw new PersistentSessionFailure(
                                "consumer_detach_failed", root(error), true,
                                false, false);
                    }
                    rememberDetached(invalid, epoch);
                    group.clear();
                    invalid.release();
                    emitConsumerClosed(events, invalid.owner, invalid.requestId,
                            invalid.view, invalid.indexes, reason, cameraId, epoch);
                }
                try {
                    reconcileSourceDemand(port);
                } catch (Throwable error) {
                    throw new PersistentSessionFailure(
                            "source_demand_reconcile_failed", root(error), true,
                            true, false);
                }
            }

            void invalidateStockAvmGroup(
                    PersistentCameraPort port, String reason,
                    PersistentEventSink events, int cameraId, int epoch)
                    throws PersistentSessionFailure {
                if (!isStockAvmGroup(activityGroup)) return;
                ConsumerGroup.Snapshot invalid = activityGroup.snapshot();
                if (isReverseStockAvmGroup(activityGroup) && invalid.surfaces.length > 1) {
                    try {
                        if (!port.remove(invalid.surfaces[0], invalid.indexes[0])) {
                            throw new IllegalStateException("rmPreviewSurface returned false");
                        }
                    } catch (Throwable error) {
                        throw new PersistentSessionFailure(
                                "consumer_detach_failed", root(error), true,
                                false, false);
                    }
                    Surface[] remainingSurfaces = Arrays.copyOfRange(
                            invalid.surfaces, 1, invalid.surfaces.length);
                    int[] remainingIndexes = Arrays.copyOfRange(
                            invalid.indexes, 1, invalid.indexes.length);
                    activityGroup.set(remainingSurfaces, remainingIndexes, invalid.requestId,
                            invalid.view, invalid.exclusive, false,
                            invalid.attached, false, invalid.sourceDemandMask);
                    activityGroup.restoreActive(Arrays.copyOfRange(
                            invalid.active, 1, invalid.active.length));
                    activityGroup.fanoutAttached = Arrays.copyOfRange(
                            invalid.fanoutAttached, 1, invalid.fanoutAttached.length);
                    if (invalid.surfaces[0] != null) {
                        rememberDetached(invalid.surfaces[0], invalid.owner,
                                invalid.requestId, epoch);
                        invalid.surfaces[0].release();
                    }
                    events.emit("stock_avm_input_detached", "component",
                            "reverse_preview_background", "camera_owner", invalid.owner,
                            "request_id", invalid.requestId, "view", invalid.view,
                            "reason", reason, "producer_epoch", epoch,
                            "preview_index", 0);
                    return;
                }
                try {
                    detachGroup(port, activityGroup);
                } catch (Throwable error) {
                    throw new PersistentSessionFailure(
                            "consumer_detach_failed", root(error), true,
                            false, false);
                }
                rememberDetached(invalid, epoch);
                activityGroup.clear();
                invalid.release();
                emitConsumerClosed(events, invalid.owner, invalid.requestId,
                        invalid.view, invalid.indexes, reason, cameraId, epoch);
            }

            static boolean isStockAvmGroup(ConsumerGroup group) {
                return group != null && group.has() && group.shellOwned
                        && ("stock_avm_input".equals(group.view)
                        || isReverseStockAvmGroup(group));
            }

            static boolean isReverseStockAvmGroup(ConsumerGroup group) {
                return group != null && group.has() && group.shellOwned
                        && "reverse_preview_with_stock_base".equals(group.view)
                        && group.indexes.length > 0 && group.indexes[0] == 0;
            }

            TeardownOutcome tearDown(
                    PersistentCameraPort port, String reason, Throwable failure,
                    boolean closeStock, int stockRequestId,
                    boolean shellCloseAlreadyQueued,
                    PersistentShellCloseSink shellClose,
                    PersistentEventSink events, int epoch) {
                return tearDown(port, reason, failure, closeStock, stockRequestId,
                        shellCloseAlreadyQueued, shellClose, events, epoch,
                        null, new Surface[0]);
            }

            TeardownOutcome tearDown(
                    PersistentCameraPort port, String reason, Throwable failure,
                    boolean closeStock, int stockRequestId,
                    boolean shellCloseAlreadyQueued,
                    PersistentShellCloseSink shellClose,
                    PersistentEventSink events, int epoch,
                    ActivityPreviewState.Snapshot<Surface> activityPreview,
                    Surface[] pendingReverseSurfaces) {
                clearDetachedConsumerIdentities();
                Throwable first = failure;
                ConsumerGroup.Snapshot[] abandoned = activeSnapshots();
                String terminalError = first == null ? reason : summary(first);
                for (ConsumerGroup.Snapshot consumer : abandoned) {
                    try {
                        events.emit("camera_error", "stage", reason,
                                "camera_tag", "pano_h", "camera_owner", consumer.owner,
                                "request_id", consumer.requestId,
                                "view", consumer.view == null ? "unknown" : consumer.view,
                                "preview_indexes", Arrays.toString(consumer.indexes),
                                "producer_epoch", epoch, "error", terminalError);
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
                if (port != null) {
                    Throwable stopError = stopPersistentProducer(port);
                    first = mergeFailure(first, stopError);
                    if (stopError == null) {
                        first = mergeFailure(first, detachDirectInputs(port));
                        first = mergeFailure(first, detachActivityPreview(port, activityPreview));
                        first = mergeFailure(first, detachSources(port));
                    }
                    try {
                        port.close();
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
                if (fanout != null) {
                    try {
                        fanout.close();
                    } catch (Throwable closeError) {
                        first = mergeFailure(first, closeError);
                    }
                }
                if (activityPreview != null && activityPreview.release
                        && activityPreview.value != null) {
                    try {
                        activityPreview.value.release();
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
                try {
                    releaseTeardownSurfaces(pendingReverseSurfaces);
                } catch (Throwable error) {
                    first = mergeFailure(first, error);
                }
                for (ConsumerGroup group : consumerGroups()) {
                    try {
                        releaseAndClear(group);
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
                // Controller demand survives a producer retry; only its owner clears it.
                reverseWarmLease.clear();
                fanout = null;
                clearSources();
                producerOpen = false;
                activeProducerEpoch = 0;
                restoreFailure = null;
                restoreFailureFatal = false;
                boolean shellCloseQueued = shellCloseAlreadyQueued;
                if (closeStock && !shellCloseQueued) {
                    try {
                        shellCloseQueued = shellClose.close(reason, stockRequestId);
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
                String error = first == null ? "" : summary(first);
                try {
                    events.emit("camera_producer_closed", "reason", reason,
                            "camera_tag", "pano_h", "producer_epoch", epoch,
                            "error", error);
                } catch (Throwable emitError) {
                    first = mergeFailure(first, emitError);
                }
                return new TeardownOutcome(shellCloseQueued, first);
            }

            private static Throwable detachActivityPreview(
                    PersistentCameraPort port,
                    ActivityPreviewState.Snapshot<Surface> preview) {
                if (preview == null || !preview.attached || preview.value == null) return null;
                try {
                    return port.remove(preview.value, preview.index) ? null
                            : new IllegalStateException(
                                    "rmPreviewSurface returned false for Activity preview");
                } catch (Throwable error) {
                    return root(error);
                }
            }

            private static void releaseTeardownSurfaces(Surface[] surfaces) {
                Throwable first = null;
                if (surfaces != null) {
                    for (Surface surface : surfaces) {
                        if (surface == null) continue;
                        try {
                            surface.release();
                        } catch (Throwable error) {
                            first = mergeFailure(first, error);
                        }
                    }
                }
                if (first != null) throw new IllegalStateException(
                        "camera Surface cleanup failed", first);
            }

            void rememberDetached(ConsumerGroup.Snapshot snapshot, int epoch) {
                if (snapshot == null || epoch <= 0 || snapshot.surfaces == null) return;
                DetachedConsumerIdentity identity = new DetachedConsumerIdentity(
                        snapshot.owner, snapshot.requestId, epoch);
                for (Surface target : snapshot.surfaces) {
                    if (target != null) detachedConsumerIdentities.put(target, identity);
                }
            }

            void rememberDetached(Surface target, String owner, int requestId, int epoch) {
                if (target == null || epoch <= 0) return;
                detachedConsumerIdentities.put(target,
                        new DetachedConsumerIdentity(owner, requestId, epoch));
            }

            void clearDetachedConsumerIdentities() {
                detachedConsumerIdentities.clear();
            }

            DetachedConsumerIdentity detachedConsumerIdentity(Surface target, int epoch) {
                if (target == null || epoch <= 0) return null;
                DetachedConsumerIdentity identity = detachedConsumerIdentities.get(target);
                return identity != null && identity.producerEpoch == epoch ? identity : null;
            }

            static final class DetachedConsumerIdentity {
                final String owner;
                final int requestId;
                final int producerEpoch;

                DetachedConsumerIdentity(String owner, int requestId, int producerEpoch) {
                    this.owner = owner;
                    this.requestId = requestId;
                    this.producerEpoch = producerEpoch;
                }
            }

            private ConsumerGroup.Snapshot[] activeSnapshots() {
                ArrayDeque<ConsumerGroup.Snapshot> result = new ArrayDeque<>();
                for (ConsumerGroup group : consumerGroups()) {
                    if (group.has()) result.add(group.snapshot());
                }
                return result.toArray(new ConsumerGroup.Snapshot[0]);
            }

            private static Surface removeTarget(ConsumerGroup group, int position) {
                Surface detached = group.surfaces[position];
                int removedIndex = group.indexes[position];
                int nextLength = group.surfaces.length - 1;
                Surface[] nextSurfaces = new Surface[nextLength];
                int[] nextIndexes = new int[nextLength];
                boolean[] nextActive = new boolean[nextLength];
                boolean[] nextFanoutAttached = new boolean[nextLength];
                System.arraycopy(group.surfaces, 0, nextSurfaces, 0, position);
                System.arraycopy(group.surfaces, position + 1,
                        nextSurfaces, position, nextLength - position);
                System.arraycopy(group.indexes, 0, nextIndexes, 0, position);
                System.arraycopy(group.indexes, position + 1,
                        nextIndexes, position, nextLength - position);
                System.arraycopy(group.active, 0, nextActive, 0, position);
                System.arraycopy(group.active, position + 1,
                        nextActive, position, nextLength - position);
                System.arraycopy(group.fanoutAttached, 0,
                        nextFanoutAttached, 0, position);
                System.arraycopy(group.fanoutAttached, position + 1,
                        nextFanoutAttached, position, nextLength - position);
                group.surfaces = nextSurfaces;
                group.indexes = nextIndexes;
                group.active = nextActive;
                group.fanoutAttached = nextFanoutAttached;
                boolean sourceRemains = false;
                for (int index : nextIndexes) sourceRemains |= index == removedIndex;
                if (!sourceRemains) {
                    group.sourceDemandMask &= ~CameraSourceDemand.bit(removedIndex);
                }
                return detached;
            }

            private ConsumerGroup[] groupsToDetach(ConsumerGroup target, boolean exclusive) {
                ArrayDeque<ConsumerGroup> groups = new ArrayDeque<>();
                if (target.attached) groups.add(target);
                if (exclusive) {
                    if (overlayGroup != target && overlayGroup.attached) groups.add(overlayGroup);
                    if (parkingGroup != target && parkingGroup.attached
                            && target != reverseGroup) groups.add(parkingGroup);
                    if (mirrorGroup != target && mirrorGroup.attached
                            && target != reverseGroup) groups.add(mirrorGroup);
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
                restoreFailureFatal = false;
                if (activityGroup.has() && activityGroup.exclusive) {
                    return true;
                }
                if (reverseGroup.has()) {
                    return restoreGroups(
                            port, new ConsumerGroup[]{parkingGroup, mirrorGroup,
                                    motionGroup, viewingGroup, motionPreviewGroups[0], motionPreviewGroups[1],
                                    motionPreviewGroups[2], motionPreviewGroups[3]},
                            events, shellClose, cameraId, epoch);
                }
                return restoreGroups(
                        port, new ConsumerGroup[]{overlayGroup, parkingGroup, activityGroup,
                                mirrorGroup, motionGroup, viewingGroup, motionPreviewGroups[0],
                                motionPreviewGroups[1], motionPreviewGroups[2],
                                motionPreviewGroups[3]},
                        events, shellClose, cameraId, epoch);
            }

            private boolean restoreGroups(
                    PersistentCameraPort port, ConsumerGroup[] groups,
                    PersistentEventSink events,
                    PersistentShellCloseSink shellClose, int cameraId, int epoch) {
                boolean restored = true;
                restoreFailureFatal = false;
                for (ConsumerGroup group : groups) {
                    if (!group.has() || group.attached) continue;
                    try {
                        group.fanoutAttached = attachGroup(port,
                                group.surfaces, group.indexes,
                                group.firstSurfaceDirect, group.sourceDemandMask,
                                group.requestId, epoch, false, null);
                        group.attached = true;
                        group.directSurfaceAttached = group.firstSurfaceDirect;
                        applyActiveState(group);
                        events.emit("camera_consumer_attached",
                                "camera_owner", group.owner,
                                "request_id", group.requestId,
                                "preview_indexes", Arrays.toString(group.indexes),
                                "producer_epoch", epoch,
                                "restored", true);
                    } catch (Throwable error) {
                        restoreFailure = error.getCause() == null
                                ? root(error) : root(error.getCause());
                        Throwable detachError = detachAfterRestoreFailure(port, group);
                        if (detachError != null) {
                            restoreFailureFatal = true;
                            restoreFailure.addSuppressed(detachError);
                            restored = false;
                            continue;
                        }
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
                rememberDetached(requested, epoch);
                requested.release();
                if (previous.surfaces.length == 0) return true;
                if (!previous.attached) {
                    terminalSnapshot(previous, "consumer_restore_failed", restoreFailure,
                            events, shellClose, cameraId, epoch);
                    return true;
                }
                try {
                    boolean[] attachedTargets = attachGroup(port,
                            previous.surfaces, previous.indexes,
                            previous.firstSurfaceDirect, previous.sourceDemandMask,
                            previous.requestId, epoch, false, null);
                    target.set(previous.surfaces, previous.indexes, previous.requestId,
                            previous.view, previous.exclusive, previous.shellOwned,
                            true, previous.firstSurfaceDirect, previous.sourceDemandMask);
                    target.fanoutAttached = attachedTargets;
                    target.restoreActive(previous.active);
                    applyActiveState(target);
                    events.emit("camera_consumer_attached",
                            "camera_owner", previous.owner,
                            "request_id", previous.requestId,
                            "preview_indexes", Arrays.toString(previous.indexes),
                            "producer_epoch", epoch, "restored", true);
                    return true;
                } catch (Throwable error) {
                    restoreFailure = root(error);
                    Throwable detachError = detachAfterRestoreFailure(port, target);
                    if (detachError != null) {
                        restoreFailureFatal = true;
                        restoreFailure.addSuppressed(detachError);
                        return false;
                    }
                    rememberDetached(target.snapshot(), epoch);
                    target.clear();
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
                rememberDetached(failed, epoch);
                terminalSnapshot(failed, reason, error,
                        events, shellClose, cameraId, epoch);
            }

            private Throwable detachAfterRestoreFailure(
                    PersistentCameraPort port, ConsumerGroup group) {
                try {
                    detachGroup(port, group);
                    return null;
                } catch (Throwable error) {
                    return root(error);
                }
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
                ArrayList<Surface> attached = new ArrayList<>();
                for (int i = 0; i < group.surfaces.length; i++) {
                    if (!group.fanoutAttached[i]) continue;
                    attached.add(group.surfaces[i]);
                }
                if (!attached.isEmpty()) {
                    try {
                        fanout.detach(attached.toArray(new Surface[0]));
                        Arrays.fill(group.fanoutAttached, false);
                    } catch (Exception error) {
                        if (first == null) first = error;
                    }
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
                if (!group.attached) {
                    rememberDetached(failedSurface, group.owner, group.requestId, epoch);
                    return false;
                }
                int failedPosition = group.indexOf(failedSurface);
                if (failedIndex == 4
                        && failedPosition >= 0
                        && group.indexes[failedPosition] == 4
                        && (group == reverseGroup
                        || group == activityGroup
                        && isActivityReversePreviewView(group.view))) {
                    Surface detached = removeTarget(group, failedPosition);
                    rememberDetached(detached, group.owner, group.requestId, epoch);
                    if (detached != null) detached.release();
                    try {
                        reconcileSourceDemand(port);
                    } catch (Throwable cleanupError) {
                        throw new PersistentSessionFailure(
                                "source_demand_reconcile_failed", root(cleanupError), true,
                                true, false);
                    }
                    events.emit("camera_consumer_detached",
                            "camera_owner", group.owner,
                            "request_id", group.requestId,
                            "view", group.view == null ? "unknown" : group.view,
                            "preview_index", failedIndex,
                            "preview_indexes", Arrays.toString(new int[]{failedIndex}),
                            "reason", "optional_reverse_target_failed",
                            "producer_epoch", epoch);
                    return true;
                }
                ConsumerGroup.Snapshot failed = group.snapshot();
                try {
                    detachGroup(port, group);
                } catch (Throwable cleanupError) {
                    throw new PersistentSessionFailure(
                            "consumer_detach_failed", root(cleanupError), true,
                            true, false);
                }
                rememberDetached(failed, epoch);
                group.clear();
                boolean shellCloseQueued = failed.shellOwned
                        && shellClose.close("consumer_render_failed", failed.requestId);
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
                try {
                    reconcileSourceDemand(port);
                } catch (Throwable cleanupError) {
                    throw new PersistentSessionFailure(
                            "source_demand_reconcile_failed", root(cleanupError), true,
                            true, shellCloseQueued);
                }
                return true;
            }

            private ConsumerGroup groupContaining(Surface target) {
                for (ConsumerGroup group : consumerGroups()) {
                    if (contains(group.surfaces, target)) return group;
                }
                return null;
            }

            private static boolean contains(Surface[] surfaces, Surface target) {
                for (Surface surface : surfaces) if (surface == target) return true;
                return false;
            }

            private boolean[] attachGroup(
                    PersistentCameraPort port, Surface[] surfaces, int[] indexes,
                    boolean firstSurfaceDirect, int sourceDemandMask,
                    int requestId, int producerEpoch, boolean deferRollbackOnFailure,
                    boolean[] directAttemptedOnCall) throws Exception {
                return attachGroup(port, surfaces, indexes, firstSurfaceDirect, sourceDemandMask,
                        requestId, producerEpoch, deferRollbackOnFailure, directAttemptedOnCall, null);
            }

            private boolean[] attachGroup(PersistentCameraPort port, Surface[] surfaces, int[] indexes,
                    boolean firstSurfaceDirect, int sourceDemandMask, int requestId,
                    int producerEpoch, boolean deferRollbackOnFailure,
                    boolean[] directAttemptedOnCall, boolean[] initialActive) throws Exception {
                ArrayList<Surface> targetSurfaces = new ArrayList<>();
                ArrayList<Integer> targetIndexValues = new ArrayList<>();
                ArrayList<Boolean> targetActivity = new ArrayList<>();
                boolean[] attachedTargets = new boolean[surfaces.length];
                for (int i = firstSurfaceDirect ? 1 : 0; i < surfaces.length; i++) {
                    int index = indexes[i];
                    if (index < 0
                            || (sourceDemandMask & CameraSourceDemand.bit(index)) == 0) {
                        continue;
                    }
                    targetSurfaces.add(surfaces[i]);
                    targetIndexValues.add(index);
                    if (initialActive != null) targetActivity.add(initialActive[i]);
                    attachedTargets[i] = true;
                }
                Surface[] targets = targetSurfaces.toArray(new Surface[0]);
                int[] targetIndexes = new int[targetIndexValues.size()];
                boolean[] activeTargets = initialActive == null ? null : new boolean[targetIndexes.length];
                for (int i = 0; i < targetIndexes.length; i++) {
                    targetIndexes[i] = targetIndexValues.get(i);
                    if (activeTargets != null) activeTargets[i] = targetActivity.get(i);
                }
                boolean fanoutAttached = false;
                boolean directAttempted = false;
                try {
                    if (targets.length > 0) {
                        fanout.attach(targets, targetIndexes, requestId, producerEpoch, activeTargets);
                        fanoutAttached = true;
                    }
                    if (firstSurfaceDirect) {
                        directAttempted = true;
                        if (directAttemptedOnCall != null) directAttemptedOnCall[0] = true;
                        if (!port.add(surfaces[0], indexes[0])) {
                            throw new IllegalStateException(
                                    "addPreviewSurface returned false for direct index "
                                            + indexes[0]);
                        }
                    }
                } catch (Exception error) {
                    if (deferRollbackOnFailure) throw error;
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
                return attachedTargets;
            }

            private void applyActiveState(ConsumerGroup group) throws Exception {
                if (!group.attached || fanout == null) return;
                for (int i = 0; i < group.surfaces.length; i++) {
                    if (group.fanoutAttached[i] && !group.active[i]) {
                        fanout.setActive(group.surfaces[i], false);
                    }
                }
            }

            void setActive(ConsumerGroup group, Surface surface, boolean active)
                    throws Exception {
                if (fanout == null || !group.attached) {
                    throw new IllegalStateException("overlay target is not attached");
                }
                int index = group.indexOf(surface);
                if (index < 0) throw new IllegalStateException("overlay Surface is not attached");
                group.active[index] = active;
                if (group.fanoutAttached[index]) fanout.setActive(surface, active);
            }

            private void detachRequestedGroup(
                    PersistentCameraPort port, Surface[] surfaces, int[] indexes,
                    boolean firstSurfaceDirect, boolean directAttempted,
                    int sourceDemandMask) throws Exception {
                Exception first = null;
                if (firstSurfaceDirect && directAttempted) {
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
                ArrayList<Surface> attached = new ArrayList<>();
                for (int i = firstSurfaceDirect ? 1 : 0; i < surfaces.length; i++) {
                    int index = indexes[i];
                    if (index >= 0
                            && (sourceDemandMask & CameraSourceDemand.bit(index)) != 0) {
                        attached.add(surfaces[i]);
                    }
                }
                if (!attached.isEmpty()) {
                    try {
                        fanout.detach(attached.toArray(new Surface[0]));
                    } catch (Exception error) {
                        if (first == null) first = error;
                        else if (first != error) first.addSuppressed(error);
                    }
                }
                if (first != null) throw first;
            }

            private Throwable detachDirectInputs(PersistentCameraPort port) {
                Throwable first = null;
                for (ConsumerGroup group : new ConsumerGroup[]{
                        activityGroup, overlayGroup, parkingGroup, reverseGroup, mirrorGroup}) {
                    if (!group.directSurfaceAttached) continue;
                    try {
                        if (!port.remove(group.surfaces[0], group.indexes[0])) {
                            first = mergeFailure(first, new IllegalStateException(
                                    "rmPreviewSurface returned false for direct index "
                                            + group.indexes[0]));
                        } else {
                            group.directSurfaceAttached = false;
                        }
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
                return first;
            }

            void setBlindStandbyDemand(PersistentCameraPort port, int mask)
                    throws Exception {
                blindStandbyDemandMask = mask;
                reconcileIfOpen(port);
            }

            void setMirrorDemand(PersistentCameraPort port, int mask) throws Exception {
                mirrorDemandMask = mask;
                if (mirrorGroup.has()) {
                    int available = CameraSourceDemand.fromIndexes(
                            mirrorGroup.indexes, mirrorGroup.firstSurfaceDirect);
                    mirrorGroup.sourceDemandMask = mask & available;
                }
                reconcileIfOpen(port);
            }

            void setGroupSourceDemand(
                    PersistentCameraPort port, ConsumerGroup group, int mask)
                    throws Exception {
                int available = CameraSourceDemand.fromIndexes(
                        group.indexes, group.firstSurfaceDirect);
                group.sourceDemandMask = mask & available;
                reconcileIfOpen(port);
            }

            boolean expireReverseDemand(
                    PersistentCameraPort port, long generation,
                    int requestId, int epoch) throws Exception {
                if (!reverseWarmLease.expire(generation, requestId, epoch)) return false;
                reconcileIfOpen(port);
                return true;
            }

            void disableReverseDemand(PersistentCameraPort port) throws Exception {
                reverseWarmLease.clear();
                reverseGroup.sourceDemandMask = 0;
                if (isReverseOwner(activityGroup)) activityGroup.sourceDemandMask = 0;
                reconcileIfOpen(port);
            }

            int retainedSourceDemandMask() {
                int mask = vehiclePowerOff ? 0 : blindStandbyDemandMask | mirrorDemandMask
                        | reverseWarmLease.sourceMask();
                for (ConsumerGroup group : consumerGroups()) {
                    if (group.has()) {
                        mask |= effectiveGroupSourceDemandMask(
                                vehiclePowerOff, group.owner, group.sourceDemandMask);
                    }
                }
                return mask;
            }

            long rebindReverseWarmLease(int oldEpoch, int newEpoch) {
                int requestId = reverseWarmLease.requestId();
                if (requestId <= 0 || reverseWarmLease.producerEpoch() != oldEpoch) return 0L;
                return reverseWarmLease.arm(
                        requestId, newEpoch, reverseWarmLease.sourceMask());
            }

            boolean isIdle() {
                return retainedSourceDemandMask() == 0 && !hasConsumers();
            }

            boolean hasConsumers() {
                for (ConsumerGroup group : consumerGroups()) if (group.has()) return true;
                return motionDetectionWanted;
            }

            private void reconcileIfOpen(PersistentCameraPort port) throws Exception {
                if (!producerOpen || fanout == null) return;
                if (port == null) {
                    throw new IllegalStateException(
                            "camera port required while reconciling source demand");
                }
                reconcileSourceDemand(port);
            }

            private void reconcileSourceDemand(PersistentCameraPort port) throws Exception {
                // The owner checks Activity preview too, then stops the producer before detaching.
                if (isIdle()) return;
                int required = retainedSourceDemandMask();
                try {
                    ensureSources(port, indexesFromMask(required));
                } catch (Exception error) {
                    if (!vehiclePowerOff) throw error;
                    recordVehiclePowerOffReconcileFailure(error);
                }
                for (ConsumerGroup group : consumerGroups()) {
                    if (!group.has() || !group.attached) continue;
                    for (int i = 0; i < group.surfaces.length; i++) {
                        int index = group.indexes[i];
                        int groupDemand = effectiveGroupSourceDemandMask(
                                vehiclePowerOff, group.owner, group.sourceDemandMask);
                        boolean demanded = (groupDemand
                                & CameraSourceDemand.bit(index)) != 0;
                        if (group.fanoutAttached[i] && !demanded) {
                            boolean ordinarySuppressed = vehiclePowerOff
                                    && isOrdinaryCameraGroupOwner(group.owner);
                            if (ordinarySuppressed) {
                                try {
                                    fanout.setActive(group.surfaces[i], false);
                                    group.active[i] = false;
                                } catch (Exception error) {
                                    recordVehiclePowerOffReconcileFailure(error);
                                }
                            }
                            try {
                                fanout.detach(new Surface[]{group.surfaces[i]});
                                group.fanoutAttached[i] = false;
                                if (ordinarySuppressed) group.active[i] = false;
                            } catch (Exception error) {
                                if (!vehiclePowerOff) throw error;
                                recordVehiclePowerOffReconcileFailure(error);
                            }
                        } else if (!group.fanoutAttached[i] && demanded) {
                            try {
                                fanout.attach(new Surface[]{group.surfaces[i]}, new int[]{index},
                                        group.requestId, activeProducerEpoch);
                                group.fanoutAttached[i] = true;
                                if (!group.active[i]) fanout.setActive(group.surfaces[i], false);
                            } catch (Exception error) {
                                if (!vehiclePowerOff) throw error;
                                recordVehiclePowerOffReconcileFailure(error);
                            }
                        }
                    }
                }
                for (int index = 0; index < sourceAttached.length; index++) {
                    if ((required & CameraSourceDemand.bit(index)) != 0) continue;
                    if (sourceAttached[index]) {
                        Surface source = sourceSurfaces[index];
                        try {
                            if (!port.remove(source, index)) {
                                throw new IllegalStateException(
                                        "rmPreviewSurface returned false for idle RAW source index "
                                                + index);
                            }
                        } catch (Exception error) {
                            if (!vehiclePowerOff) throw error;
                            recordVehiclePowerOffReconcileFailure(error);
                            continue;
                        }
                        sourceAttached[index] = false;
                    }
                    // A fanout target may still own this RAW Surface after a failed detach.
                    // Keep its source worker and reference until a later reconcile confirms
                    // that the target is gone; the OEM input above is already detached.
                    if (hasAttachedFanoutTarget(index)) continue;
                    Surface source = sourceSurfaces[index];
                    if (source == null) continue;
                    try {
                        fanout.releaseSource(index);
                        sourceSurfaces[index] = null;
                    } catch (Exception error) {
                        if (!vehiclePowerOff) throw error;
                        recordVehiclePowerOffReconcileFailure(error);
                    }
                }
            }

            private boolean hasAttachedFanoutTarget(int sourceIndex) {
                for (ConsumerGroup group : consumerGroups()) {
                    for (int i = 0; i < group.fanoutAttached.length; i++) {
                        if (group.fanoutAttached[i] && group.indexes[i] == sourceIndex) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private static int[] indexesFromMask(int mask) {
                int count = Integer.bitCount(mask & 0x1f);
                int[] indexes = new int[count];
                int offset = 0;
                for (int index = 0; index <= 4; index++) {
                    if ((mask & CameraSourceDemand.bit(index)) != 0) {
                        indexes[offset++] = index;
                    }
                }
                return indexes;
            }

            private boolean isReverseOwner(ConsumerGroup group) {
                return group == reverseGroup || group == activityGroup
                        && isActivityReversePreviewView(group.view);
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
                if (motionDetectionAttached && motionDetectionSurface != null) {
                    try {
                        if (!port.remove(motionDetectionSurface, 4123))
                            first = new IllegalStateException("motion input detach failed");
                        else motionDetectionAttached = false;
                    } catch (Throwable error) { first = mergeFailure(first, error); }
                }
                for (int index = 0; index < sourceAttached.length; index++) {
                    if (!sourceAttached[index]) continue;
                    try {
                        if (!port.remove(sourceSurfaces[index], index)) {
                            first = mergeFailure(first, new IllegalStateException(
                                    "rmPreviewSurface returned false for RAW index " + index));
                        } else {
                            sourceAttached[index] = false;
                            sourceSurfaces[index] = null;
                        }
                    } catch (Throwable error) {
                        first = mergeFailure(first, error);
                    }
                }
                return first;
            }

            private void clearSources() {
                Arrays.fill(sourceSurfaces, null);
                Arrays.fill(sourceAttached, false);
                clearMotionInput();
            }

            void attachMotionInput(PersistentCameraPort port) throws Exception {
                if (!motionDetectionWanted || motionDetectionAttached) return;
                motionDetectionAttached = true;
                if (!port.add(motionDetectionSurface, 4123))
                    throw new IllegalStateException("motion addPreviewSurface returned false");
            }

            void clearMotionInput() {
                if (motionDetectionSurface != null) motionDetectionSurface.release();
                motionDetectionSurface = null;
                motionDetectionWanted = false;
                motionDetectionAttached = false;
            }
        }

        static final class CloseOutcome {
            final PersistentCloseDecision decision;
            final boolean shellCloseQueued;
            final long reverseWarmGeneration;
            final boolean reverseWarmArmed;

            CloseOutcome(PersistentCloseDecision decision, boolean shellCloseQueued) {
                this(decision, shellCloseQueued, 0L, false);
            }

            CloseOutcome(PersistentCloseDecision decision, boolean shellCloseQueued,
                    long reverseWarmGeneration, boolean reverseWarmArmed) {
                this.decision = decision;
                this.shellCloseQueued = shellCloseQueued;
                this.reverseWarmGeneration = reverseWarmGeneration;
                this.reverseWarmArmed = reverseWarmArmed;
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
            public boolean stop() throws Exception {
                return invokeBoolean(type, target, "stopPreview", new Class<?>[0]);
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
            boolean[] active = new boolean[0];
            boolean[] fanoutAttached = new boolean[0];
            int sourceDemandMask;
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
                set(nextSurfaces, nextIndexes, nextRequestId, nextView, nextExclusive,
                        nextShellOwned, nextAttached, nextFirstSurfaceDirect,
                        CameraSourceDemand.fromIndexes(nextIndexes, nextFirstSurfaceDirect));
            }

            void set(
                    Surface[] nextSurfaces, int[] nextIndexes, int nextRequestId,
                    String nextView, boolean nextExclusive,
                    boolean nextShellOwned, boolean nextAttached,
                    boolean nextFirstSurfaceDirect, int nextSourceDemandMask) {
                surfaces = nextSurfaces;
                indexes = nextIndexes.clone();
                active = new boolean[nextSurfaces.length];
                fanoutAttached = new boolean[nextSurfaces.length];
                Arrays.fill(active, true);
                sourceDemandMask = nextSourceDemandMask;
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
                        exclusive, shellOwned, attached, firstSurfaceDirect,
                        active, fanoutAttached, sourceDemandMask);
            }

            int indexOf(Surface target) {
                for (int i = 0; i < surfaces.length; i++) {
                    if (surfaces[i] == target) return i;
                }
                return -1;
            }

            int indexOfIndex(int sourceIndex) {
                for (int i = 0; i < indexes.length; i++) {
                    if (indexes[i] == sourceIndex) return i;
                }
                return -1;
            }

            void restoreActive(boolean[] values) {
                if (values == null || values.length != surfaces.length) {
                    active = new boolean[surfaces.length];
                    Arrays.fill(active, true);
                } else {
                    active = values.clone();
                }
            }

            void clear() {
                surfaces = new Surface[0];
                indexes = new int[0];
                active = new boolean[0];
                fanoutAttached = new boolean[0];
                sourceDemandMask = 0;
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
                final boolean[] active;
                final boolean[] fanoutAttached;
                final int sourceDemandMask;

                Snapshot(
                        String owner, Surface[] surfaces, int[] indexes,
                        int requestId, String view, boolean exclusive,
                        boolean shellOwned, boolean attached,
                        boolean firstSurfaceDirect, boolean[] active,
                        boolean[] fanoutAttached, int sourceDemandMask) {
                    this.owner = owner;
                    this.surfaces = surfaces;
                    this.indexes = indexes.clone();
                    this.requestId = requestId;
                    this.view = view;
                    this.exclusive = exclusive;
                    this.shellOwned = shellOwned;
                    this.attached = attached;
                    this.firstSurfaceDirect = firstSurfaceDirect;
                    this.active = active == null ? new boolean[surfaces.length] : active.clone();
                    this.fanoutAttached = fanoutAttached == null
                            ? new boolean[surfaces.length] : fanoutAttached.clone();
                    this.sourceDemandMask = sourceDemandMask;
                    if (active == null) Arrays.fill(this.active, true);
                }

                void release() {
                    releaseSurfaces(surfaces);
                }

                void releaseExcept(Surface[] keep) {
                    for (Surface surface : surfaces) {
                        if (surface != null && !containsIdentity(keep, surface)) surface.release();
                    }
                }

                private static boolean containsIdentity(Surface[] values, Surface target) {
                    if (values == null) return false;
                    for (Surface value : values) if (value == target) return true;
                    return false;
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

        private static final class ReversePreviewRequest {
            final Surface panoOutput;
            final Surface[] directSurfaces;
            final int[] directIndexes;

            ReversePreviewRequest(
                    Surface panoOutput, Surface[] directSurfaces, int[] directIndexes) {
                this.panoOutput = panoOutput;
                this.directSurfaces = directSurfaces;
                this.directIndexes = directIndexes;
            }
        }

        private static ReversePreviewRequest readTargetedReversePreview(Parcel data, int callerUid) {
            int hasPanorama = data.readInt();
            if (hasPanorama != 0 && hasPanorama != 1) {
                throw new IllegalArgumentException("invalid Pano-base flag");
            }
            Surface panoOutput = null;
            Surface[] directSurfaces = new Surface[0];
            int[] directIndexes = new int[0];
            try {
                if (hasPanorama == 1) {
                    panoOutput = CameraGpuOutput.readSurface(data, callerUid);
                }
                int count = data.readInt();
                if (count < 1 || count > 4) {
                    throw new IllegalArgumentException(
                            "one to four direct reverse Surface/index pairs required");
                }
                directSurfaces = new Surface[count];
                directIndexes = new int[count];
                for (int i = 0; i < count; i++) {
                    directIndexes[i] = data.readInt();
                    directSurfaces[i] = CameraGpuOutput.readSurface(data, callerUid);
                }
                String validation = validateReversePreviewTargets(
                        panoOutput, directSurfaces, directIndexes);
                if (validation != null) throw new IllegalArgumentException(validation);
                return new ReversePreviewRequest(panoOutput, directSurfaces, directIndexes);
            } catch (RuntimeException | Error error) {
                releaseSurface(panoOutput);
                releaseSurfaces(directSurfaces);
                throw error;
            }
        }

        private static Surface[] readReverseSurfaces(Parcel data) {
            int count = data.readInt();
            if (count != 4 && count != 5) {
                throw new IllegalArgumentException(
                        "four or five reverse preview Surfaces required");
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

        private static int[] reverseIndexes(int directCount) {
            int[] indexes = new int[directCount];
            for (int i = 0; i < directCount; i++) indexes[i] = i + 1;
            return indexes;
        }

        private static boolean isCameraErrorResult(String value) {
            try {
                return value != null
                        && "camera_error".equals(new JSONObject(value).optString("kind"));
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean isCameraBusyResult(String value) {
            try {
                return value != null
                        && "camera_busy".equals(new JSONObject(value).optString("kind"));
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static void releaseSurfaces(Surface[] values) {
            if (values == null) return;
            for (Surface value : values) {
                if (value != null) value.release();
            }
        }

        private static void releaseSurface(Surface value) {
            if (value != null) value.release();
        }

        private InvocationHandler eventHandler(Object source) {
            return (proxy, method, args) -> {
                if ("onEvent".equals(method.getName()) && args != null && args.length >= 4) {
                    emit("avm_event", "type", args[1], "arg1", args[2], "arg2", args[3]);
                    if (args[1] instanceof Number && nativeSourceDied(((Number) args[1]).intValue())) {
                        callbackHandler.post(() -> {
                            synchronized (HelperBinder.this) {
                                if (source != camera) return; // Late event from an already replaced source.
                                if (persistentPanoProducer) tearDownPersistentProducer(
                                        NATIVE_SOURCE_RECOVERY, new IllegalStateException("OEM camera service died"));
                                else closeOneShotCamera(NATIVE_SOURCE_RECOVERY, false);
                            }
                        });
                    }
                    return null;
                }
                if ("toString".equals(method.getName())) return "CameraProbeEventCallback";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                return null;
            };
        }

        private void emit(String kind, Object... fields) {
            if ("camera_error".equals(kind)
                    && "camera_viewing".equals(fieldString(fields, "camera_owner"))
                    && viewingOutput != null) viewingOutput.failed("camera_source_failed");
            if (!DiagnosticLogPolicy.shouldProduce(kind)) return;
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
            Object[] forwarded = reverseStockControllerFields(kind, fields);
            if (!hasReverseStockComponent(forwarded)
                    && fieldString(forwarded, "renderer").startsWith("stock_avm")
                    && ("camera_error".equals(kind) || "camera_closed".equals(kind))) {
                settleOrdinaryStockTerminal(requestId(forwarded), "camera_error".equals(kind));
            }
            emit(kind, forwarded);
            if ("camera_shell_died".equals(kind)) {
                cameraShellDiedCleanup();
                if (!Boolean.parseBoolean(fieldString(forwarded, "planned_restart"))) {
                    turnController.recoverCameraHelper();
                }
            } else if ("stock_avm_shell_died".equals(kind)) {
                if (!hasReverseStockContext() || isCurrentReverseStockDeath(forwarded)) {
                    stockAvmShellDiedCleanup();
                }
            } else if ("camera_closed".equals(kind)
                    && hasReverseStockComponent(forwarded)) {
                settleReverseStockBackgroundClosed(
                        requestId(forwarded), fieldInt(forwarded, "producer_epoch", 0));
            } else if ("camera_error".equals(kind)
                    && hasReverseStockComponent(forwarded)) {
                settleReverseStockBackgroundError(
                        requestId(forwarded), fieldInt(forwarded, "producer_epoch", 0));
            }
        }

        private synchronized boolean hasReverseStockContext() {
            return reverseStockActive || reverseStockClosePending
                    || reverseStockRetiredRequestId > 0 && activityGroup.has()
                    && "reverse_preview_with_stock_base".equals(activityGroup.view);
        }

        private synchronized boolean isCurrentReverseStockDeath(Object[] fields) {
            return isCurrentReverseStockDeath(
                    requestId(fields), fieldLong(fields, "avm_shell_epoch", 0L));
        }

        private synchronized boolean isCurrentReverseStockDeath(JSONObject event) {
            return isCurrentReverseStockDeath(
                    event.optInt("request_id", 0), event.optLong("avm_shell_epoch", 0L));
        }

        private synchronized boolean isCurrentReverseStockDeath(
                int eventRequestId, long eventEpoch) {
            if (reverseStockActive) {
                return (eventRequestId <= 0 || eventRequestId == reverseStockRequestId)
                        && (reverseStockAvmShellEpoch <= 0
                        || eventEpoch > 0 && eventEpoch == reverseStockAvmShellEpoch);
            }
            if (reverseStockClosePending) {
                return (eventRequestId <= 0 || eventRequestId == reverseStockCloseRequestId)
                        && (reverseStockAvmShellEpoch <= 0
                        || eventEpoch > 0 && eventEpoch == reverseStockAvmShellEpoch);
            }
            return false;
        }

        private synchronized Object[] reverseStockControllerFields(
                String kind, Object[] fields) {
            if (!isReverseStockControllerEvent(kind, fields)) return fields;
            int requestId = requestId(fields);
            boolean active = reverseStockActive
                    && (requestId <= 0 || requestId == reverseStockRequestId);
            boolean closing = reverseStockClosePending
                    && (requestId > 0 && requestId == reverseStockCloseRequestId
                    || requestId <= 0 && "stock_avm_shell_died".equals(kind));
            boolean stale = requestId > 0 && !active && !closing
                    && (reverseStockActive || reverseStockClosePending);
            if (requestId <= 0) {
                requestId = active ? reverseStockRequestId
                        : closing ? reverseStockCloseRequestId : reverseStockRetiredRequestId;
            }
            int epoch = active ? reverseStockProducerEpoch
                    : closing ? reverseStockCloseProducerEpoch
                    : stale ? (reverseStockActive
                            ? reverseStockProducerEpoch : reverseStockCloseProducerEpoch)
                    : reverseStockRetiredProducerEpoch;
            Object[] scoped = Arrays.copyOf(fields, fields.length + 8);
            int at = fields.length;
            scoped[at++] = "component";
            scoped[at++] = "reverse_preview_background";
            scoped[at++] = "camera_owner";
            scoped[at++] = CAMERA_OWNER_ACTIVITY;
            scoped[at++] = "request_id";
            scoped[at++] = requestId;
            scoped[at++] = "producer_epoch";
            scoped[at] = epoch;
            return scoped;
        }

        private synchronized boolean isReverseStockControllerEvent(
                String kind, Object[] fields) {
            if (fields == null) return false;
            if (!"camera_error".equals(kind)
                    && !"stock_avm_shell_died".equals(kind)
                    && !"stock_avm_config_stage".equals(kind)
                    && !"camera_closed".equals(kind)) return false;
            String renderer = fieldString(fields, "renderer");
            // Generic controller errors are not stock-background errors.  Only an explicit
            // stock renderer (or a stock-specific event kind) may be scoped here.
            if (("camera_error".equals(kind) || "camera_closed".equals(kind))
                    && !renderer.startsWith("stock_avm")) {
                return false;
            }
            int eventRequestId = requestId(fields);
            int eventEpoch = fieldInt(fields, "producer_epoch", 0);
            if ("camera_closed".equals(kind)) {
                return reverseStockCloseIdentityMatches(
                        eventRequestId, eventEpoch,
                        reverseStockActive, reverseStockRequestId, reverseStockProducerEpoch,
                        reverseStockClosePending, reverseStockCloseRequestId,
                        reverseStockCloseProducerEpoch,
                        reverseStockRetiredRequestId, reverseStockRetiredProducerEpoch);
            }
            long shellEpoch = fieldLong(fields, "avm_shell_epoch", 0L);
            boolean shellDeath = "stock_avm_shell_died".equals(kind);
            boolean activeRequestMatch = "camera_closed".equals(kind)
                    ? eventRequestId > 0 && eventRequestId == reverseStockRequestId
                    : eventRequestId <= 0 || eventRequestId == reverseStockRequestId;
            boolean shellEpochMatch = !shellDeath || reverseStockAvmShellEpoch <= 0
                    || shellEpoch > 0 && shellEpoch == reverseStockAvmShellEpoch;
            if (reverseStockActive
                    && activeRequestMatch
                    && (eventEpoch <= 0 || eventEpoch == reverseStockProducerEpoch)
                    && shellEpochMatch) {
                return true;
            }
            if (reverseStockClosePending
                    && (eventRequestId > 0 && eventRequestId == reverseStockCloseRequestId
                    || eventRequestId <= 0 && "stock_avm_shell_died".equals(kind))
                    && (eventEpoch <= 0 || eventEpoch == reverseStockCloseProducerEpoch)
                    && shellEpochMatch) {
                return true;
            }
            // A request-scoped stock callback from an older Reverse request must remain
            // background-scoped, even though it cannot settle the current request.
            if (eventRequestId > 0 && !"camera_closed".equals(kind)
                    && (reverseStockActive || reverseStockClosePending)) {
                return true;
            }
            // TurnSignalController reports shell death without request_id, then emits the
            // request-scoped camera_error.  Keep only that retired identity for the pair.
            return eventRequestId > 0
                    && eventRequestId == reverseStockRetiredRequestId
                    && (fieldInt(fields, "producer_epoch", 0) <= 0
                    || fieldInt(fields, "producer_epoch", 0) == reverseStockRetiredProducerEpoch);
        }

        static boolean reverseStockCloseIdentityMatches(
                int eventRequestId, int eventProducerEpoch,
                boolean active, int activeRequestId, int activeProducerEpoch,
                boolean closing, int closeRequestId, int closeProducerEpoch,
                int retiredRequestId, int retiredProducerEpoch) {
            if (eventRequestId <= 0) return false;
            return (active && eventRequestId == activeRequestId
                    && producerEpochMatches(eventProducerEpoch, activeProducerEpoch)
                    || closing && eventRequestId == closeRequestId
                    && producerEpochMatches(eventProducerEpoch, closeProducerEpoch)
                    || eventRequestId == retiredRequestId
                    && producerEpochMatches(eventProducerEpoch, retiredProducerEpoch));
        }

        static int[] reverseStockRetiredIdentityAfterShellDeath(
                boolean active, int activeRequestId, int activeProducerEpoch,
                boolean closing, int closeRequestId, int closeProducerEpoch) {
            if (closing && closeRequestId > 0 && closeProducerEpoch > 0) {
                return new int[]{closeRequestId, closeProducerEpoch};
            }
            if (active && activeRequestId > 0 && activeProducerEpoch > 0) {
                return new int[]{activeRequestId, activeProducerEpoch};
            }
            return new int[]{0, 0};
        }

        private static boolean producerEpochMatches(int eventEpoch, int expectedEpoch) {
            return expectedEpoch > 0 && (eventEpoch <= 0 || eventEpoch == expectedEpoch);
        }

        private static String fieldString(Object[] fields, String name) {
            if (fields == null) return "";
            for (int i = 0; i + 1 < fields.length; i += 2) {
                if (name.equals(String.valueOf(fields[i]))) {
                    return String.valueOf(fields[i + 1]);
                }
            }
            return "";
        }

        private static int fieldInt(Object[] fields, String name, int fallback) {
            String value = fieldString(fields, name);
            if (value.isEmpty()) return fallback;
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static long fieldLong(Object[] fields, String name, long fallback) {
            String value = fieldString(fields, name);
            if (value.isEmpty()) return fallback;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private synchronized boolean hasReverseStockComponent(Object[] fields) {
            return "reverse_preview_background".equals(
                    fieldString(fields, "component"));
        }

        private synchronized void settleReverseStockBackgroundError(
                int eventRequestId, int eventEpoch) {
            if (!reverseStockActive) return;
            int requestId = eventRequestId > 0 ? eventRequestId : reverseStockRequestId;
            if (!reverseStockRequestMatches(requestId)
                    || eventEpoch > 0 && reverseStockProducerEpoch != eventEpoch) return;
            if (reverseStockInputAttached) {
                cancelPendingStock("reverse_preview_stock_error", requestId);
                stockAvmShellDiedCleanup();
                return;
            }
            stockRequest.clear();
            pendingReversePreviewRequestId = 0;
            reverseStockRetiredRequestId = requestId;
            reverseStockRetiredProducerEpoch = reverseStockProducerEpoch;
            reverseStockActive = false;
            reverseStockInputAttached = false;
            reverseStockRequestId = 0;
            reverseStockProducerEpoch = 0;
            reverseStockRetainedCallbackEpoch = 0;
            reverseStockAvmShellEpoch = 0L;
        }

        private synchronized void settleReverseStockBackgroundClosed(
                int eventRequestId, int eventEpoch) {
            if (eventRequestId <= 0) return;
            boolean active = reverseStockActive
                    && reverseStockRequestId == eventRequestId
                    && (eventEpoch <= 0 || eventEpoch == reverseStockProducerEpoch);
            boolean closing = reverseStockClosePending
                    && reverseStockCloseRequestId == eventRequestId
                    && (eventEpoch <= 0 || eventEpoch == reverseStockCloseProducerEpoch);
            boolean retired = reverseStockRetiredRequestId == eventRequestId
                    && (eventEpoch <= 0 || eventEpoch == reverseStockRetiredProducerEpoch);
            if (active) {
                if (reverseStockInputAttached) {
                    detachReverseStockInput("stock_avm_shell_closed");
                }
                reverseStockRetiredRequestId = eventRequestId;
                reverseStockRetiredProducerEpoch = reverseStockProducerEpoch;
                clearReverseStockState();
                return;
            }
            if (closing) clearReverseStockCloseState();
            if (retired) {
                reverseStockRetiredRequestId = 0;
                reverseStockRetiredProducerEpoch = 0;
            }
        }

        private synchronized void stockAvmShellDiedCleanup() {
            boolean stockWasActive = stockRequest.isActive()
                    || (camera != null && CAMERA_OWNER_ACTIVITY.equals(activeCameraOwner)
                    && viewName != null && !viewName.startsWith("direct_"));
            stockRequest.clear();
            boolean reverseStock = reverseStockActive
                    || reverseStockInputAttached
                    || reverseStockClosePending;
            if (reverseStockActive || reverseStockClosePending) {
                int[] retired = reverseStockRetiredIdentityAfterShellDeath(
                        reverseStockActive, reverseStockRequestId, reverseStockProducerEpoch,
                        reverseStockClosePending, reverseStockCloseRequestId,
                        reverseStockCloseProducerEpoch);
                if (retired[0] > 0) {
                    reverseStockRetiredRequestId = retired[0];
                    reverseStockRetiredProducerEpoch = retired[1];
                }
            }
            reverseStockActive = false;
            reverseStockInputAttached = false;
            reverseStockRequestId = 0;
            reverseStockProducerEpoch = 0;
            reverseStockRetainedCallbackEpoch = 0;
            reverseStockAvmShellEpoch = 0L;
            reverseStockClosePending = false;
            reverseStockCloseRequestId = 0;
            reverseStockCloseProducerEpoch = 0;
            releaseSurfaces(pendingReversePreviewSurfaces);
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            if (!persistentPanoProducer) {
                if (stockWasActive && camera != null) {
                    closeOneShotCamera("stock_avm_shell_died", false);
                } else if (camera == null) {
                    emit("camera_closed", "renderer", "stock_avm_shell",
                            "view", viewName == null ? "unknown" : viewName,
                            "reason", "stock_avm_shell_died", "request_id", activeCameraRequestId,
                            "component", reverseStock ? "reverse_preview_background" : "stock_avm",
                            "error", "");
                }
                return;
            }
            try {
                persistentSession.invalidateStockAvmGroup(
                        new ReflectivePersistentCameraPort(camera),
                        "stock_avm_shell_died", this::emit,
                        producerCameraId, producerEpoch);
                refreshPersistentLegacyState();
                closePersistentProducerIfIdle("stock_avm_shell_died", true);
            } catch (PersistentSessionFailure failure) {
                tearDownPersistentProducer(failure.reason, failure.getCause(), true);
            }
        }

        private synchronized void cameraShellDiedCleanup() {
            // The stock SDK lives in another process; losing the overlay shell does not
            // terminate it. Close it before dropping the input group's ownership.
            boolean shellCloseQueued = cancelPendingStock("camera_shell_died", stockRequest.id());
            releaseSurfaces(pendingReversePreviewSurfaces);
            pendingReversePreviewSurfaces = new Surface[0];
            pendingReversePreviewRequestId = 0;
            if (persistentPanoProducer) {
                try {
                    persistentSession.invalidateCameraShellGroups(
                            new ReflectivePersistentCameraPort(camera),
                            "camera_shell_died", this::emit,
                            producerCameraId, producerEpoch);
                    refreshPersistentLegacyState();
                    closePersistentProducerIfIdle("camera_shell_died", shellCloseQueued);
                } catch (PersistentSessionFailure failure) {
                    tearDownPersistentProducer(
                            failure.reason, failure.getCause(), true);
                }
                return;
            }
            closeCamera("camera_shell_died");
        }

        private synchronized void settleOrdinaryStockTerminal(int requestId, boolean failed) {
            if (!stockRequest.matches(requestId) || reverseStockActive) return;
            String reason = failed ? "stock_avm_session_failed" : "stock_avm_session_closed";
            boolean shellCloseQueued = failed && cancelPendingStock(reason, requestId);
            stockRequest.clear();
            if (persistentPanoProducer) {
                if (activityGroup.requestId != requestId
                        || !PersistentSession.isStockAvmGroup(activityGroup)) return;
                try {
                    persistentSession.invalidateStockAvmGroup(
                            new ReflectivePersistentCameraPort(camera), reason, this::emit,
                            producerCameraId, producerEpoch);
                    refreshPersistentLegacyState();
                    closePersistentProducerIfIdle(reason, shellCloseQueued);
                } catch (PersistentSessionFailure error) {
                    tearDownPersistentProducer(error.reason, error.getCause(), shellCloseQueued);
                }
            } else if (CAMERA_OWNER_ACTIVITY.equals(activeCameraOwner)
                    && activeCameraRequestId == requestId) {
                closeOneShotCamera(reason, false);
            }
        }

        private void acceptShellEvent(String line) {
            if (line == null) return;
            String key = null;
            String forwardedLine = line;
            boolean reverseStockDeath = false;
            try {
                JSONObject event = new JSONObject(line);
                String kind = event.optString("kind");
                forwardedLine = scopeReverseStockEvent(event);
                event = new JSONObject(forwardedLine);
                key = lifetimeCounterKey(kind);
                if (isMusicJournalEvent(kind)) {
                    synchronized (musicJournal) {
                        appendBounded(musicJournal, line, 20);
                    }
                }
                int requestId = event.optInt("request_id", 0);
                boolean pendingPreviewError;
                synchronized (this) {
                    boolean reverseStockEvent = isReverseStockEvent(event);
                    boolean stockTerminal = isCurrentStockTerminal(event, stockRequest.id());
                    pendingPreviewError = "camera_error".equals(kind)
                            && !reverseStockEvent
                            && matchesPendingCameraRequest(
                                    pendingReversePreviewRequestId, requestId);
                    if (!reverseStockEvent && (stockTerminal || pendingPreviewError)) {
                        if (stockTerminal) {
                            settleOrdinaryStockTerminal(requestId, "camera_error".equals(kind));
                        } else {
                            stockRequest.clear();
                        }
                        releaseSurfaces(pendingReversePreviewSurfaces);
                        pendingReversePreviewSurfaces = new Surface[0];
                        if (pendingReversePreviewRequestId == requestId) {
                            pendingReversePreviewRequestId = 0;
                        }
                    }
                    if (reverseStockEvent) {
                        int reverseRequest = requestId > 0
                                ? requestId : reverseStockRequestId;
                        if ("stock_avm_shell_died".equals(kind)) {
                            reverseStockDeath = !hasReverseStockContext()
                                    || isCurrentReverseStockDeath(event);
                        } else if ("camera_error".equals(kind)
                                && reverseStockRequestMatches(reverseRequest)) {
                            if (reverseStockInputAttached) {
                                cancelPendingStock(
                                        "reverse_preview_stock_error", reverseRequest);
                                detachReverseStockInput(
                                        "reverse_preview_stock_error");
                            } else {
                                settleReverseStockBackgroundError(
                                        reverseRequest, reverseStockProducerEpoch);
                            }
                        } else if ("camera_closed".equals(kind)
                                && reverseStockRequestMatches(reverseRequest)) {
                            if (reverseStockInputAttached) {
                                detachReverseStockInput("stock_avm_shell_closed");
                            }
                            clearReverseStockState();
                        } else if ("camera_closed".equals(kind)
                                && reverseStockClosePending
                                && reverseStockCloseRequestId == reverseRequest) {
                            clearReverseStockCloseState();
                        }
                    }
                }
                if (pendingPreviewError) {
                    turnController.closeStockAvm("reverse_preview_base_failed", requestId);
                }
            } catch (Throwable error) {
                Log.w(TAG, "Counter event parse failed", error);
            }
            if (reverseStockDeath) stockAvmShellDiedCleanup();
            if (key != null) incrementCounter(key);
            forwardLine(forwardedLine);
            if (key != null) emitCounters();
        }

        private synchronized String scopeReverseStockEvent(JSONObject event) {
            if (event == null || !isStockShellEvent(event)) return event.toString();
            int eventRequestId = event.optInt("request_id", 0);
            int eventEpoch = event.optInt("producer_epoch", 0);
            boolean activeMatch = reverseStockActive
                    && (eventRequestId <= 0 || eventRequestId == reverseStockRequestId)
                    && (eventEpoch <= 0 || eventEpoch == reverseStockProducerEpoch);
            boolean closeMatch = reverseStockClosePending
                    && (eventRequestId <= 0 || eventRequestId == reverseStockCloseRequestId)
                    && (eventEpoch <= 0 || eventEpoch == reverseStockCloseProducerEpoch);
            boolean retiredMatch = eventRequestId > 0
                    && eventRequestId == reverseStockRetiredRequestId
                    && (eventEpoch <= 0 || eventEpoch == reverseStockRetiredProducerEpoch);
            boolean staleMatch = eventRequestId > 0
                    && !activeMatch && !closeMatch && !retiredMatch
                    && (reverseStockActive || reverseStockClosePending);
            if (!activeMatch && !closeMatch && !retiredMatch && !staleMatch) {
                return event.toString();
            }
            int requestId = eventRequestId > 0 ? eventRequestId
                    : activeMatch ? reverseStockRequestId : reverseStockCloseRequestId;
            int epoch = activeMatch ? reverseStockProducerEpoch
                    : closeMatch ? reverseStockCloseProducerEpoch : reverseStockRetiredProducerEpoch;
            long avmEpoch = event.optLong("avm_shell_epoch", 0L);
            if (activeMatch && avmEpoch > 0L
                    && ("stock_avm_shell_attached".equals(event.optString("kind"))
                    || "camera_shell_opened".equals(event.optString("kind")))) {
                reverseStockAvmShellEpoch = avmEpoch;
            }
            try {
                event.put("component", "reverse_preview_background");
                event.put("camera_owner", CAMERA_OWNER_ACTIVITY);
                event.put("request_id", requestId);
                if (epoch > 0 && !event.has("producer_epoch")) {
                    event.put("producer_epoch", epoch);
                }
                event.put("stock_close_pending", closeMatch);
                return event.toString();
            } catch (Throwable ignored) {
                return event.toString();
            }
        }

        private static boolean isStockShellEvent(JSONObject event) {
            String kind = event.optString("kind");
            String renderer = event.optString("renderer");
            return renderer.startsWith("stock_avm")
                    || kind.startsWith("stock_avm_")
                    || "camera_config_read".equals(kind)
                    || "camera_shell_opened".equals(kind);
        }

        static boolean isCurrentStockTerminal(JSONObject event, int activeStockRequestId) {
            String kind = event.optString("kind");
            return activeStockRequestId > 0
                    && event.optInt("request_id", 0) == activeStockRequestId
                    && ("camera_closed".equals(kind) || "camera_error".equals(kind))
                    && isStockShellEvent(event);
        }

        private static boolean isReverseStockEvent(JSONObject event) {
            return event != null
                    && "reverse_preview_background".equals(
                            event.optString("component"));
        }

        private synchronized void detachReverseStockInput(String reason) {
            if (!persistentPanoProducer || !activityGroup.has()
                    || !PersistentSession.isReverseStockAvmGroup(activityGroup)) return;
            try {
                persistentSession.invalidateStockAvmGroup(
                        new ReflectivePersistentCameraPort(camera), reason, this::emit,
                        producerCameraId, producerEpoch);
                refreshPersistentLegacyState();
                closePersistentProducerIfIdle(reason);
            } catch (PersistentSessionFailure failure) {
                tearDownPersistentProducer(failure.reason, failure.getCause(), true);
            }
        }

        private synchronized void clearReverseStockState() {
            if (stockRequest.matches(reverseStockRequestId)) stockRequest.clear();
            pendingReversePreviewRequestId = 0;
            reverseStockActive = false;
            reverseStockInputAttached = false;
            reverseStockRequestId = 0;
            reverseStockProducerEpoch = 0;
            reverseStockRetainedCallbackEpoch = 0;
            reverseStockAvmShellEpoch = 0L;
        }

        private synchronized void clearReverseStockCloseState() {
            reverseStockClosePending = false;
            reverseStockCloseRequestId = 0;
            reverseStockCloseProducerEpoch = 0;
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
                    || "music_source_filter_status".equals(kind)
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

    /**
     * IDs above 10000 are RTSP/IP cameras (IPCamDevice) and IDs 0..999 are local HAL3
     * cameras. On some firmware an id in between (observed: exactly 10000) is sent to the
     * HAL3 path, whose id formatting overflows and aborts bmmcameraserver.
     */
    static boolean isUnsafeBmmCameraId(int cameraId) {
        return cameraId >= 1000 && cameraId <= 10000;
    }

    static Object openAvmCamera(Class<?> avm, int cameraId) throws Exception {
        if (isUnsafeBmmCameraId(cameraId)) {
            throw new IllegalArgumentException("Unsupported BMM camera ID: " + cameraId);
        }

        Object camera = avm.getMethod("open", int.class).invoke(null, cameraId);
        if (camera != null) return camera;

        Constructor<?> constructor;
        Method open;
        try {
            constructor = avm.getDeclaredConstructor(int.class);
            open = avm.getDeclaredMethod("open");
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("AVMCamera.open returned null", error);
        }
        constructor.setAccessible(true);
        open.setAccessible(true);
        camera = constructor.newInstance(cameraId);
        try {
            if (Boolean.TRUE.equals(open.invoke(camera))) return camera;
            throw new IllegalStateException("AVMCamera.open returned null");
        } catch (Throwable error) {
            try {
                HelperBinder.closeProducerObject(camera);
            } catch (Throwable closeError) {
                error.addSuppressed(root(closeError));
            }
            if (error instanceof Exception) throw (Exception) error;
            throw (Error) error;
        }
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
            exemptions.invoke(instance, (Object) new String[]{
                    "Landroid/hardware/", "Landroid/os/SystemProperties;", "Landroid/view/",
                    "Landroid/app/"});
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
