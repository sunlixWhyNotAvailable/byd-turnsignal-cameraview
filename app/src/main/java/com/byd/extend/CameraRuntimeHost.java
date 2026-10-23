package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Lives in bydextend_helper. App callback death is NOT camera runtime shutdown. */
final class CameraRuntimeHost {
    static final int ATTACH = 1, SETTINGS = 2, VISIBILITY = 3, PREVIEW = 4,
            TOGGLE_REVERSE = 5, ACK_SETTINGS = 6, STOP = 7, STATUS = 8, DETACH = 9,
            PANO = 10, POWER = 11, NAV_VOLUME = 12;
    static final int MOTION_ATTACH = 13, MOTION_DETACH = 14, MOTION_AUTHORIZE = 15,
            MOTION_RECORD_ATTACH = 16, MOTION_RECORD_DETACH = 17,
            MOTION_OWN_WAKE = 18, MOTION_END_WAKE = 19, MOTION_BIND_WORKER = 20,
            MOTION_UNBIND_WORKER = 21;
    static final int VIEW_ATTACH = 22, VIEW_DETACH = 23, VIEW_REGISTER_HELPER = 24,
            VIEW_UPDATE_OUTPUT = 25;
    static final String CALLBACK = "com.byd.extend.ICameraRuntimeClient";
    static final int POWER_QUICKBOOT = 1, POWER_SCREEN_OFF = 2, POWER_SCREEN_ON = 3,
            POWER_USER_PRESENT = 4;
    static final int EVENT = 1, PREFERENCES = 2, AVM_CONFIG = 3;
    private final HandlerThread thread = new HandlerThread("camera-runtime");
    private final Handler handler;
    private final CameraRuntimeContext context;
    private final int appUid;
    private final Consumer<String> journal;
    private final java.util.function.BiConsumer<Integer, Integer> platformSignal;
    private final CameraHelperMain.HelperBinder helper;
    private BlindSpotOverlayController blind;
    private ParkingCameraController parking;
    private ReverseCameraController reverse;
    private RearviewMirrorController mirror;
    private final CameraRuntimeLifetime<IBinder> lifetime = new CameraRuntimeLifetime<>();
    private boolean visible;
    private boolean preview;
    private boolean appSettingsReceived;
    private IBinder linkedClient;
    private IBinder.DeathRecipient clientDeath;
    private String motionSession;
    private IBinder motionWorker;
    private IBinder.DeathRecipient motionDeath;
    private IBinder viewerOwner;
    private String viewerSession;
    private long viewerGeneration;
    private IBinder.DeathRecipient viewerDeath;
    private volatile IBinder viewingHelper;
    private IBinder.DeathRecipient viewingHelperDeath;
    private volatile int viewingHelperPid;
    private final String viewingHelperApkIdentity;
    private final String motionSourceEpoch = java.util.UUID.randomUUID().toString();
    private final MotionWakeOwnership motionWake = new MotionWakeOwnership();
    private JSONObject motionPower, motionLock, motionDisplay;
    private final CameraSourceDemand.PowerGate vehiclePower = new CameraSourceDemand.PowerGate();
    private String vehiclePowerStateLine;
    private long vehiclePowerSourceEpoch = -1L;
    private long vehiclePowerRevision = -1L;
    private boolean vehiclePowerKnown;
    private boolean vehiclePowerOff;
    private int vehiclePowerFailureCount;
    private final Runnable discoveryRetry = this::retryCameraDiscovery;
    private IBinder cameraEndpointFor(IBinder owner) { return new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (Binder.getCallingUid() != appUid) throw new SecurityException("camera caller denied");
            if (lifetime.client() != owner || !owner.isBinderAlive() || lifetime.stopped())
                throw new IllegalStateException("camera client attachment expired");
            if (!CameraRuntimeClient.isCameraTransaction(code))
                throw new SecurityException("not a camera operation");
            int callerUid = Binder.getCallingUid();
            long identity = Binder.clearCallingIdentity();
            try { return helper.transactCameraAs(code, data, reply, flags, callerUid); }
            catch (android.os.RemoteException error) { throw new IllegalStateException(error); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
    }; }

