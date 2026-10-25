package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;
import java.io.PrintWriter;
import java.io.StringWriter;
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
            VIEW_UPDATE_OUTPUT = 25, VIEW_MARK_CHANNEL_UNAVAILABLE = 26;
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
    private MotionRecordingOutput viewerOutput;
    private Surface viewerEncoderReference;
    private int viewerRequestId;
    private boolean viewerExteriorAttached;
    private boolean viewerComposerExternal;
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
                || operation == VIEW_UPDATE_OUTPUT || operation == VIEW_MARK_CHANNEL_UNAVAILABLE)
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
                        if (viewerOwner != null) {
                            try { clearViewer(); }
                            catch (Exception error) { reportViewerCleanupFailure("helper_death", error); }
                        }
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
        if (owner == null || !owner.isBinderAlive() || !MotionDetectorProtocol.validSession(session))
            throw new IllegalArgumentException("invalid camera viewer owner");

        if (operation == VIEW_ATTACH) {
            Surface[] surfaces = data.createTypedArray(Surface.CREATOR);
            int requestId = 0;
            MotionRecordingOutput output = null;
            java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                requestId = data.readInt();
                if (surfaces == null || surfaces.length != 4 || requestId <= 0)
                    throw new IllegalArgumentException("invalid camera viewer surfaces");
                for (Surface surface : surfaces)
                    if (surface == null || !surface.isValid())
                        throw new IllegalArgumentException("invalid camera viewer surface");
                Parcel outputPayload = Parcel.obtain();
                ViewerConfig config;
                try {
                    outputPayload.appendFrom(data, data.dataPosition(), data.dataAvail());
                    outputPayload.setDataPosition(0);
                    config = readAttachViewerConfig(outputPayload);
                    outputPayload.setDataPosition(0);
                    output = MotionRecordingOutput.readViewingFromParcel(outputPayload, session, surfaces);
                    if (outputPayload.dataAvail() != 0)
                        throw new IllegalArgumentException("unexpected viewer attach payload");
                    data.setDataPosition(data.dataSize());
                } finally { outputPayload.recycle(); }
                output.register();
                final MotionRecordingOutput requestedOutput = output;
                final int requestedId = requestId;
                Surface[] inputs = callViewerOwned(() -> {
                    try {
                        if (lifetime.stopped()) throw new IllegalStateException("camera runtime stopped");
                        if (viewerOwner != null) throw new IllegalStateException("camera viewer busy");
                        viewerOwner = owner;
                        viewerSession = session;
                        viewerGeneration = 0;
                        viewerOutput = requestedOutput;
                        viewerRequestId = requestedId;
                        viewerExteriorAttached = false;
                        viewerComposerExternal = false;
                        viewerEncoderReference = null;
                        viewerDeath = () -> handler.post(() -> {
                            if (viewerOwner == owner) {
                                try { clearViewer(); }
                                catch (Exception error) { reportViewerCleanupFailure("owner_death", error); }
                            }
                        });
                        owner.linkToDeath(viewerDeath, 0);
                        return requireViewerInputs(
                                prepareInitialViewer(requestedOutput, config, requestedId));
                    } catch (Throwable error) {
                        try {
                            if (viewerOutput == requestedOutput) clearViewer();
                            else cleanupUninstalledOutput(requestedOutput, error);
                        } catch (Throwable cleanup) {
                            if (cleanup != error) error.addSuppressed(cleanup);
                        }
                        rethrowViewerFailure(error);
                        throw new AssertionError("unreachable");
                    }
                }, dispatched);
                reply.writeNoException();
                reply.writeInt(1);
                reply.writeTypedArray(inputs, 0);
                return operation;
            } finally {
                if (output != null) output.unregister();
                else releaseSurfaces(surfaces);
                if (output != null && !dispatched.get()) cleanupUninstalledOutput(output, null);
            }
        }

        if (operation == VIEW_UPDATE_OUTPUT) {
            long generation = data.readLong();
            ViewerConfig config = readLiveViewerConfig(data);
            int encoderFlag = data.readInt();
            if (encoderFlag != 0 && encoderFlag != 1)
                throw new IllegalArgumentException("invalid viewer encoder flag");
            Surface encoder = encoderFlag == 0 ? null : Surface.CREATOR.createFromParcel(data);
            java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicBoolean encoderTransferred = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                if (generation <= 0 || data.dataAvail() != 0
                        || encoder != null && !encoder.isValid())
                    throw new IllegalArgumentException("invalid viewer output update");
                Surface[] inputs = call(() -> {
                    if (!pending.compareAndSet(true, false))
                        throw new IllegalStateException("viewer output update expired before dispatch");
                    if (viewerOwner != owner || !java.util.Objects.equals(viewerSession, session)
                            || generation <= viewerGeneration || viewerOutput == null) {
                        if (encoder != null) {
                            safeRelease(encoder);
                            encoderTransferred.set(true);
                        }
                        throw new SecurityException("stale viewer output owner/generation");
                    }
                    viewerGeneration = generation;
                    try {
                        return updateViewerOutput(viewerOutput, config, encoder, encoderTransferred, false);
                    } catch (Throwable error) {
                        if (encoder != null && !encoderTransferred.get()) {
                            safeRelease(encoder);
                            encoderTransferred.set(true);
                        }
                        try { clearViewer(); }
                        catch (Throwable cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                        rethrowViewerFailure(error);
                        throw new AssertionError("unreachable");
                    }
                });
                reply.writeNoException();
                reply.writeInt(1);
                reply.writeTypedArray(inputs, 0);
                return operation;
            } finally {
                if (pending.compareAndSet(true, false) && encoder != null) safeRelease(encoder);
            }
        }

        if (operation == VIEW_MARK_CHANNEL_UNAVAILABLE) {
            int marker = data.readInt();
            int channel = data.readInt();
            String reference = data.readString();
            if (marker != MotionRecordingOutput.VIEWING_PARCEL_VERSION
                    || (channel != 4 && channel != 5) || reference == null
                    || !reference.matches("[0-9a-fA-F]{32}") || data.dataAvail() != 0)
                throw new IllegalArgumentException("invalid viewer channel failure notice");
            call(() -> {
                if (viewerOwner != owner || !java.util.Objects.equals(viewerSession, session)
                        || viewerOutput == null)
                    throw new SecurityException("stale viewer channel failure owner/session");
                viewerOutput.markLiveChannelUnavailable(channel, reference.toLowerCase(java.util.Locale.ROOT));
                return null;
            });
            reply.writeNoException();
            reply.writeInt(1);
            return operation;
        }

        if (operation == VIEW_DETACH) {
            if (data.dataAvail() != 0) throw new IllegalArgumentException("unexpected viewer detach payload");
            call(() -> {
                if (viewerOwner == null) return null;
                if (viewerOwner != owner || !java.util.Objects.equals(viewerSession, session))
                    throw new SecurityException("camera viewer owner changed");
                clearViewer();
                return null;
            });
            reply.writeNoException();
            reply.writeInt(1);
            return operation;
        }
        throw new IllegalArgumentException("unknown camera viewing operation");
    }

    private static final class ViewerConfig {
        final int view, allowedMask, availableMask;
        final boolean exteriorReady;
        final int[] widths, heights;
        final String locale;

        ViewerConfig(int view, int allowedMask, boolean exteriorReady, int availableMask,
                int[] widths, int[] heights, String locale) {
            this.view = view;
            this.allowedMask = allowedMask;
            this.exteriorReady = exteriorReady;
            this.availableMask = availableMask;
            this.widths = widths;
            this.heights = heights;
            this.locale = locale;
        }
    }

    private ViewerConfig readAttachViewerConfig(Parcel data) {
        Parcel copy = Parcel.obtain();
        try {
            copy.appendFrom(data, data.dataPosition(), data.dataAvail());
            copy.setDataPosition(0);
            copy.readInt(); copy.readInt(); copy.createIntArray();
            copy.readLong(); copy.readStrongBinder();
            ViewerConfig config = readLiveViewerConfig(copy);
            if (copy.dataAvail() != 0) throw new IllegalArgumentException("unexpected viewer attach payload");
            return config;
        } finally { copy.recycle(); }
    }

    private ViewerConfig readLiveViewerConfig(Parcel data) {
        int marker = data.readInt();
        int view = data.readInt();
        int allowedMask = data.readInt();
        int exterior = data.readInt();
        int availableMask = data.readInt();
        int[] widths = data.createIntArray();
        int[] heights = data.createIntArray();
        String locale = data.readString();
        if (marker != MotionRecordingOutput.VIEWING_PARCEL_VERSION)
            throw new IllegalArgumentException("stale remote viewing payload");
        if (exterior != 0 && exterior != 1)
            throw new IllegalArgumentException("invalid exterior readiness flag");
        ViewerConfig config = new ViewerConfig(view, allowedMask, exterior == 1,
                availableMask, widths, heights, locale);
        validateViewerConfig(config);
        return config;
    }

    private static void validateViewerConfig(ViewerConfig config) {
        if (config == null || !RemoteCameraView.valid(config.view)
                || !RemoteCameraView.allowed(config.view, config.allowedMask)
                || config.availableMask < 0 || config.availableMask > RemoteCameraView.BOTH
                || (config.availableMask & ~RemoteCameraView.interiorMask(
                        config.view, config.allowedMask)) != 0
                || config.exteriorReady && !RemoteCameraView.needsExterior(config.view)
                || config.widths == null || config.widths.length != 2
                || config.heights == null || config.heights.length != 2
                || config.locale == null || !config.locale.matches("[A-Za-z-]{1,16}"))
            throw new IllegalArgumentException("invalid remote live camera selection");
        RemoteCameraView.validateMask(config.allowedMask);
        RemoteCameraView.validateMask(config.availableMask);
        for (int index = 0; index < 2; index++) {
            int width = config.widths[index], height = config.heights[index];
            if (width < 0 || height < 0 || width > 8192 || height > 8192
                    || (width == 0) != (height == 0)
                    || (config.availableMask & (1 << index)) != 0 && (width < 2 || height < 2))
                throw new IllegalArgumentException("invalid remote interior camera dimensions");
        }
    }

    private Surface[] prepareInitialViewer(MotionRecordingOutput output,
            ViewerConfig config, int requestId) throws Exception {
        if (!config.exteriorReady) {
            output.prepareStandalone(context);
            viewerComposerExternal = false;
            return output.updateLiveViewingOutput(null, config.view, config.allowedMask,
                    false, config.availableMask, config.widths, config.heights, config.locale);
        }
        try {
            lifetime.start(this::initialize);
            attachViewerExterior(output, requestId);
            viewerComposerExternal = true;
            return output.updateLiveViewingOutput(null, config.view, config.allowedMask,
                    true, config.availableMask, config.widths, config.heights, config.locale);
        } catch (Throwable externalFailure) {
            return fallbackStandalone(output, config, null,
                    new java.util.concurrent.atomic.AtomicBoolean(), externalFailure);
        }
    }

    private Surface[] updateViewerOutput(MotionRecordingOutput output, ViewerConfig config,
            Surface encoder, java.util.concurrent.atomic.AtomicBoolean encoderTransferred,
            boolean recreateComposer) throws Exception {
        boolean composerRecreated = false;
        boolean effectiveExterior = false;
        Throwable externalFailure = null;
        Surface effectiveEncoder = encoder;
        boolean effectiveEncoderOwned = false;
        boolean effectiveEncoderTransferred = false;

        try {
            if (config.exteriorReady) {
                if (!viewerExteriorAttached || !viewerComposerExternal) {
                    if (viewerExteriorAttached) {
                        Throwable detachFailure = detachViewerExterior();
                        if (detachFailure != null) externalFailure = detachFailure;
                    }
                    if (externalFailure == null) {
                        output.closeComposer();
                        viewerComposerExternal = false;
                        composerRecreated = true;
                        try {
                            lifetime.start(this::initialize);
                            attachViewerExterior(output, viewerRequestId);
                            viewerComposerExternal = true;
                        } catch (Throwable failure) {
                            externalFailure = failure;
                        }
                    }
                } else {
                    effectiveExterior = true;
                }
                effectiveExterior = externalFailure == null && viewerComposerExternal;
            } else {
                if (viewerExteriorAttached) {
                    Throwable detachFailure = detachViewerExterior();
                    if (detachFailure != null) {
                        reportViewerOperationFailure("detach_external_viewing", detachFailure);
                        rethrowViewerFailure(detachFailure);
                        throw new AssertionError("unreachable");
                    }
                }
                if (viewerComposerExternal) {
                    output.closeComposer();
                    viewerComposerExternal = false;
                    composerRecreated = true;
                }
                output.prepareStandalone(context);
            }

            if (externalFailure != null) {
                return fallbackStandalone(output, config, encoder, encoderTransferred, externalFailure);
            }

            if (encoder == null && (composerRecreated || recreateComposer)
                    && viewerEncoderReference != null) {
                effectiveEncoder = duplicateSurface(viewerEncoderReference);
                effectiveEncoderOwned = true;
            }
            Surface nextReference = encoder == null ? null : duplicateSurface(encoder);
            if (encoder != null) {
                Surface previous = viewerEncoderReference;
                viewerEncoderReference = nextReference;
                nextReference = null;
                if (previous != null) safeRelease(previous);
            }
            if (encoder != null) encoderTransferred.set(true);
            effectiveEncoderTransferred = true;
            Surface[] result = output.updateLiveViewingOutput(effectiveEncoder, config.view,
                    config.allowedMask, effectiveExterior, config.availableMask,
                    config.widths, config.heights, config.locale);
            return requireViewerInputs(result);
        } finally {
            if (effectiveEncoderOwned && !effectiveEncoderTransferred && effectiveEncoder != null)
                safeRelease(effectiveEncoder);
            if (encoder != null && !encoderTransferred.get()) {
                safeRelease(encoder);
                encoderTransferred.set(true);
            }
        }
    }

    private Surface[] fallbackStandalone(MotionRecordingOutput output, ViewerConfig config,
            Surface encoder, java.util.concurrent.atomic.AtomicBoolean encoderTransferred,
            Throwable primary) throws Exception {
        Throwable failure = primary;
        Throwable detachFailure = detachViewerExterior();
        if (detachFailure != null) {
            if (detachFailure != failure) failure.addSuppressed(detachFailure);
            reportViewerOperationFailure("external_viewing_fallback", failure);
            rethrowViewerFailure(failure);
            throw new AssertionError("unreachable");
        }
        viewerComposerExternal = false;
        Surface effectiveEncoder = encoder;
        boolean effectiveEncoderOwned = false;
        boolean effectiveEncoderTransferred = false;
        try {
            try { output.closeComposer(); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            output.prepareStandalone(context);
            if (encoder == null && viewerEncoderReference != null) {
                effectiveEncoder = duplicateSurface(viewerEncoderReference);
                effectiveEncoderOwned = true;
            }
            if (encoder != null) {
                Surface nextReference = duplicateSurface(encoder);
                Surface previous = viewerEncoderReference;
                viewerEncoderReference = nextReference;
                if (previous != null) safeRelease(previous);
                encoderTransferred.set(true);
            }
            effectiveEncoderTransferred = true;
            Surface[] inputs = output.updateLiveViewingOutput(effectiveEncoder, config.view,
                    config.allowedMask, false, config.availableMask,
                    config.widths, config.heights, config.locale);
            reportViewerOperationFailure("external_viewing_fallback", failure);
            return requireViewerInputs(inputs);
        } catch (Throwable fallbackFailure) {
            if (fallbackFailure != failure) failure.addSuppressed(fallbackFailure);
            reportViewerOperationFailure("external_viewing_fallback", failure);
            rethrowViewerFailure(failure);
            throw new AssertionError("unreachable");
        } finally {
            if (effectiveEncoderOwned && !effectiveEncoderTransferred && effectiveEncoder != null)
                safeRelease(effectiveEncoder);
            if (encoder != null && !encoderTransferred.get()) {
                safeRelease(encoder);
                encoderTransferred.set(true);
            }
        }
    }

    private void attachViewerExterior(MotionRecordingOutput output, int requestId) throws Exception {
        output.closeComposer();
        viewerComposerExternal = false;
        Surface[] primary = output.inputs();
        Surface[] aliases = duplicateSurfaces(primary);
        boolean registered = false;
        boolean passedToHelper = false;
        try {
            output.registerAliases(aliases);
            registered = true;
            viewerExteriorAttached = true;
            passedToHelper = true;
            helper.attachViewing(aliases, requestId);
            viewerComposerExternal = true;
        } finally {
            if (registered) output.unregisterAliases(aliases);
            if (!passedToHelper) releaseSurfaces(aliases);
        }
    }

    private Throwable detachViewerExterior() {
        if (!viewerExteriorAttached) return null;
        try {
            helper.detachViewing();
            viewerExteriorAttached = false;
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static Surface[] requireViewerInputs(Surface[] inputs) {
        if (inputs == null || inputs.length != 2)
            throw new IllegalStateException("remote composer returned an invalid interior Surface pair");
        for (Surface input : inputs)
            if (input != null && !input.isValid())
                throw new IllegalStateException("remote composer returned an invalid interior Surface");
        return inputs;
    }

    private static Surface[] duplicateSurfaces(Surface[] primary) throws Exception {
        if (primary == null || primary.length != 4)
            throw new IllegalArgumentException("four viewer output surfaces required");
        Surface[] aliases = new Surface[primary.length];
        try {
            for (int index = 0; index < primary.length; index++) {
                if (primary[index] == null || !primary[index].isValid())
                    throw new IllegalArgumentException("invalid primary viewer Surface");
                aliases[index] = duplicateSurface(primary[index]);
            }
            return aliases;
        } catch (Throwable failure) {
            releaseSurfaces(aliases);
            rethrowViewerFailure(failure);
            throw new AssertionError("unreachable");
        }
    }

    private static Surface duplicateSurface(Surface source) {
        if (source == null || !source.isValid()) throw new IllegalArgumentException("invalid viewer Surface alias");
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Surface copy = Surface.CREATOR.createFromParcel(parcel);
            if (copy == null || !copy.isValid()) {
                if (copy != null) copy.release();
                throw new IllegalStateException("viewer Surface alias is invalid");
            }
            return copy;
        } finally { parcel.recycle(); }
    }

    private <T> T callViewerOwned(java.util.concurrent.Callable<T> task,
            java.util.concurrent.atomic.AtomicBoolean dispatched) throws Exception {
        if (android.os.Looper.myLooper() == handler.getLooper()) {
            dispatched.set(true);
            return task.call();
        }
        java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(0);
        FutureTask<T> queued = new FutureTask<>(() -> {
            if (!state.compareAndSet(0, 1)) throw new java.util.concurrent.CancellationException();
            dispatched.set(true);
            return task.call();
        });
        if (!handler.post(queued)) throw new IllegalStateException("camera runtime handler closed");
        try {
            return queued.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            if (state.compareAndSet(0, 2)) queued.cancel(false);
            else if (state.get() == 1) dispatched.set(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (java.util.concurrent.TimeoutException timeout) {
            if (state.compareAndSet(0, 2)) queued.cancel(false);
            else if (state.get() == 1) dispatched.set(true);
            throw timeout;
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }

    private void cleanupUninstalledOutput(MotionRecordingOutput output, Throwable primary) {
        try { output.closeComposer(); }
        catch (Throwable failure) {
            if (primary != null && failure != primary) primary.addSuppressed(failure);
            reportViewerOperationFailure("uninstalled_viewer_composer_close", failure);
            return;
        }
        try { output.releaseInputs(); }
        catch (Throwable failure) {
            if (primary != null && failure != primary) primary.addSuppressed(failure);
            reportViewerOperationFailure("uninstalled_viewer_input_release", failure);
        }
    }

    private void clearViewer() throws Exception {
        MotionRecordingOutput output = viewerOutput;
        Throwable detachFailure = detachViewerExterior();
        if (detachFailure != null) {
            reportViewerOperationFailure("external_viewing_detach", detachFailure);
            rethrowViewerFailure(detachFailure);
            throw new AssertionError("unreachable");
        }
        if (output != null) {
            try { output.closeComposer(); }
            catch (Throwable failure) {
                reportViewerOperationFailure("composer_close", failure);
                rethrowViewerFailure(failure);
                throw new AssertionError("unreachable");
            }
            viewerComposerExternal = false;
            try { output.releaseInputs(); }
            catch (Throwable failure) {
                reportViewerOperationFailure("input_release", failure);
                rethrowViewerFailure(failure);
                throw new AssertionError("unreachable");
            }
        }
        Surface encoder = viewerEncoderReference;
        if (encoder != null) {
            try { encoder.release(); }
            catch (Throwable failure) {
                reportViewerOperationFailure("encoder_release", failure);
                rethrowViewerFailure(failure);
                throw new AssertionError("unreachable");
            }
        }
        if (viewerOwner != null && viewerDeath != null) viewerOwner.unlinkToDeath(viewerDeath, 0);
        viewerOutput = null;
        viewerEncoderReference = null;
        viewerRequestId = 0;
        viewerExteriorAttached = false;
        viewerComposerExternal = false;
        viewerOwner = null;
        viewerDeath = null;
        viewerSession = null;
        viewerGeneration = 0;
    }

    private void reportViewerCleanupFailure(String operation, Throwable failure) {
        reportViewerOperationFailure("cleanup_" + operation, failure);
    }

    private void reportViewerOperationFailure(String operation, Throwable failure) {
        event("camera_viewing_" + operation + "_error", "operation", operation,
                "error", failure.toString(), "stack_trace", viewerStackTrace(failure));
    }

    private static String viewerStackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static void rethrowViewerFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException(failure);
    }

    private static void releaseSurfaces(Surface[] surfaces) {
        if (surfaces == null) return;
        for (Surface surface : surfaces) safeRelease(surface);
    }

    private static void safeRelease(Surface surface) {
        if (surface == null) return;
        try { surface.release(); }
        catch (Throwable failure) {
            android.util.Log.e("CameraRuntimeHost", "viewer Surface release failed", failure);
        }
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
            catch (Exception error) { reportViewerCleanupFailure("runtime_shutdown", error); }
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
