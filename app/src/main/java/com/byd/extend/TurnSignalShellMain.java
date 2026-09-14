package com.byd.extend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TurnSignalShellMain {
    private static final String RECOVERY_COMPONENT =
            "com.byd.extend/com.byd.extend.ShellRecoveryReceiver";

    private TurnSignalShellMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
                "usage: TurnSignalShellMain <appUid> <apkPath> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[2]);
        OwnerLock owner = OwnerLock.acquire();
        if (owner == null) return;
        Looper looper = prepareMainLooperForShell();
        Context context = systemContext();
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
            System.out.flush();
            Looper.loop();
        } finally {
            binder.stop();
            System.out.flush();
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
        private final int appUid;
        private final int versionCode;
        private final TurnSignalGuardRuntime runtime;
        private final BlindSpotWarningRuntime warningRuntime;
        private final ReverseGearRuntime reverseGearRuntime;
        private final MusicVisualizerRuntime musicRuntime;
        private final ParkingRadarRuntime parkingRadarRuntime;
        private final AvasRuntime avasRuntime;
        private final String avasInitializationError;
        private final PowerManager powerManager;
        private final Runnable processTerminator;
        private final ExecutorService recoveryWorker = Executors.newSingleThreadExecutor();
        private final ExecutorService avasCloseWorker = Executors.newSingleThreadExecutor();
        private final Runnable recoveryRunnable = this::attemptRecovery;
        private final Runnable wakeCheckRunnable = this::checkDeferredRecovery;
        private final BroadcastReceiver powerReceiver;
        private final AwakeSessionState awakeSession;
        private IBinder callback;
        private IBinder.DeathRecipient callbackDeathRecipient;
        private IBinder controllerToken;
        private boolean guardEnabled;
        private boolean recoveryEnabled;
        private boolean recoveryCommandInFlight;
        private boolean awaitingControllerAttach;
        private boolean powerReceiverRegistered;
        private IBinder.DeathRecipient controllerDeathRecipient;
        private boolean stdoutFlushScheduled;
        private boolean processTerminationRequested;
        private boolean avasCloseClaimed;
        private boolean nonAvasStopped;

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
            powerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    String action = intent == null ? "" : String.valueOf(intent.getAction());
                    handler.post(() -> powerStateChanged(action));
                }
            };
            runtime = new TurnSignalGuardRuntime(
                    context, handler, this::emit, this::markStartupCleanupAttempted);
            warningRuntime = new BlindSpotWarningRuntime(context, handler, this::emit);
            reverseGearRuntime = new ReverseGearRuntime(context, handler, this::emit);
            musicRuntime = new MusicVisualizerRuntime(context, handler, this::emit);
            parkingRadarRuntime = new ParkingRadarRuntime(context, handler, this::emit);
            AvasRuntime createdAvas = null;
            String avasError = "";
            try {
                createdAvas = new AvasRuntime(
                        avasShellContext(context), appUid, this::forwardAvasEvent);
            } catch (Throwable error) {
                avasError = summary(error);
            }
            avasRuntime = createdAvas;
            avasInitializationError = avasError;
        }

        void start() {
            registerPowerReceiver();
            runtime.start();
            warningRuntime.start();
            reverseGearRuntime.start();
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
            handler.removeCallbacks(recoveryRunnable);
            handler.removeCallbacks(wakeCheckRunnable);
            unregisterPowerReceiver();
            recoveryWorker.shutdownNow();
            clearCallback();
            musicRuntime.stop();
            parkingRadarRuntime.stop();
            reverseGearRuntime.stop();
            warningRuntime.stop();
            runtime.stop();
            closeAvasOnce();
            avasCloseWorker.shutdownNow();
            saveAwakeSession();
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (!TurnSignalShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)) {
                if (reply != null) reply.writeException(new SecurityException("caller uid denied"));
                return true;
            }
            try {
                data.enforceInterface(TurnSignalShellProtocol.DESCRIPTOR);
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
                    musicRuntime.configure(data.readInt() != 0);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_PARKING_RADAR) {
                    boolean configured = data.readInt() != 0;
                    parkingRadarRuntime.configure(configured);
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
                if (code == TurnSignalShellProtocol.TX_REPORT_AVAS_STATUS) {
                    requireAvasRuntime().reportStatus();
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
                    guardEnabled = false;
                    recoveryEnabled = false;
                    reply.writeNoException();
                    handler.post(() -> {
                        musicRuntime.stop();
                        parkingRadarRuntime.stop();
                        reverseGearRuntime.stop();
                        warningRuntime.stop();
                        runtime.stop();
                        emit("shell_shutdown", "reason", "controller_request");
                        avasCloseWorker.execute(() -> {
                            try {
                                closeAvasOnce();
                            } finally {
                                terminateProcessOnce();
                            }
                        });
                    });
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SHUTDOWN_KEEPING_AVAS) {
                    guardEnabled = false;
                    recoveryEnabled = false;
                    reply.writeNoException();
                    handler.post(() -> {
                        nonAvasStopped = true;
                        if (avasRuntime != null) avasRuntime.stopAllAuditions();
                        // This helper will be reattached: keep the metadata executor and power state.
                        musicRuntime.configure(false);
                        parkingRadarRuntime.stop();
                        reverseGearRuntime.stop();
                        warningRuntime.stop();
                        runtime.stop();
                        emit("shell_shutdown", "reason", "controller_detached_avas_retained");
                    });
                    return true;
                }
                return false;
            } catch (Throwable error) {
                if (reply != null) reply.writeException(new IllegalStateException(summary(error)));
                emit("shell_transaction_error", "code", code, "error", summary(error));
                return true;
            }
        }

        private synchronized void registerCallback(IBinder value) throws RemoteException {
            if (value == null) throw new IllegalArgumentException("callback is null");
            if (callback != value) {
                IBinder.DeathRecipient recipient =
                        () -> handler.post(() -> clearCallback(value));
                value.linkToDeath(recipient, 0);
                if (avasRuntime != null) {
                    String replacedSession = avasRuntime.auditionSessionId();
                    if (TurnSignalShellProtocol.isAvasSessionAllowed(replacedSession)) {
                        handler.post(() -> avasRuntime.stopAudition(replacedSession));
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
            musicRuntime.reportStatus();
            // AVAS status emits back through this Binder; never enter its monitor while
            // registerCallback still owns the ShellBinder monitor.
            if (avasRuntime != null) handler.post(avasRuntime::reportStatus);
            emitPowerState("callback_registered", false);
        }

        private synchronized void clearCallback(IBinder value) {
            if (callback == value) {
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
                    runtime.start();
                    warningRuntime.start();
                    reverseGearRuntime.start();
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
            if (!restart) return;
            if (interactive) {
                scheduleRecovery(RECOVERY_DEATH_SETTLE_MS, "controller_died");
            } else {
                emit("controller_recovery_deferred", "reason", "android_not_interactive");
                scheduleWakeCheck();
            }
        }

        private void registerPowerReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction("android.intent.action.QUICKBOOT_POWERON");
            try {
                context.registerReceiver(powerReceiver, filter);
                powerReceiverRegistered = true;
                emit("shell_power_receiver", "registered", true);
            } catch (Throwable error) {
                emit("shell_power_receiver", "registered", false,
                        "error", summary(error));
            }
        }

        private void unregisterPowerReceiver() {
            if (!powerReceiverRegistered) return;
            powerReceiverRegistered = false;
            try {
                context.unregisterReceiver(powerReceiver);
            } catch (Throwable ignored) {
            }
        }

        private void powerStateChanged(String action) {
            boolean interactive = isInteractive();
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
                        "error", summary(error));
                return false;
            }
        }

        private boolean markStartupCleanupAttempted(long generation) {
            if (generation <= 0 || generation != awakeSession.generation) return false;
            if (awakeSession.cleanupAttemptedGeneration == generation) return true;
            long previous = awakeSession.cleanupAttemptedGeneration;
            awakeSession.cleanupAttemptedGeneration = generation;
            if (saveAwakeSession()) return true;
            awakeSession.cleanupAttemptedGeneration = previous;
            return false;
        }

        private long bootCount() {
            try {
                return Settings.Global.getInt(
                        context.getContentResolver(), Settings.Global.BOOT_COUNT);
            } catch (Throwable ignored) {
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
            System.out.println(line);
            scheduleStdoutFlush();
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
                System.out.flush();
            }, LOG_FLUSH_DELAY_MS)) {
                synchronized (this) {
                    stdoutFlushScheduled = false;
                }
                System.out.flush();
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

    /** Gives only AVAS a real shell-package attribution and shell-owned AudioManager. */
    static Context avasShellContext(Context system) throws Exception {
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
        System.out.flush();
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