    CameraRuntimeHost(Context system, int appUid, Consumer<String> journal,
            java.util.function.BiConsumer<Integer, Integer> platformSignal) throws Exception {
        this.appUid = appUid;
        this.journal = journal;
        this.platformSignal = platformSignal;
        viewingHelperApkIdentity = CameraViewingShellProtocol.currentApkIdentity(system);
        context = new CameraRuntimeContext(system, appUid, this::readAvmConfig,
                value -> platformSignal.accept(POWER, value));
        CameraShellMain.initializeSystemFontsForShell();
        thread.start();
        handler = new Handler(thread.getLooper());
        helper = new CameraHelperMain.HelperBinder(context, handler, this::acceptCameraLine);
        context.settings.setChangeListener(delta -> handler.post(() -> send(PREFERENCES, null, Bundle.EMPTY)));
    }

    int transact(Parcel data, Parcel reply) throws Exception {
        int operation = data.readInt();
        if (operation == VIEW_ATTACH || operation == VIEW_DETACH || operation == VIEW_REGISTER_HELPER
                || operation == VIEW_UPDATE_OUTPUT)
            return transactViewing(operation, data, reply);
        if (operation >= MOTION_ATTACH && operation <= MOTION_UNBIND_WORKER)
            return transactMotion(operation, data, reply);
        if (Binder.getCallingUid() != appUid) throw new SecurityException("camera app caller required");
        IBinder owner = operation == ATTACH ? null : data.readStrongBinder();
        // Decode on the Binder thread; a Parcel must never outlive its transaction.
        Bundle settings = operation == ATTACH || operation == SETTINGS || operation == ACK_SETTINGS
                ? data.readBundle(getClass().getClassLoader()) : null;
        IBinder callback = operation == ATTACH ? data.readStrongBinder() : null;
        boolean flag = operation == ATTACH || operation == SETTINGS
                || operation == VISIBILITY || operation == PREVIEW ? data.readInt() != 0 : false;
        boolean initialVisible = operation == ATTACH && data.readInt() != 0;
        boolean initialPreview = operation == ATTACH && data.readInt() != 0;
        int platformValue = operation == PANO || operation == POWER ? data.readInt() : 0;
        String scope = operation == SETTINGS || operation == PANO ? data.readString() : "";
        Bundle pending = call(() -> {
            if (lifetime.stopped() && operation != STOP) throw new IllegalStateException("camera runtime stopped");
            if (operation != ATTACH && (owner == null || lifetime.client() != owner))
                throw new IllegalStateException("camera client attachment expired");
            switch (operation) {
                case ATTACH:
                    if (callback == null || settings == null) throw new IllegalArgumentException("missing attachment");
                    linkClient(callback);
                    context.overlayAllowed = flag;
                    boolean initialAppSnapshot = !appSettingsReceived;
                    Bundle changes = context.settings.reconcile(settings, initialAppSnapshot);
                    appSettingsReceived = true;
                    if (!lifetime.started()) {
                        visible = initialVisible; preview = initialPreview;
                        context.uiVisible = visible;
                    }
                    boolean retained;
                    try { retained = lifetime.attach(callback, this::initialize); }
                    catch (RuntimeException error) { stopOnHandler(); throw error; }
                    if (retained && initialAppSnapshot) {
                        BlindSpotOverlayController.migrateOverlayPreferences(context.settings);
                        applySettings("");
                        changes = context.settings.reconcile(settings, false);
                    }
                    event("camera_runtime_attached", "pid", android.os.Process.myPid(), "retained", retained);
                    replayCameraPowerState();
                    helper.reportRetainedCameraState();
                    return changes;
                case SETTINGS:
                    context.overlayAllowed = flag;
                    Bundle unsaved = context.settings.updateFromClient(settings);
                    if (!"sync".equals(scope)) applySettings(scope);
                    return unsaved;
                case ACK_SETTINGS: context.settings.acknowledge(settings); break;
                case STATUS:
                    replayCameraPowerState();
                    helper.reportRetainedCameraState();
                    break;
                case PANO:
                    if (platformValue < -1 || platformValue > 1 || scope == null || scope.length() > 64)
                        throw new IllegalArgumentException("invalid pano state");
                    blind.oemVisibility(platformValue >= 0, platformValue == 1);
                    reverse.oemVisibility(platformValue >= 0, platformValue == 1, scope);
                    mirror.oemVisibility(platformValue >= 0, platformValue == 1);
                    break;
                case POWER:
                    if (platformValue < 0 || platformValue > POWER_USER_PRESENT)
                        throw new IllegalArgumentException("invalid power event");
                    platformSignal.accept(operation, platformValue);
                    break;
                case NAV_VOLUME: platformSignal.accept(operation, 0); break;
                case DETACH: clientDied(owner); break;
                case VISIBILITY: setVisibility(flag); break;
                case PREVIEW:
                    preview = flag;
                    applyVehiclePower();
                    break;
                case TOGGLE_REVERSE:
                    if (!visible) reverse.requestSteeringToggle(0);
                    break;
                case STOP: stopOnHandler(); break;
                default: throw new IllegalArgumentException("unknown camera runtime command");
            }
            return Bundle.EMPTY;
        });
        reply.writeNoException();
        if (operation == ATTACH) {
            reply.writeStrongBinder(cameraEndpointFor(callback));
            reply.writeBundle(pending);
        }
        if (operation == SETTINGS) reply.writeBundle(pending);
        return operation;
    }

