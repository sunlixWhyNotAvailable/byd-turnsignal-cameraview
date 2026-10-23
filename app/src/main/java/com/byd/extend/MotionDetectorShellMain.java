package com.byd.extend;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.PowerManager;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.Surface;
import android.media.Image;
import com.byd.sentrymode.monitor.library.MotionNativeLibrary;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell-UID worker. It consumes a Surface from the existing camera owner; it never opens AVMCamera. */
public final class MotionDetectorShellMain {
    private static final long CONTROL_TIMEOUT_MS = 5_000L;
    private static final long FIRST_INFERENCE_TIMEOUT_MS = 10_000L;
    private static final String EXTEND_LOCK = MotionDetectorProtocol.LOCK_PATH;
    private static final AtomicLong BINDER_FAILURE_SEQUENCE = new AtomicLong();

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[0].matches("[0-9]{1,10}") || args[1].isEmpty())
                throw new IllegalArgumentException("Expected app UID and Extend APK identity");
            if (Process.myUid() != 2000) throw new SecurityException("Motion worker must run as shell UID 2000");
            int appUid = Integer.parseInt(args[0]);
            String identity = args[1];
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            try (RandomAccessFile file = new RandomAccessFile(EXTEND_LOCK, "rw")) {
                FileLock acquired = file.getChannel().tryLock();
                if (acquired == null) throw new IllegalStateException("Motion worker already running");
                try (FileLock lock = acquired) {
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
                Context system = (Context) activityThreadClass.getMethod("getSystemContext")
                        .invoke(activityThread);
                if (system == null) throw new IllegalStateException("system context unavailable");
                OemLogPolicy.initialize(system);
                CameraShellMain.initializeSystemFontsForShell();
                MotionDetectorShellMain daemon = new MotionDetectorShellMain(appUid, identity, system);
                System.out.println("motion_native_environment LD_LIBRARY_PATH=" + System.getenv("LD_LIBRARY_PATH")
                        + " java.library.path=" + System.getProperty("java.library.path"));
                daemon.publish();
                synchronized (daemon) { daemon.wait(); }
                }
            }
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private final int appUid;
    private final String extendIdentity;
    private final Context systemContext;
    private final HandlerThread controlThread = new HandlerThread("motion-detector-control");
    private final Handler control;
    private final ExecutorService hostOperations = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "motion-camera-host-ops"));
    private final ExecutorService nativeOperations = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "motion-native-ops"));
    private final Object analysisLock = new Object();
    private final AtomicLong operationSequence = new AtomicLong();
    private final MotionEventPolicy policy = new MotionEventPolicy();
    private final ShellBinder binder = new ShellBinder();
    private final MotionScreenControl screen;
    private final MotionSessionConfigStore configStore;
    private final MotionSessionRuntime sessionRuntime = new MotionSessionRuntime();
    private CameraChargeTelemetry.Owner cameraChargeOwner;
    private volatile CameraChargePolicy.State cameraChargeState = new CameraChargePolicy.State(
            null, null, CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE,
            0, false, true);
    private boolean chargeHandlingEnabled;
    private final MotionCameraPreparation cameraPreparation;
    private final Runnable armCheck = this::advanceEligibility;
    private final Runnable telemetryRefresh = this::refreshTelemetry;
    private boolean telemetryRefreshQueued;
    private boolean telemetryRefreshPending;
    private boolean cameraRecovering;
    private String lastCameraPreparation = "";
    private final Runnable firstInferenceTimeout = this::onFirstInferenceTimeout;
    private final Runnable warningWindowTimeout = this::onWarningWindowTimeout;
    private final Runnable warningTimeout = this::finishWarningAfterPolicy;
    private final Runnable warningCloseTimeout = this::onWarningCloseTimeout;
    private final Runnable recordingTailFinished = this::finishRecordingTail;
    private volatile MotionConfig config;
    private MotionSessionConfigStore.Snapshot pendingConfig;
    private IBinder pendingConfigureCallback;
    private String pendingConfigureToken;
    private MotionShellMediaContext mediaContext;
    private MotionMediaLibrary mediaLibrary;
    private MotionMediaIndexBridge mediaBridge;
    private MotionVideoRecorder recorder;
    private MotionRecordingOutput composer;
    private PowerManager.WakeLock wakeLock;
    private int recordingGeneration;
    private int recordingRequestId;
    private boolean recordingAttached;
    private boolean recordingStopRequested;
    private long recordingAttemptRevision;
    private long recordingFailureRevision = Long.MIN_VALUE;
    private boolean teardownPending;
    private boolean teardownNotifyHost;
    private boolean teardownHostShutdown;
    private boolean teardownSurfacesDrained;
    private boolean teardownHostUnbound;
    private boolean teardownUnbindAttempted;
    private boolean teardownDemandCleared;
    private boolean teardownHostOperationPending;
    private long teardownGeneration;
    private boolean chargeStartupPending;
    private long chargeStartupGeneration;
    private boolean nativePreparationPending;
    private boolean nativeDeletePending;
    private boolean nativeDeleteComplete;
    private boolean readerAttachPending;
    private boolean readerDetachPending;
    private boolean recordingDetachPending;
    private long sessionGeneration;
    private String teardownFinalState = "idle";
    private String teardownReason = "";
    private IBinder teardownCallback;
    private volatile boolean warning;
    private boolean warningWindowPending;
    private volatile boolean warningWindowDrawn;
    private boolean shuttingDown;
    private volatile boolean workerBound;
    private volatile boolean workerBinding;
    private volatile IBinder telemetryHost;
    private IBinder.DeathRecipient telemetryHostDeath;
    private boolean warningVisible;
    private boolean warningClosing;
    private boolean warningRestoreAllowed;
    private boolean warningWindowLaunched;
    private int warningWindowGeneration;
    private ResultReceiver warningWindowCommands;
    private boolean warningPreparingSleep;
    private boolean warningSleepAttempted;
    private boolean warningScreenFinishIssued;
    private boolean warningAwaitingClosed;
    private boolean warningBlackFallback;
    private boolean warningPowerOnClose;
    private boolean warningCloseSent;
    private boolean warningWakeTokenOwned;
    private String deliberatelyDetachedProducerReason = "";
    private String warningWindowFailure = "";
    private volatile String expectedSourceEpoch = "";

    private volatile String sessionId;
    private volatile IBinder appCallback;
    private IBinder.DeathRecipient appCallbackDeath;
    private IBinder pendingAppCallback;
    private boolean cameraHostPending;
    private boolean cameraHostReadyDuringBinding;
    private volatile String state = "idle";
    private volatile String lastError = "";
    private volatile boolean prepared;
    private volatile boolean eligible;
    private boolean attached;
    private boolean nativeInitialized;
    private volatile int sensitivity;
    private AssetManager assets;
    private MotionNativeLibrary nativeApi;
    private volatile byte[] nativeInput;
    private volatile ImageReader reader;
    private MotionSharedSource sharedSource;
    private Surface surface;
    private long frameNumber;
    private boolean analysisBusy;
    private final FramePerformance framePerformance = new FramePerformance();
    private final FrameCadence frameCadence = new FrameCadence();
    private final int[] loggedFrameLayout = new int[8];
    private String lastReportedDetectorState = "";
    private final Map<IBinder, ChargeObserver> viewingChargeObservers = new IdentityHashMap<>();
    private volatile IBinder viewingHelper;
    private volatile int viewingHelperPid;
    private IBinder.DeathRecipient registeredViewingHelperDeath;
    private boolean firstInferenceReceived;
    private volatile ManualCheck manualCheck;
    private final Map<IBinder, RemoteViewLease> remoteViewLeases = new IdentityHashMap<>();

    private final class ChargeObserver {
        final IBinder callback;
        final IBinder.DeathRecipient death;
        ChargeObserver(IBinder callback) {
            this.callback = callback;
            death = () -> control.post(() -> removeViewingChargeObserver(callback));
        }
    }

    private static final class ConfigureResult {
        final MotionSessionConfigStore.Snapshot snapshot;
        final int applyKind;

        ConfigureResult(MotionSessionConfigStore.Snapshot snapshot, int applyKind) {
            this.snapshot = snapshot;
            this.applyKind = applyKind;
        }
    }

    private static final class OperationTrace {
        final long id;
        final String kind;
        final String sessionId;
        final long queuedNs;

        OperationTrace(long id, String kind, String sessionId, long queuedNs) {
            this.id = id;
            this.kind = kind;
            this.sessionId = sessionId;
            this.queuedNs = queuedNs;
        }
    }

    private static final class PendingResult<T> extends FutureTask<T> {
        PendingResult() { super(() -> null); }
        void succeed(T value) { set(value); }
        void fail(Throwable error) { setException(error); }
    }

    private static final class CameraOperationResult {
        final long operationId;
        final long queuedNs;
        final long startedNs;
        final long finishedNs;
        final boolean accepted;
        final Throwable failure;

        CameraOperationResult(long operationId, long queuedNs, long startedNs,
                long finishedNs, boolean accepted, Throwable failure) {
            this.operationId = operationId;
            this.queuedNs = queuedNs;
            this.startedNs = startedNs;
            this.finishedNs = finishedNs;
            this.accepted = accepted;
            this.failure = failure;
        }
    }

    private static final class TeardownHostResult {
        final long operationId;
        final long queuedNs;
        final long startedNs;
        final long finishedNs;
        final long telemetryNs;
        final long wakeNs;
        final long unbindNs;
        final boolean unbound;
        final Throwable telemetryFailure;
        final Throwable wakeFailure;
        final Throwable unbindFailure;

        TeardownHostResult(long operationId, long queuedNs, long startedNs, long finishedNs,
                long telemetryNs, long wakeNs, long unbindNs, boolean unbound,
                Throwable telemetryFailure, Throwable wakeFailure, Throwable unbindFailure) {
            this.operationId = operationId;
            this.queuedNs = queuedNs;
            this.startedNs = startedNs;
            this.finishedNs = finishedNs;
            this.telemetryNs = telemetryNs;
            this.wakeNs = wakeNs;
            this.unbindNs = unbindNs;
            this.unbound = unbound;
            this.telemetryFailure = telemetryFailure;
            this.wakeFailure = wakeFailure;
            this.unbindFailure = unbindFailure;
        }
    }

    private static final class NativeCleanupFailure {
        final String operation;
        final Throwable failure;

        NativeCleanupFailure(String operation, Throwable failure) {
            this.operation = operation;
            this.failure = failure;
        }
    }

    private static final class NativeDeleteResult {
        final long operationId;
        final long queuedNs;
        final long startedNs;
        final long finishedNs;
        final ArrayList<NativeCleanupFailure> failures;
        final Throwable failure;

        NativeDeleteResult(long operationId, long queuedNs, long startedNs,
                long finishedNs, ArrayList<NativeCleanupFailure> failures, Throwable failure) {
            this.operationId = operationId;
            this.queuedNs = queuedNs;
            this.startedNs = startedNs;
            this.finishedNs = finishedNs;
            this.failures = failures;
            this.failure = failure;
        }
    }

    private interface CameraOperationCompletion {
        void complete(CameraOperationResult result);
    }

    private interface NativeDeleteCompletion {
        void complete(NativeDeleteResult result);
    }

    private interface CheckPowerCompletion {
        void complete(Throwable failure);
    }

    private static final class NativeSetupResult {
        final int modelValue;
        final int mirrorSetting;
        final int sensitivity;
        final String stage;
        final long operationId;
        final long queuedNs;
        final long startedNs;
        final long finishedNs;
        final long setupNs;
        final long initNs;
        final Throwable failure;

        NativeSetupResult(MotionNativeSetup setup, long operationId, long queuedNs,
                long startedNs, long finishedNs, long setupNs, long initNs, Throwable failure) {
            this.modelValue = setup.modelValue;
            this.mirrorSetting = setup.mirrorSetting;
            this.sensitivity = setup.sensitivity;
            this.stage = setup.stage;
            this.operationId = operationId;
            this.queuedNs = queuedNs;
            this.startedNs = startedNs;
            this.finishedNs = finishedNs;
            this.setupNs = setupNs;
            this.initNs = initNs;
            this.failure = failure;
        }
    }

    private static final class NativeFrameResult {
        final long operationId;
        final long queuedNs;
        final long startedNs;
        final long finishedNs;
        final long detectNs;
        final int raw;
        final int nativeState;
        final Throwable failure;

        NativeFrameResult(long operationId, long queuedNs, long startedNs,
                long finishedNs, long detectNs, int raw, int nativeState, Throwable failure) {
            this.operationId = operationId;
            this.queuedNs = queuedNs;
            this.startedNs = startedNs;
            this.finishedNs = finishedNs;
            this.detectNs = detectNs;
            this.raw = raw;
            this.nativeState = nativeState;
            this.failure = failure;
        }
    }

    private final class ManualCheck {
        final String id;
        final IBinder owner;
        final ResultReceiver results;
        final MotionConfig recordingConfig;
        final long deadline = SystemClock.elapsedRealtime() + MotionCheckupClient.TIMEOUT_MS;
        final MotionCheckupFrames frames = new MotionCheckupFrames();
        final IBinder.DeathRecipient death;
        final Runnable timeout;
        volatile boolean cancelled;
        boolean ownsSession, ownsReader, ownsRecording, ownsNative, finishing, completing;
        boolean waitingForReaderDetach, waitingForRecordingDetach, waitingForNativeDelete;
        boolean waitingForSessionTeardown;
        String previousState;
        String completionReason, completionDetail;
        int completionStatus;
        Throwable cleanupFailure;
        int stage = MotionCheckupClient.ACCESS;
        ManualCheck(String id, IBinder owner, ResultReceiver results, MotionConfig recordingConfig) {
            this.id = id; this.owner = owner; this.results = results;
            this.recordingConfig = recordingConfig;
            death = () -> cancelCheck(this);
            timeout = () -> {
                if (manualCheck == this && completing && waitingForSessionTeardown) {
                    waitingForSessionTeardown = false;
                    markCheckCleanupPending(this,
                            new IllegalStateException("temporary Motion session teardown did not finish within the bounded cleanup wait"));
                    finishManualCheck(this);
                    return;
                }
                completeCheck(this, MotionCheckupClient.ERROR, "timeout",
                        "Check-up stage did not complete before its deadline", true);
            };
        }
    }

    private final class RemoteViewLease {
        final String id;
        final IBinder owner;
        final IBinder.DeathRecipient death;
        RemoteViewLease(String id, IBinder owner) {
            this.id = id;
            this.owner = owner;
            death = () -> control.post(() -> releaseRemoteViewOnControl(id, owner));
        }
    }

    private MotionDetectorShellMain(int appUid, String extendIdentity, Context systemContext) {
        this.appUid = appUid;
        this.extendIdentity = extendIdentity;
        this.systemContext = systemContext;
        this.configStore = new MotionSessionConfigStore(appUid);
        controlThread.start();
        control = new Handler(controlThread.getLooper());
        cameraPreparation = new MotionCameraPreparation(systemContext, new MotionCameraPreparation.Listener() {
            @Override public void onSample(String id, int power, int online, int stream, String error) {
                control.post(() -> cameraPreparationSample(id, power, online, stream, error));
            }
            @Override public void onRelease(String id, String error) {
                logEvent("motion_camera_release", id, "confirmed", error.isEmpty(), "error", error);
            }
            @Override public void onStopped() {
                control.post(() -> {
                    if (teardownPending) continuePendingTeardown();
                    else if (!sessionRuntime.arming() && !sessionRuntime.armed() && !eligible
                            && !warning && (recorder == null || !recorder.isRecording())) holdCpu(false);
                });
            }
        });
        screen = new MotionScreenControl((active, generation, screenState, detail) -> control.post(() -> {
            if (!sameSession(active)) return;
            if (generation >= 0 && generation != warningWindowGeneration) return;
            sendScreen(active, screenState, detail);
            if ("wake_requested".equals(screenState)) {
                launchWarningWindow(active, generation);
                return;
            }
            if ("cover_release_requested".equals(screenState)) {
                if (!warning || generation != warningWindowGeneration || !warningWindowDrawn
                        || warningClosing || !eligible) return;
                warningVisible = true;
                policy.beginVisibleWarning(SystemClock.elapsedRealtime());
                scheduleWarningDeadline();
                sendStatus(active, "warning_visible", "warning interval begins after owned cover release was confirmed");
                return;
            }
            if ("black_restore_observed".equals(screenState)) {
                closeWarningAfterBlackRestore(active, generation);
                endOwnedWake(active);
                return;
            }
            if (isWarningCycleFailure(screenState)) {
                if (eligible) sessionRuntime.blockWarningRetry();
                warningWindowFailure = screenState + (detail.isEmpty() ? "" : ": " + detail);
                warningWindowPending = false;
                control.removeCallbacks(warningWindowTimeout);
                sendError(active, "warning_preflight", warningWindowFailure);
                if (warning) enterWarningBlackFallback(active, warningWindowFailure, true);
                endOwnedWake(active);
                return;
            }
            if ("user_takeover".equals(screenState) || "user_dismissed_no_sleep".equals(screenState)) {
                if (eligible) sessionRuntime.blockWarningRetry();
                warningWindowFailure = screenState + (detail.isEmpty() ? "" : ": " + detail);
                if (warning && !warningPowerOnClose && !warningAwaitingClosed)
                    enterWarningBlackFallback(active, warningWindowFailure, false);
                endOwnedWake(active);
                return;
            }
            if ("wake_skipped".equals(screenState) || "wake_uncertain".equals(screenState)
                    || "cover_release_skipped".equals(screenState)
                    || "cover_release_uncertain".equals(screenState)
                    || "sleep_skipped".equals(screenState) || "sleep_uncertain".equals(screenState)
                    || "black_restore_uncertain".equals(screenState) || "finish_uncertain".equals(screenState)
                    || "off_session_lost".equals(screenState)) endOwnedWake(active);
        }));
    }

    private void publish() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        serviceManager.getMethod("addService", String.class, IBinder.class)
                .invoke(null, MotionDetectorProtocol.SERVICE_NAME, binder);
        System.out.println("motion_worker_ready uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " protocol=" + MotionDetectorProtocol.VERSION);
        System.out.flush();
        control.post(this::resumePersistedConfig);
    }

    private final class ShellBinder extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(MotionDetectorProtocol.DESCRIPTOR);
                return true;
            }
            int callerUid = Binder.getCallingUid();
            if (code == MotionDetectorProtocol.TX_TELEMETRY) {
                if (callerUid != 2000) throw new SecurityException("Motion telemetry requires shell UID");
                data.enforceInterface(MotionDetectorProtocol.DESCRIPTOR);
                String id = data.readString();
                String line = data.readString();
                if (!MotionDetectorProtocol.validSession(id) || line == null || line.length() > 8192
                        || data.dataAvail() != 0)
                    throw new IllegalArgumentException("invalid Motion telemetry event");
                control.post(() -> acceptTelemetry(id, line));
                return true;
            }
            if (code == MotionDetectorProtocol.TX_REGISTER_VIEW_HELPER && callerUid != appUid) {
                if (reply != null) reply.writeException(new SecurityException("viewing helper registration requires app UID"));
                return true;
            }
            if (isViewingHelperOperation(code)) {
                boolean appChargeRead = code == MotionDetectorProtocol.TX_READ_CHARGE
                        && callerUid == appUid;
                if (!appChargeRead && !isRegisteredViewingCaller(callerUid, Binder.getCallingPid())) {
                    if (reply != null) reply.writeException(new SecurityException("unregistered viewing helper"));
                    return true;
                }
            } else if (callerUid != appUid && !(code == MotionDetectorProtocol.TX_PING
                    && isRegisteredViewingCaller(callerUid, Binder.getCallingPid()))) {
                if (reply != null) reply.writeException(new SecurityException("motion caller denied"));
                return true;
            }
            try {
                data.enforceInterface(MotionDetectorProtocol.DESCRIPTOR);
                switch (code) {
                    case MotionDetectorProtocol.TX_CHECK_START: {
                        String id = data.readString();
                        IBinder owner = data.readStrongBinder();
                        ResultReceiver results = data.readTypedObject(ResultReceiver.CREATOR);
                        MotionConfig recordingConfig = MotionConfig.parse(data.readString());
                        if (!MotionDetectorProtocol.validSession(id) || owner == null || results == null
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion check-up request");
                        ManualCheck check = new ManualCheck(id, owner, results, recordingConfig);
                        synchronized (binder) {
                            if (manualCheck != null) throw new IllegalStateException("Motion check-up already running");
                            owner.linkToDeath(check.death, 0);
                            manualCheck = check;
                        }
                        control.post(() -> beginCheck(check));
                        reply.writeNoException();
                        return true;
                    }
                    case MotionDetectorProtocol.TX_CHECK_CANCEL: {
                        String id = data.readString();
                        if (!MotionDetectorProtocol.validSession(id) || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion check-up cancellation");
                        ManualCheck check = manualCheck;
                        if (check != null && check.id.equals(id)) cancelCheck(check);
                        reply.writeNoException();
                        return true;
                    }
                    case MotionDetectorProtocol.TX_PING:
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("ping payload not empty");
                        reply.writeNoException();
                        reply.writeInt(MotionDetectorProtocol.VERSION);
                        reply.writeInt(BuildConfig.VERSION_CODE);
                        reply.writeInt(Process.myUid());
                        reply.writeInt(Process.myPid());
                        reply.writeString(extendIdentity);
                        return true;
                    case MotionDetectorProtocol.TX_CAMERA_HOST_READY:
                        if (data.dataAvail() != 0)
                            throw new IllegalArgumentException("camera-host readiness payload not empty");
                        callControl(() -> { onCameraHostReadyOnControl(); return null; });
                        reply.writeNoException();
                        return true;
                    case MotionDetectorProtocol.TX_CONFIGURE: {
                        int version = data.readInt();
                        int build = data.readInt();
                        String configJson = data.readString();
                        String requestToken = data.readString();
                        IBinder configureCallback = data.readStrongBinder();
                        if (version != MotionDetectorProtocol.VERSION || build != BuildConfig.VERSION_CODE
                                || configJson == null || configJson.length() > 8192
                                || !MotionDetectorProtocol.validSession(requestToken)
                                || configureCallback == null || !configureCallback.isBinderAlive()
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion config request");
                        ConfigureResult configured = callControl(
                                () -> configureOnControl(configJson, configureCallback, requestToken));
                        reply.writeNoException();
                        reply.writeInt(1);
                        reply.writeString(configured.snapshot == null ? "" : configured.snapshot.sessionId);
                        reply.writeStrongBinder(binder);
                        reply.writeInt(configured.applyKind);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_ATTACH_UI: {
                        String id = data.readString();
                        IBinder callback = data.readStrongBinder();
                        if (!MotionDetectorProtocol.validSession(id) || callback == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion UI attachment");
                        int accepted = callControl(() -> attachUiOnControl(id, callback));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_DETACH_UI: {
                        String id = data.readString();
                        IBinder callback = data.readStrongBinder();
                        if (!MotionDetectorProtocol.validSession(id) || callback == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion UI detachment");
                        int accepted = callControl(() -> detachUiOnControl(id, callback));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_STATUS: {
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("status payload not empty");
                        reply.writeNoException();
                        reply.writeString(state);
                        reply.writeInt(prepared ? 1 : 0);
                        reply.writeInt(eligible ? 1 : 0);
                        reply.writeString(lastError);
                        reply.writeLong(SystemClock.elapsedRealtime());
                        reply.writeString(sessionId == null ? "" : sessionId);
                        reply.writeString(config == null ? "" : config.toJson());
                        reply.writeInt(warning ? 1 : 0);
                        reply.writeInt(warningWindowDrawn ? 1 : 0);
                        MotionDetectorProtocol.writeChargeState(reply, cameraChargeState);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_READ_CHARGE: {
                        if (data.dataAvail() != 0)
                            throw new IllegalArgumentException("charge refresh payload not empty");
                        CameraChargePolicy.State fresh = readChargeStateFromBinder();
                        reply.writeNoException();
                        MotionDetectorProtocol.writeChargeState(reply, fresh);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_REGISTER_VIEW_HELPER: {
                        IBinder helper = data.readStrongBinder();
                        int pid = data.readInt();
                        String apk = data.readString();
                        if (helper == null || !helper.isBinderAlive() || pid <= 0
                                || !extendIdentity.equals(apk) || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid viewing helper registration");
                        CameraViewingShellProtocol.Identity identity = CameraViewingShellProtocol.readIdentity(helper);
                        if (identity == null || !identity.matches(pid, extendIdentity))
                            throw new SecurityException("viewing helper APK/PID identity mismatch");
                        registerViewingHelper(helper, pid);
                        reply.writeNoException();
                        reply.writeInt(1);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_VIEW_OBSERVE_CHARGE: {
                        IBinder callback = data.readStrongBinder();
                        if (callback == null || !callback.isBinderAlive() || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid viewing charge observer");
                        callControl(() -> { addViewingChargeObserver(callback); return null; });
                        reply.writeNoException();
                        reply.writeInt(1);
                        sendViewingChargeState(callback, cameraChargeState);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_VIEW_DETACH_CHARGE: {
                        IBinder callback = data.readStrongBinder();
                        if (callback == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid viewing charge observer detach");
                        callControl(() -> { removeViewingChargeObserver(callback); return null; });
                        reply.writeNoException();
                        reply.writeInt(1);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_VIEW_PREPARE: {
                        String id = data.readString();
                        IBinder owner = data.readStrongBinder();
                        if (!MotionDetectorProtocol.validSession(id) || owner == null
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid remote camera preparation request");
                        boolean ready = prepareRemoteViewFromBinder(id, owner);
                        reply.writeNoException();
                        reply.writeInt(ready ? 1 : 0);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_VIEW_RELEASE: {
                        String id = data.readString();
                        IBinder owner = data.readStrongBinder();
                        if (!MotionDetectorProtocol.validSession(id) || owner == null
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid remote camera release request");
                        int released = callControl(() -> releaseRemoteViewOnControl(id, owner));
                        reply.writeNoException();
                        reply.writeInt(released);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_EXPLICIT_SHUTDOWN: {
                        String id = data.readString();
                        String reason = data.readString();
                        if (!MotionDetectorProtocol.validSession(id) || !validReason(reason)
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion shutdown request");
                        int accepted = callControl(() -> suspendForExplicitShutdown(id, reason));
                        reply.writeNoException();
                        reply.writeInt(accepted);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_IMPORT_MEDIA_INDEX: {
                        ParcelFileDescriptor source = data.readTypedObject(ParcelFileDescriptor.CREATOR);
                        if (source == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("legacy Motion index file descriptor required");
                        try (ParcelFileDescriptor descriptor = source;
                                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                            ensureMediaBridge();
                            boolean complete = mediaBridge.importLegacyIndex(input);
                            reply.writeNoException();
                            reply.writeInt(complete ? 1 : 0);
                        }
                        return true;
                    }
                    case MotionDetectorProtocol.TX_REFRESH_MEDIA: {
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("refresh payload not empty");
                        ensureMediaBridge();
                        int changed = mediaLibrary.refresh();
                        reply.writeNoException();
                        reply.writeInt(changed);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_QUERY_MEDIA: {
                        MotionMediaLibrary.Query query = readMediaQuery(data);
                        String configJson = data.readString();
                        String pageToken = data.readString();
                        if (configJson == null || configJson.length() > 8192
                                || (pageToken != null && (pageToken.length() > 20
                                || !pageToken.matches("[0-9]+"))) || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion media query");
                        MotionConfig requested = MotionConfig.parse(configJson);
                        ensureMediaBridge();
                        String result = mediaBridge.queryPage(query, requested, pageToken);
                        reply.writeNoException();
                        reply.writeString(result);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_READ_MEDIA: {
                        String volumeId = data.readString();
                        long mediaStoreId = data.readLong();
                        if (!validVolumeId(volumeId) || mediaStoreId <= 0 || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion media selector");
                        ensureMediaBridge();
                        String result = mediaBridge.read(volumeId, mediaStoreId);
                        reply.writeNoException();
                        reply.writeString(result);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_DELETE_MEDIA: {
                        String volumeId = data.readString();
                        long mediaStoreId = data.readLong();
                        if (!validVolumeId(volumeId) || mediaStoreId <= 0 || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion media selector");
                        ensureMediaBridge();
                        boolean deleted = mediaBridge.delete(volumeId, mediaStoreId);
                        reply.writeNoException();
                        reply.writeInt(deleted ? 1 : 0);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_OPEN_THUMBNAIL: {
                        String volumeId = data.readString();
                        long mediaStoreId = data.readLong();
                        String expectedKey = data.readString();
                        if (!validVolumeId(volumeId) || mediaStoreId <= 0
                                || !MotionThumbnailKey.isValid(expectedKey) || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid Motion thumbnail selector");
                        MotionMediaIndexBridge bridge;
                        synchronized (this) { bridge = mediaBridge; }
                        ParcelFileDescriptor cover = bridge == null ? null
                                : bridge.openThumbnail(volumeId, mediaStoreId, expectedKey);
                        reply.writeNoException();
                        reply.writeTypedObject(cover, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                        return true;
                    }
                    case MotionDetectorProtocol.TX_START:
                    case MotionDetectorProtocol.TX_SET_ELIGIBLE:
                    case MotionDetectorProtocol.TX_STOP:
                        throw new IllegalArgumentException("obsolete Motion control transaction");
                    case MotionDetectorProtocol.TX_WAKE_FOR_WARNING:
                    case MotionDetectorProtocol.TX_WARNING_WINDOW_READY:
                    case MotionDetectorProtocol.TX_FINISH_WARNING:
                    case MotionDetectorProtocol.TX_USER_TAKEOVER: {
                        throw new IllegalArgumentException("Motion screen lifecycle requires its window callback");
                    }
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            } catch (Throwable error) {
                long reference = BINDER_FAILURE_SEQUENCE.incrementAndGet();
                String diagnosticRef = Process.myPid() + "-" + reference;
                CameraViewingDiagnostics.failure("motion_binder_transaction_" + code
                        + "_ref_" + diagnosticRef, "shell_worker", error);
                if (reply != null) {
                    String detail = CameraViewingDiagnostics.safeText(error.getMessage());
                    String message = "Motion transaction " + code + " failed"
                            + (detail.isEmpty() ? "" : ": " + detail)
                            + " (diagnostic ref " + diagnosticRef + ")";
                    Exception transportError;
                    if (error instanceof SecurityException)
                        transportError = new SecurityException(message);
                    else if (error instanceof IllegalArgumentException)
                        transportError = new IllegalArgumentException(message);
                    else if (error instanceof IllegalStateException)
                        transportError = new IllegalStateException(message);
                    else transportError = new IllegalStateException(message);
                    reply.writeException(transportError);
                }
                return true;
            }
        }
    }

    private ConfigureResult configureOnControl(String configJson, IBinder configureCallback,
            String requestToken) throws Exception {
        ManualCheck check = manualCheck;
        if (check != null) completeCheck(check, MotionCheckupClient.NOT_CHECKED,
                "cancelled", "Motion settings changed during check-up", true);
        int installedUid = systemContext.getPackageManager().getPackageUid(
                MotionShellMediaContext.OWNER_PACKAGE, 0);
        if (installedUid != appUid) throw new SecurityException("installed app UID changed");
        MotionSessionConfigStore.Snapshot next = configStore.write(configJson);
        if (next == null) {
            pendingConfig = null;
            pendingConfigureCallback = null;
            pendingConfigureToken = null;
            pendingAppCallback = null;
            clearRemoteViewLeases();
            stopChargeTelemetry();
            if (sessionId != null) stopOnControl(sessionId, "settings_disabled");
            config = null;
            eligible = false;
            return new ConfigureResult(null, MotionDetectorProtocol.CONFIG_APPLIED);
        }
        if (next.sessionId.equals(sessionId) && !shuttingDown && !sessionRuntime.suspended()) {
            if (teardownPending) {
                pendingConfig = next;
                pendingConfigureCallback = configureCallback;
                pendingConfigureToken = requestToken;
                return new ConfigureResult(null, MotionDetectorProtocol.CONFIG_PENDING);
            }
            pendingAppCallback = configureCallback;
            boolean activeWithoutHost = !workerBinding && !cameraHostPending
                    && "charge_blocked".equals(state);
            if (!workerBinding && !cameraHostPending && (workerBound || activeWithoutHost))
                attachPendingUiOnControl(next.sessionId);
            if (config != null && next.config.onlyMotionModesDifferFrom(config)) {
                applyModeOnlyConfig(next);
                return new ConfigureResult(next, MotionDetectorProtocol.CONFIG_MODE_ONLY);
            }
            if (config != null && next.config.toJson().equals(config.toJson()))
                return new ConfigureResult(next, MotionDetectorProtocol.CONFIG_RETAINED);
            return new ConfigureResult(next, MotionDetectorProtocol.CONFIG_APPLIED);
        }
        boolean applied = startConfiguredSession(next, configureCallback, requestToken);
        return new ConfigureResult(applied ? next : null,
                applied ? MotionDetectorProtocol.CONFIG_APPLIED : MotionDetectorProtocol.CONFIG_PENDING);
    }

    private void applyModeOnlyConfig(MotionSessionConfigStore.Snapshot next) throws Exception {
        String id = sessionId;
        MotionConfig previous = config;
        if (!sameSession(id) || teardownPending || shuttingDown || sessionRuntime.suspended()
                || previous == null || !next.config.onlyMotionModesDifferFrom(previous))
            throw new IllegalStateException("Motion session cannot accept an in-place mode update");
        MotionSessionRuntime.Change change = sessionRuntime.updateModes(
                next.config, SystemClock.elapsedRealtime());
        config = next.config;
        applyEligibilityChange(id, change);
        if (!sameSession(id)) return;
        if (previous.warningEnabled && !config.warningEnabled && warning && change != MotionSessionRuntime.Change.DISARMED)
            finishWarning(id, false);
        if (previous.recordingEnabled && !config.recordingEnabled && eligible
                && recorder != null && change != MotionSessionRuntime.Change.DISARMED)
            stopRecording("recording_disabled");
        if (!previous.recordingEnabled && config.recordingEnabled && eligible) startRecording(id);
        if (!previous.motionEnabled() && config.motionEnabled() && !prepared
                && !cameraChargeState.isBlocked() && !chargeStartupPending
                && !control.post(() -> prepareSession(id)))
            throw new IllegalStateException("Motion control thread refused detector preparation");
    }

    private boolean startChargeTelemetry(MotionConfig next, String id, long generation) {
        try {
            if (cameraChargeOwner == null)
                cameraChargeOwner = new CameraChargeTelemetry.Owner(
                        systemContext, control, hostOperations, this::onChargeStateChanged);
            chargeStartupPending = true;
            chargeStartupGeneration = generation;
            cameraChargeOwner.start(next,
                    (fresh, failure) -> onChargeTelemetryStarted(id, generation, fresh, failure));
            return true;
        } catch (Throwable failure) {
            chargeStartupPending = false;
            cameraChargeState = new CameraChargePolicy.State(null, null,
                    CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE,
                    CameraChargePolicy.CAUSE_SOC | CameraChargePolicy.CAUSE_VOLTAGE, true, false);
            logEvent("camera_charge_owner_failed", sessionId, "error", summary(failure));
            return false;
        }
    }

    private void onChargeTelemetryStarted(String id, long generation,
            CameraChargePolicy.State fresh, Throwable failure) {
        if (!chargeStartupPending || chargeStartupGeneration != generation
                || !operationIsCurrent(id, generation, sessionId, sessionGeneration)) return;
        chargeStartupPending = false;
        if (failure != null || fresh == null) {
            Throwable cause = failure == null
                    ? new IllegalStateException("fresh camera charge read returned no state") : failure;
            cameraChargeState = new CameraChargePolicy.State(null, null,
                    CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE,
                    CameraChargePolicy.CAUSE_SOC | CameraChargePolicy.CAUSE_VOLTAGE, true, false);
            logEvent("camera_charge_owner_failed", id, "operation", "initial_read",
                    "error", summary(cause));
        } else cameraChargeState = fresh;
        chargeHandlingEnabled = true;
        continueConfiguredStart(id);
    }

    private CameraChargePolicy.State readChargeStateFromBinder() throws Exception {
        PendingResult<CameraChargePolicy.State> result = new PendingResult<>();
        if (!control.post(() -> {
            CameraChargeTelemetry.Owner chargeOwner = cameraChargeOwner;
            if (chargeOwner == null || !chargeOwner.isMonitoring()) {
                result.fail(new IllegalStateException("shared camera charge telemetry is not active"));
                return;
            }
            String requestedSession = sessionId;
            long generation = sessionGeneration;
            chargeOwner.readNow((fresh, failure) -> {
                if (result.isDone()) return;
                if (failure != null) result.fail(failure);
                else if (generation != sessionGeneration || !sameNullableSession(requestedSession, sessionId))
                    result.fail(new IllegalStateException("camera charge read completed for a stale session"));
                else result.succeed(fresh);
            });
        })) throw new IllegalStateException("Motion control thread refused charge refresh");
        return awaitPendingResult(result, "camera charge refresh");
    }

    private boolean prepareRemoteViewFromBinder(String id, IBinder owner) throws Exception {
        PendingResult<Boolean> result = new PendingResult<>();
        if (!control.post(() -> {
            try { prepareRemoteViewOnControl(id, owner, result); }
            catch (Throwable failure) { result.fail(failure); }
        })) throw new IllegalStateException("Motion control thread refused remote camera preparation");
        return awaitPendingResult(result, "remote camera preparation");
    }

    private void prepareRemoteViewOnControl(String id, IBinder owner, PendingResult<Boolean> result) {
        if (result.isDone()) return;
        if (sessionId == null || shuttingDown || teardownPending || sessionRuntime.suspended()
                || config == null || !config.remoteViewingEnabled || owner == null
                || !owner.isBinderAlive() || cameraChargeOwner == null
                || !cameraChargeOwner.isMonitoring()) {
            result.succeed(false);
            return;
        }
        RemoteViewLease lease = remoteViewLeases.get(owner);
        if (lease != null) {
            result.succeed(completeRemoteViewPreparation(id, owner, lease,
                    cameraChargeState, sessionId, sessionGeneration));
            return;
        }
        String requestedSession = sessionId;
        long generation = sessionGeneration;
        cameraChargeOwner.readNow((fresh, failure) -> {
            if (result.isDone()) return;
            if (failure != null) {
                result.fail(failure);
                return;
            }
            if (!operationIsCurrent(requestedSession, generation, sessionId, sessionGeneration)) {
                result.succeed(false);
                return;
            }
            try {
                result.succeed(completeRemoteViewPreparation(id, owner,
                        remoteViewLeases.get(owner), fresh, requestedSession, generation));
            } catch (Throwable error) { result.fail(error); }
        });
    }

    private boolean completeRemoteViewPreparation(String id, IBinder owner, RemoteViewLease lease,
            CameraChargePolicy.State fresh, String requestedSession, long generation) {
        if (!operationIsCurrent(requestedSession, generation, sessionId, sessionGeneration)
                || shuttingDown || teardownPending || sessionRuntime.suspended()
                || config == null || !config.remoteViewingEnabled || owner == null
                || !owner.isBinderAlive() || fresh == null || !fresh.isMonitoring() || fresh.isBlocked())
            return false;
        int power = sessionRuntime.power();
        if (power != 0 && power != 1 && power != 2) return false;
        if (lease != null && !lease.id.equals(id)) return false;
        if (lease == null) {
            lease = new RemoteViewLease(id, owner);
            try { owner.linkToDeath(lease.death, 0); }
            catch (RemoteException deadOwner) { return false; }
            remoteViewLeases.put(owner, lease);
        }
        updateCameraDemand();
        // OFF uses the same owner as Motion preparation. ON/standby viewing has no IDC requirement.
        return power != 0 || cameraPreparation.ready(sessionId);
    }

    private static boolean sameNullableSession(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isViewingHelperOperation(int code) {
        return code == MotionDetectorProtocol.TX_READ_CHARGE
                || code == MotionDetectorProtocol.TX_VIEW_PREPARE
                || code == MotionDetectorProtocol.TX_VIEW_RELEASE
                || code == MotionDetectorProtocol.TX_VIEW_OBSERVE_CHARGE
                || code == MotionDetectorProtocol.TX_VIEW_DETACH_CHARGE;
    }

    private boolean isRegisteredViewingCaller(int uid, int pid) {
        IBinder helper = viewingHelper;
        return uid == 2000 && pid > 0 && pid == viewingHelperPid
                && helper != null && helper.isBinderAlive();
    }

    private void registerViewingHelper(IBinder helper, int pid) throws Exception {
        synchronized (binder) {
            if (viewingHelper == helper && viewingHelperPid == pid && helper.isBinderAlive()) return;
        }
        callControl(() -> {
            synchronized (binder) {
                if (viewingHelper == helper && viewingHelperPid == pid && helper.isBinderAlive()) return null;
                if (viewingHelper != null && viewingHelper.isBinderAlive())
                    throw new SecurityException("another viewing helper is already registered");
                clearViewingHelperLocked();
                IBinder.DeathRecipient death = () -> control.post(() -> viewingHelperDied(helper));
                helper.linkToDeath(death, 0);
                viewingHelper = helper;
                viewingHelperPid = pid;
                registeredViewingHelperDeath = death;
                return null;
            }
        });
    }

    private void viewingHelperDied(IBinder expected) {
        synchronized (binder) {
            if (viewingHelper != expected) return;
            clearViewingHelperLocked();
        }
    }

    private void clearViewingHelperLocked() {
        IBinder old = viewingHelper;
        IBinder.DeathRecipient death = registeredViewingHelperDeath;
        viewingHelper = null;
        viewingHelperPid = 0;
        registeredViewingHelperDeath = null;
        if (old != null && death != null) {
            try { old.unlinkToDeath(death, 0); } catch (Throwable ignored) { }
        }
        ArrayList<IBinder> callbacks = new ArrayList<>(viewingChargeObservers.keySet());
        for (IBinder callback : callbacks) removeViewingChargeObserver(callback);
    }

    private void addViewingChargeObserver(IBinder callback) throws RemoteException {
        ChargeObserver previous = viewingChargeObservers.get(callback);
        if (previous != null) return;
        ChargeObserver observer = new ChargeObserver(callback);
        callback.linkToDeath(observer.death, 0);
        viewingChargeObservers.put(callback, observer);
    }

    private void removeViewingChargeObserver(IBinder callback) {
        ChargeObserver observer = viewingChargeObservers.remove(callback);
        if (observer != null) {
            try { callback.unlinkToDeath(observer.death, 0); } catch (Throwable ignored) { }
        }
    }

    private void sendViewingChargeState(IBinder callback, CameraChargePolicy.State state) {
        if (callback == null || state == null || !callback.isBinderAlive()) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.VIEW_CHARGE_CALLBACK_DESCRIPTOR);
            MotionDetectorProtocol.writeChargeState(data, state);
            if (!callback.transact(MotionDetectorProtocol.CB_VIEW_CHARGE, data, null, IBinder.FLAG_ONEWAY))
                control.post(() -> removeViewingChargeObserver(callback));
        } catch (RemoteException unavailable) {
            control.post(() -> removeViewingChargeObserver(callback));
        } finally { data.recycle(); }
    }

    private void sendViewingChargeState(CameraChargePolicy.State state) {
        for (ChargeObserver observer : new ArrayList<>(viewingChargeObservers.values()))
            sendViewingChargeState(observer.callback, state);
    }

    private int releaseRemoteViewOnControl(String id, IBinder owner) {
        RemoteViewLease lease = remoteViewLeases.get(owner);
        if (lease != null && lease.id.equals(id)) {
            remoteViewLeases.remove(owner);
            try { owner.unlinkToDeath(lease.death, 0); } catch (Throwable ignored) { }
            updateCameraDemand();
        }
        return 1;
    }

    private void clearRemoteViewLeases() {
        if (remoteViewLeases.isEmpty()) return;
        ArrayList<RemoteViewLease> leases = new ArrayList<>(remoteViewLeases.values());
        remoteViewLeases.clear();
        for (RemoteViewLease lease : leases) {
            try { lease.owner.unlinkToDeath(lease.death, 0); } catch (Throwable ignored) { }
        }
    }

    private void stopChargeTelemetry() {
        CameraChargeTelemetry.Owner owner = cameraChargeOwner;
        if (owner != null) owner.close();
    }

    private void onChargeStateChanged(CameraChargePolicy.State next) {
        if (next == null) return;
        CameraChargePolicy.State previous = cameraChargeState;
        cameraChargeState = next;
        sendViewingChargeState(next);
        String id = sessionId;
        if (id != null) sendChargeState(id, next);
        if (!chargeHandlingEnabled || !next.isMonitoring() || !sameSession(id)
                || config == null) return;

        if (!previous.isBlocked() && next.isBlocked()) {
            clearRemoteViewLeases();
            if (config.motionEnabled()) {
                try {
                    if (eligible) setEligibleOnControl(id, false);
                    else if (warning) finishWarning(id, false);
                    if (recorder != null && recorder.isRecording() && !recordingTailPending()) {
                        long tailMs = Math.max(0L, config.afterSeconds * 1000L);
                        recorder.finishAfter(SystemClock.elapsedRealtime() + tailMs);
                        state = "recording_tail";
                        control.removeCallbacks(recordingTailFinished);
                        control.postDelayed(recordingTailFinished, tailMs);
                    }
                } catch (Throwable failure) {
                    fail(id, "camera_charge_block", failure);
                    return;
                }
            }
            updateCameraDemand();
            if (!recordingTailPending() && !warning) state = "charge_blocked";
            sendStatus(id, "charge_blocked", "a confirmed low-charge cause pauses tablet warning, recording and remote viewing");
            return;
        }

        if (previous.isBlocked() && !next.isBlocked()) {
            if (config.motionEnabled()) {
                if (workerBound) {
                    if (prepared) updateCameraDemand();
                    else if (!workerBinding) prepareSession(id);
                } else beginWorkerBinding(id);
            } else {
                state = "viewing_only";
                sendStatus(id, state, "fresh charge readings are above every latched threshold");
            }
        }
    }

    private void sendChargeState(String id, CameraChargePolicy.State next) {
        IBinder target = appCallback;
        if (target == null || !sameSession(id)) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            MotionDetectorProtocol.writeChargeState(data, next);
            target.transact(MotionDetectorProtocol.CB_CHARGE, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) { detachUiOnControl(id, target); }
        finally { data.recycle(); }
    }

    private void cancelCheck(ManualCheck check) {
        check.cancelled = true;
        control.post(() -> completeCheck(check, MotionCheckupClient.NOT_CHECKED,
                "cancelled", "Manual check-up was cancelled", true));
    }

    private boolean checkLive(ManualCheck check) {
        return manualCheck == check && !check.cancelled && check.owner.isBinderAlive()
                && SystemClock.elapsedRealtime() < check.deadline;
    }

    private void checkResult(ManualCheck check, int status, String reason, String detail, boolean terminal) {
        logEvent("motion_checkup", sessionId, "check_id", check.id, "stage", check.stage,
                "status", status, "reason", reason, "detail", detail, "terminal", terminal);
        Bundle result = new Bundle();
        result.putInt("stage", check.stage);
        result.putString("reason", reason);
        result.putString("detail", detail);
        result.putBoolean("terminal", terminal);
        check.results.send(status, result);
    }

    private void beginCheck(ManualCheck check) {
        if (!checkLive(check)) {
            completeCheck(check, MotionCheckupClient.NOT_CHECKED, "cancelled", "Check-up is no longer active", true);
            return;
        }
        control.postDelayed(check.timeout, Math.max(0L, check.deadline - SystemClock.elapsedRealtime()));
        readCheckPower(check, "check_power_begin", failure -> {
            if (failure != null) {
                boolean off = "power_not_on".equals(failure.getMessage());
                completeCheck(check, off ? MotionCheckupClient.NOT_CHECKED : MotionCheckupClient.ERROR,
                        off ? "power_not_on" : "check_failed", summary(failure), true);
                return;
            }
            continueBeginCheck(check);
        });
    }

    private void continueBeginCheck(ManualCheck check) {
        if (!checkLive(check)) {
            completeCheck(check, MotionCheckupClient.NOT_CHECKED, "cancelled", "Check-up ended during Power read", true);
            return;
        }
        try {
            checkResult(check, MotionCheckupClient.OK, "access_ok", "Authenticated shell worker; Power=ON", false);
            check.stage = MotionCheckupClient.DETECTOR;
            checkResult(check, MotionCheckupClient.RUNNING, "", "", false);
            if (teardownPending && !lastError.isEmpty()) {
                completeCheck(check, MotionCheckupClient.ERROR, "cleanup_pending", lastError, false);
                return;
            }
            if (eligible || attached || recorder != null || warning || warningClosing || warningWindowPending
                    || workerBinding || teardownPending || shuttingDown || sessionRuntime.arming() || sessionRuntime.armed()
                    || sessionId != null && !prepared && !"error".equals(state)
                        && (config == null || config.motionEnabled() || !workerBound)) {
                completeCheck(check, MotionCheckupClient.NOT_CHECKED, "busy",
                        "Active or preparing Motion resources are left unchanged; try after they finish", true);
                return;
            }
            if (cameraChargeState.isBlocked()) {
                completeCheck(check, MotionCheckupClient.NOT_CHECKED, "charge_blocked",
                        "Shared camera charge protection is active", true);
                return;
            }
            if (sessionId != null) {
                if (prepared) startCheckFrames(check);
                else {
                    check.ownsNative = true;
                    check.previousState = state;
                    prepareSession(sessionId);
                }
            } else {
                // Ephemeral disabled config is never persisted or published to the app observer.
                check.ownsSession = true;
                startConfiguredSession(new MotionSessionConfigStore.Snapshot(check.id, MotionConfig.parse("{}")));
            }
        } catch (Throwable error) {
            boolean off = "power_not_on".equals(error.getMessage());
            completeCheck(check, off ? MotionCheckupClient.NOT_CHECKED : MotionCheckupClient.ERROR,
                    off ? "power_not_on" : "check_failed", summary(error), true);
        }
    }

    private void readCheckPower(ManualCheck check, String operation, CheckPowerCompletion completion) {
        long operationId = operationSequence.incrementAndGet();
        long queuedNs = SystemClock.elapsedRealtimeNanos();
        AtomicBoolean completed = new AtomicBoolean();
        Runnable timeout = () -> {
            if (!completed.compareAndSet(false, true) || !checkLive(check)) return;
            completion.complete(new java.util.concurrent.TimeoutException(
                    "Power read timed out after 1500 ms"));
        };
        control.postDelayed(timeout, 1_500L);
        try {
            hostOperations.execute(() -> {
                long startedNs = SystemClock.elapsedRealtimeNanos();
                Throwable failure = null;
                try {
                    if (AvasEventPolicy.normalizedPower(MotionScreenControl.readPowerRaw()) != 1)
                        throw new IllegalStateException("power_not_on");
                } catch (Throwable error) { failure = error; }
                long finishedNs = SystemClock.elapsedRealtimeNanos();
                Throwable result = failure;
                control.post(() -> {
                    if (!completed.compareAndSet(false, true)) return;
                    control.removeCallbacks(timeout);
                    logEvent("motion_camera_host_operation", sessionId,
                            "operation_id", operationId, "operation", operation,
                            "queue_ms", nanosToMillis(startedNs - queuedNs),
                            "run_ms", nanosToMillis(finishedNs - startedNs),
                            "outcome", result == null ? "ok" : "error",
                            "error", result == null ? "" : summary(result),
                            "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                            "context_package", systemContext.getPackageName(),
                            "thread", "motion-camera-host-ops");
                    if (checkLive(check)) completion.complete(result);
                });
            });
        } catch (Throwable error) {
            control.post(() -> {
                if (!completed.compareAndSet(false, true)) return;
                control.removeCallbacks(timeout);
                logEvent("motion_camera_host_operation", sessionId,
                        "operation_id", operationId, "operation", operation,
                        "queue_ms", nanosToMillis(SystemClock.elapsedRealtimeNanos() - queuedNs),
                        "run_ms", 0.0, "outcome", "error", "error", summary(error),
                        "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                        "context_package", systemContext.getPackageName(),
                        "thread", "motion-detector-control");
                if (checkLive(check)) completion.complete(error);
            });
        }
    }

    private void startCheckFrames(ManualCheck check) {
        if (!checkLive(check)) {
            completeCheck(check, MotionCheckupClient.NOT_CHECKED, "cancelled", "Check-up is no longer active", true);
            return;
        }
        readCheckPower(check, "check_power_frames", failure -> {
            if (failure != null) {
                completeCheck(check, MotionCheckupClient.ERROR, "check_failed", summary(failure), true);
                return;
            }
            startCheckFramesAfterPower(check);
        });
    }

    private void startCheckFramesAfterPower(ManualCheck check) {
        if (!checkLive(check)) {
            completeCheck(check, MotionCheckupClient.NOT_CHECKED, "cancelled", "Check-up is no longer active", true);
            return;
        }
        try {
            checkResult(check, MotionCheckupClient.OK, "detector_ok", "OEM native detector initialized", false);
            check.stage = MotionCheckupClient.FRAMES;
            checkResult(check, MotionCheckupClient.RUNNING, "", "", false);
            check.ownsReader = true;
            attachReader(sessionId, check);
            control.removeCallbacks(check.timeout);
            control.postDelayed(check.timeout, Math.min(FIRST_INFERENCE_TIMEOUT_MS,
                    Math.max(0L, check.deadline - SystemClock.elapsedRealtime())));
        } catch (Throwable error) {
            completeCheck(check, MotionCheckupClient.ERROR, "check_failed", summary(error), true);
        }
    }

    private void completeCheck(ManualCheck check, int status, String reason, String detail, boolean release) {
        if (manualCheck != check || check.completing) return;
        check.completing = true;
        check.cancelled = true;
        check.completionStatus = status;
        check.completionReason = reason;
        check.completionDetail = detail;
        control.removeCallbacks(check.timeout);
        try { check.owner.unlinkToDeath(check.death, 0); } catch (java.util.NoSuchElementException ignored) { }
        if (!release) {
            finishManualCheck(check);
            return;
        }
        if (check.ownsSession && check.id.equals(sessionId)) {
            stopOnControl(sessionId, "manual_check_finished");
            if (sameSession(check.id)) {
                check.waitingForSessionTeardown = true;
                control.postDelayed(check.timeout, CONTROL_TIMEOUT_MS);
                return;
            }
            finishManualCheck(check);
            return;
        }

        boolean released = true;
        if (check.ownsRecording) released = stopRecording("manual_check_finished");
        if (check.ownsReader) released &= detachAndCloseReader(false);
        check.waitingForReaderDetach = readerAttachPending || readerDetachPending;
        check.waitingForRecordingDetach = recordingDetachPending;
        if (check.waitingForReaderDetach || check.waitingForRecordingDetach) return;
        if (!released) {
            markCheckCleanupPending(check,
                    new IllegalStateException("camera resource release has not been confirmed"));
            finishManualCheck(check);
            return;
        }
        continueManualCheckCleanup(check);
    }

    private void continueManualCheckCleanup(ManualCheck check) {
        if (manualCheck != check || !check.completing || check.waitingForReaderDetach
                || check.waitingForRecordingDetach || check.waitingForNativeDelete) return;
        if (check.cleanupFailure != null || teardownPending || shuttingDown) {
            if (check.cleanupFailure != null) markCheckCleanupPending(check, check.cleanupFailure);
            else markCheckCleanupPending(check,
                    new IllegalStateException("session teardown owns Motion resource cleanup"));
            finishManualCheck(check);
            return;
        }
        if (check.ownsNative) {
            String id = sessionId;
            if (id == null) {
                markCheckCleanupPending(check,
                        new IllegalStateException("Motion session ended before native cleanup completed"));
                finishManualCheck(check);
                return;
            }
            check.waitingForNativeDelete = true;
            nativeDeletePending = true;
            submitNativeDelete(id, "manual_check", result -> {
                if (manualCheck != check || !check.completing) return;
                nativeDeletePending = false;
                check.waitingForNativeDelete = false;
                if (result.failure != null) markCheckCleanupPending(check, result.failure);
                for (NativeCleanupFailure cleanup : result.failures) {
                    recordNativeCleanupFailure(cleanup.operation, cleanup.failure, null);
                    markCheckCleanupPending(check,
                            new IllegalStateException(cleanup.operation + ": " + summary(cleanup.failure)));
                }
                if (result.failure == null) {
                    prepared = false;
                    if (teardownPending && sameSession(id)) nativeDeleteComplete = true;
                    if (!teardownPending && !shuttingDown) {
                        state = cameraChargeState.isBlocked() ? "charge_blocked" : check.previousState;
                        sendStatus(id, state, "Temporary detector released; saved camera configuration is unchanged");
                    }
                }
                finishManualCheck(check);
                if (teardownPending) continuePendingTeardown();
            });
            return;
        }
        finishManualCheck(check);
    }

    private void markCheckCleanupPending(ManualCheck check, Throwable failure) {
        check.completionStatus = MotionCheckupClient.ERROR;
        check.completionReason = "cleanup_pending";
        String detail = failure == null ? "camera resource release has not been confirmed" : summary(failure);
        if (check.completionDetail == null || check.completionDetail.isEmpty())
            check.completionDetail = detail;
        else if (!check.completionDetail.contains(detail)) check.completionDetail += "; " + detail;
    }

    private void finishManualCheck(ManualCheck check) {
        if (manualCheck != check) return;
        synchronized (binder) { manualCheck = null; }
        if (!teardownPending) holdCpu(false);
        checkResult(check, check.completionStatus, check.completionReason,
                check.completionDetail == null ? "" : check.completionDetail, true);
    }

    private void startCheckRecording(ManualCheck check) {
        if (!checkLive(check)) {
            completeCheck(check, MotionCheckupClient.NOT_CHECKED, "cancelled", "Check-up ended", true);
            return;
        }
        readCheckPower(check, "check_power_recording", failure -> {
            if (failure != null) {
                completeCheck(check, MotionCheckupClient.ERROR, "check_failed", summary(failure), true);
                return;
            }
            startCheckRecordingAfterPower(check);
        });
    }

    private void startCheckRecordingAfterPower(ManualCheck check) {
        if (!checkLive(check)) {
            completeCheck(check, MotionCheckupClient.NOT_CHECKED, "cancelled", "Check-up ended", true);
            return;
        }
        checkResult(check, MotionCheckupClient.OK,
                check.frames.motionObserved() ? "frames_motion" : "frames_clear",
                "Two fresh frames processed", false);
        check.stage = MotionCheckupClient.RECORDING;
        checkResult(check, MotionCheckupClient.RUNNING, "", "", false);
        control.removeCallbacks(check.timeout);
        control.postDelayed(check.timeout, Math.max(0L, check.deadline - SystemClock.elapsedRealtime()));
        check.ownsRecording = true;
        startRecording(sessionId, check);
    }

    private void resumePersistedConfig() {
        try {
            MotionSessionConfigStore.Snapshot saved = configStore.read();
            if (saved != null && sessionId == null) startConfiguredSession(saved);
        } catch (Throwable error) {
            state = "error";
            lastError = "persisted_config: " + summary(error);
            System.err.println("motion_config_restore_failed " + lastError);
        }
    }

    private boolean startConfiguredSession(MotionSessionConfigStore.Snapshot snapshot) throws Exception {
        return startConfiguredSession(snapshot, null, null);
    }

    private boolean startConfiguredSession(MotionSessionConfigStore.Snapshot snapshot,
            IBinder configureCallback, String requestToken) throws Exception {
        clearRemoteViewLeases();
        if (sessionId != null) {
            if (teardownPending) {
                pendingConfig = snapshot;
                pendingConfigureCallback = configureCallback;
                pendingConfigureToken = requestToken;
                sendStatus(sessionId, "settings_pending_teardown",
                        "latest settings snapshot will start after confirmed encoder and Surface drain");
                return false;
            }
            stopOnControl(sessionId, "settings_changed");
            if (sessionId != null) {
                pendingConfig = snapshot;
                pendingConfigureCallback = configureCallback;
                pendingConfigureToken = requestToken;
                sendStatus(sessionId, "settings_pending_teardown",
                        "latest settings snapshot will start after confirmed encoder and Surface drain");
                return false;
            }
        }
        String id = snapshot.sessionId;
        synchronized (analysisLock) { sessionGeneration++; }
        config = snapshot.config;
        sessionRuntime.configure(id, config);
        shuttingDown = false;
        sessionId = id;
        appCallback = null;
        pendingAppCallback = configureCallback;
        cameraHostPending = false;
        cameraHostReadyDuringBinding = false;
        state = "preparing";
        prepared = false;
        eligible = false;
        attached = false;
        warning = false;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningVisible = false;
        warningClosing = false;
        warningRestoreAllowed = false;
        warningWindowLaunched = false;
        warningWindowCommands = null;
        warningWindowGeneration++;
        control.removeCallbacks(warningCloseTimeout);
        warningPreparingSleep = false;
        warningSleepAttempted = false;
        warningScreenFinishIssued = false;
        warningAwaitingClosed = false;
        warningBlackFallback = false;
        warningPowerOnClose = false;
        warningCloseSent = false;
        warningWakeTokenOwned = false;
        warningWindowFailure = "";
        deliberatelyDetachedProducerReason = "";
        lastError = "";
        lastReportedDetectorState = "";
        frameNumber = 0L;
        firstInferenceReceived = false;
        cameraRecovering = false;
        lastCameraPreparation = "";
        workerBound = false;
        expectedSourceEpoch = "";
        screen.beginSession(id);
        chargeHandlingEnabled = false;
        long generation = sessionGeneration;
        if (startChargeTelemetry(config, id, generation)) {
            sendStatus(id, "preparing", "waiting for a fresh charge sample before camera or native preparation");
            return true;
        }
        chargeHandlingEnabled = true;
        continueConfiguredStart(id);
        return true;
    }

    private void continueConfiguredStart(String id) {
        if (!sameSession(id) || shuttingDown || teardownPending) return;
        if (cameraChargeState.isBlocked()) {
            state = "charge_blocked";
            sendStatus(id, state, "shared charge protection is latched; Motion surfaces and recording are suspended");
        } else {
            sendStatus(id, "preparing", "shell-owned Motion session; inspecting installed OEM detector artifacts");
        }
        if (config.motionEnabled() && cameraChargeState.isBlocked() && !config.remoteViewingEnabled) {
            attachPendingUiOnControl(id);
            return;
        }
        beginWorkerBinding(id);
    }

    private void beginWorkerBinding(String id) {
        if (!sameSession(id) || chargeStartupPending || workerBinding || workerBound || config == null) return;
        workerBinding = true;
        cameraHostPending = false;
        hostOperations.execute(() -> {
            Throwable failure = null;
            try {
                if (!cameraOperation(MotionDetectorProtocol.CAMERA_BIND_WORKER, id, null, null, 0)) {
                    throw new IllegalStateException("camera runtime refused Motion worker binding");
                }
                setMotionTelemetryDemand(id, true);
            } catch (Throwable error) { failure = error; }
            Throwable result = failure;
            boolean hostBound = workerBound;
            control.post(() -> onHostBindingComplete(id, hostBound, result));
        });
    }

    private void onHostBindingComplete(String id, boolean hostBound, Throwable failure) {
        workerBinding = false;
        if (sameSession(id)) {
            workerBound = hostBound;
            if (teardownPending) {
                continuePendingTeardown();
                return;
            }
        }
        if (failure != null) {
            if (sameSession(id) && isCameraOwnerNotPublished(failure)) {
                cameraHostPending = true;
                state = "waiting_for_camera_host";
                lastError = "camera_bind: " + summary(failure);
                CameraViewingDiagnostics.failure("motion_camera_host_bind", "camera_runtime", failure);
                sendStatus(id, state, "waiting for the camera owner service to become available; " + lastError);
                if (cameraHostReadyDuringBinding) {
                    cameraHostReadyDuringBinding = false;
                    cameraHostPending = false;
                    beginWorkerBinding(id);
                }
            } else if (sameSession(id)) fail(id, "camera_bind", failure);
            else if (hostBound) hostOperations.execute(() -> cleanupHostBinding(id));
            return;
        }
        if (!sameSession(id) || shuttingDown) {
            workerBinding = false;
            hostOperations.execute(() -> cleanupHostBinding(id));
            return;
        }
        workerBinding = false;
        workerBound = true;
        cameraHostPending = false;
        cameraHostReadyDuringBinding = false;
        if ("waiting_for_camera_host".equals(state)) {
            state = "preparing";
            lastError = "";
        }
        attachPendingUiOnControl(id);
        if (config == null) return;
        ManualCheck check = manualCheck;
        if (check != null && check.ownsSession && check.id.equals(id)) {
            prepareSession(id);
            return;
        }
        if (!config.motionEnabled()) {
            state = cameraChargeState.isBlocked() ? "charge_blocked" : "viewing_only";
            sendStatus(id, state, cameraChargeState.isBlocked()
                    ? "shared charge protection blocks viewing; vehicle power telemetry remains observed"
                    : "remote viewing owns the worker; Motion detection is not enabled");
            return;
        }
        if (cameraChargeState.isBlocked()) {
            state = "charge_blocked";
            sendStatus(id, state, "shared charge protection blocks Motion camera consumers");
            return;
        }
        prepareSession(id);
    }

    private void onCameraHostReadyOnControl() throws Exception {
        if (workerBinding) {
            cameraHostReadyDuringBinding = true;
            return;
        }
        if (sessionId != null) {
            if (teardownPending || shuttingDown || sessionRuntime.suspended() || config == null)
                return;
            if (config.motionEnabled() && cameraChargeState.isBlocked()
                    && !config.remoteViewingEnabled) return;
            boolean hostLost = telemetryHost == null || !telemetryHost.isBinderAlive();
            if (cameraHostPending || !workerBound || hostLost) {
                if (hostLost && telemetryHost != null) {
                    try { watchTelemetryHost(sessionId, null); }
                    catch (RemoteException ignored) { }
                }
                workerBound = false;
                cameraHostPending = false;
                beginWorkerBinding(sessionId);
            }
            return;
        }
        // The event may precede persisted-config restoration or an app configure call. Resume
        // the latest enabled intent without requiring a previous active session as a gate.
        if (shuttingDown || sessionRuntime.suspended()) return;
        MotionSessionConfigStore.Snapshot latest = configStore.read();
        if (latest != null) startConfiguredSession(latest);
    }

    private static boolean isCameraOwnerNotPublished(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalStateException
                    && "existing camera owner service is unavailable".equals(cause.getMessage()))
                return true;
        }
        return false;
    }

    private void attachPendingUiOnControl(String id) {
        IBinder callback = pendingAppCallback;
        pendingAppCallback = null;
        if (callback == null || !callback.isBinderAlive()) return;
        try { attachUiOnControl(id, callback); }
        catch (Throwable failure) {
            CameraViewingDiagnostics.failure("motion_ui_attach", "app_callback", failure);
            sendError(callback, id, "ui_attach", summary(failure));
        }
    }

    private void cleanupHostBinding(String id) {
        try { setMotionTelemetryDemand(id, false); } catch (Throwable ignored) { }
        try { cameraOperation(MotionDetectorProtocol.CAMERA_END_WAKE, id, null, null, 0); }
        catch (Throwable ignored) { }
        try { cameraOperation(MotionDetectorProtocol.CAMERA_UNBIND_WORKER, id, null, null, 0); }
        catch (Throwable ignored) { }
    }

    private int attachUiOnControl(String id, IBinder callback) throws Exception {
        if (!sameSession(id) || shuttingDown || sessionRuntime.suspended())
            throw new IllegalStateException("Motion session is not active");
        if (!callback.isBinderAlive()) throw new IllegalStateException("Motion UI callback is dead");
        detachUiOnControl(id, appCallback);
        appCallback = callback;
        appCallbackDeath = () -> control.post(() -> detachUiOnControl(id, callback));
        callback.linkToDeath(appCallbackDeath, 0);
        if (prepared) sendPrepared(id);
        sendChargeState(id, cameraChargeState);
        String detail = "UI observer attached; shell session continues independently";
        if ("error".equals(state) && !lastError.isEmpty()) detail += "; " + lastError;
        sendStatus(id, state, detail);
        return 1;
    }

    private int detachUiOnControl(String id, IBinder callback) {
        if (!sameSession(id) || callback == null || appCallback != callback) return 1;
        if (appCallbackDeath != null) {
            try { callback.unlinkToDeath(appCallbackDeath, 0); } catch (Throwable ignored) { }
        }
        appCallback = null;
        appCallbackDeath = null;
        return 1;
    }

    private void setMotionTelemetryDemand(String id, boolean enabled) throws Exception {
        IBinder guard = serviceBinder(TurnSignalShellProtocol.SERVICE_NAME);
        if (guard == null) throw new IllegalStateException("Motion telemetry helper is unavailable");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(binder);
            data.writeInt(enabled ? 1 : 0);
            if (!guard.transact(TurnSignalShellProtocol.TX_SET_MOTION_TELEMETRY, data, reply, 0))
                throw new IllegalStateException("Motion telemetry subscription was not accepted");
            reply.readException();
            if (reply.dataAvail() != 0) throw new IllegalStateException("ambiguous Motion telemetry reply");
        } finally { data.recycle(); reply.recycle(); }
    }

    private int suspendForExplicitShutdown(String id, String reason) {
        if (!sameSession(id)) return 1;
        pendingConfig = null;
        pendingConfigureCallback = null;
        pendingConfigureToken = null;
        shuttingDown = true;
        sessionRuntime.suspendForHostShutdown();
        stopOnControl(id, "explicit_shutdown_" + reason);
        return 1;
    }

    private void prepareSession(String id) {
        if (!sameSession(id) || chargeStartupPending) return;
        ManualCheck pendingCheck = manualCheck;
        boolean checkOwnsPreparation = pendingCheck != null && (pendingCheck.ownsSession || pendingCheck.ownsNative);
        if (config == null || !config.motionEnabled() && !checkOwnsPreparation || cameraChargeState.isBlocked()) {
            if (checkOwnsPreparation) {
                completeCheck(pendingCheck, MotionCheckupClient.NOT_CHECKED, "charge_blocked",
                        "Shared camera charge protection is active", true);
                return;
            }
            if (config != null && config.remoteViewingEnabled && !cameraChargeState.isBlocked()) {
                state = "viewing_only";
                sendStatus(id, state, "Motion detection remains off for remote-only configuration");
            } else if (cameraChargeState.isBlocked()) {
                state = "charge_blocked";
                sendStatus(id, state, "shared charge protection blocks Motion preparation");
            }
            return;
        }
        if (checkOwnsPreparation && !checkLive(pendingCheck)) {
            completeCheck(pendingCheck, MotionCheckupClient.NOT_CHECKED, "cancelled",
                    "Check-up ended while the camera host was binding", true);
            return;
        }
        if (prepared || nativePreparationPending) return;
        nativePreparationPending = true;
        long generation = sessionGeneration;
        OperationTrace trace = new OperationTrace(operationSequence.incrementAndGet(),
                "setup", id, SystemClock.elapsedRealtimeNanos());
        try {
            nativeOperations.execute(() -> {
                long startedNs = SystemClock.elapsedRealtimeNanos();
                long setupStartNs = startedNs;
                long setupNs = 0L;
                long initNs = 0L;
                MotionNativeSetup setup = new MotionNativeSetup(systemContext);
                Throwable failure = null;
                try {
                    logEvent("motion_native_environment", id, "operation_id", trace.id,
                            "sdk", Build.VERSION.SDK_INT, "release", Build.VERSION.RELEASE,
                            "display", Build.DISPLAY, "incremental", Build.VERSION.INCREMENTAL,
                            "model", Build.MODEL, "process_uid", Process.myUid(),
                            "process_pid", Process.myPid(), "context_package", systemContext.getPackageName(),
                            "thread", Thread.currentThread().getName());
                    setup.prepare();
                    ApplicationInfo owner = systemContext.getPackageManager().getApplicationInfo(
                            MotionShellMediaContext.OWNER_PACKAGE, 0);
                    MotionYuvPacker.loadNative(new File(owner.nativeLibraryDir, "libmotion_yuv.so").getPath());
                    logEvent("motion_packer_ready", id, "operation_id", trace.id,
                            "implementation", "native_nv12");
                    assets = setup.assets;
                    setupNs = SystemClock.elapsedRealtimeNanos() - setupStartNs;
                    setup.stage = "native_init";
                    long initStartNs = SystemClock.elapsedRealtimeNanos();
                    nativeApi = new MotionNativeLibrary();
                    nativeInput = new byte[MotionDetectorProtocol.INPUT_BYTES];
                    logEvent("motion_native_init_begin", id, "operation_id", trace.id,
                            "model_value", setup.modelValue,
                            "mirrors_open", setup.mirrorSetting == 0, "sensitivity", sensitivity);
                    int init = nativeApi.init(assets, MotionDetectorProtocol.PREVIEW_WIDTH,
                            MotionDetectorProtocol.PREVIEW_HEIGHT, setup.mirrorSetting == 0,
                            setup.modelValue);
                    initNs = SystemClock.elapsedRealtimeNanos() - initStartNs;
                    logEvent("motion_native_init", id, "operation_id", trace.id, "result", init,
                            "preview_width", MotionDetectorProtocol.PREVIEW_WIDTH,
                            "preview_height", MotionDetectorProtocol.PREVIEW_HEIGHT,
                            "model_value", setup.modelValue, "sensitivity", sensitivity);
                    if (init != 0 && init != 1) throw new IOException("native init refused: " + init);
                    nativeInitialized = true;
                    setup.stage = "prepared_session";
                } catch (Throwable error) {
                    failure = error;
                    if (setupNs == 0L) setupNs = SystemClock.elapsedRealtimeNanos() - setupStartNs;
                    if (assets == null && setup.assets != null) assets = setup.assets;
                    deleteNative(error);
                }
                long finishedNs = SystemClock.elapsedRealtimeNanos();
                NativeSetupResult result = new NativeSetupResult(setup, trace.id, trace.queuedNs,
                        startedNs, finishedNs, setupNs, initNs, failure);
                control.post(() -> onNativePreparationComplete(id, generation, result));
            });
        } catch (Throwable error) {
            nativePreparationPending = false;
            long now = SystemClock.elapsedRealtimeNanos();
            MotionNativeSetup setup = new MotionNativeSetup(systemContext);
            setup.stage = "native_queue";
            onNativePreparationComplete(id, generation, new NativeSetupResult(setup, trace.id,
                    trace.queuedNs, now, now, 0L, 0L, error));
        }
    }

    private void onNativePreparationComplete(String id, long generation, NativeSetupResult result) {
        nativePreparationPending = false;
        logNativeOperation(id, "setup", result.operationId, result.queuedNs,
                result.startedNs, result.finishedNs, result.setupNs, result.initNs,
                result.failure == null ? "ok" : "error",
                result.failure == null ? "" : summary(result.failure), result.stage);
        if (!operationIsCurrent(id, generation, sessionId, sessionGeneration)) {
            if (teardownPending) continuePendingTeardown();
            return;
        }
        if (result.failure != null) {
            String reference = Process.myPid() + "-" + UUID.randomUUID().toString().replace("-", "");
            String detail = result.stage + ": " + summary(result.failure)
                    + " (diagnostic ref " + reference + ")";
            if (!prepared && config != null && config.remoteViewingEnabled
                    && !remoteViewLeases.isEmpty()) {
                lastError = detail;
                CameraViewingDiagnostics.failure("motion_native_setup_ref_" + reference,
                        "shell_worker", result.failure);
                state = "viewing_only";
                sendError(id, result.stage, lastError);
                sendStatus(id, state, "Motion detector preparation failed; remote viewing remains active; " + lastError);
                updateCameraDemand();
                return;
            }
            CameraViewingDiagnostics.failure("motion_native_setup_ref_" + reference,
                    "shell_worker", result.failure);
            fail(id, result.stage, new IllegalStateException(detail, result.failure));
            return;
        }
        sensitivity = result.sensitivity;
        prepared = true;
        state = "prepared";
        sendPrepared(id);
        sendStatus(id, "prepared", "OEM artifacts and native init accepted; awaiting fresh shell telemetry");
        try {
            ManualCheck check = manualCheck;
            boolean checkOwnsPreparation = check != null && (check.ownsSession || check.ownsNative);
            if (checkOwnsPreparation) {
                if (checkLive(check)) startCheckFrames(check);
                return;
            }
            else if (sessionRuntime.armed()) setEligibleOnControl(id, true);
            else if (!sessionRuntime.arming()) holdCpu(false);
            updateCameraDemand();
        } catch (Throwable error) {
            fail(id, "prepared_session", error);
        }
    }

    private void logNativeOperation(String id, String operation, long operationId,
            long queuedNs, long startedNs, long finishedNs, long setupNs, long initNs,
            String outcome, String error, String stage) {
        logEvent("motion_native_operation", id, "operation_id", operationId,
                "operation", operation, "queue_ms", nanosToMillis(startedNs - queuedNs),
                "run_ms", nanosToMillis(finishedNs - startedNs),
                "setup_ms", nanosToMillis(setupNs), "init_ms", nanosToMillis(initNs),
                "stage", stage, "outcome", outcome, "error", error,
                "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                "thread", Thread.currentThread().getName());
    }

    private static double nanosToMillis(long value) {
        return value / 1_000_000.0;
    }

    private int setEligibleOnControl(String id, boolean next) throws Exception {
        requireSession(id);
        if (next && cameraChargeState.isBlocked()) {
            state = "charge_blocked";
            sendStatus(id, state, "fresh charge readings have not cleared every confirmed low cause");
            return 1;
        }
        if (!config.motionEnabled() && next)
            throw new IllegalStateException("Motion eligibility is disabled for remote-only configuration");
        if (!prepared) throw new IllegalStateException("detector is not prepared");
        if (eligible == next && !(readerAttachPending && !next)) return 1;
        if (!next) {
            policy.setEligible(false, SystemClock.elapsedRealtime());
            eligible = false;
            control.removeCallbacks(firstInferenceTimeout);
            screen.setEligible(id, false);
            if (warning) finishWarning(id, false);
            if (recorder != null && recorder.isRecording()) {
                long deadline = SystemClock.elapsedRealtime() + config.afterSeconds * 1000L;
                recorder.finishAfter(deadline);
                control.removeCallbacks(recordingTailFinished);
                control.postDelayed(recordingTailFinished, config.afterSeconds * 1000L);
                state = "recording_tail";
            } else {
                boolean recordingStopped = stopRecording("eligibility_ended", false);
                boolean readerDetached = composer == null && detachAndCloseReader(false);
                state = recordingStopped && readerDetached ? "prepared" : "recording_detach_pending";
                if (composer == null && !readerDetached)
                    sendError(id, "camera_detach", "shared Surface detach remains pending");
            }
            sendStatus(id, state, "fresh Motion telemetry no longer meets the configured activation mode");
            updateCameraDemand();
            return 1;
        }
        cameraPreparation.start(id);
        if (!cameraPreparation.ready(id)) {
            if (!"camera_preparing".equals(state)) {
                state = "camera_preparing";
                sendStatus(id, state, "waiting for IDC online and AVM stream readiness");
            }
            return 1;
        }
        if (readerAttachPending) return 1;
        if (cameraRecovering) {
            if (!stopRecording("camera_recovery", false) || !detachAndCloseReader(false)) return 1;
        }
        if (attached && reader != null && surface != null) {
            activateEligibleReader(id);
            return 1;
        }
        attachReader(id, null);
        return 1;
    }

    private void activateEligibleReader(String id) {
        if (!sameSession(id) || !attached || reader == null || cameraChargeState.isBlocked()
                || !sessionRuntime.armed() || teardownPending) return;
        cameraRecovering = false;
        eligible = true;
        policy.setEligible(true, SystemClock.elapsedRealtime() - MotionDetectorProtocol.WARMUP_MS);
        screen.setEligible(id, true);
        if (recorder != null && recorder.isBuffering() && !recordingStopRequested) {
            control.removeCallbacks(recordingTailFinished);
            recorder.resumeAfterFinish();
        }
        state = "waiting_for_frames";
        lastReportedDetectorState = "";
        firstInferenceReceived = false;
        control.removeCallbacks(firstInferenceTimeout);
        control.postDelayed(firstInferenceTimeout, FIRST_INFERENCE_TIMEOUT_MS);
        if (config.recordingEnabled) startRecording(id);
        sendStatus(id, "waiting_for_frames", "camera Surface attached; status remains pending until first successful native inference");
    }

    private void attachReader(String id, ManualCheck check) throws Exception {
        if (attached && reader != null && surface != null) return;
        if (readerAttachPending) return;
        if (attached || reader != null || surface != null)
            throw new IllegalStateException("motion Surface attachment is in an uncertain state");
        final long generation = sessionGeneration;
        framePerformance.reset();
        frameCadence.reset();
        java.util.Arrays.fill(loggedFrameLayout, 0);
        sharedSource = new MotionSharedSource(error ->
                control.post(() -> { if (sameSession(id)) fail(id, "shared_source", error); }));
        reader = ImageReader.newInstance(MotionDetectorProtocol.INPUT_WIDTH,
                MotionDetectorProtocol.INPUT_HEIGHT, ImageFormat.YUV_420_888, 2);
        surface = reader.getSurface();
        ImageReader ownerReader = reader;
        MotionSharedSource ownerSource = sharedSource;
        reader.setOnImageAvailableListener(source -> {
            drainLatestFrame(ownerReader, ownerSource, id, generation);
        }, ownerSource.handler());
        readerAttachPending = true;
        submitCameraOperation(MotionDetectorProtocol.CAMERA_ATTACH, id, surface,
                "surface_attach", result -> {
                    readerAttachPending = false;
                    if (!sameSession(id) || reader != ownerReader) {
                        if (result.accepted || result.failure != null) attached = true;
                        if (check != null && check.completing
                                && check.waitingForReaderDetach) {
                            check.waitingForReaderDetach = false;
                            check.cleanupFailure = new IllegalStateException(
                                    "camera Surface attach completed for a stale session");
                            continueManualCheckCleanup(check);
                        }
                        return;
                    }
                    if (result.failure != null) {
                        attached = true;
                        if (check != null && check.completing) {
                            detachAndCloseReader(false);
                            if (!readerDetachPending) {
                                check.waitingForReaderDetach = false;
                                check.cleanupFailure = result.failure;
                                continueManualCheckCleanup(check);
                            }
                            return;
                        }
                        if (check != null) completeCheck(check, MotionCheckupClient.ERROR,
                                "camera_attach", summary(result.failure), true);
                        else fail(id, "camera_attach", result.failure);
                        return;
                    }
                    if (!result.accepted) {
                        IllegalStateException failure = new IllegalStateException(
                                "existing camera owner refused Surface index 4123");
                        boolean closed = closeReaderLocally(ownerReader, ownerSource, surface);
                        if (check != null && check.completing) {
                            check.waitingForReaderDetach = false;
                            if (!closed) check.cleanupFailure = failure;
                            continueManualCheckCleanup(check);
                            return;
                        }
                        if (check != null) completeCheck(check, MotionCheckupClient.ERROR,
                                "camera_attach", summary(failure), true);
                        else fail(id, "camera_attach", failure);
                        return;
                    }
                    attached = true;
                    if (!operationIsCurrent(id, generation, sessionId, sessionGeneration)
                            || teardownPending) {
                        continuePendingTeardown();
                    } else if (check != null) {
                        if (checkLive(check)) return;
                        detachAndCloseReader(false);
                        if (check.completing && !readerDetachPending) {
                            check.waitingForReaderDetach = false;
                            check.cleanupFailure = new IllegalStateException(
                                    "camera Surface was not confirmed detached after check completion");
                            continueManualCheckCleanup(check);
                        }
                    } else if (sessionRuntime.armed() && !cameraChargeState.isBlocked()) {
                        activateEligibleReader(id);
                    } else detachAndCloseReader(false);
                });
    }

    private void drainLatestFrame(ImageReader ownerReader, MotionSharedSource source,
            String id, long generation) {
        try {
            if (reader != ownerReader) return;
            Image frame = ownerReader.acquireLatestImage();
            if (frame == null) return;
            processFrame(frame, ownerReader, source, id, generation);
        } catch (Throwable error) {
            control.post(() -> {
                if (sameSession(id) && reader == ownerReader) {
                    if (teardownPending) continuePendingTeardown();
                    else fail(id, "frame", error);
                }
            });
        }
    }

    private void processFrame(Image frame, ImageReader ownerReader, MotionSharedSource source,
            String id, long generation) throws Exception {
        boolean reserved = false;
        ManualCheck check = null;
        MotionYuvPacker.Plane y = null, u = null, v = null;
        int width = 0, height = 0;
        byte[] input = null;
        long frameTimeNs = 0L, packStartNs = 0L, packEndNs = 0L;
        Throwable primaryFailure = null;
        Throwable closeFailure = null;
        try {
            if (frame.getFormat() != ImageFormat.YUV_420_888
                    || frame.getWidth() != MotionDetectorProtocol.INPUT_WIDTH
                    || frame.getHeight() != MotionDetectorProtocol.INPUT_HEIGHT)
                throw new IllegalStateException("composed camera frame mismatch: expected format="
                        + ImageFormat.YUV_420_888 + " size=" + MotionDetectorProtocol.INPUT_WIDTH + "x"
                        + MotionDetectorProtocol.INPUT_HEIGHT + " actual format=" + frame.getFormat()
                        + " size=" + frame.getWidth() + "x" + frame.getHeight());
            Rect crop = frame.getCropRect();
            if (crop.left != 0 || crop.top != 0 || crop.right != frame.getWidth()
                    || crop.bottom != frame.getHeight())
                throw new IllegalStateException("motion frame crop is not the full composition: crop="
                        + crop + " size=" + frame.getWidth() + "x" + frame.getHeight());
            check = manualCheck;
            boolean wantsAnalysis = eligible || check != null && checkLive(check) && !check.finishing;
            if (wantsAnalysis && frameCadence.take(SystemClock.elapsedRealtimeNanos())
                    && reserveAnalysis(ownerReader, generation)) {
                reserved = true;
                Image.Plane[] planes = frame.getPlanes();
                if (planes == null || planes.length != 3)
                    throw new IllegalStateException("YUV_420_888 requires three planes");
                width = frame.getWidth();
                height = frame.getHeight();
                y = plane(planes[0]);
                u = plane(planes[1]);
                v = plane(planes[2]);
                input = nativeInput;
                if (input == null) throw new IllegalStateException("native input is not prepared");
                packStartNs = SystemClock.elapsedRealtimeNanos();
                MotionYuvPacker.pack(y, u, v, width, height, input);
                packEndNs = SystemClock.elapsedRealtimeNanos();
                frameTimeNs = frame.getTimestamp();
            }
            source.snapshot(frame);
        } catch (Throwable error) {
            primaryFailure = error;
            if (reserved) releaseAnalysis();
            throw error;
        } finally {
            try { frame.close(); }
            catch (Throwable error) {
                if (primaryFailure != null) {
                    if (error != primaryFailure) primaryFailure.addSuppressed(error);
                } else closeFailure = error;
            }
        }
        if (closeFailure != null) {
            if (reserved) releaseAnalysis();
            if (closeFailure instanceof RuntimeException) throw (RuntimeException) closeFailure;
            if (closeFailure instanceof Error) throw (Error) closeFailure;
            throw new IllegalStateException("OEM image close failed", closeFailure);
        }
        if (reserved) enqueueNativeDetection(id, generation, ownerReader, check,
                frameTimeNs, packStartNs, packEndNs, width, height, y, u, v, input);
    }

    private boolean reserveAnalysis(ImageReader ownerReader, long generation) {
        synchronized (analysisLock) {
            if (generation != sessionGeneration || reader != ownerReader || analysisBusy) return false;
            analysisBusy = true;
            return true;
        }
    }

    private void releaseAnalysis() {
        synchronized (analysisLock) { analysisBusy = false; }
    }

    private boolean analysisInFlight() {
        synchronized (analysisLock) { return analysisBusy; }
    }

    private void enqueueNativeDetection(String id, long generation, ImageReader ownerReader,
            ManualCheck check, long frameTimeNs, long packStartNs, long packEndNs,
            int width, int height, MotionYuvPacker.Plane y, MotionYuvPacker.Plane u,
            MotionYuvPacker.Plane v, byte[] input) {
        OperationTrace trace = new OperationTrace(operationSequence.incrementAndGet(),
                "frame", id, SystemClock.elapsedRealtimeNanos());
        try {
            nativeOperations.execute(() -> {
                long startedNs = SystemClock.elapsedRealtimeNanos();
                long detectStartNs = startedNs;
                long detectNs = 0L;
                int raw = MotionEventPolicy.ERROR_RESULT;
                int nativeState = MotionEventPolicy.ERROR_RESULT;
                Throwable failure = null;
                try {
                    raw = nativeApi.detect(input, MotionDetectorProtocol.INPUT_WIDTH,
                            MotionDetectorProtocol.INPUT_HEIGHT, sensitivity);
                    detectNs = SystemClock.elapsedRealtimeNanos() - detectStartNs;
                    if (raw != MotionEventPolicy.ERROR_RESULT) nativeState = nativeApi.getState();
                } catch (Throwable error) { failure = error; }
                long finishedNs = SystemClock.elapsedRealtimeNanos();
                NativeFrameResult result = new NativeFrameResult(trace.id, trace.queuedNs,
                        startedNs, finishedNs, detectNs, raw, nativeState, failure);
                if (!control.post(() -> onNativeFrameComplete(id, generation, ownerReader, check,
                        frameTimeNs, packStartNs, packEndNs, width, height, y, u, v, result)))
                    releaseAnalysis();
            });
        } catch (Throwable error) {
            control.post(() -> {
                try {
                    if (operationIsCurrent(id, generation, sessionId, sessionGeneration))
                        fail(id, "native_detect", error);
                } finally {
                    releaseAnalysis();
                    if (teardownPending) continuePendingTeardown();
                }
            });
        }
    }

    private void onNativeFrameComplete(String id, long generation, ImageReader ownerReader,
            ManualCheck check, long frameTimeNs, long packStartNs, long packEndNs,
            int width, int height, MotionYuvPacker.Plane y, MotionYuvPacker.Plane u,
            MotionYuvPacker.Plane v, NativeFrameResult result) {
        try {
            if (!operationIsCurrent(id, generation, sessionId, sessionGeneration)
                    || teardownPending || reader != ownerReader
                    || !(eligible || check != null && checkLive(check) && !check.finishing)) return;
            if (result.failure != null) {
                fail(id, "native_detect", result.failure);
                return;
            }
            detectFrame(check, frameTimeNs, packStartNs, packEndNs, width, height,
                    y, u, v, result);
        } catch (Throwable error) {
            if (sameSession(id) && !teardownPending) fail(id, "frame", error);
        } finally {
            releaseAnalysis();
            if (teardownPending) continuePendingTeardown();
        }
    }

    private void detectFrame(ManualCheck check, long frameTimeNs, long packStartNs, long packEndNs,
            int width, int height, MotionYuvPacker.Plane y, MotionYuvPacker.Plane u,
            MotionYuvPacker.Plane v, NativeFrameResult result) throws Exception {
        long now = SystemClock.elapsedRealtime();
        int raw = result.raw;
        int nativeState = result.nativeState;
        frameNumber++;
        framePerformance.record(packStartNs, packEndNs, result.queuedNs,
                result.startedNs, result.finishedNs, result.detectNs, result.operationId);
        noteFrameLayout(width, height, y, u, v);
        if (framePerformance.due()) flushFramePerformance();
        if (check != null && check.ownsReader) {
            boolean done = check.frames.observe(frameTimeNs, raw);
            logEvent("motion_checkup_frame", sessionId, "check_id", check.id,
                    "timestamp_ns", frameTimeNs, "raw_result", raw, "native_state", nativeState);
            if (done && !check.finishing) {
                check.finishing = true;
                // Release the Image before detaching or closing its ImageReader.
                control.post(() -> startCheckRecording(check));
            }
            return;
        }
        if (raw == MotionEventPolicy.ERROR_RESULT) {
            noteFirstInference(raw, "not_read_after_error", frameTimeNs);
            policy.observe(raw, now);
            fail(sessionId, "native_detect", new IllegalStateException(
                    "native detect returned -2; getState was not called"));
            return;
        }
        noteFirstInference(raw, nativeState, frameTimeNs);
        MotionEventPolicy.Decision decision = policy.observe(raw, now);
        state = policy.warningActive(now) ? "warning_active" : "observing";
        if (!state.equals(lastReportedDetectorState)) {
            lastReportedDetectorState = state;
            sendStatus(sessionId, state, "detector policy state changed");
        }
        if (decision.level != MotionEventPolicy.LEVEL_NONE) {
            sendDetection(sessionId, decision.level, decision.elapsedMs, raw,
                    nativeState, frameNumber, frameTimeNs, decision.warningUntilMs);
            if (recorder != null) recorder.onDetection(decision.level, decision.elapsedMs);
            if (warningVisible && !warningClosing) scheduleWarningDeadline();
            if (config != null && config.warningEnabled && !warning)
                requestWarningWake(sessionId);
        }
    }

    private void noteFrameLayout(int width, int height,
            MotionYuvPacker.Plane y, MotionYuvPacker.Plane u, MotionYuvPacker.Plane v) {
        if (loggedFrameLayout[0] == width && loggedFrameLayout[1] == height
                && loggedFrameLayout[2] == y.rowStride && loggedFrameLayout[3] == y.pixelStride
                && loggedFrameLayout[4] == u.rowStride && loggedFrameLayout[5] == u.pixelStride
                && loggedFrameLayout[6] == v.rowStride && loggedFrameLayout[7] == v.pixelStride) return;
        loggedFrameLayout[0] = width;
        loggedFrameLayout[1] = height;
        loggedFrameLayout[2] = y.rowStride;
        loggedFrameLayout[3] = y.pixelStride;
        loggedFrameLayout[4] = u.rowStride;
        loggedFrameLayout[5] = u.pixelStride;
        loggedFrameLayout[6] = v.rowStride;
        loggedFrameLayout[7] = v.pixelStride;
        logEvent("motion_frame_layout", sessionId, "width", width, "height", height,
                "y_row_stride", y.rowStride, "y_pixel_stride", y.pixelStride,
                "u_row_stride", u.rowStride, "u_pixel_stride", u.pixelStride,
                "v_row_stride", v.rowStride, "v_pixel_stride", v.pixelStride);
    }

    private void flushFramePerformance() {
        if (framePerformance.frames == 0) return;
        logEvent("motion_frame_performance", sessionId,
                "window_ms", framePerformance.windowNs() / 1_000_000.0,
                "frames", framePerformance.frames,
                "operation_id_first", framePerformance.firstOperationId,
                "operation_id_last", framePerformance.lastOperationId,
                "pack_avg_ms", framePerformance.packTotalNs / 1_000_000.0 / framePerformance.frames,
                "pack_max_ms", framePerformance.packMaxNs / 1_000_000.0,
                "native_queue_avg_ms", framePerformance.nativeQueueTotalNs / 1_000_000.0 / framePerformance.frames,
                "native_queue_max_ms", framePerformance.nativeQueueMaxNs / 1_000_000.0,
                "native_run_avg_ms", framePerformance.nativeRunTotalNs / 1_000_000.0 / framePerformance.frames,
                "native_run_max_ms", framePerformance.nativeRunMaxNs / 1_000_000.0,
                "detect_avg_ms", framePerformance.detectTotalNs / 1_000_000.0 / framePerformance.frames,
                "detect_max_ms", framePerformance.detectMaxNs / 1_000_000.0);
        framePerformance.reset();
    }

    /** Start-to-start limit on acquisition; missed slots never create a catch-up burst. */
    static final class FrameCadence {
        private long nextStartNs;

        boolean take(long nowNs) {
            if (nowNs < nextStartNs) return false;
            nextStartNs = nowNs + 400_000_000L;
            return true;
        }

        void reset() { nextStartNs = 0L; }
    }

    /** Control-thread aggregates of completed pack/native calls, not process CPU time. */
    static final class FramePerformance {
        long frames, startNs, endNs;
        long firstOperationId, lastOperationId;
        long packTotalNs, packMaxNs, nativeQueueTotalNs, nativeQueueMaxNs;
        long nativeRunTotalNs, nativeRunMaxNs, detectTotalNs, detectMaxNs;

        void record(long packStartNs, long packEndNs, long queuedNs, long nativeStartNs,
                long nativeEndNs, long detectNs, long operationId) {
            if (frames == 0) {
                startNs = packStartNs;
                firstOperationId = operationId;
            }
            endNs = nativeEndNs;
            lastOperationId = operationId;
            frames++;
            long packNs = packEndNs - packStartNs;
            long queueNs = nativeStartNs - queuedNs;
            long nativeNs = nativeEndNs - nativeStartNs;
            packTotalNs += packNs;
            packMaxNs = Math.max(packMaxNs, packNs);
            nativeQueueTotalNs += queueNs;
            nativeQueueMaxNs = Math.max(nativeQueueMaxNs, queueNs);
            nativeRunTotalNs += nativeNs;
            nativeRunMaxNs = Math.max(nativeRunMaxNs, nativeNs);
            detectTotalNs += detectNs;
            detectMaxNs = Math.max(detectMaxNs, detectNs);
        }

        long windowNs() { return frames == 0 ? 0 : endNs - startNs; }
        boolean due() { return frames > 0 && windowNs() >= 30_000_000_000L; }

        void reset() {
            frames = startNs = endNs = 0;
            firstOperationId = lastOperationId = 0L;
            packTotalNs = packMaxNs = nativeQueueTotalNs = nativeQueueMaxNs = 0;
            nativeRunTotalNs = nativeRunMaxNs = detectTotalNs = detectMaxNs = 0;
        }
    }

    private void noteFirstInference(int raw, Object nativeState, long frameTimestampNs) {
        if (firstInferenceReceived) return;
        firstInferenceReceived = true;
        control.removeCallbacks(firstInferenceTimeout);
        logEvent("motion_first_inference", sessionId, "frame", frameNumber,
                "raw_result", raw, "native_state", nativeState,
                "frame_timestamp_ns", frameTimestampNs);
    }

    private void onFirstInferenceTimeout() {
        String id = sessionId;
        if (id == null || !sameSession(id) || !eligible || firstInferenceReceived) return;
        fail(id, "first_inference_timeout", new IOException(
                "camera Surface produced no detector inference within 10 seconds"));
    }

    private static MotionYuvPacker.Plane plane(Image.Plane plane) {
        ByteBuffer view = plane.getBuffer();
        return new MotionYuvPacker.Plane(view, plane.getRowStride(), plane.getPixelStride());
    }

    private boolean cameraOperation(int operation, String id, Surface detector,
            Surface[] recording, int requestId) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("camera Surface routing requires shell UID");
        IBinder cameraHost = serviceBinder(TurnSignalShellProtocol.SERVICE_NAME);
        if (cameraHost == null) throw new IllegalStateException("existing camera owner service is unavailable");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(operation);
            data.writeString(id);
            data.writeStrongBinder(binder);
            if (operation == MotionDetectorProtocol.CAMERA_ATTACH) data.writeTypedObject(detector, 0);
            if (operation == MotionDetectorProtocol.CAMERA_RECORD_ATTACH) {
                data.writeTypedArray(recording, 0);
                data.writeInt(requestId);
                composer.writeToParcel(data);
            }
            // Only an attempted transaction can leave a remote binding after an uncertain reply.
            if (operation == MotionDetectorProtocol.CAMERA_BIND_WORKER) workerBound = true;
            if (!cameraHost.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0)) {
                if (operation == MotionDetectorProtocol.CAMERA_BIND_WORKER) workerBound = false;
                throw new IllegalStateException("camera runtime did not accept auxiliary operation " + operation);
            }
            reply.readException();
            boolean accepted = reply.readInt() == 1;
            if (operation == MotionDetectorProtocol.CAMERA_BIND_WORKER) {
                String epoch = reply.readString();
                if (!accepted && reply.dataAvail() == 0) {
                    workerBound = false;
                    return false;
                }
                if (!accepted || epoch == null || epoch.isEmpty() || epoch.length() > 128
                        || reply.dataAvail() != 0)
                    throw new IllegalStateException("camera runtime returned an invalid Motion source epoch");
                expectedSourceEpoch = epoch;
                workerBound = true;
                watchTelemetryHost(id, cameraHost);
                return true;
            }
            if (operation == MotionDetectorProtocol.CAMERA_UNBIND_WORKER
                    && accepted && reply.dataAvail() == 0) {
                workerBound = false;
                watchTelemetryHost(id, null);
            }
            return accepted && reply.dataAvail() == 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean cameraOperation(int operation, String id, Surface detector) throws Exception {
        return cameraOperation(operation, id, detector, null, 0);
    }

    private void submitCameraOperation(int operation, String id, Surface detector,
            String operationName, CameraOperationCompletion completion) {
        long operationId = operationSequence.incrementAndGet();
        long queuedNs = SystemClock.elapsedRealtimeNanos();
        try {
            hostOperations.execute(() -> {
                long startedNs = SystemClock.elapsedRealtimeNanos();
                boolean accepted = false;
                Throwable failure = null;
                try { accepted = cameraOperation(operation, id, detector); }
                catch (Throwable error) { failure = error; }
                long finishedNs = SystemClock.elapsedRealtimeNanos();
                CameraOperationResult result = new CameraOperationResult(operationId,
                        queuedNs, startedNs, finishedNs, accepted, failure);
                control.post(() -> {
                    logEvent("motion_camera_host_operation", id,
                            "operation_id", result.operationId, "operation", operationName,
                            "queue_ms", nanosToMillis(result.startedNs - result.queuedNs),
                            "run_ms", nanosToMillis(result.finishedNs - result.startedNs),
                            "outcome", result.failure != null ? "error" : result.accepted ? "accepted" : "refused",
                            "error", result.failure == null ? "" : summary(result.failure),
                            "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                            "context_package", systemContext.getPackageName(),
                            "thread", "motion-camera-host-ops");
                    completion.complete(result);
                });
            });
        } catch (Throwable error) {
            long now = SystemClock.elapsedRealtimeNanos();
            CameraOperationResult result = new CameraOperationResult(operationId,
                    queuedNs, now, now, false, error);
            control.post(() -> {
                logEvent("motion_camera_host_operation", id,
                        "operation_id", result.operationId, "operation", operationName,
                        "queue_ms", nanosToMillis(result.startedNs - result.queuedNs),
                        "run_ms", 0.0, "outcome", "error", "error", summary(error),
                        "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                        "context_package", systemContext.getPackageName(),
                        "thread", "motion-detector-control");
                completion.complete(result);
            });
        }
    }

    private void submitTeardownHostOperation(String id, long generation,
            boolean unsubscribe, boolean releaseWake, boolean unbind) {
        teardownHostOperationPending = true;
        long operationId = operationSequence.incrementAndGet();
        long queuedNs = SystemClock.elapsedRealtimeNanos();
        try {
            hostOperations.execute(() -> {
                long startedNs = SystemClock.elapsedRealtimeNanos();
                long telemetryNs = 0L, wakeNs = 0L, unbindNs = 0L;
                Throwable telemetryFailure = null, wakeFailure = null, unbindFailure = null;
                boolean unbound = false;
                if (unsubscribe) {
                    long stageNs = SystemClock.elapsedRealtimeNanos();
                    try { setMotionTelemetryDemand(id, false); }
                    catch (Throwable error) { telemetryFailure = error; }
                    telemetryNs = SystemClock.elapsedRealtimeNanos() - stageNs;
                }
                if (releaseWake) {
                    long stageNs = SystemClock.elapsedRealtimeNanos();
                    try { cameraOperation(MotionDetectorProtocol.CAMERA_END_WAKE, id, null, null, 0); }
                    catch (Throwable error) { wakeFailure = error; }
                    wakeNs = SystemClock.elapsedRealtimeNanos() - stageNs;
                }
                if (unbind) {
                    long stageNs = SystemClock.elapsedRealtimeNanos();
                    try {
                        if (!cameraOperation(MotionDetectorProtocol.CAMERA_UNBIND_WORKER, id, null, null, 0))
                            throw new IllegalStateException("camera runtime did not confirm Motion worker unbind");
                        unbound = true;
                    } catch (Throwable error) { unbindFailure = error; }
                    unbindNs = SystemClock.elapsedRealtimeNanos() - stageNs;
                }
                long finishedNs = SystemClock.elapsedRealtimeNanos();
                TeardownHostResult result = new TeardownHostResult(operationId, queuedNs,
                        startedNs, finishedNs, telemetryNs, wakeNs, unbindNs, unbound,
                        telemetryFailure, wakeFailure, unbindFailure);
                control.post(() -> onTeardownHostOperationComplete(id, generation, result));
            });
        } catch (Throwable error) {
            teardownHostOperationPending = false;
            sendError(id, "teardown_host_queue", summary(error));
        }
    }

    private void onTeardownHostOperationComplete(String id, long generation, TeardownHostResult result) {
        if (!sameSession(id) || !teardownPending || generation != teardownGeneration
                || generation != sessionGeneration) return;
        teardownHostOperationPending = false;
        logEvent("motion_camera_host_operation", id,
                "operation_id", result.operationId, "operation", "teardown",
                "queue_ms", nanosToMillis(result.startedNs - result.queuedNs),
                "run_ms", nanosToMillis(result.finishedNs - result.startedNs),
                "telemetry_unsubscribe_ms", nanosToMillis(result.telemetryNs),
                "wake_release_ms", nanosToMillis(result.wakeNs),
                "worker_unbind_ms", nanosToMillis(result.unbindNs),
                "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                "context_package", systemContext.getPackageName(),
                "thread", "motion-camera-host-ops");
        if (result.telemetryFailure != null)
            sendError(id, "telemetry_unsubscribe", summary(result.telemetryFailure));
        if (result.wakeFailure != null)
            sendError(id, "wake_release", summary(result.wakeFailure));
        if (result.unbindFailure != null) {
            sendError(id, "worker_unbind", summary(result.unbindFailure));
            logEvent("motion_teardown_pending", id, "resource", "camera_host",
                    "reason", summary(result.unbindFailure));
        }
        if (result.unbound) {
            teardownHostUnbound = true;
            teardownSurfacesDrained = true;
            stopRecording(teardownReason, true);
            detachAndCloseReader(true);
        }
        continuePendingTeardown();
    }

    private void requestWarningWake(String id) {
        if (!sameSession(id) || !eligible || warning || warningClosing || sessionRuntime.warningRetryBlocked()
                || config == null || !config.warningEnabled || cameraChargeState.isBlocked()) return;
        warning = true;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningVisible = false;
        warningWindowLaunched = false;
        warningWindowCommands = null;
        warningWindowFailure = "";
        warningPreparingSleep = false;
        warningSleepAttempted = false;
        warningScreenFinishIssued = false;
        warningAwaitingClosed = false;
        warningBlackFallback = false;
        warningPowerOnClose = false;
        warningCloseSent = false;
        warningWakeTokenOwned = false;
        int generation = ++warningWindowGeneration;
        control.removeCallbacks(warningTimeout);
        holdCpu(true);
        submitCameraOperation(MotionDetectorProtocol.CAMERA_OWN_WAKE, id, null,
                "wake_acquire", result -> {
                    if (!sameSession(id) || generation != warningWindowGeneration || !warning) {
                        if (result.accepted) releaseStaleWake(id);
                        return;
                    }
                    if (result.failure != null || !result.accepted) {
                        Throwable failure = result.failure == null
                                ? new IllegalStateException("camera host refused Motion-owned wake token")
                                : result.failure;
                        sessionRuntime.blockWarningRetry();
                        warningWindowFailure = summary(failure);
                        sendError(id, "wake_registration", warningWindowFailure);
                        sendStatus(id, "warning_error", warningWindowFailure);
                        finishWarning(id, false);
                        return;
                    }
                    warningWakeTokenOwned = true;
                    screen.wakeForWarning(id, generation);
                    sendStatus(id, "warning_waking", "shell registered the current OFF-state camera producer before wake reason 13");
                });
    }

    private void releaseStaleWake(String id) {
        submitCameraOperation(MotionDetectorProtocol.CAMERA_END_WAKE, id, null,
                "stale_wake_release", result -> {
                    if (result.failure != null) sendError(id, "wake_release", summary(result.failure));
                });
    }

    private void launchWarningWindow(String id, int generation) {
        control.post(() -> {
            if (!sameSession(id) || generation != warningWindowGeneration || !warning || warningClosing
                    || !eligible || warningWindowPending || warningWindowDrawn) return;
            warningWindowPending = true;
            warningWindowLaunched = true;
            ResultReceiver reporter = new ResultReceiver(control) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    onWarningWindowResult(id, generation, code, data);
                }
            };
            control.removeCallbacks(warningWindowTimeout);
            control.postDelayed(warningWindowTimeout, MotionDetectorProtocol.WARNING_MS);
            try {
                Intent intent = new Intent().setClassName(MotionShellMediaContext.OWNER_PACKAGE,
                                "com.byd.extend.ui.motion.MotionFullscreenActivity")
                        .setAction(MotionDetectorProtocol.ACTION_SHOW_WARNING)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(MotionDetectorProtocol.EXTRA_SESSION, id)
                        .putExtra(MotionDetectorProtocol.EXTRA_WINDOW_REPORTER,
                                MotionDetectorProtocol.remoteReceiver(reporter));
                startActivityFromShell(intent);
                sendStatus(id, "warning_window_pending", "shell launched warning Activity; first draw must be acknowledged within 10 seconds");
            } catch (Throwable error) {
                warningWindowPending = false;
                warningWindowLaunched = false;
                if (eligible) sessionRuntime.blockWarningRetry();
                warningWindowFailure = summary(error);
                sendError(id, "warning_activity_launch", warningWindowFailure);
                finishWarning(id, false);
            }
        });
    }

    private void onWarningWindowTimeout() {
        if (!warning || warningWindowDrawn || sessionId == null) return;
        warningWindowPending = false;
        if (eligible) sessionRuntime.blockWarningRetry();
        warningWindowFailure = "first_draw_timeout";
        sendError(sessionId, "warning_first_draw", warningWindowFailure);
        sendStatus(sessionId, "warning_error", "first-draw acknowledgement was not received within 10 seconds");
        enterWarningBlackFallback(sessionId, warningWindowFailure, true);
    }

    private int acknowledgeWarningDraw(String id) throws Exception {
        requireSession(id);
        if (!warning || warningClosing || !warningWindowPending || warningWindowDrawn)
            throw new IllegalStateException("warning Activity is not awaiting its first draw");
        warningWindowPending = false;
        warningWindowDrawn = true;
        control.removeCallbacks(warningWindowTimeout);
        screen.warningWindowReady(id, warningWindowGeneration);
        sendStatus(id, "warning_drawn", "matching runtime window reported first draw; OEM cover is not yet released");
        return 1;
    }

    private int finishWarning(String id, boolean restoreAllowed) {
        if (!sameSession(id)) return 0;
        if (!warning) return 1;
        control.removeCallbacks(warningWindowTimeout);
        control.removeCallbacks(warningTimeout);
        if (warningPowerOnClose) {
            closeWarningForPowerOn(id);
            return 1;
        }
        boolean canRestore = restoreAllowed && eligible && warningWindowDrawn
                && sessionRuntime.power() == 0 && !sessionRuntime.suspended() && !teardownPending;
        if (warningClosing) {
            warningRestoreAllowed &= canRestore;
            if (!warningRestoreAllowed && !warningSleepAttempted && !warningAwaitingClosed)
                enterWarningBlackFallback(id, "warning became ineligible before owned sleep", true);
            return 1;
        }
        warningClosing = true;
        warningRestoreAllowed = canRestore;
        if (!warningWindowLaunched) {
            if (!warningScreenFinishIssued) {
                warningScreenFinishIssued = true;
                screen.finishWarning(id, warningWindowGeneration, false);
            }
            finalizeWarningWindow(id, false, false);
            return 1;
        }
        if (!canRestore) {
            enterWarningBlackFallback(id, "owned sleep is not currently allowed", true);
            return 1;
        }
        requestWarningSleepPreparation(id);
        return 1;
    }

    private void requestWarningSleepPreparation(String id) {
        if (!warning || warningPowerOnClose || warningBlackFallback || warningSleepAttempted) return;
        warningClosing = true;
        warningPreparingSleep = true;
        warningRestoreAllowed = true;
        control.removeCallbacks(warningCloseTimeout);
        control.postDelayed(warningCloseTimeout, MotionDetectorProtocol.WINDOW_CLOSE_TIMEOUT_MS);
        if (warningWindowCommands != null)
            warningWindowCommands.send(MotionDetectorProtocol.WINDOW_PREPARE_SLEEP, Bundle.EMPTY);
        sendStatus(id, "warning_sleep_preparing", "waiting for Activity to remove keep-screen and turn-screen-on flags");
    }

    private void onWarningPrepared(String id) {
        if (!warning || !warningPreparingSleep || warningPowerOnClose || warningBlackFallback) return;
        control.removeCallbacks(warningCloseTimeout);
        warningPreparingSleep = false;
        boolean canSleep = warningRestoreAllowed && warningWindowDrawn && eligible
                && sessionRuntime.power() == 0 && !sessionRuntime.suspended() && !teardownPending;
        if (!canSleep) {
            enterWarningBlackFallback(id, "sleep preparation completed after ownership became invalid", true);
            return;
        }
        warningSleepAttempted = true;
        warningScreenFinishIssued = true;
        if (warningWindowCommands != null)
            warningWindowCommands.send(MotionDetectorProtocol.WINDOW_SLEEP_STARTED, Bundle.EMPTY);
        screen.finishWarning(id, warningWindowGeneration, true);
        sendStatus(id, "warning_sleep_started", "Activity acknowledged flags cleared; guarded OEM sleep attempt started");
    }

    private void enterWarningBlackFallback(String id, String reason, boolean stopScreen) {
        if (!sameSession(id) || !warning) return;
        if (warningPowerOnClose) {
            sendWarningWindowClose(id);
            return;
        }
        warningClosing = true;
        warningRestoreAllowed = false;
        warningPreparingSleep = false;
        warningBlackFallback = true;
        control.removeCallbacks(warningWindowTimeout);
        control.removeCallbacks(warningTimeout);
        control.removeCallbacks(warningCloseTimeout);
        if (!warningWindowLaunched) {
            finalizeWarningWindow(id, false, false);
            return;
        }
        if (warningWindowCommands != null)
            warningWindowCommands.send(MotionDetectorProtocol.WINDOW_BLACK_FALLBACK, Bundle.EMPTY);
        if (stopScreen && !warningSleepAttempted && !warningScreenFinishIssued) {
            warningScreenFinishIssued = true;
            screen.finishWarning(id, warningWindowGeneration, false);
        }
        endOwnedWake(id);
        sendStatus(id, "warning_black_fallback", reason == null ? "sleep/restore not confirmed" : reason);
    }

    private void closeWarningAfterBlackRestore(String id, int generation) {
        if (!sameSession(id) || generation != warningWindowGeneration || !warning
                || !warningSleepAttempted || warningPowerOnClose) return;
        warningClosing = true;
        warningAwaitingClosed = true;
        warningPreparingSleep = false;
        warningRestoreAllowed = true;
        sendWarningWindowClose(id);
    }

    private void closeWarningForPowerOn(String id) {
        if (!sameSession(id) || !warning) return;
        warningPowerOnClose = true;
        warningClosing = true;
        warningPreparingSleep = false;
        warningAwaitingClosed = true;
        warningRestoreAllowed = false;
        warningBlackFallback = false;
        control.removeCallbacks(warningTimeout);
        control.removeCallbacks(warningWindowTimeout);
        screen.markUserTakeover(id, warningWindowGeneration);
        if (!warningWindowLaunched) {
            finalizeWarningWindow(id, false, false);
            return;
        }
        sendWarningWindowClose(id);
    }

    private void sendWarningWindowClose(String id) {
        if (warningWindowCommands == null) return;
        if (warningCloseSent) return;
        warningWindowCommands.send(MotionDetectorProtocol.WINDOW_CLOSE, Bundle.EMPTY);
        warningCloseSent = true;
        warningAwaitingClosed = true;
        control.removeCallbacks(warningCloseTimeout);
        control.postDelayed(warningCloseTimeout, MotionDetectorProtocol.WINDOW_CLOSE_TIMEOUT_MS);
    }

    private void finalizeWarningWindow(String id, boolean closed, boolean cancelled) {
        if (!sameSession(id) || !warning) return;
        control.removeCallbacks(warningWindowTimeout);
        control.removeCallbacks(warningTimeout);
        control.removeCallbacks(warningCloseTimeout);
        if (cancelled) {
            if (eligible) sessionRuntime.blockWarningRetry();
            screen.markUserTakeover(id, warningWindowGeneration);
        }
        warning = false;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningVisible = false;
        warningWindowLaunched = false;
        warningWindowCommands = null;
        warningClosing = false;
        warningRestoreAllowed = false;
        warningPreparingSleep = false;
        warningAwaitingClosed = false;
        warningBlackFallback = false;
        warningPowerOnClose = false;
        warningCloseSent = false;
        warningSleepAttempted = false;
        warningScreenFinishIssued = false;
        warningWindowGeneration++;
        endOwnedWake(id);
        sendStatus(id, cancelled ? "warning_user_dismissed" : closed ? "warning_window_closed" : "warning_window_unavailable",
                "Activity closed=" + closed + "; cancelled=" + cancelled);
        if (teardownPending) continuePendingTeardown();
        else holdCpu(false);
    }

    private void finishWarningAfterPolicy() {
        String id = sessionId;
        if (id == null || !warning || !warningVisible || warningClosing) return;
        if (SystemClock.elapsedRealtime() >= policy.warningUntilMs()) finishWarning(id, true);
        else scheduleWarningDeadline();
    }

    private void scheduleWarningDeadline() {
        long deadline = policy.warningUntilMs();
        control.removeCallbacks(warningTimeout);
        control.postDelayed(warningTimeout, Math.max(0L, deadline - SystemClock.elapsedRealtime()));
        if (warningWindowCommands != null) {
            Bundle data = new Bundle();
            data.putLong(MotionDetectorProtocol.WINDOW_DEADLINE, deadline);
            warningWindowCommands.send(MotionDetectorProtocol.WINDOW_SET_DEADLINE, data);
        }
    }

    private void onWarningCloseTimeout() {
        if (sessionId == null || !warning || !warningClosing) return;
        if (eligible) sessionRuntime.blockWarningRetry();
        if (warningPreparingSleep) {
            sendError(sessionId, "warning_window_prepare", "PREPARED not received within 2 seconds; owned sleep refused");
            enterWarningBlackFallback(sessionId, "Activity did not acknowledge sleep preparation", true);
            return;
        }
        if (warningAwaitingClosed) {
            sendError(sessionId, "warning_window_close", "CLOSED not received within 2 seconds; warning remains active");
            if (!warningPowerOnClose && warningWindowCommands != null)
                warningWindowCommands.send(MotionDetectorProtocol.WINDOW_BLACK_FALLBACK, Bundle.EMPTY);
            if (teardownPending) sendStatus(sessionId, "teardown_wait_warning_close", "retaining Activity command channel until actual CLOSED acknowledgement");
        }
    }

    private void onWarningWindowResult(String id, int generation, int code, Bundle data) {
        boolean currentWindow = sameSession(id) && generation == warningWindowGeneration
                && warning && warningWindowLaunched;
        try {
            ResultReceiver commands = code == MotionDetectorProtocol.WINDOW_READY && data != null
                    ? data.getParcelable(MotionDetectorProtocol.WINDOW_COMMANDS) : null;
            if (!currentWindow) {
                if (commands != null) commands.send(MotionDetectorProtocol.WINDOW_CLOSE, Bundle.EMPTY);
                return;
            }
            if (code == MotionDetectorProtocol.WINDOW_READY) {
                if (commands == null) throw new IllegalStateException("window close channel missing");
                warningWindowCommands = commands;
                sendStatus(id, "warning_window_ready", "matching runtime window supplied close/deadline channel");
                if (warningPowerOnClose || warningCloseSent) sendWarningWindowClose(id);
                else if (warningBlackFallback) commands.send(MotionDetectorProtocol.WINDOW_BLACK_FALLBACK, Bundle.EMPTY);
                else if (warningPreparingSleep) commands.send(MotionDetectorProtocol.WINDOW_PREPARE_SLEEP, Bundle.EMPTY);
            } else if (code == MotionDetectorProtocol.WINDOW_DRAWN && !warningClosing) {
                if (warningWindowCommands == null) throw new IllegalStateException("draw arrived before window READY");
                acknowledgeWarningDraw(id);
            } else if (code == MotionDetectorProtocol.WINDOW_REQUEST_FINISH && !warningClosing) {
                finishWarning(id, true);
            } else if (code == MotionDetectorProtocol.WINDOW_PREPARED) {
                onWarningPrepared(id);
            } else if (code == MotionDetectorProtocol.WINDOW_PAUSED) {
                screen.warningActivityPaused(id, generation);
            } else if (code == MotionDetectorProtocol.WINDOW_USER_TAKEOVER) {
                if (eligible) sessionRuntime.blockWarningRetry();
                screen.markUserTakeover(id, generation);
                warningClosing = true;
                warningRestoreAllowed = false;
                warningPreparingSleep = false;
                warningAwaitingClosed = true;
                control.removeCallbacks(warningTimeout);
                control.postDelayed(warningCloseTimeout, MotionDetectorProtocol.WINDOW_CLOSE_TIMEOUT_MS);
            } else if (code == MotionDetectorProtocol.WINDOW_CLOSED) {
                boolean cancelled = data == null || data.getBoolean(MotionDetectorProtocol.WINDOW_CANCELLED, true);
                finalizeWarningWindow(id, true, cancelled || !warningPowerOnClose && !warningSleepAttempted);
            }
        } catch (Throwable error) {
            if (!currentWindow) {
                System.err.println("motion_stale_window_callback " + summary(error));
                return;
            }
            if (eligible) sessionRuntime.blockWarningRetry();
            sendError(id, "warning_window_callback", summary(error));
            enterWarningBlackFallback(id, summary(error), true);
        }
    }

    private static int startActivityFromShell(Intent intent) throws Exception {
        Class<?> taskManager = Class.forName("android.app.IActivityTaskManager");
        IBinder taskBinder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "activity_task");
        if (taskBinder == null) throw new IllegalStateException("activity_task service unavailable");
        Object tasks = Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, taskBinder);
        Method start = taskManager.getMethod("startActivity",
                Class.forName("android.app.IApplicationThread"), String.class, String.class,
                Intent.class, String.class, IBinder.class, String.class, int.class, int.class,
                Class.forName("android.app.ProfilerInfo"), Class.forName("android.os.Bundle"));
        int result = (Integer) start.invoke(tasks, null, "com.android.shell", null, intent,
                null, null, null, 0, 0, null, null);
        if (result < 0) throw new IllegalStateException("shell Activity launch rejected: " + result);
        return result;
    }

    private static boolean isWarningCycleFailure(String state) {
        return "wake_skipped".equals(state) || "wake_uncertain".equals(state)
                || "cover_release_skipped".equals(state) || "cover_release_uncertain".equals(state)
                || "sleep_skipped".equals(state) || "sleep_uncertain".equals(state)
                || "black_restore_uncertain".equals(state) || "finish_uncertain".equals(state)
                || "off_session_lost".equals(state);
    }

    private void endOwnedWake(String id) {
        if (!warningWakeTokenOwned) return;
        warningWakeTokenOwned = false;
        submitCameraOperation(MotionDetectorProtocol.CAMERA_END_WAKE, id, null,
                "wake_release", result -> {
                    if (result.failure != null) sendError(id, "wake_release", summary(result.failure));
                });
    }

    private void holdCpu(boolean required) {
        required = requiresCpuHold(required,
                sessionId != null,
                sessionRuntime.arming() || sessionRuntime.armed() || eligible,
                warning, !cameraPreparation.stopped(), manualCheck != null,
                recorder != null && recorder.isBuffering());
        try {
            if (required && wakeLock == null) {
                Context attributedContext = TurnSignalShellMain.shellContext(systemContext);
                PowerManager manager = (PowerManager) attributedContext.getSystemService(Context.POWER_SERVICE);
                if (manager == null) throw new IllegalStateException("PowerManager unavailable");
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "com.byd.extend:motion-shell");
                wakeLock.setReferenceCounted(false);
            }
            if (required && !wakeLock.isHeld()) wakeLock.acquire();
            else if (!required && wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception error) {
            if (sessionId != null) sendError(sessionId, "wake_lock", summary(error));
        }
    }

    static boolean requiresCpuHold(boolean requested, boolean liveSession, boolean sessionActive,
            boolean warningActive, boolean preparing, boolean manualCheckActive,
            boolean recorderBuffering) {
        return requested || liveSession && sessionActive || warningActive || preparing || manualCheckActive
                || recorderBuffering;
    }

    private void acceptTelemetry(String id, String line) {
        if (!sameSession(id) || line == null) return;
        try {
            org.json.JSONObject event = new org.json.JSONObject(line);
            if (expectedSourceEpoch.equals(event.optString("source_epoch", ""))
                    && event.has("extended_logs"))
                DiagnosticLogPolicy.configure(event.getBoolean("extended_logs"));
            String kind = event.optString("kind", "");
            if ("motion_host_shutdown".equals(kind)) {
                shutdownFromHost(id, event.optString("reason", "host_shutdown"),
                        event.optBoolean("surfaces_drained", false));
                return;
            }
            if ("camera_producer_closed".equals(kind) || "camera_error".equals(kind)) {
                if (!expectedSourceEpoch.equals(event.optString("source_epoch", ""))
                        || !MotionDetectorProtocol.isMotionCameraLoss(kind,
                            event.optString("camera_owner", ""), event.optInt("request_id", 0),
                            recordingAttached ? recordingRequestId : 0)) return;
                handleProducerLoss(id, kind, event.optString("reason", kind),
                        event.optString("error", ""));
                return;
            }
            boolean terminal = teardownPending || shuttingDown || "error".equals(state);
            if (!terminal && ("helper_death".equals(kind) || "helper_ping_failed".equals(kind))) {
                if (!expectedSourceEpoch.equals(event.optString("source_epoch", ""))) return;
                sessionRuntime.invalidateTelemetry();
                cameraPreparation.stop();
                handleProducerLoss(id, kind, kind, event.optString("error", ""));
                return;
            }
            if (!"motion_vehicle_state".equals(kind) && !"shell_power_state".equals(kind)) return;
            String signal = event.optString("signal", "");
            if (!"power".equals(signal) && !"lock".equals(signal)) return;
            String epoch = event.optString("source_epoch", "");
            if (epoch.isEmpty() || !epoch.equals(expectedSourceEpoch)) return;
            int value = event.optInt("value", Integer.MIN_VALUE);
            if (terminal) {
                if (warning && "power".equals(signal)
                        && AvasEventPolicy.normalizedPower(value) == 1)
                    closeWarningForPowerOn(id);
                return;
            }
            if ("power".equals(signal) && AvasEventPolicy.normalizedPower(value) == 1)
                closeWarningForPowerOn(id);
            ManualCheck check = manualCheck;
            if (check != null && "power".equals(signal) && AvasEventPolicy.normalizedPower(value) != 1) {
                completeCheck(check, MotionCheckupClient.NOT_CHECKED, "power_not_on",
                        "Power is no longer ON; check-up resources released", true);
                if (!sameSession(id)) return;
            }
            boolean baseline = event.optBoolean("baseline", false);
            long now = SystemClock.elapsedRealtime();
            MotionSessionRuntime.Change change = sessionRuntime.telemetry(epoch, signal,
                    value, baseline, now);
            applyEligibilityChange(id, change);
            if (sessionRuntime.needsTelemetry()) requestTelemetryRefresh();
        } catch (Throwable error) {
            System.err.println("motion_telemetry_rejected " + summary(error));
        }
    }

    private void advanceEligibility() {
        if (sessionId == null || shuttingDown || teardownPending || sessionRuntime.suspended()) return;
        long now = SystemClock.elapsedRealtime();
        applyEligibilityChange(sessionId, sessionRuntime.advance(now));
    }

    private void applyEligibilityChange(String id, MotionSessionRuntime.Change change) {
        if (!sameSession(id) || shuttingDown || teardownPending || sessionRuntime.suspended()) return;
        try {
            if (change == MotionSessionRuntime.Change.EPOCH_CHANGED) {
                if (eligible) setEligibleOnControl(id, false);
                eligible = false;
                control.removeCallbacks(armCheck);
                sendStatus(id, "telemetry_epoch_changed", "old Power/Lock baselines discarded");
            }
            if (sessionRuntime.arming()) {
                holdCpu(true);
                control.removeCallbacks(armCheck);
                control.postDelayed(armCheck, sessionRuntime.remainingArmDelayMs(SystemClock.elapsedRealtime()));
                if (change == MotionSessionRuntime.Change.ARMING || change == MotionSessionRuntime.Change.EPOCH_CHANGED)
                    sendStatus(id, "arming", "fresh mode-eligible telemetry must remain valid for 2.5 seconds");
            } else control.removeCallbacks(armCheck);
            if (change == MotionSessionRuntime.Change.ARMED) {
                holdCpu(true);
                sendStatus(id, "armed", "2.5-second shell telemetry warm-up completed");
                if (prepared) setEligibleOnControl(id, true);
            } else if (change == MotionSessionRuntime.Change.DISARMED) {
                control.removeCallbacks(armCheck);
                if (eligible) setEligibleOnControl(id, false);
                eligible = false;
                if (recorder == null || !recorder.isRecording()) holdCpu(false);
                sendStatus(id, "disarmed", "Power/Lock telemetry no longer meets Motion mode");
            }
            updateCameraDemand();
        } catch (Throwable error) {
            fail(id, "eligibility", error);
        }
    }

    private void updateCameraDemand() {
        boolean recordingDrain = recorder != null && recorder.isRecording();
        boolean motionDemand = config != null && config.motionEnabled()
                && !cameraChargeState.isBlocked()
                && (sessionRuntime.arming() || sessionRuntime.armed() || eligible);
        boolean viewingDemand = config != null && config.remoteViewingEnabled
                && !remoteViewLeases.isEmpty() && !cameraChargeState.isBlocked();
        boolean activeSession = sessionId != null && !shuttingDown && !teardownPending
                && !sessionRuntime.suspended();
        boolean needed = activeSession && sessionRuntime.power() == 0
                && (prepared && (motionDemand || recordingDrain) || viewingDemand);
        if (needed) {
            // The app viewer holds its own process-lifetime CPU lease; Motion keeps its existing one.
            if (prepared && (motionDemand || recordingDrain)) holdCpu(true);
            cameraPreparation.start(sessionId);
        } else cameraPreparation.stop();
    }

    private void cameraPreparationSample(String id, int power, int online, int stream, String error) {
        if (!sameSession(id) || shuttingDown || teardownPending || sessionRuntime.suspended()
                || config == null || !config.motionEnabled()) return;
        String snapshot = power + "/" + online + "/" + stream + "/" + error;
        if (!snapshot.equals(lastCameraPreparation)) {
            lastCameraPreparation = snapshot;
            logEvent("motion_camera_preparation", id, "power", power, "idc_online", online,
                    "avm_stream", stream, "ready", cameraPreparation.ready(id), "error", error);
        }
        if (power != 0) {
            // Never continue OFF-state requests on unknown/ON power; obtain a fresh shared baseline.
            sessionRuntime.invalidateTelemetry();
            control.removeCallbacks(armCheck);
            try { if (eligible) setEligibleOnControl(id, false); }
            catch (Throwable failure) { fail(id, "camera_power_lost", failure); return; }
            cameraPreparation.stop();
            if (!warning && (recorder == null || !recorder.isRecording())) holdCpu(false);
            requestTelemetryRefresh();
            return;
        }
        // A ready flag gates opening. A later flag loss alone is not proof of lost frames.
        if (!cameraChargeState.isBlocked() && prepared && sessionRuntime.armed()
                && !eligible && cameraPreparation.ready(id)) {
            try { setEligibleOnControl(id, true); }
            catch (Throwable failure) { fail(id, "camera_prepare_attach", failure); }
        }
    }

    private void requestTelemetryRefresh() {
        if (sessionId == null || shuttingDown || teardownPending || sessionRuntime.suspended()
                || telemetryRefreshPending || telemetryRefreshQueued) return;
        telemetryRefreshQueued = true;
        control.postDelayed(telemetryRefresh, AvasTelemetryController.FALLBACK_MS);
    }

    private void refreshTelemetry() {
        telemetryRefreshQueued = false;
        String id = sessionId;
        if (id == null || shuttingDown || teardownPending || sessionRuntime.suspended()
                || telemetryRefreshPending) return;
        telemetryRefreshPending = true;
        IBinder host = telemetryHost;
        boolean rebind = host == null || !host.isBinderAlive();
        if (rebind) workerBinding = true;
        hostOperations.execute(() -> {
            try {
                if (sameSession(id) && !shuttingDown && !teardownPending) {
                    if (rebind && !cameraOperation(MotionDetectorProtocol.CAMERA_BIND_WORKER, id, null))
                        throw new IllegalStateException("Motion telemetry host rebind refused");
                    setMotionTelemetryDemand(id, true);
                }
            }
            catch (Exception error) { logEvent("motion_telemetry_refresh_failed", id, "error", summary(error)); }
            finally {
                control.post(() -> {
                    telemetryRefreshPending = false;
                    if (rebind) workerBinding = false;
                    if (teardownPending) { continuePendingTeardown(); return; }
                    if (sessionId != null && sessionRuntime.needsTelemetry()) requestTelemetryRefresh();
                });
            }
        });
    }

    private synchronized void watchTelemetryHost(String id, IBinder next) throws RemoteException {
        if (telemetryHost == next) return;
        if (telemetryHost != null && telemetryHostDeath != null) {
            try { telemetryHost.unlinkToDeath(telemetryHostDeath, 0); }
            catch (java.util.NoSuchElementException ignored) { }
        }
        telemetryHost = next;
        telemetryHostDeath = null;
        if (next == null) return;
        telemetryHostDeath = () -> control.post(() -> {
            if (!sameSession(id) || telemetryHost != next) return;
            try { watchTelemetryHost(id, null); } catch (RemoteException ignored) { }
            workerBound = false;
            expectedSourceEpoch = "";
            sessionRuntime.invalidateTelemetry();
            cameraPreparation.stop();
            handleProducerLoss(id, "camera_producer_closed", "telemetry_host_died", "Binder died");
        });
        try { next.linkToDeath(telemetryHostDeath, 0); }
        catch (RemoteException dead) {
            telemetryHostDeath.binderDied();
            throw dead;
        }
    }

    private void handleProducerLoss(String id, String kind, String reason, String detail) {
        boolean producerClosed = "camera_producer_closed".equals(kind);
        if (teardownPending) {
            if (producerClosed) {
                teardownSurfacesDrained = true;
                if (teardownHostShutdown) teardownHostUnbound = true;
                continuePendingTeardown();
            }
            return;
        }
        boolean deliberate = !eligible && reason.equals(deliberatelyDetachedProducerReason)
                && ("motion_detector_idle".equals(reason) || "motion_recording_stop".equals(reason)
                    || "source_demand_idle".equals(reason));
        boolean viewingIdle = producerClosed && "source_demand_idle".equals(reason)
                && !eligible && !attached && recorder == null && manualCheck == null
                && config != null && !config.motionEnabled();
        if (deliberate || viewingIdle || !sameSession(id) || shuttingDown) return;
        ManualCheck check = manualCheck;
        if (check != null) {
            if (producerClosed && check.ownsReader) detachAndCloseReader(true);
            if (producerClosed && check.ownsRecording) stopRecording("camera_source_lost", true);
            completeCheck(check, MotionCheckupClient.ERROR, "check_failed", kind + ": " + reason, true);
            if (!sameSession(id)) return;
        }
        sendStatus(id, "camera_recovering", kind + ":" + reason
                + (detail.isEmpty() ? "" : ":" + detail));
        sessionRuntime.cameraLost();
        cameraRecovering = true;
        control.removeCallbacks(armCheck);
        try {
            if (eligible) {
                policy.setEligible(false, SystemClock.elapsedRealtime());
                screen.setEligible(id, false);
                control.removeCallbacks(firstInferenceTimeout);
            }
            eligible = false;
            if (warning) finishWarning(id, false);
            boolean recordingStopped = stopRecording("camera_source_lost", producerClosed);
            boolean readerDetached = detachAndCloseReader(producerClosed);
            if (!recordingStopped || !readerDetached)
                sendStatus(id, "camera_recovering", "camera consumer retained until host detach or producer closure is confirmed");
            applyEligibilityChange(id, sessionRuntime.advance(SystemClock.elapsedRealtime()));
            if (!sessionRuntime.arming() && !sessionRuntime.armed() && !warning
                    && (recorder == null || !recorder.isRecording())) holdCpu(false);
            requestTelemetryRefresh();
        } catch (Throwable error) {
            fail(id, "camera_source_lost", error);
        }
    }

    private void startRecording(String id) {
        if (!sameSession(id) || !eligible || config == null || !config.recordingEnabled
                || cameraChargeState.isBlocked() || recorder != null || shuttingDown || teardownPending
                || recordingFailureRevision == sessionRuntime.recordingRevision()) return;
        startRecording(id, null);
    }

    private void startRecording(String id, ManualCheck check) {
        if (cameraChargeState.isBlocked()) {
            if (check != null) completeCheck(check, MotionCheckupClient.NOT_CHECKED,
                    "charge_blocked", "shared camera charge protection is active", true);
            return;
        }
        MotionConfig recordingConfig = check == null ? config : check.recordingConfig;
        recordingAttemptRevision = sessionRuntime.recordingRevision();
        try {
            int generation = ++recordingGeneration;
            ensureMediaBridge();
            final MotionVideoRecorder[] owner = new MotionVideoRecorder[1];
            MotionVideoRecorder created = new MotionVideoRecorder(mediaContext, mediaLibrary, id,
                    detail -> control.post(() -> onRecorderEvent(id, owner[0], generation, detail)));
            owner[0] = created;
            recorder = created;
            recorder.startBuffering(recordingConfig, check != null);
            if (!recorder.isBuffering() || recorder.inputSurface() == null)
                throw new IllegalStateException("hardware video encoder did not start");
            Surface encoderSurface = recorder.retainInputSurface();
            composer = new MotionRecordingOutput(
                    recorder.width(), recorder.height(), recordingConfig.cameraOrder, id,
                    recorder.frameTimestamp(),
                    detail -> control.post(() -> {
                        if (!sameSession(id) || generation != recordingGeneration) return;
                        sendError(id, "video_composer", detail);
                        if (check != null) {
                            completeCheck(check, MotionCheckupClient.ERROR, "check_failed", detail, true);
                            return;
                        }
                        blockRecordingRetry(id, "composer_failed");
                        stopRecording("composer_failed");
                    }));
            // Retain cleanup ownership even when initialization times out on the GL thread.
            composer.initialize(encoderSurface);
            if (sharedSource == null) throw new IllegalStateException("Motion shared source is not attached");
            composer.startLocal(mediaContext.ownerResourcesContext(), sharedSource);
            recordingRequestId = recordingRequestId == Integer.MAX_VALUE ? 1 : recordingRequestId + 1;
            deliberatelyDetachedProducerReason = "";
            sendStatus(id, "recording_buffering", "hardware encoder shares the original OEM 4123 input with detection");
        } catch (Throwable error) {
            if (check != null) {
                completeCheck(check, MotionCheckupClient.ERROR, "check_failed", summary(error), true);
                return;
            }
            sendError(id, "recording_start", summary(error));
            blockRecordingRetry(id, "start_failed");
            stopRecording("start_failed");
        }
    }

    private void blockRecordingRetry(String id, String reason) {
        recordingFailureRevision = recordingAttemptRevision;
        logEvent("motion_recording_retry_blocked", id, "reason", reason,
                "attempt_revision", recordingAttemptRevision,
                "current_revision", sessionRuntime.recordingRevision());
    }

    private void onRecorderEvent(String id, MotionVideoRecorder owner, int generation, String detail) {
        if (owner == null || recorder != owner) return;
        ManualCheck check = manualCheck;
        if (check != null && check.ownsRecording
                && detail != null && detail.startsWith("input_release_required:")) {
            completeCheck(check, owner.checkupSavedAndDeleted() ? MotionCheckupClient.OK : MotionCheckupClient.ERROR,
                    owner.checkupSavedAndDeleted() ? "recording_ok" : "check_failed",
                    owner.checkupDetail(), true);
            return;
        }
        if (detail != null && detail.startsWith("input_release_required:")) {
            if (!recordingStopRequested) blockRecordingRetry(id, detail);
            if (teardownPending) continuePendingTeardown();
            else stopRecording(detail.substring("input_release_required:".length()));
            updateCameraDemand();
            return;
        }
        if (detail != null && detail.startsWith("stopped:")) {
            if (owner.isBuffering()) return;
            if (!recordingStopRequested) blockRecordingRetry(id, detail);
            if (recordingAttached || composer != null) {
                logEvent("motion_teardown_pending", id, "resource", "recorder",
                        "reason", "video worker ended before camera detach was confirmed");
                return;
            }
            try { owner.close(); }
            catch (Throwable error) { sendError(id, "recorder_close", summary(error)); return; }
            recorder = null;
            recordingStopRequested = false;
            if (teardownPending) continuePendingTeardown();
            else if (sameSession(id) && eligible && config != null && config.recordingEnabled)
                startRecording(id);
            else if (sameSession(id) && !eligible && manualCheck == null) {
                if (recordingTailPending()) finishRecordingTailIfReady(id);
                else detachAndCloseReader(false);
            }
            updateCameraDemand();
            if (!teardownPending) holdCpu(false);
            return;
        }
        if (!sameSession(id) || generation != recordingGeneration) return;
        if (detail != null && (detail.startsWith("video_encoder_unavailable:")
                || detail.startsWith("video_encoder_failed:"))) {
            if (check != null && check.ownsRecording) {
                completeCheck(check, MotionCheckupClient.ERROR, "check_failed", detail, true);
                return;
            }
            sendError(id, "video_encoder", detail);
            blockRecordingRetry(id, detail);
            stopRecording("encoder_failed");
        }
    }

    private boolean stopRecording(String reason) { return stopRecording(reason, false); }

    private void finishRecordingTail() {
        if (teardownPending) {
            continuePendingTeardown();
            return;
        }
        String id = sessionId;
        if (id == null || eligible || !"recording_tail".equals(state)) return;
        boolean stopped = stopRecording("postroll_finished", false);
        if (id == null || !sameSession(id)) return;
        if (stopped && finishRecordingTailIfReady(id)) {
            updateCameraDemand();
            return;
        }
        if (!stopped || recordingTailPending()) {
            state = "recording_teardown_pending";
            sendStatus(id, state, "camera host has not confirmed encoder drain; consumer resources retained");
        }
        updateCameraDemand();
    }

    private boolean recordingTailPending() {
        return "recording_tail".equals(state) || "recording_teardown_pending".equals(state);
    }

    private boolean finishRecordingTailIfReady(String id) {
        if (!sameSession(id) || eligible || manualCheck != null || !recordingTailPending()
                || recordingAttached || composer != null || recorder != null
                || !detachAndCloseReader(false)) return false;
        state = "prepared";
        sendStatus(id, state, "recording post-roll completed and encoder detached");
        return true;
    }

    private boolean stopRecording(String reason, boolean surfacesDrained) {
        control.removeCallbacks(recordingTailFinished);
        recordingGeneration++;
        String id = sessionId;
        if (recordingAttached && id != null && !surfacesDrained) {
            if (recordingDetachPending) return false;
            deliberatelyDetachedProducerReason = "motion_recording_stop";
            long generation = sessionGeneration;
            recordingDetachPending = true;
            submitCameraOperation(MotionDetectorProtocol.CAMERA_RECORD_DETACH, id, null,
                    "recording_surface_detach", result -> {
                        recordingDetachPending = false;
                        ManualCheck check = manualCheck;
                        boolean checkWaiting = check != null && check.completing
                                && check.waitingForRecordingDetach;
                        if (!sameSession(id)) return;
                        if (result.failure != null || !result.accepted) {
                            Throwable failure = result.failure == null
                                    ? new IllegalStateException("camera owner did not confirm Motion encoder detach")
                                    : result.failure;
                            sendError(id, "recording_detach", summary(failure));
                            logEvent("motion_teardown_pending", id, "resource", "encoder_surface",
                                    "reason", summary(failure));
                            if (checkWaiting) {
                                check.waitingForRecordingDetach = false;
                                check.cleanupFailure = failure;
                                continueManualCheckCleanup(check);
                            }
                            if (teardownPending) continuePendingTeardown();
                            return;
                        }
                        recordingAttached = false;
                        boolean stopped = stopRecording(reason, false);
                        if (checkWaiting) {
                            check.waitingForRecordingDetach = false;
                            if (!stopped) check.cleanupFailure = new IllegalStateException(
                                    "encoder cleanup remains pending after Surface detach");
                            continueManualCheckCleanup(check);
                        }
                        if (!operationIsCurrent(id, generation, sessionId, sessionGeneration)
                                && !teardownPending) return;
                        finishRecordingStop(id, reason, stopped);
                    });
            return false;
        } else if (surfacesDrained) recordingAttached = false;

        MotionRecordingOutput oldComposer = composer;
        if (oldComposer != null) {
            try {
                oldComposer.close(() -> control.post(() -> {
                    if (composer != oldComposer || !sameSession(id)) return;
                    if (teardownPending) continuePendingTeardown();
                    else {
                        boolean stopped = stopRecording(reason);
                        if (stopped && sameSession(id)) {
                            if (eligible && config != null && config.recordingEnabled)
                                startRecording(id);
                            else if (!eligible && manualCheck == null) {
                                if (recordingTailPending()) finishRecordingTailIfReady(id);
                                else detachAndCloseReader(false);
                            }
                        }
                    }
                    updateCameraDemand();
                }));
                composer = null;
            } catch (Throwable error) {
                if (sameSession(id)) sendError(id, "composer_close", summary(error));
                logEvent("motion_teardown_pending", id, "resource", "composer",
                        "reason", summary(error));
                return false;
            }
        }

        MotionVideoRecorder oldRecorder = recorder;
        if (oldRecorder != null) {
            try {
                // A self-ended encoder must keep its Surface until camera and GL owners release it.
                oldRecorder.releaseInputSurface();
                if (!recordingStopRequested) {
                    recordingStopRequested = true;
                    oldRecorder.stop(reason);
                }
                if (oldRecorder.isBuffering()) {
                    if (sameSession(id)) sendStatus(id, "recording_teardown_pending",
                            "encoder drain is still active; shared media provider retained");
                    logEvent("motion_teardown_pending", id, "resource", "recorder",
                            "reason", reason, "buffering", true);
                    return false;
                }
                oldRecorder.close();
                recorder = null;
                recordingStopRequested = false;
            } catch (Throwable error) {
                if (sameSession(id)) sendError(id, "recorder_close", summary(error));
                logEvent("motion_teardown_pending", id, "resource", "recorder",
                        "reason", summary(error));
                return false;
            }
        }
        if (!eligible && !warning) holdCpu(false);
        return !recordingAttached && composer == null && recorder == null;
    }

    private void finishRecordingStop(String id, String reason, boolean stopped) {
        if (teardownPending) continuePendingTeardown();
        else if (stopped && sameSession(id)) {
            if (eligible && config != null && config.recordingEnabled) startRecording(id);
            else if (!eligible && manualCheck == null) {
                if (recordingTailPending()) finishRecordingTailIfReady(id);
                else detachAndCloseReader(false);
            }
        }
        updateCameraDemand();
    }

    private void shutdownFromHost(String id, String reason, boolean surfacesDrained) {
        if (!sameSession(id)) return;
        pendingConfig = null;
        pendingConfigureCallback = null;
        pendingConfigureToken = null;
        shuttingDown = true;
        sessionRuntime.suspendForHostShutdown();
        control.removeCallbacks(armCheck);
        if (teardownPending) {
            teardownHostShutdown = true;
            teardownSurfacesDrained |= surfacesDrained;
            teardownHostUnbound |= surfacesDrained;
            continuePendingTeardown();
            return;
        }
        beginTeardown(id, "host_shutdown_" + reason, false, true, surfacesDrained, null, "suspended");
    }

    private int stopOnControl(String id, String reason) {
        if (!sameSession(id)) return 0;
        beginTeardown(id, reason, true, false, false, appCallback, "idle");
        return 1;
    }

    private void beginTeardown(String id, String reason, boolean notifyHost,
            boolean hostShutdown, boolean surfacesDrained, IBinder callback, String finalState) {
        ManualCheck check = manualCheck;
        if (check != null) completeCheck(check, MotionCheckupClient.NOT_CHECKED, "cancelled", reason, false);
        if (teardownPending && sameSession(id)) return;
        teardownPending = true;
        chargeStartupPending = false;
        chargeHandlingEnabled = false;
        synchronized (analysisLock) {
            sessionGeneration++;
            teardownGeneration = sessionGeneration;
        }
        clearRemoteViewLeases();
        cameraPreparation.stop();
        control.removeCallbacks(telemetryRefresh);
        telemetryRefreshQueued = false;
        teardownNotifyHost = notifyHost;
        teardownHostShutdown = hostShutdown;
        teardownSurfacesDrained = surfacesDrained;
        teardownHostUnbound = hostShutdown && surfacesDrained;
        teardownUnbindAttempted = false;
        teardownDemandCleared = false;
        teardownReason = reason;
        teardownCallback = callback;
        teardownFinalState = finalState;
        state = "stopping";
        continuePendingTeardown();
    }

    private void continuePendingTeardown() {
        String id = sessionId;
        if (!teardownPending || id == null) return;
        if (workerBinding) {
            state = "teardown_pending";
            return;
        }
        if (warning) {
            if (sessionRuntime.power() == 1) closeWarningForPowerOn(id);
            else enterWarningBlackFallback(id, "Motion teardown is waiting for the active warning window", true);
            if (warning) {
                state = "teardown_wait_warning_close";
                sendStatus(id, state, "warning Activity remains available for manual or PowerON close until CLOSED is confirmed");
                return;
            }
        }
        control.removeCallbacks(armCheck);
        control.removeCallbacks(firstInferenceTimeout);
        control.removeCallbacks(warningWindowTimeout);
        control.removeCallbacks(warningTimeout);
        control.removeCallbacks(recordingTailFinished);
        eligible = false;
        policy.stop(SystemClock.elapsedRealtime());
        screen.setEligible(id, false);
        deliberatelyDetachedProducerReason = "motion_detector_idle";
        boolean recorderStopped = stopRecording(teardownReason, teardownSurfacesDrained);
        boolean readerDetached = detachAndCloseReader(teardownSurfacesDrained);

        boolean surfacesNeedHostDrain = (!recorderStopped && recordingAttached)
                || (!readerDetached && attached) || readerAttachPending || readerDetachPending
                || recordingDetachPending;
        boolean unsubscribe = teardownNotifyHost && workerBound && !teardownDemandCleared;
        boolean unbind = workerBound && !teardownHostUnbound && !teardownUnbindAttempted
                && (teardownNotifyHost || surfacesNeedHostDrain);
        if (teardownHostOperationPending) {
            state = "teardown_pending";
            return;
        }
        if (unsubscribe || unbind) {
            if (unsubscribe) teardownDemandCleared = true;
            if (unbind) teardownUnbindAttempted = true;
            submitTeardownHostOperation(id, teardownGeneration, unsubscribe,
                    unsubscribe, unbind);
            state = "teardown_pending";
            return;
        }

        boolean resourcesSafe = recorderStopped && readerDetached
                && (!recordingAttached && !attached);
        boolean hostReleased = !workerBound || !teardownNotifyHost || teardownHostUnbound
                || teardownHostShutdown && teardownSurfacesDrained;
        if (!resourcesSafe || !hostReleased || !cameraPreparation.stopped()) {
            state = "teardown_pending";
            if (!lastError.isEmpty()) sendStatus(id, state, lastError);
            else sendStatus(id, state, "Motion surfaces, encoder or OEM stream request are awaiting confirmed release");
            return;
        }

        if (nativePreparationPending || analysisInFlight()) {
            state = "teardown_pending";
            return;
        }
        if (!nativeDeleteComplete) {
            if (!nativeDeletePending) {
                nativeDeletePending = true;
                submitNativeDelete(id, "teardown", result -> {
                    if (!sameSession(id) || !teardownPending
                            || !operationIsCurrent(id, teardownGeneration, sessionId, sessionGeneration)) return;
                    nativeDeletePending = false;
                    if (result.failure != null) {
                        sendError(id, "native_teardown", summary(result.failure));
                        state = "teardown_pending";
                        return;
                    }
                    for (NativeCleanupFailure cleanup : result.failures)
                        recordNativeCleanupFailure(cleanup.operation, cleanup.failure, null);
                    nativeDeleteComplete = true;
                    continuePendingTeardown();
                });
            }
            state = "teardown_pending";
            return;
        }
        control.removeCallbacks(warningCloseTimeout);
        warningWindowGeneration++;
        warningWindowCommands = null;
        warningWindowLaunched = false;
        warningClosing = false;
        warningVisible = false;
        warning = false;
        warningWindowPending = false;
        warningWindowDrawn = false;
        warningRestoreAllowed = false;
        warningPreparingSleep = false;
        warningSleepAttempted = false;
        warningScreenFinishIssued = false;
        warningAwaitingClosed = false;
        warningBlackFallback = false;
        warningPowerOnClose = false;
        warningCloseSent = false;
        warningWakeTokenOwned = false;
        prepared = false;
        eligible = false;
        IBinder callback = teardownCallback;
        IBinder pendingCallback = pendingAppCallback;
        pendingAppCallback = null;
        if (callback != null) detachUiOnControl(id, callback);
        String finishedState = teardownFinalState;
        String finishedReason = teardownReason;
        if (teardownHostShutdown && teardownSurfacesDrained) workerBound = false;
        teardownPending = false;
        teardownGeneration = 0L;
        teardownHostOperationPending = false;
        nativePreparationPending = false;
        nativeDeletePending = false;
        nativeDeleteComplete = false;
        readerAttachPending = false;
        readerDetachPending = false;
        recordingDetachPending = false;
        teardownCallback = null;
        cameraHostPending = false;
        cameraHostReadyDuringBinding = false;
        sessionId = null;
        state = finishedState;
        ManualCheck teardownCheck = manualCheck;
        if (teardownCheck != null && teardownCheck.id.equals(id)
                && teardownCheck.completing && teardownCheck.waitingForSessionTeardown) {
            control.removeCallbacks(teardownCheck.timeout);
            teardownCheck.waitingForSessionTeardown = false;
            finishManualCheck(teardownCheck);
        }
        IBinder statusCallback = callback == null ? pendingCallback : callback;
        if ("suspended".equals(finishedState))
            sendStatus(statusCallback, id, "suspended", "explicit host shutdown; saved Motion config retained until authenticated configure");
        else if ("error".equals(finishedState))
            sendStatus(statusCallback, id, "error", lastError);
        else sendStatus(statusCallback, id, "stopped", finishedReason);

        MotionSessionConfigStore.Snapshot next = pendingConfig;
        pendingConfig = null;
        if (next != null) {
            IBinder configureCallback = pendingConfigureCallback;
            String requestToken = pendingConfigureToken;
            pendingConfigureCallback = null;
            pendingConfigureToken = null;
            try {
                if (startConfiguredSession(next, configureCallback, requestToken))
                    sendConfigured(configureCallback, requestToken, next);
            } catch (Throwable error) {
                state = "error";
                lastError = "pending_config: " + summary(error);
                System.err.println("motion_pending_config_failed " + lastError);
                System.err.flush();
            }
        }
        holdCpu(false);
    }

    private boolean detachAndCloseReader(boolean surfacesDrained) {
        if (composer != null) return false; // Recording tail still owns the same OEM source.
        flushFramePerformance();
        if (readerAttachPending || readerDetachPending && !surfacesDrained) return false;
        if (attached && !surfacesDrained) {
            String id = sessionId;
            if (id == null) return false;
            deliberatelyDetachedProducerReason = "motion_detector_idle";
            ImageReader ownerReader = reader;
            MotionSharedSource ownerSource = sharedSource;
            Surface ownerSurface = surface;
            ManualCheck check = manualCheck;
            readerDetachPending = true;
            submitCameraOperation(MotionDetectorProtocol.CAMERA_DETACH, id, null,
                    "surface_detach", result -> {
                        readerDetachPending = false;
                        if (teardownPending && teardownSurfacesDrained) {
                            attached = false;
                            closeReaderLocally(ownerReader, ownerSource, ownerSurface);
                            if (check != null && check.completing
                                    && check.waitingForReaderDetach) {
                                check.waitingForReaderDetach = false;
                                continueManualCheckCleanup(check);
                            }
                            continuePendingTeardown();
                            return;
                        }
                        if (!sameSession(id) || reader != ownerReader) return;
                        if (result.failure != null || !result.accepted) {
                            Throwable failure = result.failure == null
                                    ? new IllegalStateException("existing camera owner did not confirm Surface detach")
                                    : result.failure;
                            sendError(id, "camera_detach", summary(failure));
                            if (check != null && check.completing
                                    && check.waitingForReaderDetach) {
                                check.waitingForReaderDetach = false;
                                check.cleanupFailure = failure;
                                continueManualCheckCleanup(check);
                            }
                            if (teardownPending) continuePendingTeardown();
                            return;
                        }
                        attached = false;
                        if (!closeReaderLocally(ownerReader, ownerSource, ownerSurface)) {
                            if (check != null && check.completing
                                    && check.waitingForReaderDetach) {
                                check.waitingForReaderDetach = false;
                                check.cleanupFailure = new IllegalStateException(
                                        "camera consumer resources were not fully released after Surface detach");
                                continueManualCheckCleanup(check);
                            }
                            if (teardownPending) continuePendingTeardown();
                            return;
                        }
                        if (check != null && check.completing
                                && check.waitingForReaderDetach) {
                            check.waitingForReaderDetach = false;
                            continueManualCheckCleanup(check);
                        }
                        if (teardownPending) continuePendingTeardown();
                        else if (sessionRuntime.armed() && !eligible && prepared
                                && !cameraChargeState.isBlocked()) {
                            try { setEligibleOnControl(id, true); }
                            catch (Throwable error) { fail(id, "camera_reattach", error); }
                        } else if (!eligible && "recording_detach_pending".equals(state)) {
                            state = "prepared";
                            sendStatus(id, state, "shared Surface detach was confirmed");
                        }
                    });
            return false;
        }
        if (surfacesDrained) {
            attached = false;
            readerDetachPending = false;
        } else if (attached) return false;
        return closeReaderLocally(reader, sharedSource, surface);
    }

    private boolean closeReaderLocally(ImageReader oldReader, MotionSharedSource oldSource,
            Surface oldSurface) {
        if (oldReader != null) oldReader.setOnImageAvailableListener(null, null);
        try {
            if (oldSource != null) oldSource.close();
            if (sharedSource == oldSource) sharedSource = null;
        } catch (Throwable error) {
            if (sessionId != null) sendError(sessionId, "shared_source_close", summary(error));
            return false;
        }
        try {
            if (oldReader != null) oldReader.close();
            if (reader == oldReader) reader = null;
        } catch (Throwable error) {
            if (sessionId != null) sendError(sessionId, "camera_reader_close", summary(error));
            return false;
        }
        try {
            if (oldSurface != null) oldSurface.release();
            if (surface == oldSurface) surface = null;
        } catch (Throwable error) {
            if (sessionId != null) sendError(sessionId, "camera_surface_release", summary(error));
            return false;
        }
        return true;
    }

    private void fail(String id, String stage, Throwable error) {
        if (!sameSession(id)) return;
        ManualCheck check = manualCheck;
        if (check != null && (check.ownsSession || check.ownsReader || check.ownsNative)) {
            completeCheck(check, MotionCheckupClient.ERROR, "check_failed", stage + ": " + summary(error), true);
            return;
        }
        lastError = stage + ": " + summary(error);
        IBinder callback = appCallback == null ? pendingAppCallback : appCallback;
        sendError(callback, id, stage, summary(error));
        beginTeardown(id, "runtime_error", true, false, false, appCallback, "error");
    }

    private ArrayList<NativeCleanupFailure> deleteNative(Throwable primaryFailure) {
        ArrayList<NativeCleanupFailure> failures = new ArrayList<>();
        if (nativeInitialized) {
            nativeInitialized = false;
            try { nativeApi.delete(); }
            catch (Throwable error) { preserveNativeCleanupFailure("native_delete", error, primaryFailure, failures); }
        }
        nativeApi = null;
        nativeInput = null;
        if (assets != null) {
            try { assets.close(); }
            catch (Throwable error) { preserveNativeCleanupFailure("asset_close", error, primaryFailure, failures); }
            assets = null;
        }
        return failures;
    }

    private void submitNativeDelete(String id, String operation, NativeDeleteCompletion completion) {
        OperationTrace trace = new OperationTrace(operationSequence.incrementAndGet(),
                operation, id, SystemClock.elapsedRealtimeNanos());
        try {
            nativeOperations.execute(() -> {
                long startedNs = SystemClock.elapsedRealtimeNanos();
                ArrayList<NativeCleanupFailure> failures = new ArrayList<>();
                Throwable failure = null;
                try { failures = deleteNative(null); }
                catch (Throwable error) { failure = error; }
                long finishedNs = SystemClock.elapsedRealtimeNanos();
                NativeDeleteResult result = new NativeDeleteResult(trace.id, trace.queuedNs,
                        startedNs, finishedNs, failures, failure);
                control.post(() -> {
                    logNativeOperation(id, operation, result.operationId, result.queuedNs,
                            result.startedNs, result.finishedNs, 0L, 0L,
                            result.failure == null ? "ok" : "error",
                            result.failure == null ? "" : summary(result.failure), "native_teardown");
                    completion.complete(result);
                });
            });
        } catch (Throwable error) {
            long now = SystemClock.elapsedRealtimeNanos();
            NativeDeleteResult result = new NativeDeleteResult(trace.id, trace.queuedNs,
                    now, now, new ArrayList<>(), error);
            control.post(() -> {
                logNativeOperation(id, operation, result.operationId, result.queuedNs,
                        result.startedNs, result.finishedNs, 0L, 0L, "error",
                        summary(error), "native_queue");
                completion.complete(result);
            });
        }
    }

    private static void preserveNativeCleanupFailure(String operation, Throwable failure,
            Throwable primaryFailure, ArrayList<NativeCleanupFailure> failures) {
        if (primaryFailure != null) {
            if (failure != primaryFailure) primaryFailure.addSuppressed(failure);
        } else failures.add(new NativeCleanupFailure(operation, failure));
    }

    private void recordNativeCleanupFailure(String operation, Throwable failure, Throwable primaryFailure) {
        lastError += "; " + operation + ": " + summary(failure);
        if (primaryFailure != null) {
            if (failure != primaryFailure) primaryFailure.addSuppressed(failure);
            return;
        }
        String reference = Process.myPid() + "-" + UUID.randomUUID().toString().replace("-", "");
        CameraViewingDiagnostics.failure("motion_native_cleanup_" + operation + "_ref_" + reference,
                "shell_worker", failure);
    }

    private <T> T awaitPendingResult(PendingResult<T> result, String operation) throws Exception {
        try { return result.get(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (java.util.concurrent.TimeoutException timeout) {
            result.cancel(false);
            throw new IllegalStateException(operation + " timed out after " + CONTROL_TIMEOUT_MS + " ms", timeout);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(operation + " failed", cause);
        }
    }

    private <T> T callControl(ControlCall<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call::run);
        if (Looper.myLooper() == control.getLooper()) return call.run();
        if (!control.post(task)) throw new IllegalStateException("motion control thread unavailable");
        try { return task.get(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(false);
            throw new IllegalStateException("motion control result uncertain after timeout", timeout);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            CameraViewingDiagnostics.failure("motion_control_dispatch", "shell_worker", cause);
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Motion control operation failed", cause);
        }
    }

    private synchronized void ensureMediaBridge() throws Exception {
        if (mediaBridge != null) return;
        MotionShellMediaContext nextContext = new MotionShellMediaContext(systemContext, appUid,
                MotionShellMediaContext.OWNER_PACKAGE, new File(MotionDetectorProtocol.STATE_ROOT));
        try {
            MotionMediaLibrary nextLibrary = new MotionMediaLibrary(nextContext);
            MotionMediaIndexBridge nextBridge = new MotionMediaIndexBridge(nextLibrary);
            mediaContext = nextContext;
            mediaLibrary = nextLibrary;
            mediaBridge = nextBridge;
        } catch (Throwable failure) {
            nextContext.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw new RuntimeException(failure);
        }
    }

    private static MotionMediaLibrary.Query readMediaQuery(Parcel data) {
        boolean hasStart = readFlag(data);
        long start = hasStart ? data.readLong() : 0L;
        boolean hasEnd = readFlag(data);
        long end = hasEnd ? data.readLong() : 0L;
        String volumeId = data.readString();
        int sort = data.readInt();
        if ((hasStart && start < 0) || (hasEnd && end < 0)
                || hasStart && hasEnd && start > end
                || volumeId != null && !volumeId.isEmpty() && !validVolumeId(volumeId)
                || sort < 0 || sort > 1)
            throw new IllegalArgumentException("invalid Motion media query fields");
        return new MotionMediaLibrary.Query(hasStart ? start : null, hasEnd ? end : null,
                volumeId == null || volumeId.isEmpty() ? null : volumeId,
                sort == 0 ? MotionMediaLibrary.Sort.NEWEST_FIRST : MotionMediaLibrary.Sort.OLDEST_FIRST);
    }

    private static boolean validVolumeId(String volumeId) {
        return volumeId != null && volumeId.length() <= 128
                && volumeId.matches("[A-Za-z0-9_-]{1,128}");
    }

    private void requireSession(String id) {
        if (!sameSession(id) || shuttingDown || sessionRuntime.suspended())
            throw new IllegalStateException("motion session expired");
    }

    static boolean operationIsCurrent(String operationSession, long operationGeneration,
            String currentSession, long currentGeneration) {
        return operationSession != null && operationSession.equals(currentSession)
                && operationGeneration == currentGeneration;
    }

    private boolean sameSession(String id) { return id != null && id.equals(sessionId); }

    private void sendPrepared(String id) {
        logEvent("motion_worker_prepared", id, "process_id", Process.myPid());
        IBinder target = appCallback;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(binder);
            data.writeInt(MotionDetectorProtocol.VERSION);
            data.writeInt(BuildConfig.VERSION_CODE);
            data.writeInt(Process.myPid());
            target.transact(MotionDetectorProtocol.CB_PREPARED, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) { detachUiOnControl(id, target); }
        finally { data.recycle(); }
    }

    private void sendStatus(String id, String next, String detail) {
        sendStatus(appCallback, id, next, detail);
    }

    private void sendConfigured(IBinder target, String requestToken,
            MotionSessionConfigStore.Snapshot snapshot) {
        if (target == null || !target.isBinderAlive()
                || !MotionDetectorProtocol.validSession(requestToken) || snapshot == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeInt(MotionDetectorProtocol.VERSION);
            data.writeInt(BuildConfig.VERSION_CODE);
            data.writeInt(Process.myPid());
            data.writeString(requestToken);
            data.writeString(snapshot.sessionId);
            data.writeString(snapshot.config.toJson());
            target.transact(MotionDetectorProtocol.CB_CONFIGURED, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) { }
        finally { data.recycle(); }
    }

    private void sendStatus(IBinder target, String id, String next, String detail) {
        logEvent("motion_status", id, "state", next, "detail", detail);
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeString(next);
            data.writeString(detail);
            data.writeLong(SystemClock.elapsedRealtime());
            target.transact(MotionDetectorProtocol.CB_STATUS, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void sendDetection(String id, int level, long elapsedMs, int raw, int nativeState,
            long frame, long frameTimeNs, long warningUntil) {
        logEvent("motion_detection", id, "level", level, "elapsed_ms", elapsedMs,
                "raw_result", raw, "native_state", nativeState, "frame", frame,
                "frame_timestamp_ns", frameTimeNs, "warning_until_ms", warningUntil);
        IBinder target = appCallback;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeInt(level);
            data.writeLong(elapsedMs);
            data.writeInt(raw);
            data.writeInt(nativeState);
            data.writeLong(frame);
            data.writeLong(frameTimeNs);
            data.writeLong(warningUntil);
            target.transact(MotionDetectorProtocol.CB_DETECTION, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void sendError(String id, String stage, String detail) {
        sendError(appCallback, id, stage, detail);
    }

    private void sendError(IBinder target, String id, String stage, String detail) {
        logEvent("motion_error", id, "stage", stage, "detail", detail);
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeString(stage);
            data.writeString(detail);
            data.writeLong(SystemClock.elapsedRealtime());
            target.transact(MotionDetectorProtocol.CB_ERROR, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            if (target == appCallback) detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void sendScreen(String id, String screenState, String detail) {
        logEvent("motion_screen", id, "state", screenState, "detail", detail);
        IBinder target = appCallback;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(id);
            data.writeString(screenState);
            data.writeString(detail);
            data.writeLong(SystemClock.elapsedRealtime());
            target.transact(MotionDetectorProtocol.CB_SCREEN, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            detachUiOnControl(id, target);
        } finally { data.recycle(); }
    }

    private void logEvent(String kind, String id, Object... fields) {
        try {
            org.json.JSONObject event = new org.json.JSONObject().put("kind", kind)
                    .put("session_id", id == null ? "" : id)
                    .put("process_id", Process.myPid())
                    .put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("wall_ms", System.currentTimeMillis());
            for (int i = 0; i + 1 < fields.length; i += 2)
                event.put(String.valueOf(fields[i]), fields[i + 1]);
            System.out.println(event.toString());
            System.out.flush();
        } catch (Throwable error) {
            System.err.println("motion_log_failure kind=" + kind + " error=" + summary(error));
        }
    }

    private static boolean readFlag(Parcel data) {
        int value = data.readInt();
        if (value != 0 && value != 1) throw new IllegalArgumentException("flag must be 0 or 1");
        return value == 1;
    }

    private static boolean validReason(String reason) {
        return reason != null && reason.length() <= 64
                && reason.matches("[A-Za-z0-9_.-]*");
    }

    private static IBinder serviceBinder(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private interface ControlCall<T> { T run() throws Exception; }

    static final class MotionNativeSetup {
        final Context context;
        ApplicationInfo info;
        AssetManager assets;
        int modelValue;
        int mirrorSetting;
        int sensitivity;
        String stage = "oem_package";

        MotionNativeSetup(Context context) { this.context = context; }

        void prepare() throws Exception {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    MotionDetectorProtocol.OEM_PACKAGE, 0);
            info = context.getPackageManager().getApplicationInfo(MotionDetectorProtocol.OEM_PACKAGE, 0);
            System.out.println("motion_oem_package version=" + packageInfo.versionCode
                    + " sourceDir=" + info.sourceDir + " nativeLibraryDir=" + info.nativeLibraryDir);
            if (info.sourceDir == null || info.sourceDir.isEmpty()
                    || info.nativeLibraryDir == null || info.nativeLibraryDir.isEmpty())
                throw new IOException("OEM package has no APK or native-library path: sourceDir="
                        + info.sourceDir + " nativeLibraryDir=" + info.nativeLibraryDir);

            stage = "oem_model_value";
            modelValue = parseModel(runCommand("/system/bin/content", "query", "--user", "0",
                    "--uri", "content://carsettings/config", "--projection", "value",
                    "--where", "key='model_value'"));
            stage = "oem_mirror_setting";
            mirrorSetting = parseSetting(runCommand("/system/bin/settings", "--user", "0",
                    "get", "global", "sentrymode_rearview_status"), 0, 1);
            stage = "oem_sensitivity_setting";
            int sensitivitySetting = parseSetting(runCommand("/system/bin/settings", "--user", "0",
                    "get", "global", "sentrymode_sensitivity_level"), 2, 2);
            sensitivity = 3 - sensitivitySetting;

            stage = "oem_assets";
            assets = AssetManager.class.getDeclaredConstructor().newInstance();
            int assetPath = (Integer) AssetManager.class.getMethod("addAssetPath", String.class)
                    .invoke(assets, info.sourceDir);
            if (assetPath == 0) throw new IOException("OEM APK assets unavailable: " + info.sourceDir);
            // The installed native detector selects its own model asset during init.

            for (String name : new String[] { "libc++_shared.so", "libSNPE.so", "libnative-lib-motion.so" }) {
                stage = "native_load:" + name;
                String path = new File(info.nativeLibraryDir, name).getPath();
                System.out.println("motion_native_load_begin path=" + path);
                System.out.flush();
                System.load(path);
                System.out.println("motion_native_load_complete path=" + path);
                System.out.flush();
            }
        }

        private static String runCommand(String... command) throws Exception {
            java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS))
                    throw new IOException("configuration read timed out: " + String.join(" ", command));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    for (int n; (n = input.read(buffer)) != -1;) {
                        if (bytes.size() + n > 8192) throw new IOException("configuration response too large");
                        bytes.write(buffer, 0, n);
                    }
                }
                if (process.exitValue() != 0)
                    throw new IOException("configuration command failed: " + String.join(" ", command)
                            + " exit=" + process.exitValue() + " output=" + bytes.toString("UTF-8").trim());
                return bytes.toString("UTF-8").trim();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                process.getInputStream().close();
                process.getOutputStream().close();
                process.getErrorStream().close();
            }
        }

        static int parseModel(String text) throws IOException {
            Matcher row = Pattern.compile("Row: 0 value=([0-9]+)").matcher(text.trim());
            if (!row.matches()) throw new IOException("model_value missing or ambiguous: " + text);
            return parseBounded(row.group(1), 1, Integer.MAX_VALUE);
        }

        private static int parseSetting(String text, int absentDefault, int max) throws IOException {
            String value = text.trim();
            return "null".equals(value) ? absentDefault : parseBounded(value, 0, max);
        }

        private static int parseBounded(String value, int min, int max) throws IOException {
            if (!value.matches("[0-9]+")) throw new IOException("invalid OEM setting value: " + value);
            int result;
            try { result = Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new IOException("invalid OEM setting value: " + value, error); }
            if (result < min || result > max) throw new IOException("OEM setting out of range: value="
                    + value + " expected=" + min + ".." + max);
            return result;
        }
    }
}
