package com.byd.extend;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One-shot shell-UID screen control guarded by an observed, continuously-OFF session. */
final class MotionScreenControl implements AutoCloseable {
    interface Listener { void onState(String sessionId, int warningGeneration, String state, String detail); }
    private static final String POWER_IFACE = "android.os.IPowerManager";
    private static final String DISPLAY_IFACE = "android.hardware.display.IDisplayManager";
    private static final String ANIMATION_IFACE = "android.os.IAccAnimation";
    private static final int POWER_OFF = 0;
    private static final int DISPLAY_OFF = 1;
    private static final int DISPLAY_ON = 2;
    private static final long POLL_MS = 250L;
    private static final long WAIT_MS = 1_500L;

    private final HandlerThread thread = new HandlerThread("motion-screen-control");
    private final Handler handler;
    private final Listener stateListener;
    private final ExecutorService systemCalls = Executors.newSingleThreadExecutor(r -> {
        Thread worker = new Thread(r, "motion-screen-system-call");
        worker.setDaemon(true);
        return worker;
    });
    private boolean systemCallUncertain;
    private final Runnable observe = this::observePower;

    private String sessionId;
    private boolean eligible;
    private boolean offContinuous;
    private boolean ownsWake;
    private boolean warningActive;
    private boolean priorDisplayOn;
    private boolean priorBlackCover;
    private volatile boolean restoreCommandIssued;
    private final CellularRecovery cellular;
    private boolean coverAttempted;
    private boolean coverRemoved;
    private boolean finishAttempted;
    private volatile int warningGeneration = -1;
    private volatile boolean userTakeoverRequested;
    private volatile int takeoverGeneration = -1;
    private boolean sleepCommandIssued;
    private final MotionScreenCyclePolicy cycle = new MotionScreenCyclePolicy();