    private int transactMotion(int operation, Parcel data, Parcel reply) throws Exception {
        if (Binder.getCallingUid() != 2000)
            throw new SecurityException("motion caller denied");
        String session = data.readString();
        IBinder worker = data.readStrongBinder();
        android.view.Surface detector = operation == MOTION_ATTACH
                ? data.readTypedObject(android.view.Surface.CREATOR) : null;
        android.view.Surface[] recording = operation == MOTION_RECORD_ATTACH
                ? data.createTypedArray(android.view.Surface.CREATOR) : null;
        int requestId = operation == MOTION_RECORD_ATTACH ? data.readInt() : 0;
        MotionRecordingOutput recordingOutput = null;
        boolean[] consumed = {false};
        try {
            if (operation == MOTION_RECORD_ATTACH) {
                recordingOutput = MotionRecordingOutput.readFromParcel(data, session, recording);
                recordingOutput.register();
            }
            if (!MotionDetectorProtocol.validSession(session) || data.dataAvail() != 0)
                throw new IllegalArgumentException("invalid motion session");
            call(() -> {
                if (lifetime.stopped()) throw new IllegalStateException("camera runtime stopped");
                if (operation == MOTION_AUTHORIZE || operation == MOTION_BIND_WORKER) {
                    if (worker == null || !worker.isBinderAlive())
                        throw new IllegalStateException("motion owner unavailable");
                    try { lifetime.start(this::initialize); }
                    catch (RuntimeException error) { stopOnHandler(); throw error; }
                    if (!session.equals(motionSession) || worker != motionWorker) clearMotion();
                    motionSession = session;
                    if (motionWorker == null) {
                        motionWorker = worker;
                        motionDeath = () -> handler.post(() -> {
                            if (motionWorker == worker) clearMotion();
                        });
                        worker.linkToDeath(motionDeath, 0);
                    }
                    if (operation == MOTION_BIND_WORKER) {
                        sendMotionBaseline(motionDisplay);
                    }
                } else {
                    if (!session.equals(motionSession) || motionWorker == null
                            || !motionWorker.isBinderAlive() || worker != motionWorker)
                        throw new SecurityException("motion session expired");
                    if (operation == MOTION_ATTACH) {
                        consumed[0] = true;
                        helper.attachMotionDetector(detector);
                    } else if (operation == MOTION_DETACH) helper.detachMotionDetector();
                    else if (operation == MOTION_RECORD_ATTACH) {
                        if (recording == null || recording.length != 4 || requestId <= 0)
                            throw new IllegalArgumentException("four motion surfaces required");
                        consumed[0] = true;
                        helper.attachMotionRecording(recording, requestId);
                    } else if (operation == MOTION_RECORD_DETACH) helper.detachMotionRecording();
                    else if (operation == MOTION_OWN_WAKE) {
                        int epoch = helper.motionProducerEpoch();
                        if (!motionPowerOff() || epoch <= 0)
                            throw new IllegalStateException("Motion wake requires live OFF-state producer");
                        motionWake.arm(session, epoch, android.os.SystemClock.elapsedRealtime());
                        event("motion_owned_wake_registered", "session", session, "producer_epoch", epoch);
                    } else if (operation == MOTION_END_WAKE) motionWake.clearPending();
                    else if (operation == MOTION_UNBIND_WORKER) {
                        // A successful reply is also the worker's permission to release consumers.
                        helper.detachMotionRecording();
                        helper.detachMotionDetector();
                        clearMotion();
                    }
                }
                return null;
            });
            reply.writeNoException();
            reply.writeInt(1);
            if (operation == MOTION_BIND_WORKER) reply.writeString(motionSourceEpoch);
            return operation;
        } finally {
            if (recordingOutput != null) recordingOutput.unregister();
            if (!consumed[0]) {
                if (detector != null) detector.release();
                if (recording != null) for (android.view.Surface surface : recording)
                    if (surface != null) surface.release();
            }
        }
    }

