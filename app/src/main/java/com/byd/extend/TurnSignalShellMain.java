package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class TurnSignalShellMain {
    private static final String RECOVERY_COMPONENT =
            "com.byd.extend/com.byd.extend.ShellRecoveryReceiver";

    private TurnSignalShellMain() {}

    static String readCameraRendererMarker() throws Exception {
        JSONObject result = new JSONObject();
        result.put("path", "/collect2/autovideo/sdk/pano_sdk.txt");
        try (InputStream input = java.nio.file.Files.newInputStream(
                java.nio.file.Paths.get("/collect2/autovideo/sdk/pano_sdk.txt"))) {
            result.put("renderer", readCameraRendererMarkerText(input));
            result.put("status", "ok");
        } catch (java.nio.file.NoSuchFileException missing) {
            result.put("status", "missing");
        } catch (java.nio.file.AccessDeniedException | SecurityException denied) {
            result.put("status", "denied");
        } catch (java.io.IOException error) {
            result.put("status", "error");
            result.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        return result.toString();
    }

    static String readCameraRendererMarkerText(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 4096) throw new java.io.IOException("renderer marker too large");
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
                "usage: TurnSignalShellMain <appUid> <apkPath> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[2]);
        OwnerLock owner = OwnerLock.acquire();
        if (owner == null) return;
        Looper looper = prepareMainLooperForShell();
        Context context = systemContext();
        OemLogPolicy.initialize(context);
        Handler handler = new Handler(looper);
        ShellBinder binder = new ShellBinder(context, handler, appUid, versionCode);
        binder.attachInterface(null, TurnSignalShellProtocol.DESCRIPTOR);
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method addService = serviceManager.getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, TurnSignalShellProtocol.SERVICE_NAME, binder);
            binder.start();
            System.out.println("READY pid=" + Process.myPid()
                    + " protocol=" + TurnSignalShellProtocol.VERSION
                    + " build=" + versionCode);
            DiagnosticLogPolicy.flushOutput();
            Looper.loop();
        } finally {
            binder.stop();
            DiagnosticLogPolicy.flushOutput();
            owner.close();
        }
    }

    static final class ShellBinder extends Binder {
        private static final String AWAKE_SESSION_PATH =
                "/data/local/tmp/bydextend_awake_session";
        private static final long RECOVERY_DEATH_SETTLE_MS = 500;
        private static final long RECOVERY_WAKE_SETTLE_MS = 500;
        private static final long RECOVERY_WAKE_CHECK_MS = 1_000;
        private static final long RECOVERY_RETRY_MS = 5_000;
        private static final long RECOVERY_COMMAND_TIMEOUT_MS = 2_000;
        private static final long LOG_FLUSH_DELAY_MS = 250;

        private final Context context;
        private final Handler handler;
        private final HandlerThread guardThread;
        private final Handler guardHandler;
        private final int appUid;
        private final int versionCode;
        private final TurnSignalGuardRuntime runtime;
        private final BlindSpotWarningRuntime warningRuntime;
        private final ReverseGearRuntime reverseGearRuntime;
        private final ReverseSteeringRuntime reverseSteeringRuntime;
        private final MusicVisualizerRuntime musicRuntime;
        private final ParkingRadarRuntime parkingRadarRuntime;
        private final RescueButtonTelemetry rescueButtons;
        private final AvasRuntime avasRuntime;
        private final String avasInitializationError;
        private final PowerManager powerManager;
        private final Runnable processTerminator;
        private final ExecutorService recoveryWorker = Executors.newSingleThreadExecutor();
        private final ExecutorService avasCloseWorker = Executors.newSingleThreadExecutor();
        private final Runnable recoveryRunnable = this::attemptRecovery;
        private final Runnable wakeCheckRunnable = this::checkDeferredRecovery;
        private final AwakeSessionState awakeSession;
        private final Object guardEventLock = new Object();
        private final ArrayDeque<String> pendingGuardEvents = new ArrayDeque<>();
        private final CountDownLatch guardRuntimeStopped = new CountDownLatch(1);
        private boolean guardEventDrainPosted;
        private volatile boolean guardEventsClosed;
        private boolean guardShutdownRequested;
        private boolean guardShutdownFailed;
        private boolean guardShutdownCompleted;
        private long guardDetachGeneration;
        private IBinder callback;
        private IBinder.DeathRecipient callbackDeathRecipient;
        private IBinder controllerToken;
        private boolean guardEnabled;
        private boolean recoveryEnabled;
        private boolean recoveryCommandInFlight;
        private boolean awaitingControllerAttach;
        private IBinder.DeathRecipient controllerDeathRecipient;
        private boolean stdoutFlushScheduled;
        private boolean processTerminationRequested;
        private boolean avasCloseClaimed;
        private boolean nonAvasStopped;
        private boolean guardDetachPending;
        private volatile CameraRuntimeHost cameraRuntime;
        private volatile boolean appMotionTelemetry;
        private volatile boolean workerMotionTelemetry;

        ShellBinder(Context context, Handler handler, int appUid, int versionCode) {
            this(context, handler, appUid, versionCode, TurnSignalShellMain::terminateProcess);
        }

        ShellBinder(
                Context context, Handler handler, int appUid, int versionCode,
                Runnable processTerminator) {
            if (processTerminator == null) {
                throw new IllegalArgumentException("process terminator is null");
            }
            this.context = context;
            this.handler = handler;
            this.appUid = appUid;
            this.versionCode = versionCode;
            this.processTerminator = processTerminator;
            powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            awakeSession = loadAwakeSession();
            guardThread = new HandlerThread(
                    "bydextend_guard_runtime", Process.THREAD_PRIORITY_DEFAULT);
            guardThread.start();
            guardHandler = new Handler(guardThread.getLooper());
            runtime = new TurnSignalGuardRuntime(
                    context, guardHandler, this::queueGuardEvent,
                    this::markStartupCleanupAttempted);
            warningRuntime = new BlindSpotWarningRuntime(context, handler, this::emit);
            reverseGearRuntime = new ReverseGearRuntime(context, handler, this::emit);
            reverseSteeringRuntime = new ReverseSteeringRuntime(context, handler, this::emit);
            musicRuntime = new MusicVisualizerRuntime(context, handler, this::emit);
            parkingRadarRuntime = new ParkingRadarRuntime(context, handler, this::emit);
            rescueButtons = new RescueButtonTelemetry(context, handler, this::emit);
            AvasRuntime createdAvas = null;
            String avasError = "";
            try {
                createdAvas = new AvasRuntime(
                        shellContext(context), appUid, this::forwardAvasEvent);
            } catch (Throwable error) {
                avasError = summary(error);
            }
            avasRuntime = createdAvas;
            avasInitializationError = avasError;
        }

        void start() {
            runtime.start();
            warningRuntime.start();
            reverseGearRuntime.start();
            reverseSteeringRuntime.start();
            handler.post(() -> powerStateChanged("helper_start"));
            parkingRadarRuntime.start();
            if (avasRuntime != null) {
                try {
                    avasRuntime.start();
                } catch (Throwable error) {
                    emit("avas_error", "stage", "start", "error", summary(error));
                }
            } else {
                emit("avas_error", "stage", "initialize", "error", avasInitializationError);
            }
        }

        void stop() {
            rescueButtons.configure(false);
            CameraRuntimeHost cameras = cameraRuntime;
            if (cameras != null) cameras.shutdown();
            handler.removeCallbacks(recoveryRunnable);
            handler.removeCallbacks(wakeCheckRunnable);
            recoveryWorker.shutdownNow();
            clearCallback();
            musicRuntime.stop();
            parkingRadarRuntime.stop();
            reverseGearRuntime.stop();
            reverseSteeringRuntime.stop();
            warningRuntime.stop();
            stopGuardForProcess();
            closeAvasOnce();
            avasCloseWorker.shutdownNow();
            saveAwakeSession();
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            CameraRuntimeHost viewingHost = cameraRuntime;
            if (!TurnSignalShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)
                    && !(code == TurnSignalShellProtocol.TX_PING && viewingHost != null
                    && viewingHost.isRegisteredViewingCaller(Binder.getCallingUid(), Binder.getCallingPid()))
                    && !(Binder.getCallingUid() == 2000 && (code == TurnSignalShellProtocol.TX_CAMERA_RUNTIME
                    || code == TurnSignalShellProtocol.TX_SET_MOTION_TELEMETRY))) {
                if (reply != null) reply.writeException(new SecurityException("caller uid denied"));
                return true;
            }
            try {
                data.enforceInterface(TurnSignalShellProtocol.DESCRIPTOR);
                if (code == TurnSignalShellProtocol.TX_READ_CAMERA_RENDERER_MARKER) {
                    if (data.dataAvail() != 0) throw new IllegalArgumentException("marker request takes no arguments");
                    String marker = readCameraRendererMarker();
                    reply.writeNoException();
                    reply.writeString(marker);
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CAMERA_RUNTIME) {
                    CameraRuntimeHost cameras;
                    boolean created;
                    synchronized (this) {
                        created = cameraRuntime == null || cameraRuntime.isStopped();
                        if (created) cameraRuntime = new CameraRuntimeHost(
                                context, appUid, line -> {
                                    if (DiagnosticLogPolicy.shouldPersist(DiagnosticLogPolicy.kind(line))) {
                                        DiagnosticLogPolicy.print(line);
                                        scheduleStdoutFlush();
                                    }
                                }, this::platformSignal);
                        if (created && avasRuntime != null)
                            cameraRuntime.seedCameraPowerState(avasRuntime.cameraPowerStateSnapshot());
                        cameras = cameraRuntime;
                    }
                    if (created && avasRuntime != null) handler.post(() -> {
                        if (cameraRuntime == cameras && !cameras.isStopped())
                            avasRuntime.setCameraTelemetryEnabled(true);
                    });
                    int cameraOperation = cameras.transact(data, reply);
                    if (cameraOperation == CameraRuntimeHost.ATTACH
                            || cameraOperation == CameraRuntimeHost.MOTION_BIND_WORKER) handler.post(() -> {
                        runtime.reportStatus(); warningRuntime.reportStatus();
                        reverseGearRuntime.reportStatus(); reverseSteeringRuntime.reportStatus();
                        emitPowerState("camera_runtime", false);
                    });
                    if (cameraOperation == CameraRuntimeHost.STOP) {
                        synchronized (this) {
                            if (cameraRuntime == cameras) {
                                cameraRuntime = null;
                            }
                        }
                        if (avasRuntime != null) handler.post(() -> {
                            CameraRuntimeHost current = cameraRuntime;
                            if (current == null || current.isStopped())
                                avasRuntime.setCameraTelemetryEnabled(false);
                        });
                    }
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_LOGGING) {
                    int enabled = data.readInt();
                    if (enabled != 0 && enabled != 1) throw new IllegalArgumentException("invalid logging flag");
                    DiagnosticLogPolicy.configure(enabled == 1);
                    CameraRuntimeHost cameras = cameraRuntime;
                    if (cameras != null) cameras.loggingChanged();
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_RESCUE) {
                    int enabled = data.readInt();
                    if ((enabled != 0 && enabled != 1) || data.dataAvail() != 0)
                        throw new IllegalArgumentException("invalid rescue subscription flag");
                    rescueButtons.configure(enabled == 1);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_HAZARD_THANKS) {
                    int enabled = data.readInt();
                    int flashes = data.readInt();
                    if ((enabled != 0 && enabled != 1) || flashes < 1 || flashes > 3
                            || data.dataAvail() != 0)
                        throw new IllegalArgumentException("invalid thank-you flash configuration");
                    runtime.configureHazardThanks(enabled == 1, flashes);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_START_HAZARD_THANKS) {
                    String source = data.readString();
                    long requestedAtMs = data.readLong();
                    if (source == null || source.length() > 128 || requestedAtMs < 0
                            || requestedAtMs > SystemClock.elapsedRealtime() || data.dataAvail() != 0)
                        throw new IllegalArgumentException("invalid thank-you flash source");
                    runtime.startHazardThanks(source, requestedAtMs);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SET_MOTION_TELEMETRY) {
                    boolean workerCall = Binder.getCallingUid() == 2000;
                    if (workerCall) {
                        String session = data.readString();
                        IBinder worker = data.readStrongBinder();
                        CameraRuntimeHost cameras = cameraRuntime;
                        if (cameras == null || !cameras.ownsMotionWorker(session, worker))
                            throw new SecurityException("Motion telemetry owner mismatch");
                    }
                    int enabled = data.readInt();
                    if ((enabled != 0 && enabled != 1) || data.dataAvail() != 0)
                        throw new IllegalArgumentException("invalid Motion telemetry demand");
                    if (workerCall) workerMotionTelemetry = enabled == 1;
                    else appMotionTelemetry = enabled == 1;
                    requireAvasRuntime().setMotionTelemetryEnabled(appMotionTelemetry || workerMotionTelemetry);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_PING) {
                    reply.writeNoException();
                    reply.writeInt(TurnSignalShellProtocol.VERSION);
                    reply.writeInt(versionCode);
                    reply.writeInt(Process.myPid());
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_REGISTER_CALLBACK) {
                    registerCallback(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_GUARD) {
                    boolean enabled = data.readInt() != 0;
                    float outward = data.readFloat();
                    float center = data.readFloat();
                    int correctionDelay = data.readInt();
                    int maxSpeed = data.readInt();
                    int initialLatchState = data.readInt();
                    handler.post(() -> {
                        guardEnabled = enabled;
                        runtime.configure(enabled, outward, center,
                                correctionDelay, maxSpeed, initialLatchState);
                        runtime.vehiclePowerStateChanged(
                                awakeSession.interactive,
                                awakeSession.generation,
                                awakeSession.cleanupAttemptedGeneration);
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_MUSIC) {
                    boolean enabled = data.readInt() != 0;
                    boolean captureFocusOnOpen = data.readInt() != 0;
                    boolean engineVisualizationEnabled = data.readInt() != 0;
                    musicRuntime.configure(enabled, captureFocusOnOpen,
                            engineVisualizationEnabled);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_MUSIC_FOREGROUND) {
                    String packageName = data.readString();
                    if (packageName == null || !packageName.isEmpty()
                            && !MusicMediaKeyPolicy.isValidPackage(packageName)) {
                        throw new IllegalArgumentException("invalid foreground package");
                    }
                    musicRuntime.noteForegroundPackage(packageName);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_DISPATCH_MUSIC_KEY) {
                    String foregroundPackage = data.readString();
                    int rawKeyCode = data.readInt();
                    int action = data.readInt();
                    int repeatCount = data.readInt();
                    long downTime = data.readLong();
                    long eventTime = data.readLong();
                    if (!MusicMediaKeyPolicy.isValidCommand(foregroundPackage, rawKeyCode,
                            action, repeatCount, downTime, eventTime)) {
                        throw new IllegalArgumentException("invalid music media-key command");
                    }
                    boolean handled = musicRuntime.dispatchMediaKey(foregroundPackage,
                            rawKeyCode, action, repeatCount, downTime, eventTime);
                    reply.writeNoException();
                    reply.writeInt(handled ? 1 : 0);
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_PARKING_RADAR) {
                    boolean configured = data.readInt() != 0;
                    parkingRadarRuntime.configure(configured);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_REVERSE_STEERING) {
                    String model = data.readString();
                    ReverseSteeringModelConfig config = model == null ? null
                            : new ReverseSteeringModelConfig(data.readFloat(), data.readFloat(), model);
                    reverseSteeringRuntime.configure(config);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_AVAS) {
                    AvasConfig config = AvasConfig.parse(data.readString());
                    requireAvasRuntime().configure(config);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_INSTALL_AVAS_ASSET) {
                    String assetId = data.readString();
                    if (!TurnSignalShellProtocol.isAvasAssetAllowed(assetId)) {
                        throw new IllegalArgumentException("invalid AVAS asset id");
                    }
                    ParcelFileDescriptor descriptor =
                            ParcelFileDescriptor.CREATOR.createFromParcel(data);
                    try {
                        requireAvasRuntime().installAsset(assetId, descriptor);
                        descriptor = null; // AvasRuntime owns and closes an accepted descriptor.
                    } finally {
                        if (descriptor != null) descriptor.close();
                    }
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_START_AVAS_MANUAL) {
                    String profileId = requireAvasProfile(data.readString());
                    requireAvasRuntime().startManual(profileId);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_STOP_AVAS_MANUAL) {
                    String profileId = requireAvasProfile(data.readString());
                    requireAvasRuntime().stopManual(profileId);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_START_AVAS_ENGINE) {
                    requireAvasRuntime().startEngine();
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_STOP_AVAS_ENGINE) {
                    requireAvasRuntime().stopEngine();
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_TOGGLE_AVAS_ENGINE) {
                    requireAvasRuntime().toggleEngine();
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_REPORT_AVAS_STATUS) {
                    requireAvasRuntime().reportStatus();
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_START_AVAS_MICROPHONE) {
                    String sessionId = data.readString();
                    if (!TurnSignalShellProtocol.isAvasSessionAllowed(sessionId)) {
                        throw new IllegalArgumentException("invalid AVAS microphone session");
                    }
                    ParcelFileDescriptor descriptor =
                            ParcelFileDescriptor.CREATOR.createFromParcel(data);
                    try {
                        int format = data.readInt();
                        int volume = data.readInt();
                        if (!TurnSignalShellProtocol.isAvasMicrophoneFormatAllowed(format)) {
                            throw new IllegalArgumentException("invalid AVAS microphone format");
                        }
                        if (!TurnSignalShellProtocol.isAvasMicrophoneVolumeAllowed(volume)) {
                            throw new IllegalArgumentException("invalid AVAS microphone volume");
                        }
                        requireAvasRuntime().startMicrophone(sessionId, descriptor, format, volume);
                        descriptor = null; // AvasRuntime owns the bounded PCM pipe.
                    } finally {
                        if (descriptor != null) descriptor.close();
                    }
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_STOP_AVAS_MICROPHONE) {
                    String sessionId = data.readString();
                    if (!TurnSignalShellProtocol.isAvasSessionAllowed(sessionId)) {
                        throw new IllegalArgumentException("invalid AVAS microphone session");
                    }
                    requireAvasRuntime().stopMicrophone(sessionId, "controller_stop");
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SET_AVAS_MICROPHONE_VOLUME) {
                    String sessionId = data.readString();
                    int volume = data.readInt();
                    if (!TurnSignalShellProtocol.isAvasSessionAllowed(sessionId)) {
                        throw new IllegalArgumentException("invalid AVAS microphone session");
                    }
                    if (!TurnSignalShellProtocol.isAvasMicrophoneVolumeAllowed(volume)) {
                        throw new IllegalArgumentException("invalid AVAS microphone volume");
                    }
                    requireAvasRuntime().setMicrophoneVolume(sessionId, volume);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_START_AVAS_AUDITION) {
                    String profileId = requireAvasProfile(data.readString());
                    String assetId = data.readString();
                    String sessionId = data.readString();
                    if (!TurnSignalShellProtocol.isAvasAssetAllowed(assetId)
                            || !TurnSignalShellProtocol.isAvasSessionAllowed(sessionId)) {
                        throw new IllegalArgumentException("invalid AVAS audition identity");
                    }
                    requireAvasRuntime().startAudition(profileId, assetId, sessionId);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_STOP_AVAS_AUDITION) {
                    String sessionId = data.readString();
                    if (!TurnSignalShellProtocol.isAvasSessionAllowed(sessionId)) {
                        throw new IllegalArgumentException("invalid AVAS audition session");
                    }
                    requireAvasRuntime().stopAudition(sessionId);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SET_MANUAL_STATE) {
                    int payload = data.readInt();
                    if (!TurnSignalShellProtocol.isPayloadAllowed(payload)) {
                        throw new IllegalArgumentException("payload not whitelisted");
                    }
                    runtime.setManualTurnState(payload);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_REPORT_STATUS) {
                    runtime.reportStatus();
                    warningRuntime.reportStatus();
                    reverseGearRuntime.reportStatus();
                    reverseSteeringRuntime.reportStatus();
                    musicRuntime.reportStatus();
                    parkingRadarRuntime.reportStatus();
                    if (avasRuntime != null) avasRuntime.reportStatus();
                    emitPowerState("status_report", false);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_ATTACH_CONTROLLER) {
                    attachController(data.readStrongBinder(), data.readInt() != 0);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SHUTDOWN) {
                    rescueButtons.configure(false);
                    guardEnabled = false;
                    recoveryEnabled = false;
                    reply.writeNoException();
                    handler.post(() -> {
                        CameraRuntimeHost cameras = cameraRuntime;
                        if (cameras != null) cameras.shutdown();
                        cameraRuntime = null;
                        musicRuntime.stop();
                        parkingRadarRuntime.stop();
                        reverseGearRuntime.stop();
                        reverseSteeringRuntime.stop();
                        warningRuntime.stop();
                        shutdownGuardForProcess("controller_request");
                    });
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SHUTDOWN_KEEPING_AVAS) {
                    rescueButtons.configure(false);
                    guardEnabled = false;
                    recoveryEnabled = false;
                    reply.writeNoException();
                    handler.post(() -> {
                        CameraRuntimeHost cameras = cameraRuntime;
                        boolean keepMotion = cameras != null && cameras.hasMotionWorker();
                        if (!keepMotion) {
                            if (cameras != null) cameras.shutdown();
                            cameraRuntime = null;
                            if (avasRuntime != null) avasRuntime.setCameraTelemetryEnabled(false);
                        }
                        nonAvasStopped = true;
                        guardDetachPending = true;
                        long detachGeneration = ++guardDetachGeneration;
                        if (avasRuntime != null) {
                            avasRuntime.stopAllAuditions();
                            avasRuntime.stopCurrentMicrophone("controller_detached");
                        }
                        // This helper will be reattached: keep the metadata executor and power state.
                        musicRuntime.configure(false);
                        parkingRadarRuntime.stop();
                        reverseGearRuntime.stop();
                        reverseSteeringRuntime.stop();
                        warningRuntime.stop();
                        runtime.stop(() -> handler.post(() -> {
                            drainPendingGuardEvents();
                            if (detachGeneration != guardDetachGeneration) return;
                            emit("shell_shutdown", "reason", "controller_detached_avas_retained",
                                    "motion_retained", keepMotion);
                            guardDetachPending = false;
                            if (!nonAvasStopped) runtime.start();
                        }));
                    });
                    return true;
                }
                return false;
            } catch (Throwable error) {
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_HAZARD_THANKS
                        || code == TurnSignalShellProtocol.TX_START_HAZARD_THANKS) {
                    emit("hazard_thanks_transport_error", "code", code,
                            "error", android.util.Log.getStackTraceString(error));
                }
                if (reply != null) reply.writeException(new IllegalStateException(summary(error)));
                emit("shell_transaction_error", "code", code, "error", summary(error));
                return true;
            }
        }

        private synchronized void registerCallback(IBinder value) throws RemoteException {
            if (value == null) throw new IllegalArgumentException("callback is null");
            if (callback != value) {
                rescueButtons.configure(false);
                IBinder.DeathRecipient recipient =
                        () -> handler.post(() -> clearCallback(value));
                value.linkToDeath(recipient, 0);
                if (avasRuntime != null) {
                    String replacedSession = avasRuntime.auditionSessionId();
                    if (TurnSignalShellProtocol.isAvasSessionAllowed(replacedSession)) {
                        handler.post(() -> avasRuntime.stopAudition(replacedSession));
                    }
                    String replacedMicrophone = avasRuntime.microphoneSessionId();
                    if (TurnSignalShellProtocol.isAvasSessionAllowed(replacedMicrophone)) {
                        handler.post(() -> avasRuntime.stopMicrophone(
                                replacedMicrophone, "callback_replaced"));
                    }
                }
                IBinder previous = callback;
                IBinder.DeathRecipient previousRecipient = callbackDeathRecipient;
                callback = value;
                callbackDeathRecipient = recipient;
                unlinkDeathRecipient(previous, previousRecipient);
            }
            emit("shell_callback_registered", "shell_uid", Process.myUid());
            runtime.reportStatus();
            warningRuntime.reportStatus();
            reverseGearRuntime.reportStatus();
            reverseSteeringRuntime.reportStatus();
            musicRuntime.reportStatus();
            // AVAS status emits back through this Binder; never enter its monitor while
            // registerCallback still owns the ShellBinder monitor.
            if (avasRuntime != null) handler.post(avasRuntime::reportStatus);
            emitPowerState("callback_registered", false);
        }

        private synchronized void clearCallback(IBinder value) {
            if (callback == value) {
                rescueButtons.configure(false);
                IBinder.DeathRecipient recipient = callbackDeathRecipient;
                callback = null;
                callbackDeathRecipient = null;
                unlinkDeathRecipient(value, recipient);
                // Capture the old session; a delayed death must not cancel a newer audition.
                if (avasRuntime != null) {
                    String diedSession = avasRuntime.auditionSessionId();
                    if (TurnSignalShellProtocol.isAvasSessionAllowed(diedSession)) {
                        handler.post(() -> avasRuntime.stopAudition(diedSession));
                    }
                    String diedMicrophone = avasRuntime.microphoneSessionId();
                    if (TurnSignalShellProtocol.isAvasSessionAllowed(diedMicrophone)) {
                        handler.post(() -> avasRuntime.stopMicrophone(
                                diedMicrophone, "callback_died"));
                    }
                }
            }
        }

        private void clearCallback() {
            IBinder value;
            synchronized (this) {
                value = callback;
            }
            if (value != null) clearCallback(value);
        }

        private void terminateProcessOnce() {
            synchronized (this) {
                if (processTerminationRequested) return;
                processTerminationRequested = true;
            }
            processTerminator.run();
        }

        private AvasRuntime requireAvasRuntime() {
            if (avasRuntime == null) {
                throw new IllegalStateException("AVAS unavailable: " + avasInitializationError);
            }
            return avasRuntime;
        }

        private static String requireAvasProfile(String profileId) {
            if (!TurnSignalShellProtocol.isAvasProfileAllowed(profileId)) {
                throw new IllegalArgumentException("invalid AVAS profile id");
            }
            return profileId;
        }

        private void closeAvasOnce() {
            AvasRuntime closing;
            synchronized (this) {
                if (avasCloseClaimed || avasRuntime == null) return;
                avasCloseClaimed = true;
                closing = avasRuntime;
            }
            // Runtime closure waits for playback, whose final event re-enters this Binder.
            closing.close();
        }

        private synchronized void attachController(IBinder token, boolean requestedRecovery)
                throws RemoteException {
            if (token == null) throw new IllegalArgumentException("controller token is null");
            boolean completedRecovery = controllerToken == null && recoveryEnabled;
            if (controllerToken != null) {
                controllerToken.unlinkToDeath(controllerDeathRecipient, 0);
            }
            controllerToken = token;
            recoveryEnabled = requestedRecovery;
            controllerDeathRecipient = () -> controllerDied(token);
            token.linkToDeath(controllerDeathRecipient, 0);
            emit("controller_attached", "recovery_enabled", recoveryEnabled);
            handler.post(() -> {
                musicRuntime.reconcilePowerState(
                        completedRecovery ? "controller_recovered" : "controller_attached");
                if (nonAvasStopped) {
                    nonAvasStopped = false;
                    if (!guardDetachPending) runtime.start();
                    warningRuntime.start();
                    reverseGearRuntime.start();
                    reverseSteeringRuntime.start();
                    parkingRadarRuntime.start();
                }
                awaitingControllerAttach = false;
                handler.removeCallbacks(recoveryRunnable);
                handler.removeCallbacks(wakeCheckRunnable);
                if (completedRecovery) {
                    emit("controller_recovery_complete", "reason", "controller_reattached");
                }
            });
        }

        private void controllerDied(IBinder deadToken) {
            handler.post(() -> controllerDiedOnHandler(deadToken));
        }

        private void controllerDiedOnHandler(IBinder deadToken) {
            boolean restart;
            IBinder detachedCallback;
            synchronized (this) {
                if (controllerToken != deadToken) return;
                controllerToken = null;
                controllerDeathRecipient = null;
                detachedCallback = callback;
                restart = recoveryEnabled;
            }
            if (detachedCallback != null) clearCallback(detachedCallback);
            boolean interactive = isInteractive();
            emit("controller_died", "recovery_enabled", restart,
                    "interactive", interactive,
                    "restart_requested", restart && interactive,
                    "restart_deferred", restart && !interactive);
            // Camera sleep identity belongs to the surviving helper, not UI restart policy.
            powerStateChanged("controller_died", interactive);
            if (!restart) return;
            if (interactive) {
                scheduleRecovery(RECOVERY_DEATH_SETTLE_MS, "controller_died");
            } else {
                emit("controller_recovery_deferred", "reason", "android_not_interactive");
                scheduleWakeCheck();
            }
        }

        private void platformSignal(int operation, int value) {
            handler.post(() -> {
                if (operation == CameraRuntimeHost.MOTION_UNBIND_WORKER) {
                    CameraRuntimeHost cameras = cameraRuntime;
                    if (cameras == null || !cameras.hasMotionWorker()) {
                        workerMotionTelemetry = false;
                        if (avasRuntime != null) avasRuntime.setMotionTelemetryEnabled(appMotionTelemetry);
                    }
                    return;
                }
                if (processTerminationRequested || nonAvasStopped && !workerMotionTelemetry) return;
                if (operation == CameraRuntimeHost.POWER) {
                    String action = value == CameraRuntimeHost.POWER_QUICKBOOT
                            ? "android.intent.action.QUICKBOOT_POWERON"
                            : value == CameraRuntimeHost.POWER_SCREEN_OFF ? "screen_off"
                            : value == CameraRuntimeHost.POWER_SCREEN_ON ? "screen_on"
                            : value == CameraRuntimeHost.POWER_USER_PRESENT ? "user_present" : "app_power";
                    boolean interactive = value == CameraRuntimeHost.POWER_SCREEN_OFF ? false
                            : value == CameraRuntimeHost.POWER_SCREEN_ON
                            || value == CameraRuntimeHost.POWER_USER_PRESENT || isInteractive();
                    powerStateChanged(action, interactive);
                }
                else if (operation == CameraRuntimeHost.NAV_VOLUME && avasRuntime != null)
                    avasRuntime.navigationVolumeChanged();
            });
        }

        private void powerStateChanged(String action) {
            powerStateChanged(action, isInteractive());
        }

        private void powerStateChanged(String action, boolean interactive) {
            musicRuntime.powerStateChanged(interactive);
            boolean newSession = awakeSession.update(
                    interactive,
                    "android.intent.action.QUICKBOOT_POWERON".equals(action),
                    SystemClock.elapsedRealtime());
            saveAwakeSession();
            runtime.vehiclePowerStateChanged(
                    interactive,
                    awakeSession.generation,
                    awakeSession.cleanupAttemptedGeneration);
            boolean waiting;
            synchronized (this) {
                waiting = recoveryEnabled && controllerToken == null;
            }
            emit("shell_power_state", "action", action, "interactive", interactive,
                    "recovery_waiting", waiting,
                    "awake_session_id", awakeSession.generation,
                    "new_session", newSession);
            if (!interactive) {
                handler.removeCallbacks(recoveryRunnable);
                if (waiting) {
                    emit("controller_recovery_deferred",
                            "reason", "android_not_interactive", "action", action);
                    scheduleWakeCheck();
                }
                return;
            }
            handler.removeCallbacks(wakeCheckRunnable);
            if (waiting) scheduleRecovery(RECOVERY_WAKE_SETTLE_MS, "android_wake");
        }

        private void scheduleWakeCheck() {
            handler.removeCallbacks(wakeCheckRunnable);
            handler.postDelayed(wakeCheckRunnable, RECOVERY_WAKE_CHECK_MS);
        }

        private void checkDeferredRecovery() {
            boolean waiting;
            synchronized (this) {
                waiting = recoveryEnabled && controllerToken == null;
            }
            if (!waiting) return;
            if (isInteractive()) {
                powerStateChanged("wake_poll");
            } else {
                scheduleWakeCheck();
            }
        }

        private void emitPowerState(String action, boolean newSession) {
            if (Looper.myLooper() != handler.getLooper()) {
                handler.post(() -> emitPowerState(action, newSession));
                return;
            }
            boolean waiting;
            synchronized (this) {
                waiting = recoveryEnabled && controllerToken == null;
            }
            emit("shell_power_state", "action", action,
                    "interactive", awakeSession.interactive,
                    "recovery_waiting", waiting,
                    "awake_session_id", awakeSession.generation,
                    "new_session", newSession);
        }

        private AwakeSessionState loadAwakeSession() {
            AwakeSessionState stored = null;
            try (RandomAccessFile file = new RandomAccessFile(AWAKE_SESSION_PATH, "rw")) {
                String line = file.readLine();
                if (line != null) stored = AwakeSessionState.parse(line);
            } catch (Throwable ignored) {
            }
            return AwakeSessionState.reconcile(
                    stored, bootCount(), isInteractive(), SystemClock.elapsedRealtime());
        }

        private boolean saveAwakeSession() {
            try (RandomAccessFile file = new RandomAccessFile(AWAKE_SESSION_PATH, "rw")) {
                file.setLength(0);
                file.write(awakeSession.encode().getBytes(StandardCharsets.US_ASCII));
                file.getFD().sync();
                return true;
            } catch (Throwable error) {
                emit("shell_power_state_error", "stage", "persist_session",
                        "operation", "write_awake_session", "awake_session_id", awakeSession.generation,
                        "error", android.util.Log.getStackTraceString(error));
                return false;
            }
        }

        private void markStartupCleanupAttempted(
                long generation, Consumer<Boolean> completed) {
            Runnable mark = () -> completed.accept(markStartupCleanupAttemptedOnHandler(generation));
            if (Looper.myLooper() == handler.getLooper()) {
                mark.run();
            } else if (!handler.post(mark)) {
                queueGuardEvent("startup_awake_session_cleanup_marker_dispatch_failed",
                        new Object[]{"operation", "persist_attempted_session",
                                "awake_session_id", generation,
                                "reason", "service_handler_rejected_callback"});
                completed.accept(false);
            }
        }

        private boolean markStartupCleanupAttemptedOnHandler(long generation) {
            if (generation <= 0 || generation != awakeSession.generation) return false;
            if (awakeSession.cleanupAttemptedGeneration == generation) return true;
            long previous = awakeSession.cleanupAttemptedGeneration;
            awakeSession.cleanupAttemptedGeneration = generation;
            if (saveAwakeSession()) return true;
            awakeSession.cleanupAttemptedGeneration = previous;
            return false;
        }

        private void shutdownGuardForProcess(String reason) {
            if (guardShutdownRequested) return;
            guardShutdownRequested = true;
            if (!runtime.shutdown(() -> {
                guardRuntimeStopped.countDown();
                if (!handler.post(() -> completeGuardShutdown(reason))) {
                    queueGuardEvent("turn_guard_shutdown_finalize_dispatch_failed",
                            new Object[]{"operation", "flush_terminal_events",
                                    "reason", "service_handler_rejected_callback"});
                }
            })) {
                guardShutdownFailed = true;
                guardRuntimeStopped.countDown();
                emit("turn_guard_shutdown_failed", "operation", "stop",
                        "reason", "guard_handler_rejected_shutdown");
                completeGuardShutdown(reason);
            }
        }

        private void stopGuardForProcess() {
            if (!guardShutdownRequested) {
                guardShutdownRequested = true;
                if (!runtime.shutdown(guardRuntimeStopped::countDown)) {
                    guardShutdownFailed = true;
                    guardRuntimeStopped.countDown();
                    emit("turn_guard_shutdown_failed", "operation", "stop",
                            "reason", "guard_handler_rejected_shutdown");
                }
            }
            awaitUninterruptibly(guardRuntimeStopped);
            completeGuardShutdown(null);
        }

        private void completeGuardShutdown(String reason) {
            if (guardShutdownCompleted) return;
            closeAndDrainGuardEvents();
            if (reason != null) emit("shell_shutdown", "reason", reason);
            DiagnosticLogPolicy.flushOutput();
            guardThread.quitSafely();
            joinUninterruptibly(guardThread);
            guardShutdownCompleted = true;
            if (reason != null && !guardShutdownFailed) {
                avasCloseWorker.execute(() -> {
                    try {
                        closeAvasOnce();
                    } finally {
                        terminateProcessOnce();
                    }
                });
            }
        }

        private void queueGuardEvent(String kind, Object[] fields) {
            if (guardEventsClosed || !DiagnosticLogPolicy.shouldProduce(kind)) return;
            long wallTime = System.currentTimeMillis();
            long elapsedTime = SystemClock.elapsedRealtime();
            String line = guardEventLine(kind, fields, wallTime, elapsedTime);
            boolean scheduleDrain = false;
            synchronized (guardEventLock) {
                if (guardEventsClosed) return;
                pendingGuardEvents.addLast(line);
                if (!guardEventDrainPosted) {
                    guardEventDrainPosted = true;
                    scheduleDrain = true;
                }
            }
            if (scheduleDrain && !handler.post(this::drainGuardEvents)) {
                synchronized (guardEventLock) {
                    guardEventDrainPosted = false;
                }
            }
        }

        private String guardEventLine(
                String kind, Object[] fields, long wallTime, long elapsedTime) {
            String wallTimeText = Long.toString(wallTime);
            try {
                wallTimeText = new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                        .format(new Date(wallTime));
                JSONObject json = new JSONObject();
                json.put("kind", kind);
                json.put("source", "shell_helper");
                json.put("wall_time", wallTimeText);
                json.put("t_ms", elapsedTime);
                for (int i = 0; i + 1 < fields.length; i += 2) {
                    json.put(String.valueOf(fields[i]), fields[i + 1]);
                }
                return json.toString();
            } catch (Throwable error) {
                JSONObject originalFields = new JSONObject();
                JSONObject fieldErrors = new JSONObject();
                if (fields != null) for (int i = 0; i + 1 < fields.length; i += 2) {
                    String name = guardEventFieldName(fields[i]);
                    try {
                        originalFields.put(name, fields[i + 1]);
                    } catch (Throwable fieldError) {
                        try {
                            JSONObject detail = new JSONObject();
                            detail.put("exception_class", fieldError.getClass().getName());
                            detail.put("exception_message", fieldError.getMessage() == null
                                    ? "" : fieldError.getMessage());
                            detail.put("stack_trace",
                                    android.util.Log.getStackTraceString(fieldError));
                            fieldErrors.put(name, detail);
                        } catch (Throwable ignored) {
                            // The primary serialization failure below remains available.
                        }
                    }
                }
                try {
                    JSONObject diagnostic = new JSONObject();
                    diagnostic.put("kind", "shell_event_json_error");
                    diagnostic.put("source", "shell_helper");
                    diagnostic.put("wall_time", wallTimeText);
                    diagnostic.put("t_ms", elapsedTime);
                    diagnostic.put("operation", "serialize_guard_event");
                    diagnostic.put("original_event", kind);
                    diagnostic.put("field_count", fields == null ? -1 : fields.length / 2);
                    diagnostic.put("fields", originalFields);
                    diagnostic.put("field_serialization_errors", fieldErrors);
                    diagnostic.put("exception_class", error.getClass().getName());
                    diagnostic.put("exception_message",
                            error.getMessage() == null ? "" : error.getMessage());
                    diagnostic.put("stack_trace", android.util.Log.getStackTraceString(error));
                    return diagnostic.toString();
                } catch (Throwable fallbackError) {
                    return "{\"kind\":\"shell_event_json_error\",\"source\":\"shell_helper\","
                            + "\"wall_time\":" + JSONObject.quote(wallTimeText)
                            + ",\"t_ms\":" + elapsedTime
                            + ",\"operation\":\"serialize_guard_event\",\"original_event\":"
                            + JSONObject.quote(kind) + ",\"exception_class\":"
                            + JSONObject.quote(error.getClass().getName())
                            + ",\"exception_message\":" + JSONObject.quote(
                                    error.getMessage() == null ? "" : error.getMessage())
                            + ",\"stack_trace\":" + JSONObject.quote(
                                    android.util.Log.getStackTraceString(error))
                            + ",\"fallback_exception_class\":"
                            + JSONObject.quote(fallbackError.getClass().getName())
                            + ",\"fallback_exception_message\":" + JSONObject.quote(
                                    fallbackError.getMessage() == null
                                            ? "" : fallbackError.getMessage())
                            + ",\"fallback_stack_trace\":" + JSONObject.quote(
                                    android.util.Log.getStackTraceString(fallbackError)) + "}";
                }
            }
        }

        private static String guardEventFieldName(Object value) {
            try {
                return String.valueOf(value);
            } catch (Throwable error) {
                return value == null ? "null" : value.getClass().getName();
            }
        }

        private void drainGuardEvents() {
            String line;
            synchronized (guardEventLock) {
                line = pendingGuardEvents.pollFirst();
                if (line == null) {
                    guardEventDrainPosted = false;
                    return;
                }
            }
            forwardEventLine(line);

            boolean scheduleDrain;
            synchronized (guardEventLock) {
                scheduleDrain = !pendingGuardEvents.isEmpty() && !guardEventsClosed;
                if (!scheduleDrain) guardEventDrainPosted = false;
            }
            if (scheduleDrain && !handler.post(this::drainGuardEvents)) {
                synchronized (guardEventLock) {
                    guardEventDrainPosted = false;
                }
            }
        }

        private void drainPendingGuardEvents() {
            while (true) {
                String line;
                synchronized (guardEventLock) {
                    line = pendingGuardEvents.pollFirst();
                    if (line == null) {
                        guardEventDrainPosted = false;
                        return;
                    }
                }
                forwardEventLine(line);
            }
        }

        private void closeAndDrainGuardEvents() {
            while (true) {
                String line;
                synchronized (guardEventLock) {
                    line = pendingGuardEvents.pollFirst();
                    if (line == null) {
                        guardEventsClosed = true;
                        guardEventDrainPosted = false;
                        return;
                    }
                }
                forwardEventLine(line);
            }
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private static void joinUninterruptibly(Thread thread) {
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private long bootCount() {
            try (AvasShellSettings settings = new AvasShellSettings(shellContext(context))) {
                return settings.getInt(Settings.Global.BOOT_COUNT, -1);
            } catch (Throwable error) {
                emit("shell_boot_count_failed", "operation", "read_boot_count",
                        "key", Settings.Global.BOOT_COUNT,
                        "error", android.util.Log.getStackTraceString(error));
                return -1;
            }
        }

        private boolean isInteractive() {
            try {
                return powerManager != null && powerManager.isInteractive();
            } catch (Throwable error) {
                emit("shell_power_state_error", "error", summary(error));
                return false;
            }
        }

        private void scheduleRecovery(long delayMs, String reason) {
            boolean attached;
            boolean enabled;
            synchronized (this) {
                attached = controllerToken != null;
                enabled = recoveryEnabled;
            }
            if (!shouldAttemptRecovery(enabled, attached, isInteractive(),
                    recoveryCommandInFlight)) return;
            handler.removeCallbacks(recoveryRunnable);
            handler.postDelayed(recoveryRunnable, delayMs);
            emit("controller_recovery_scheduled", "reason", reason,
                    "delay_ms", delayMs);
        }

        private void attemptRecovery() {
            boolean attached;
            boolean enabled;
            synchronized (this) {
                attached = controllerToken != null;
                enabled = recoveryEnabled;
            }
            boolean interactive = isInteractive();
            if (!shouldAttemptRecovery(enabled, attached, interactive,
                    recoveryCommandInFlight)) {
                emit("controller_recovery_skipped", "recovery_enabled", enabled,
                        "controller_attached", attached, "interactive", interactive,
                        "command_in_flight", recoveryCommandInFlight);
                return;
            }
            if (awaitingControllerAttach) {
                awaitingControllerAttach = false;
                emit("controller_attach_timeout", "timeout_ms", RECOVERY_RETRY_MS);
            }
            recoveryCommandInFlight = true;
            emit("recovery_broadcast_requested", "interactive", true);
            recoveryWorker.execute(() -> {
                RecoveryCommandResult result = runRecoveryCommand();
                handler.post(() -> finishRecoveryCommand(result));
            });
        }

        private void finishRecoveryCommand(RecoveryCommandResult result) {
            recoveryCommandInFlight = false;
            boolean attached;
            boolean enabled;
            synchronized (this) {
                attached = controllerToken != null;
                enabled = recoveryEnabled;
            }
            emit("controller_recovery_command", "ok", result.ok,
                    "exit_code", result.exitCode, "elapsed_ms", result.elapsedMs,
                    "output", result.output, "error", result.error,
                    "controller_attached", attached);
            awaitingControllerAttach = result.ok && !attached;
            if (shouldAttemptRecovery(enabled, attached, isInteractive(), false)) {
                scheduleRecovery(RECOVERY_RETRY_MS,
                        result.ok ? "controller_not_attached" : "command_failed");
            }
        }

        private static RecoveryCommandResult runRecoveryCommand() {
            long startedAt = SystemClock.elapsedRealtime();
            java.lang.Process process = null;
            try {
                process = new ProcessBuilder(recoveryCommandForTest())
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(RECOVERY_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                    return new RecoveryCommandResult(false, -1, "", "timeout",
                            SystemClock.elapsedRealtime() - startedAt);
                }
                String output = readFully(process.getInputStream()).trim();
                int exitCode = process.exitValue();
                return new RecoveryCommandResult(exitCode == 0, exitCode, output,
                        exitCode == 0 ? "" : "exit_code=" + exitCode,
                        SystemClock.elapsedRealtime() - startedAt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new RecoveryCommandResult(false, -1, "", "interrupted",
                        SystemClock.elapsedRealtime() - startedAt);
            } catch (Throwable error) {
                return new RecoveryCommandResult(false, -1, "", summary(error),
                        SystemClock.elapsedRealtime() - startedAt);
            } finally {
                if (process != null) process.destroy();
            }
        }

        static boolean shouldAttemptRecovery(
                boolean enabled, boolean attached, boolean interactive, boolean inFlight) {
            return enabled && !attached && interactive && !inFlight;
        }

        static String[] recoveryCommandForTest() {
            return new String[]{
                    "am", "broadcast", "--user", "0", "--include-stopped-packages",
                    "--receiver-foreground", "--async",
                    "-a", GuardRecovery.ACTION_SHELL_RECOVERY,
                    "-n", RECOVERY_COMPONENT
            };
        }

        private static String readFully(InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }

        private static final class RecoveryCommandResult {
            final boolean ok;
            final int exitCode;
            final String output;
            final String error;
            final long elapsedMs;

            RecoveryCommandResult(
                    boolean ok, int exitCode, String output, String error, long elapsedMs) {
                this.ok = ok;
                this.exitCode = exitCode;
                this.output = output == null ? "" : output;
                this.error = error == null ? "" : error;
                this.elapsedMs = elapsedMs;
            }
        }

        static final class AwakeSessionState {
            long bootCount;
            long generation;
            boolean interactive;
            boolean unpairedInteractiveWake;
            long lastWakeElapsedMs;
            long lastObservedElapsedMs;
            long cleanupAttemptedGeneration;

            AwakeSessionState(
                    long bootCount, long generation, boolean interactive,
                    boolean unpairedInteractiveWake,
                    long lastWakeElapsedMs, long lastObservedElapsedMs,
                    long cleanupAttemptedGeneration) {
                this.bootCount = bootCount;
                this.generation = Math.max(0, generation);
                this.interactive = interactive;
                this.unpairedInteractiveWake = unpairedInteractiveWake;
                this.lastWakeElapsedMs = Math.max(0, lastWakeElapsedMs);
                this.lastObservedElapsedMs = Math.max(0, lastObservedElapsedMs);
                this.cleanupAttemptedGeneration = Math.max(0, cleanupAttemptedGeneration);
            }

            static AwakeSessionState reconcile(
                    AwakeSessionState stored, long bootCount,
                    boolean interactive, long elapsedMs) {
                if (stored == null) {
                    return new AwakeSessionState(bootCount, interactive ? 1 : 0,
                            interactive, interactive,
                            interactive ? elapsedMs : 0, elapsedMs, 0);
                }
                boolean rebooted = bootCount >= 0 && stored.bootCount >= 0
                        && bootCount != stored.bootCount;
                if (!rebooted && elapsedMs < stored.lastObservedElapsedMs) rebooted = true;
                AwakeSessionState state = new AwakeSessionState(
                        bootCount, stored.generation, stored.interactive,
                        stored.unpairedInteractiveWake,
                        stored.lastWakeElapsedMs, elapsedMs,
                        stored.cleanupAttemptedGeneration);
                if (rebooted) {
                    state.interactive = interactive;
                    state.unpairedInteractiveWake = interactive;
                    if (interactive) {
                        state.generation++;
                        state.lastWakeElapsedMs = elapsedMs;
                    }
                } else {
                    state.update(interactive, false, elapsedMs);
                }
                return state;
            }

            boolean update(boolean nextInteractive, boolean quickboot, long elapsedMs) {
                boolean newSession = false;
                if (nextInteractive && !interactive) {
                    newSession = true;
                    unpairedInteractiveWake = true;
                } else if (!nextInteractive) {
                    unpairedInteractiveWake = false;
                }
                if (nextInteractive && quickboot) {
                    if (unpairedInteractiveWake) {
                        unpairedInteractiveWake = false;
                    } else if (!newSession) {
                        newSession = true;
                    }
                }
                if (newSession) {
                    generation++;
                    lastWakeElapsedMs = elapsedMs;
                }
                interactive = nextInteractive;
                lastObservedElapsedMs = elapsedMs;
                return newSession;
            }

            String encode() {
                return bootCount + "," + generation + "," + (interactive ? 1 : 0)
                        + "," + (unpairedInteractiveWake ? 1 : 0)
                        + "," + lastWakeElapsedMs + "," + lastObservedElapsedMs
                        + "," + cleanupAttemptedGeneration;
            }

            static AwakeSessionState parse(String value) {
                String[] fields = value == null ? new String[0] : value.trim().split(",");
                if (fields.length != 5 && fields.length != 6 && fields.length != 7) {
                    return null;
                }
                try {
                    boolean currentFormat = fields.length >= 6;
                    return new AwakeSessionState(
                            Long.parseLong(fields[0]), Long.parseLong(fields[1]),
                            "1".equals(fields[2]),
                            currentFormat && "1".equals(fields[3]),
                            Long.parseLong(fields[currentFormat ? 4 : 3]),
                            Long.parseLong(fields[currentFormat ? 5 : 4]),
                            fields.length == 7 ? Long.parseLong(fields[6]) : 0);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }

        private void emit(String kind, Object... fields) {
            if (!DiagnosticLogPolicy.shouldProduce(kind)) return;
            String line;
            try {
                JSONObject json = new JSONObject();
                json.put("kind", kind);
                json.put("source", "shell_helper");
                json.put("wall_time", new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
                json.put("t_ms", SystemClock.elapsedRealtime());
                for (int i = 0; i + 1 < fields.length; i += 2) {
                    json.put(String.valueOf(fields[i]), fields[i + 1]);
                }
                line = json.toString();
            } catch (Throwable error) {
                line = "{\"kind\":\"shell_json_error\"}";
            }
            forwardEventLine(line);
        }

        private void forwardAvasEvent(JSONObject event) {
            if (event != null) forwardEventLine(event.toString());
        }

        private void forwardEventLine(String line) {
            CameraRuntimeHost cameras = cameraRuntime;
            if (cameras != null) cameras.acceptTelemetry(line);
            if (DiagnosticLogPolicy.shouldPersist(DiagnosticLogPolicy.kind(line))) {
                DiagnosticLogPolicy.print(line);
                scheduleStdoutFlush();
            }
            IBinder target;
            synchronized (this) {
                target = callback;
            }
            if (target == null) return;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInterfaceToken(TurnSignalShellProtocol.CALLBACK_DESCRIPTOR);
                parcel.writeString(line);
                target.transact(TurnSignalShellProtocol.CB_EVENT,
                        parcel, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable error) {
                clearCallback(target);
            } finally {
                parcel.recycle();
            }
        }

        private void scheduleStdoutFlush() {
            synchronized (this) {
                if (stdoutFlushScheduled) return;
                stdoutFlushScheduled = true;
            }
            if (!handler.postDelayed(() -> {
                synchronized (ShellBinder.this) {
                    stdoutFlushScheduled = false;
                }
                DiagnosticLogPolicy.flushOutput();
            }, LOG_FLUSH_DELAY_MS)) {
                synchronized (this) {
                    stdoutFlushScheduled = false;
                }
                DiagnosticLogPolicy.flushOutput();
            }
        }

        private static void unlinkDeathRecipient(
                IBinder binder, IBinder.DeathRecipient recipient) {
            if (binder == null || recipient == null) return;
            try {
                binder.unlinkToDeath(recipient, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Real package attribution for services called by this shell-UID process. */
    static Context shellContext(Context system) throws Exception {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = threadClass.getMethod("systemMain").invoke(null);
        Context shellPackage = system.createPackageContext("com.android.shell", 0);
        Class<?> impl = Class.forName("android.app.ContextImpl");
        java.lang.reflect.Field packageInfo = impl.getDeclaredField("mPackageInfo");
        packageInfo.setAccessible(true);
        Method create = impl.getDeclaredMethod(
                "createAppContext", threadClass, Class.forName("android.app.LoadedApk"));
        create.setAccessible(true);
        return (Context) create.invoke(null, thread, packageInfo.get(shellPackage));
    }

    static Context systemContext() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = type.getMethod("systemMain").invoke(null);
        Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
        if (context == null) throw new IllegalStateException("system context unavailable");
        return context;
    }

    static Looper prepareMainLooperForShell() {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        return Looper.getMainLooper();
    }

    private static void terminateProcess() {
        DiagnosticLogPolicy.flushOutput();
        Process.killProcess(Process.myPid());
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class OwnerLock {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private OwnerLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file;
            this.channel = channel;
            this.lock = lock;
        }

        static OwnerLock acquire() throws Exception {
            RandomAccessFile file = new RandomAccessFile(TurnSignalShellProtocol.LOCK_PATH, "rw");
            FileChannel channel = file.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                file.close();
                return null;
            }
            return new OwnerLock(file, channel, lock);
        }

        void close() {
            try { lock.release(); } catch (Throwable ignored) {}
            try { channel.close(); } catch (Throwable ignored) {}
            try { file.close(); } catch (Throwable ignored) {}
        }
    }
}