    MotionScreenControl(Listener stateListener, CellularRecovery cellular) {
        this.cellular = cellular;
        this.stateListener = stateListener;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void beginSession(String id) {
        handler.post(() -> {
            handler.removeCallbacks(observe);
            sessionId = id;
            eligible = false;
            offContinuous = false;
            ownsWake = false;
            warningActive = false;
            restoreCommandIssued = false;
            coverAttempted = false;
            coverRemoved = false;
            finishAttempted = false;
            warningGeneration = -1;
            userTakeoverRequested = false;
            takeoverGeneration = -1;
            sleepCommandIssued = false;
            cycle.beginSession();
            report("session_ready", "screen control awaits eligible camera session");
        });
    }

    void setEligible(String id, boolean next) {
        handler.post(() -> {
            if (!sameSession(id)) return;
            eligible = next;
            if (!next) {
                handler.removeCallbacks(observe);
                report("ineligible", "screen writes disabled");
                return;
            }
            // The shell worker calls this only after direct fresh telemetry completed its warm-up.
            // Do not run a second continuous vehicle-state monitor before an owned wake.
            offContinuous = true;
        });
    }

    void reconcilePriorWarning(String id, int generation) {
        handler.post(() -> {
            if (!sameSession(id)) return;
            warningGeneration = generation;
            if (!eligible || !offContinuous || !cycle.needsFreshBlackOffBaseline()) {
                reportPreflight("retry_reconcile_skipped", "no eligible completed uncertain screen cycle",
                        "power=not_checked,interactive=not_checked,display=not_checked,animation=not_checked,descriptor=not_checked");
                return;
            }
            handler.removeCallbacks(observe);
            String fresh = observeFreshBlackOffBaseline(false);
            if (fresh == null) return;
            if (!eligible || !offContinuous || hasCurrentTakeover(generation)) {
                reportPreflight("retry_reconcile_skipped",
                        "eligibility or user takeover changed during reconciliation", fresh);
                return;
            }
            cycle.confirmFreshBlackOffBaseline();
            resetLocalWarningCycle();
            report("prior_cycle_reconciled", fresh);
        });
    }

    void wakeForWarning(String id, int generation) {
        handler.post(() -> {
            if (!sameSession(id)) return;
            warningGeneration = generation;
            handler.removeCallbacks(observe);
            synchronized (this) { sleepCommandIssued = false; }
            if (takeoverGeneration == generation) {
                cycle.markUserTakeover();
                reportPreflight("wake_skipped", "PowerON or user takeover arrived before the wake request",
                        "power=not_checked,interactive=not_checked,display=not_checked,animation=not_checked,descriptor=not_checked");
                return;
            }
            if (!cycle.mayStartPreflight(eligible, offContinuous, warningActive)) {
                reportPreflight("wake_skipped", "screen preflight is not currently permitted",
                        "power=not_checked,interactive=not_checked,display=not_checked,animation=not_checked,descriptor=not_checked");
                return;
            }
            boolean rearming = cycle.needsFreshBlackOffBaseline();
            String fresh = observeFreshBlackOffBaseline(!rearming);
            if (fresh == null) return;
            if (rearming) {
                cycle.confirmFreshBlackOffBaseline();
                resetLocalWarningCycle();
                report("black_baseline_observed", fresh);
            }
            if (!cycle.mayWriteWake()) {
                reportPreflight("wake_skipped", "screen cycle remains uncertain", fresh);
                return;
            }
            try {
                synchronized (this) {
                    if (takeoverGeneration == generation) {
                        cycle.markUserTakeover();
                        reportPreflight("wake_skipped", "PowerON or user takeover arrived before the wake request", fresh);
                        return;
                    }
                    cycle.beginWakeAttempt(); // No repeat unless a full black/off baseline is observed.
                }
                warningActive = true;
                restoreCommandIssued = false;
                if (priorDisplayOn) {
                    handler.postDelayed(observe, POLL_MS);
                    report("screen_ready", "prior_display_on=true; prior_black_cover=" + priorBlackCover + "; no_wake_sent=true");
                    return;
                }
                boolean wakeSent = controlCall("wakeUp reason=13", () -> {
                    if (takeoverRequested() || readPowerRaw() != POWER_OFF)
                        throw new IllegalStateException("Motion screen ownership changed before wake");
                    boolean interactive = isInteractiveRaw();
                    int display = displayStateRaw();
                    String cover = animationStatus();
                    // Another app may have woken the tablet since preflight; never claim its wake.
                    if (interactive && display == DISPLAY_ON && ("0".equals(cover) || "1".equals(cover))) {
                        priorDisplayOn = true;
                        priorBlackCover = "1".equals(cover);
                        return false;
                    }
                    if (interactive || display != DISPLAY_OFF || !"1".equals(cover))
                        throw new IllegalStateException("Motion black/OFF state changed before wake");
                    Object power = service(POWER_IFACE);
                    Class<?> api = Class.forName(POWER_IFACE);
                    api.getMethod("wakeUp", long.class, int.class, String.class, String.class)
                            .invoke(power, SystemClock.uptimeMillis(), 13,
                                    "byd-extend-motion-warning", "com.android.shell");
                    return true;
                });
                if (!wakeSent) {
                    handler.postDelayed(observe, POLL_MS);
                    report("screen_ready", "prior_display_on=true; prior_black_cover=" + priorBlackCover + "; no_wake_sent=true");
                    return;
                }
                long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!offContinuous || cycle.userTookOver() || !eligible) break;
                    if (readPower() != POWER_OFF) {
                        offContinuous = false;
                        break;
                    }
                    if (isInteractive() && displayState() == DISPLAY_ON) {
                        ownsWake = true;
                        handler.removeCallbacks(observe);
                        handler.postDelayed(observe, POLL_MS);
                        report("wake_requested", "reason=13; app warning window may now be shown");
                        return;
                    }
                    SystemClock.sleep(50L);
                }
                cycle.markUncertain();
                reportPreflight("wake_uncertain", "wake returned without a confirmed interactive transition", fresh);
            } catch (Throwable error) {
                cycle.markUncertain();
                reportPreflight("wake_uncertain", summary(error), fresh);
            }
        });
    }

    private String observeFreshBlackOffBaseline(boolean allowAlreadyOn) {
        String power = "not_checked";
        String interactive = "not_checked";
        String display = "not_checked";
        String animation = "not_checked";
        String descriptor = "not_checked";
        try {
            requireShellUid();
            int powerState = readPower();
            power = Integer.toString(powerState);
            if (powerState != POWER_OFF) {
                offContinuous = false;
                cycle.markUncertain();
                reportPreflight("wake_skipped", "vehicle Power is not OFF", freshChecks(
                        power, interactive, display, animation, descriptor));
                return null;
            }
            boolean isInteractive = isInteractive();
            interactive = Boolean.toString(isInteractive);
            int displayState = displayState();
            display = Integer.toString(displayState);
            animation = animationStatus();
            descriptor = animationDescriptor();
            boolean offBlack = !isInteractive && displayState == DISPLAY_OFF && "1".equals(animation);
            boolean alreadyOn = isInteractive && displayState == DISPLAY_ON
                    && ("0".equals(animation) || "1".equals(animation));
            if ((!offBlack && !(allowAlreadyOn && alreadyOn)) || !ANIMATION_IFACE.equals(descriptor)) {
                cycle.markUncertain();
                reportPreflight("wake_skipped", "fresh OFF/black preflight was not confirmed",
                        freshChecks(power, interactive, display, animation, descriptor));
                return null;
            }
            priorDisplayOn = alreadyOn;
            priorBlackCover = "1".equals(animation);
            return freshChecks(power, interactive, display, animation, descriptor);
        } catch (Throwable failure) {
            cycle.markUncertain();
            reportPreflight("wake_uncertain", summary(failure),
                    freshChecks(power, interactive, display, animation, descriptor));
            return null;
        }
    }

    private static String freshChecks(String power, String interactive, String display,
            String animation, String descriptor) {
        return "power=" + power + ",interactive=" + interactive + ",display=" + display
                + ",animation=" + animation + ",descriptor=" + descriptor;
    }

    private void reportPreflight(String state, String reason, String freshChecks) {
        report(state, "reason=" + reason + "; eligible=" + eligible
                + "; offContinuous=" + offContinuous + "; ownsWake=" + ownsWake
                + "; uncertain=" + cycle.uncertain() + "; wakeAttempted=" + cycle.wakeAttempted()
                + "; takeover=" + cycle.userTookOver() + "; takeoverRequested=" + userTakeoverRequested
                + "; session=" + sessionId + "; warningGeneration=" + warningGeneration
                + "; fresh=" + freshChecks);
    }

    /** Called only after the app-owned fullscreen warning window reports that it is ready. */
    void warningWindowReady(String id, int generation) {
        handler.post(() -> {
            if (!sameWarning(id, generation) || !eligible || !warningActive || cycle.userTookOver()
                    || cycle.uncertain() || coverAttempted) {
                report("cover_release_skipped", "screen session is not safely owned or command already attempted");
                return;
            }
            coverAttempted = true;
            try {
                if (!offContinuous || readPower() != POWER_OFF || !isInteractive()
                        || displayState() != DISPLAY_ON || !(priorBlackCover ? "1" : "0").equals(animationStatus())) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("cover_release_skipped", "warning-window/vehicle state is not confirmed");
                    return;
                }
                if (!priorBlackCover) {
                    report("cover_release_requested", "prior_black_cover=false; no_cover_command=true; warning ready");
                    return;
                }
                int result = animationCommand(3);
                if (result != 0 || !"0".equals(animationStatus())) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("cover_release_uncertain", "transaction 3 result/state was not confirmed");
                    return;
                }
                coverRemoved = true;
                report("cover_release_requested", "transaction=3; result=0; physical visibility is not inferred");
            } catch (Throwable error) {
                cycle.markUncertain();
                stopPowerObservation();
                report("cover_release_uncertain", summary(error));
            }
        });
    }

    void finishWarning(String id, int generation, boolean restoreAllowed) {
        handler.post(() -> {
            if (!sameWarning(id, generation) || finishAttempted) {
                report("sleep_skipped", "session mismatch or finish already attempted");
                return;
            }
            finishAttempted = true;
            if (takeoverRequested()) {
                cycle.markUserTakeover();
                ownsWake = false;
                stopPowerObservation();
                report("user_dismissed_no_sleep", "this cycle will not sleep; later wake requires fresh black/off confirmation");
                return;
            }
            if (!restoreAllowed || !eligible || !warningActive || !offContinuous || cycle.uncertain()) {
                cycle.markUncertain();
                stopPowerObservation();
                report("sleep_skipped", "warning stop is not an owned, continuously-OFF session");
                return;
            }
            if (priorBlackCover && !coverRemoved) {
                cycle.markUncertain();
                stopPowerObservation();
                report("sleep_skipped", "screen cover was not confirmed released for this warning");
                return;
            }
            try {
                if (readPower() != POWER_OFF || !isInteractive() || displayState() != DISPLAY_ON
                        || !"0".equals(animationStatus())) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("sleep_skipped", "current vehicle/display state is not confirmed");
                    return;
                }
                synchronized (this) {
                    if (takeoverRequested()) {
                        cycle.markUserTakeover();
                        ownsWake = false;
                        stopPowerObservation();
                        report("user_dismissed_no_sleep", "user took over before the owned sleep command");
                        return;
                    }
                }
                boolean slept = controlCall("goToSleep reason=13 flags=1", () -> {
                    // Serialized with the recovery switch at the actual system-call boundary.
                    synchronized (cellular) {
                        if (!MotionScreenOwnership.maySleep(priorDisplayOn, ownsWake, cellular.enabled())) return false;
                        if (takeoverRequested() || readPowerRaw() != POWER_OFF)
                            throw new IllegalStateException("Motion screen ownership changed before sleep");
                        synchronized (this) { sleepCommandIssued = true; }
                        Object power = service(POWER_IFACE);
                        Class.forName(POWER_IFACE).getMethod("goToSleep", long.class, int.class, int.class)
                                .invoke(power, SystemClock.uptimeMillis(), 13, 1);
                        return true;
                    }
                });
                if (!slept) {
                    restoreWithoutSleep();
                    return;
                }
                long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
                boolean off = false;
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!offContinuous || takeoverRequested() || readPower() != POWER_OFF) break;
                    if (!isInteractive() && displayState() == DISPLAY_OFF) {
                        off = true;
                        break;
                    }
                    SystemClock.sleep(50L);
                }
                if (!off) {
                    if (takeoverRequested()) {
                        cycle.markUserTakeover();
                        ownsWake = false;
                        stopPowerObservation();
                        report("user_dismissed_no_sleep", "user took over during the owned sleep transition");
                        return;
                    }
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("sleep_uncertain", "sleep transition was not observed; transaction 2 was not sent");
                    return;
                }
                if (takeoverRequested() || !offContinuous || readPower() != POWER_OFF) {
                    if (takeoverRequested()) {
                        cycle.markUserTakeover();
                        ownsWake = false;
                        stopPowerObservation();
                        report("user_dismissed_no_sleep", "user or vehicle Power took over before black restoration");
                    } else {
                        cycle.markUncertain();
                        stopPowerObservation();
                        report("black_restore_uncertain", "OFF ownership was lost before transaction 2");
                    }
                    return;
                }
                int result = animationCommand(2);
                if (result != 0) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("black_restore_uncertain", "transaction 2 result was not zero");
                    return;
                }
                long restoreDeadline = SystemClock.elapsedRealtime() + WAIT_MS;
                while (SystemClock.elapsedRealtime() < restoreDeadline) {
                    if (!offContinuous || takeoverRequested() || readPower() != POWER_OFF) break;
                    if (!isInteractive() && displayState() == DISPLAY_OFF
                            && "1".equals(animationStatus())) {
                        cycle.confirmOwnedBlackRestore();
                        resetLocalWarningCycle();
                        report("black_restore_observed", "transaction=2; argument=0");
                        return;
                    }
                    SystemClock.sleep(50L);
                }
                cycle.markUncertain();
                stopPowerObservation();
                report("black_restore_uncertain", "OFF/animation restore observation timed out");
            } catch (Throwable error) {
                cycle.markUncertain();
                stopPowerObservation();
                report("finish_uncertain", summary(error));
            }
        });
    }

    private void restoreWithoutSleep() throws Exception {
        if (takeoverRequested() || !offContinuous || readPower() != POWER_OFF)
            throw new IllegalStateException("Motion screen ownership changed before restoration");
        restoreCommandIssued = true;
        if (priorBlackCover) {
            int result = animationCommand(2);
            if (result != 0) throw new IllegalStateException("accanimation restore returned " + result);
            long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (takeoverRequested() || readPower() != POWER_OFF)
                    throw new IllegalStateException("Motion screen ownership changed during restoration");
                if (isInteractive() && displayState() == DISPLAY_ON && "1".equals(animationStatus())) break;
                SystemClock.sleep(50L);
            }
            if (!isInteractive() || displayState() != DISPLAY_ON || !"1".equals(animationStatus()))
                throw new IllegalStateException("Logical-ON black restoration not confirmed");
        } else if (!isInteractive() || displayState() != DISPLAY_ON || !"0".equals(animationStatus())) {
            throw new IllegalStateException("Borrowed visible display changed before completion");
        }
        cycle.confirmOwnedBlackRestore();
        resetLocalWarningCycle();
        report("warning_restored_awake", "no_sleep_sent=true; restored_initial_black_cover=" + priorBlackCover);
    }

    void markUserTakeover(String id, int generation) {
        synchronized (this) {
            if (!sameSession(id) || generation < warningGeneration) return;
            warningGeneration = generation;
            userTakeoverRequested = true;
            takeoverGeneration = generation;
        }
        handler.post(() -> {
            if (!sameWarning(id, generation)) return;
            cycle.markUserTakeover();
            ownsWake = false;
            stopPowerObservation();
            report("user_takeover", "this cycle will not sleep; later wake requires fresh black/off confirmation");
        });
    }

    void warningActivityPaused(String id, int generation) {
        synchronized (this) {
            if (!sameWarning(id, generation) || sleepCommandIssued || restoreCommandIssued) return;
            userTakeoverRequested = true;
            takeoverGeneration = generation;
        }
        handler.post(() -> {
            if (!sameWarning(id, generation)) return;
            cycle.markUserTakeover();
            ownsWake = false;
            stopPowerObservation();
            report("user_takeover", "warning Activity paused before the owned sleep command");
        });
    }

    private void observePower() {
        if (!eligible || !warningActive || sessionId == null || cycle.uncertain() || cycle.userTookOver()) return;
        try {
            if (readPower() != POWER_OFF) offContinuous = false;
        } catch (Throwable error) {
            report("power_read_error", summary(error));
            offContinuous = false;
        }
        if (!offContinuous) {
            cycle.markUncertain();
            stopPowerObservation();
            report("off_session_lost", "vehicle Power was nonzero or unreadable");
            return;
        }
        handler.postDelayed(observe, POLL_MS);
    }

    private void stopPowerObservation() {
        handler.removeCallbacks(observe);
    }

    /** Resets one-shot details only after a verified black/off baseline. */
    private void resetLocalWarningCycle() {
        stopPowerObservation();
        ownsWake = false;
        warningActive = false;
        coverAttempted = false;
        coverRemoved = false;
        finishAttempted = false;
        offContinuous = true;
        synchronized (this) {
            userTakeoverRequested = false;
            takeoverGeneration = -1;
        }
    }

    private boolean sameWarning(String id, int generation) {
        return sameSession(id) && warningGeneration == generation;
    }

    private boolean takeoverRequested() {
        return userTakeoverRequested || cycle.userTookOver();
    }

    boolean hasCurrentTakeover(int generation) {
        synchronized (this) {
            return userTakeoverRequested && takeoverGeneration == generation;
        }
    }

    private boolean sameSession(String id) {
        return sessionId != null && sessionId.equals(id);
    }

    private void report(String state, String detail) {
        if (stateListener != null && sessionId != null)
            stateListener.onState(sessionId, warningGeneration, state, detail == null ? "" : detail);
    }

    private <T> T bounded(String operation, Callable<T> action) throws Exception {
        if (systemCallUncertain)
            throw new IllegalStateException(operation + ": previous OEM call timed out; worker replacement required");
        Future<T> pending = systemCalls.submit(action);
        try {
            return pending.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            // Cancellation cannot stop a remote Binder write. Never queue another after uncertainty.
            systemCallUncertain = true;
            throw new TimeoutException(operation + " exceeded " + WAIT_MS + "ms; remote completion unknown");
        } catch (InterruptedException interrupted) {
            systemCallUncertain = true;
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(operation + " failed", cause);
        } finally {
            pending.cancel(true);
        }
    }

    private <T> T controlCall(String operation, Callable<T> action) throws Exception {
        long started = SystemClock.elapsedRealtime();
        report("screen_command_dispatch", operation);
        try {
            T result = bounded(operation, action);
            report("screen_command_return", operation + "; elapsed_ms="
                    + (SystemClock.elapsedRealtime() - started) + "; result=" + result);
            return result;
        } catch (Exception failure) {
            report("screen_command_failed", operation + "; " + summary(failure));
            throw failure;
        }
    }

    private int readPower() throws Exception { return bounded("Power read", MotionScreenControl::readPowerRaw); }

    static int readPowerRaw() throws Exception {
        IBinder target = binder("autoservice");
        if (target == null) throw new IllegalStateException("autoservice unavailable");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(target.getInterfaceDescriptor());
            input.writeInt(1001);
            input.writeInt(315621418);
            if (!target.transact(5, input, output, 0))
                throw new IllegalStateException("Power read transaction unavailable");
            output.readException();
            if (output.dataAvail() != 4) throw new IllegalStateException("Power read reply is ambiguous");
            int value = output.readInt();
            if (value < 0 || value > 4) throw new IllegalStateException("Power value out of range");
            return value;
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private boolean isInteractive() throws Exception {
        return bounded("isInteractive", MotionScreenControl::isInteractiveRaw);
    }

    private static boolean isInteractiveRaw() throws Exception {
        Object power = service(POWER_IFACE);
        return (Boolean) Class.forName(POWER_IFACE).getMethod("isInteractive").invoke(power);
    }

    private int displayState() throws Exception { return bounded("display state", MotionScreenControl::displayStateRaw); }

    private static int displayStateRaw() throws Exception {
        Object display = service(DISPLAY_IFACE);
        Object info = Class.forName(DISPLAY_IFACE).getMethod("getDisplayInfo", int.class)
                .invoke(display, 0);
        if (info == null) throw new IllegalStateException("display 0 state unavailable");
        return info.getClass().getField("state").getInt(info);
    }

    private static String animationStatus() throws Exception {
        return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class).invoke(null, "sys.accanim.status");
    }

    private String animationDescriptor() throws Exception {
        return bounded("accanimation descriptor", MotionScreenControl::animationDescriptorRaw);
    }

    private static String animationDescriptorRaw() throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(ANIMATION_IFACE);
            IBinder target = binder("accanimation");
            if (target == null || !target.transact(IBinder.INTERFACE_TRANSACTION, input, output, 0))
                throw new IllegalStateException("accanimation descriptor unavailable");
            return output.readString();
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private int animationCommand(int command) throws Exception {
        return controlCall("accanimation command=" + command, () -> animationCommandRaw(command));
    }

    private static int animationCommandRaw(int command) throws Exception {
        if (command != 2 && command != 3) throw new IllegalArgumentException("unsupported accanimation command");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(ANIMATION_IFACE);
            if (command == 2) input.writeInt(0);
            IBinder target = binder("accanimation");
            if (target == null) throw new IllegalStateException("accanimation unavailable");
            if (!target.transact(command, input, output, 0) || output.dataSize() != 4)
                throw new IllegalStateException("accanimation reply missing/ambiguous");
            return output.readInt(); // Native reply: raw int32, not an exception header.
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private static Object service(String iface) throws Exception {
        IBinder target = binder(iface.equals(POWER_IFACE) ? "power" : "display");
        if (target == null) throw new IllegalStateException(iface + " service unavailable");
        return Class.forName(iface + "$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, target);
    }

    private static IBinder binder(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private static void requireShellUid() {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("screen controls require shell UID");
    }

    private static String summary(Throwable error) {
        return CellularRecovery.diagnosticDetail(error, "stage=motion_screen");
    }

    @Override public void close() {
        handler.removeCallbacks(observe);
        handler.post(() -> {
            eligible = false;
            sessionId = null;
        });
        thread.quitSafely();
        systemCalls.shutdownNow();
    }
}