    boolean isRegisteredViewingCaller(int uid, int pid) {
        IBinder helper = viewingHelper;
        return uid == 2000 && pid > 0 && pid == viewingHelperPid
                && helper != null && helper.isBinderAlive();
    }

    private int transactViewing(int operation, Parcel data, Parcel reply) throws Exception {
        if (operation == VIEW_REGISTER_HELPER) {
            if (Binder.getCallingUid() != appUid)
                throw new SecurityException("viewing helper registration requires app UID");
            IBinder helperProcess = data.readStrongBinder();
            int pid = data.readInt();
            String apk = data.readString();
            if (helperProcess == null || !helperProcess.isBinderAlive() || pid <= 0
                    || !viewingHelperApkIdentity.equals(apk) || data.dataAvail() != 0)
                throw new IllegalArgumentException("invalid viewing helper registration");
            CameraViewingShellProtocol.Identity identity = CameraViewingShellProtocol.readIdentity(helperProcess);
            if (identity == null || !identity.matches(pid, viewingHelperApkIdentity))
                throw new SecurityException("viewing helper APK/PID identity mismatch");
            call(() -> {
                if (viewingHelper == helperProcess && viewingHelperPid == pid) return null;
                if (viewingHelper != null && viewingHelper.isBinderAlive())
                    throw new SecurityException("another viewing helper is already registered");
                if (viewerOwner != null) clearViewer();
                clearViewingHelper();
                IBinder.DeathRecipient death = () -> handler.post(() -> {
                    if (viewingHelper == helperProcess) {
                        if (viewerOwner != null) clearViewer();
                        clearViewingHelper();
                    }
                });
                helperProcess.linkToDeath(death, 0);
                viewingHelperPid = pid;
                viewingHelper = helperProcess;
                viewingHelperDeath = death;
                return null;
            });
            reply.writeNoException();
            reply.writeInt(1);
            return operation;
        }
        if (!isRegisteredViewingCaller(Binder.getCallingUid(), Binder.getCallingPid()))
            throw new SecurityException("unregistered viewing helper caller");
        IBinder owner = data.readStrongBinder();
        String session = data.readString();
        if (operation == VIEW_UPDATE_OUTPUT) {
            long generation = data.readLong();
            int direction = data.readInt();
            android.view.Surface surface = data.readInt() == 0 ? null
                    : android.view.Surface.CREATOR.createFromParcel(data);
            java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
            try {
                if (direction < -1 || direction > 3 || generation <= 0 || data.dataAvail() != 0
                        || surface != null && !surface.isValid())
                    throw new IllegalArgumentException("invalid viewer output update");
                call(() -> {
                    if (!pending.compareAndSet(true, false))
                        throw new IllegalStateException("viewer output update expired before dispatch");
                    if (viewerOwner != owner || !java.util.Objects.equals(viewerSession, session)
                            || generation <= viewerGeneration) {
                        if (surface != null) surface.release();
                        throw new SecurityException("stale viewer output owner/generation");
                    }
                    viewerGeneration = generation;
                    helper.updateViewingOutput(surface, direction);
                    return null;
                });
                reply.writeNoException(); reply.writeInt(1);
                return operation;
            } finally {
                if (pending.compareAndSet(true, false) && surface != null) surface.release();
            }
        }
        android.view.Surface[] surfaces = operation == VIEW_ATTACH
                ? data.createTypedArray(android.view.Surface.CREATOR) : null;
        int requestId = operation == VIEW_ATTACH ? data.readInt() : 0;
        MotionRecordingOutput output = null;
        boolean[] consumed = {false};
        try {
            if (owner == null || !owner.isBinderAlive() || !MotionDetectorProtocol.validSession(session))
                throw new IllegalArgumentException("invalid camera viewer owner");
            if (operation == VIEW_ATTACH) {
                if (surfaces == null || surfaces.length != 4 || requestId <= 0)
                    throw new IllegalArgumentException("invalid camera viewer surfaces");
                for (android.view.Surface surface : surfaces)
                    if (surface == null || !surface.isValid()) throw new IllegalArgumentException("invalid camera viewer surface");
                output = MotionRecordingOutput.readFromParcel(data, session, surfaces);
                output.register();
            }
            if (data.dataAvail() != 0) throw new IllegalArgumentException("unexpected viewer payload");
            call(() -> {
                if (lifetime.stopped()) throw new IllegalStateException("camera runtime stopped");
                if (operation == VIEW_ATTACH) {
                    if (viewerOwner != null) throw new IllegalStateException("camera viewer busy");
                    lifetime.start(this::initialize);
                    viewerOwner = owner;
                    viewerSession = session;
                    viewerGeneration = 0;
                    viewerDeath = () -> handler.post(() -> {
                        if (viewerOwner == owner) clearViewer();
                    });
                    try {
                        owner.linkToDeath(viewerDeath, 0);
                        consumed[0] = true;
                        helper.attachViewing(surfaces, requestId);
                    } catch (Exception error) { clearViewer(); throw error; }
                } else {
                    if (viewerOwner != null && (viewerOwner != owner
                            || !java.util.Objects.equals(viewerSession, session)))
                        throw new SecurityException("camera viewer owner changed");
                    clearViewer();
                }
                return null;
            });
            reply.writeNoException(); reply.writeInt(1);
            return operation;
        } finally {
            if (output != null) output.unregister();
            if (!consumed[0] && surfaces != null)
                for (android.view.Surface surface : surfaces) if (surface != null) surface.release();
        }
    }

    private void clearViewer() {
        helper.detachViewing();
        if (viewerOwner != null && viewerDeath != null) viewerOwner.unlinkToDeath(viewerDeath, 0);
        viewerOwner = null; viewerDeath = null; viewerSession = null; viewerGeneration = 0;
    }

    private void clearViewingHelper() {
        if (viewingHelper != null && viewingHelperDeath != null)
            viewingHelper.unlinkToDeath(viewingHelperDeath, 0);
        viewingHelper = null;
        viewingHelperDeath = null;
        viewingHelperPid = 0;
    }

    private void clearMotion() {
        motionWake.clear();
        if (motionWorker != null && motionDeath != null) motionWorker.unlinkToDeath(motionDeath, 0);
        motionWorker = null;
        motionDeath = null;
        motionSession = null;
        platformSignal.accept(MOTION_UNBIND_WORKER, 0);
        try { helper.detachMotionRecording(); }
        catch (Exception error) { event("motion_recording_detach_error", "error", error.toString()); }
        try { helper.detachMotionDetector(); }
        catch (Exception error) { event("motion_detach_error", "error", error.toString()); }
    }

    private void initialize() {
        BlindSpotOverlayController.migrateOverlayPreferences(context.settings);
        blind = new BlindSpotOverlayController(context, handler, this::event);
        parking = new ParkingCameraController(context, handler, this::event);
        reverse = new ReverseCameraController(context, handler, helper::emitControllerEvent,
                active -> { blind.setReversePriority(active); parking.setReversePriority(active); });
        mirror = new RearviewMirrorController(context, handler, this::event);
        discoverCamera();
        blind.setUiHidden(visible); parking.setUiHidden(visible);
        mirror.appVisibility(visible);
        applyVehiclePower();
        blind.attachHelper(helper); parking.attachHelper(helper); reverse.attachHelper(helper);
        mirror.attachHelper(helper);
    }

    private void discoverCamera() {
        handler.removeCallbacks(discoveryRetry);
        if (!lifetime.stopped()
                && helper.discoverCameraWithResult()
                == CameraHelperMain.DiscoveryResult.RETRYABLE_FAILURE) {
            handler.postDelayed(discoveryRetry, 3_000);
        }
    }

    private void retryCameraDiscovery() {
        handler.removeCallbacks(discoveryRetry);
        if (!lifetime.stopped()
                && helper.retryCameraDiscovery()
                == CameraHelperMain.DiscoveryResult.RETRYABLE_FAILURE) {
            handler.postDelayed(discoveryRetry, 3_000);
        }
    }

    private void setVisibility(boolean next) {
        context.uiVisible = next;
        if (visible == next) return;
        visible = next;
        mirror.appVisibility(next); blind.setUiHidden(next); parking.setUiHidden(next);
        if (!next) { preview = false; applyVehiclePower(); }
    }

    private void applyVehiclePower() {
        boolean allowed = vehiclePower.ordinaryAllowed();
        blind.setSuspended(preview || !allowed);
        parking.setSuspended(preview || !allowed);
        reverse.setRuntimeAllowed(allowed);
        mirror.setRuntimeAllowed(allowed);
    }

    private void clientDied(IBinder expected) {
        if (!lifetime.detach(expected)) return;
        helper.detachMotionCompositionPreviews();
        unlinkClient();
        // Only app-owned preview surfaces are gone; all background consumers stay attached.
        helper.closeCameraForOwner(CameraHelperMain.CAMERA_OWNER_ACTIVITY, "ui_process_died");
        setVisibility(false);
        event("camera_runtime_ui_detached", "runtime_retained", true);
    }

    private void linkClient(IBinder callback) throws android.os.RemoteException {
        if (linkedClient == callback) return;
        IBinder.DeathRecipient death = () -> handler.post(() -> clientDied(callback));
        callback.linkToDeath(death, 0);
        unlinkClient();
        linkedClient = callback;
        clientDeath = death;
    }

    private void unlinkClient() {
        if (linkedClient != null) linkedClient.unlinkToDeath(clientDeath, 0);
        linkedClient = null;
        clientDeath = null;
    }

    private void applySettings(String scope) {
        if ("warning".equals(scope)) blind.applyWarningSettings();
        else if ("trigger".equals(scope)) blind.applyTriggerSettings();
        else if ("parking".equals(scope)) parking.settingsChanged();
        else if ("reverse".equals(scope)) reverse.settingsChanged();
        else if ("mirror".equals(scope)) mirror.settingsChanged();
        else if ("logging".equals(scope)) helper.configureLogging();
        else {
            helper.configureLogging();
            blind.applySettings(); blind.applyWarningSettings(); blind.applyTriggerSettings();
            parking.settingsChanged(); reverse.settingsChanged(); mirror.settingsChanged();
        }
    }

    void acceptTelemetry(String line) {
        handler.post(() -> {
            if (lifetime.stopped()) return;
            JSONObject event = parseEvent(line);
            if (event != null && "camera_vehicle_power_state".equals(event.optString("kind"))) {
                acceptCameraVehiclePowerState(event, line);
            }
            if (event != null && "motion_vehicle_state".equals(event.optString("kind"))) {
                if ("power".equals(event.optString("signal"))) {
                    motionPower = event;
                    if (AvasEventPolicy.normalizedPower(event.optInt("value", -1)) == 1) {
                        if (motionWake.takeRetainedForPowerOn(helper.motionProducerEpoch())) {
                            event("motion_retained_source_power_on", "session", motionSession);
                            helper.recoverCameraSourceAfterWake();
                        }
                    } else if (!motionPowerOff()) motionWake.clearPending();
                } else if ("lock".equals(event.optString("signal"))) motionLock = event;
                sendMotion(event);
            }
            if (event != null && "shell_power_state".equals(DiagnosticLogPolicy.kind(line))) {
                motionDisplay = event;
                if (lifetime.updateAwakeSession(event.optLong("awake_session_id", -1),
                                event.optBoolean("interactive"))) {
                    boolean owned = motionWake.consume(motionSession, helper.motionProducerEpoch(),
                            motionPowerOff(), "android.intent.action.QUICKBOOT_POWERON".equals(
                                    event.optString("action")), android.os.SystemClock.elapsedRealtime());
                    if (owned) event("motion_owned_wake_source_retained", "session", motionSession,
                            "producer_epoch", helper.motionProducerEpoch());
                    else helper.recoverCameraSourceAfterWake();
                }
                sendMotion(event);
            }
            if (lifetime.started()) dispatch(line, event);
        });
    }

    private void acceptCameraVehiclePowerState(JSONObject event, String line) {
        Object sourceEpochValue = event.opt("source_epoch");
        Object revisionValue = event.opt("revision");
        Object knownValue = event.opt("known");
        Object offValue = event.opt("off");
        Object failuresValue = event.opt("consecutive_failed_reads");
        if (!(sourceEpochValue instanceof Number) || !(revisionValue instanceof Number)
                || !(knownValue instanceof Boolean) || !(offValue instanceof Boolean)
                || !(failuresValue instanceof Number)) {
            event("camera_vehicle_power_state_rejected", "reason", "required_field_missing");
            return;
        }
        long sourceEpoch = ((Number) sourceEpochValue).longValue();
        long revision = ((Number) revisionValue).longValue();
        boolean known = (Boolean) knownValue;
        boolean off = (Boolean) offValue;
        int failures = event.optInt("consecutive_failed_reads", -1);
        if (sourceEpoch < 0 || revision < 0 || failures < 0 || failures > 5
                || off && !known || known && failures == 5) {
            event("camera_vehicle_power_state_rejected", "reason", "invalid_state",
                    "source_epoch", sourceEpoch, "revision", revision,
                    "known", known, "off", off, "consecutive_failed_reads", failures);
            return;
        }
        if (sourceEpoch < vehiclePowerSourceEpoch
                || sourceEpoch == vehiclePowerSourceEpoch && revision < vehiclePowerRevision) return;
        if (sourceEpoch == vehiclePowerSourceEpoch && revision == vehiclePowerRevision
                && (known != vehiclePowerKnown || off != vehiclePowerOff
                || failures != vehiclePowerFailureCount)) {
            event("camera_vehicle_power_state_rejected", "reason", "decision_changed_without_revision",
                    "source_epoch", sourceEpoch, "revision", revision);
            return;
        }
        boolean wasAllowed = vehiclePower.ordinaryAllowed();
        try {
            vehiclePower.update(new CameraSourceDemand.PowerGate.Snapshot(
                    known, off, failures, revision));
        } catch (IllegalArgumentException invalid) {
            event("camera_vehicle_power_state_rejected", "reason", invalid.getMessage());
            return;
        }
        vehiclePowerSourceEpoch = sourceEpoch;
        vehiclePowerRevision = revision;
        vehiclePowerKnown = known;
        vehiclePowerOff = off;
        vehiclePowerFailureCount = failures;
        vehiclePowerStateLine = line;
        helper.setVehiclePowerOff(!vehiclePower.ordinaryAllowed());
        if (lifetime.started() && wasAllowed != vehiclePower.ordinaryAllowed()) {
            applyVehiclePower();
        }
        if (lifetime.started()) send(EVENT, line, null);
    }

    /** Queues the cached app-owned power snapshot before a new host's ATTACH transaction. */
    void seedCameraPowerState(String line) {
        if (line != null && !line.isEmpty()) acceptTelemetry(line);
    }

    private void replayCameraPowerState() {
        if (vehiclePowerStateLine != null) send(EVENT, vehiclePowerStateLine, null);
    }

    private boolean motionPowerOff() {
        return motionPower != null && motionPower.optInt("value", -1) == 0;
    }

    private void sendMotionBaseline(JSONObject value) {
        if (value == null) return;
        try { sendMotion(new JSONObject(value.toString()).put("baseline", true)); }
        catch (org.json.JSONException error) { event("motion_telemetry_error", "error", error.toString()); }
    }

    void loggingChanged() {
        handler.post(() -> {
            try { sendMotion(new JSONObject().put("kind", "motion_logging")); }
            catch (org.json.JSONException error) { event("motion_telemetry_error", "error", error.toString()); }
        });
    }

    private void sendMotion(JSONObject value) {
        IBinder target = motionWorker;
        if (target == null || value == null) return;
        Parcel data = Parcel.obtain();
        try {
            JSONObject snapshot = new JSONObject(value.toString()).put("source_epoch", motionSourceEpoch)
                    .put("extended_logs", DiagnosticLogPolicy.extended())
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime());
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(motionSession);
            data.writeString(snapshot.toString());
            if (!target.transact(MotionDetectorProtocol.TX_TELEMETRY, data, null, IBinder.FLAG_ONEWAY))
                clearMotion();
        } catch (Exception error) {
            event("motion_telemetry_delivery_failed", "error", error.toString());
            if (target == motionWorker && !target.isBinderAlive()) clearMotion();
        } finally { data.recycle(); }
    }

    private StockAvmPreview.Config readAvmConfig() throws Exception {
        // Called on the camera worker, never while the runtime handler waits for the app main thread.
        IBinder target = call(lifetime::client);
        if (target == null) throw new IllegalStateException("AVM config app unavailable");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK);
            if (!target.transact(AVM_CONFIG, data, reply, 0))
                throw new IllegalStateException("AVM config callback unavailable");
            reply.readException();
            if (target != lifetime.client() || lifetime.stopped())
                throw new IllegalStateException("AVM config client attachment expired");
            if (reply.readInt() != 1)
                throw new StockAvmPreview.StageException(reply.readString(),
                        new IllegalStateException(reply.readString()));
            return StockAvmPreview.Config.readFromParcel(reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    private void dispatch(String line) {
        dispatch(line, parseEvent(line));
    }

    private void dispatch(String line, JSONObject event) {
        if (event == null) {
            blind.acceptEvent(line); parking.acceptEvent(line); reverse.acceptEvent(line);
            mirror.acceptEvent(line);
        } else {
            blind.acceptEvent(event); parking.acceptEvent(event); reverse.acceptEvent(event);
            mirror.acceptEvent(event);
        }
    }

    private static JSONObject parseEvent(String line) {
        if (line == null) return null;
        try { return new JSONObject(line); }
        catch (org.json.JSONException ignored) { return null; }
    }

    private void acceptCameraLine(String line) {
        handler.post(() -> {
            if (lifetime.stopped() || !lifetime.started()) return;
            sendMotion(parseEvent(line));
            dispatch(line);
            journal.accept(line);
            send(EVENT, line, null);
        });
    }

    private void event(String kind, Object... fields) {
        if (!DiagnosticLogPolicy.shouldProduce(kind)) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind).put("source", "camera_runtime")
                    .put("t_ms", android.os.SystemClock.elapsedRealtime());
            for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
            String line = event.toString();
            journal.accept(line); send(EVENT, line, null);
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    private void send(int code, String line, Bundle settings) {
        if (android.os.Looper.myLooper() != handler.getLooper()) {
            handler.post(() -> send(code, line, settings));
            return;
        }
        IBinder target = lifetime.client();
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK);
            if (code == EVENT) data.writeString(line); else data.writeBundle(settings);
            if (!target.transact(code, data, null, IBinder.FLAG_ONEWAY)) clientDied(target);
        } catch (android.os.RemoteException error) { clientDied(target); }
        finally { data.recycle(); }
    }

    private <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
        if (android.os.Looper.myLooper() == handler.getLooper()) return task.call();
        FutureTask<T> queued = new FutureTask<>(task);
        if (!handler.post(queued)) throw new IllegalStateException("camera runtime closed");
        return queued.get(10, TimeUnit.SECONDS);
    }

    void shutdown() {
        try { call(() -> { stopOnHandler(); return null; }); }
        catch (Exception error) { journal.accept("{\"kind\":\"camera_runtime_shutdown_error\"}"); }
    }

    boolean isStopped() { return lifetime.stopped(); }

    boolean hasMotionWorker() {
        try { return call(() -> motionWorker != null && motionWorker.isBinderAlive()); }
        catch (Exception error) { return false; }
    }

    boolean ownsMotionWorker(String session, IBinder worker) {
        try { return call(() -> session != null && session.equals(motionSession)
                && worker != null && worker == motionWorker && worker.isBinderAlive()); }
        catch (Exception error) { return false; }
    }

    private void stopOnHandler() {
        lifetime.shutdown(() -> {
            try { clearViewer(); }
            catch (Exception error) { event("camera_viewing_detach_error", "error", error.getClass().getSimpleName()); }
            clearViewingHelper();
            boolean motionDrained = false;
            try {
                helper.detachMotionRecording();
                helper.detachMotionDetector();
                motionDrained = true;
            } catch (Exception error) {
                event("motion_shutdown_detach_error", "error", error.toString());
            }
            try { sendMotion(new JSONObject().put("kind", "motion_host_shutdown")
                    .put("surfaces_drained", motionDrained)); }
            catch (org.json.JSONException ignored) { }
            clearMotion();
            unlinkClient();
            handler.removeCallbacks(discoveryRetry);
            if (reverse != null) reverse.shutdown();
            if (mirror != null) mirror.shutdown();
            if (blind != null) blind.shutdown();
            if (parking != null) parking.shutdown();
            helper.shutdown(true);
            thread.quitSafely();
        });
    }
}
