package com.byd.extend;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns update cycles and their results independently of Activity visibility. */
public final class UpdateHintRuntime implements Application.ActivityLifecycleCallbacks {
    public static final String PREF_ENABLED = "update_hint_enabled";
    static final String PREF_AUTO_CHECK = "update_auto_check_enabled";
    static final String EXTRA_RESULT_ID = "com.byd.extend.UPDATE_RESULT_ID";
    private static UpdateHintRuntime instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService checks = Executors.newSingleThreadExecutor();
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final ExecutorService screenReads = Executors.newSingleThreadExecutor();
    private CameraRuntimeClient displayReader;
    private boolean screenReadActive;
    private long screenEpoch;
    private boolean vehiclePowerKnown;
    private boolean vehiclePowerOff;
    private boolean physicalScreenVisible;
    private boolean eligible;
    private long hintRemainingMs = 10_000L;
    private String lastScreenDiagnostic;
    private String lastOfferDecision;
    private final Runnable pollDisplay = this::reconcileDisplay;
    private final AppUpdateManager manager = new AppUpdateManager();
    private final UpdateResultPresentation presentation = new UpdateResultPresentation();
    private WeakReference<CheckListener> listener = new WeakReference<>(null);
    private final Object downloadLock = new Object();
    private WeakReference<DownloadListener> downloadListener = new WeakReference<>(null);
    private DownloadSnapshot downloadOperation;
    private CancellationSignal downloadCancellation;
    private AppUpdateManager.UpdateInfo available;
    private volatile boolean manualCheckDialogRequested;
    private boolean shutdown;
    private final DisplayManager displays;
    private boolean waitingForDisplay;
    private final Runnable deliverHint = this::deliverPendingHint;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private final UpdateAutoCheckRuntime autoCheck;
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenEpoch++;
                physicalScreenVisible = false;
                applyEligibility();
                reconcileDisplay();
            } else runtimeWake(intent.getAction());
        }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { changed(id); }
        @Override public void onDisplayRemoved(int id) { changed(id); }
        @Override public void onDisplayChanged(int id) { changed(id); }
        private void changed(int id) {
            if (id == Display.DEFAULT_DISPLAY) reconcileDisplay();
        }
    };

    interface CheckListener {
        void onCheckStarted(boolean force);
        void onCheckDiscarded();
        void onCheckFinished(AppUpdateManager.UpdateInfo available, Throwable error, boolean force);
        default void onUpdateEligibilityChanged() {}
    }

    interface DownloadListener {
        void onDownloadChanged(DownloadSnapshot snapshot);
    }

    enum DownloadPhase { DOWNLOADING, INSTALLING, READY, FAILED, CANCELLED }

    static final class DownloadSnapshot {
        final AppUpdateManager.UpdateInfo info;
        final DownloadPhase phase;
        final int progress;
        final File file;
        final String error;
        final boolean installerError;
        final boolean dialogVisible;
        final boolean initialInstallerHandoffEligible;
        final boolean initialInstallerHandoffAttempted;

        DownloadSnapshot(AppUpdateManager.UpdateInfo info, DownloadPhase phase, int progress,
                File file, String error, boolean installerError, boolean dialogVisible) {
            this(info, phase, progress, file, error, installerError, dialogVisible, false, false);
        }

        private DownloadSnapshot(AppUpdateManager.UpdateInfo info, DownloadPhase phase, int progress,
                File file, String error, boolean installerError, boolean dialogVisible,
                boolean initialInstallerHandoffEligible, boolean initialInstallerHandoffAttempted) {
            this.info = info;
            this.phase = phase;
            this.progress = progress;
            this.file = file;
            this.error = error;
            this.installerError = installerError;
            this.dialogVisible = dialogVisible;
            this.initialInstallerHandoffEligible = initialInstallerHandoffEligible;
            this.initialInstallerHandoffAttempted = initialInstallerHandoffAttempted;
        }

        DownloadSnapshot withProgress(int value) {
            return new DownloadSnapshot(info, phase, value, file, error,
                    installerError, dialogVisible, initialInstallerHandoffEligible,
                    initialInstallerHandoffAttempted);
        }

        DownloadSnapshot withPhase(DownloadPhase value, String message, boolean failedInstaller) {
            return new DownloadSnapshot(info, value, progress, file, message,
                    failedInstaller, dialogVisible, initialInstallerHandoffEligible,
                    initialInstallerHandoffAttempted);
        }

        DownloadSnapshot withDialogVisible(boolean value) {
            return new DownloadSnapshot(info, phase, progress, file, error,
                    installerError, value, value && initialInstallerHandoffEligible,
                    initialInstallerHandoffAttempted);
        }

        DownloadSnapshot withInitialInstallerHandoff(boolean eligible, boolean attempted) {
            return new DownloadSnapshot(info, phase, progress, file, error,
                    installerError, dialogVisible, eligible, attempted);
        }
    }

    static boolean shouldReuseDownload(
            DownloadSnapshot operation, AppUpdateManager.UpdateInfo requested) {
        return operation != null
                && (operation.phase == DownloadPhase.DOWNLOADING
                || operation.phase == DownloadPhase.INSTALLING
                || operation.phase == DownloadPhase.READY
                        && operation.info.version.equals(requested.version));
    }

    public static synchronized UpdateHintRuntime get(Context context) {
        if (instance == null) instance = new UpdateHintRuntime(context.getApplicationContext());
        return instance;
    }

    private UpdateHintRuntime(Context context) {
        this.context = context;
        displays = context.getSystemService(DisplayManager.class);
        preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        autoCheck = new UpdateAutoCheckRuntime(main::postDelayed, main::removeCallbacks,
                () -> preferences.getBoolean(PREF_AUTO_CHECK, true), this::startCheck,
                detail -> diagnostic("check", detail));
        settingsListener = (prefs, key) -> {
            // SharedPreferences dispatches on main: do not coalesce a rapid OFF/ON pair.
            if (PREF_AUTO_CHECK.equals(key)) {
                autoCheck.refresh();
                reconcileDisplay();
                return;
            }
            if (PREF_ENABLED.equals(key) && !enabled()) {
                hide("disabled");
                return;
            }
            main.post(() -> {
                if (AppLanguage.KEY.equals(key) || "ui_dark_theme".equals(key)) {
                    UpdateHintOverlay.refreshAppearance();
                }
            });
        };
        preferences.registerOnSharedPreferenceChangeListener(settingsListener);
        UpdateHintOverlay.setCallback(this::openResult);
        UpdateHintOverlay.setDeliveryCallback(this::onHintDelivery);
        ((Application) context).registerActivityLifecycleCallbacks(this);
        IntentFilter wakeFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        wakeFilter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(wakeReceiver, wakeFilter);
        shutdown = GuardRecovery.isUserShutdownActive(context);
        if (shutdown) autoCheck.shutdown();
        if (displays != null) displays.registerDisplayListener(displayListener, main);
        autoCheck.refresh();
    }

    void setCheckListener(CheckListener value) { listener = new WeakReference<>(value); }
    void removeCheckListener(CheckListener value) {
        if (listener.get() == value) listener.clear();
    }
    boolean isChecking() { return autoCheck.isChecking(); }
    void setManualCheckDialogRequested(boolean value) {
        manualCheckDialogRequested = value;
    }
    void setDownloadListener(DownloadListener value) {
        downloadListener = new WeakReference<>(value);
        value.onDownloadChanged(downloadOperation());
    }
    void removeDownloadListener(DownloadListener value) {
        if (downloadListener.get() == value) downloadListener.clear();
    }
    DownloadSnapshot downloadOperation() {
        synchronized (downloadLock) { return downloadOperation; }
    }
    DownloadSnapshot showDownloadOperation() {
        DownloadSnapshot current;
        synchronized (downloadLock) {
            if (downloadOperation == null) return null;
            downloadOperation = downloadOperation.withDialogVisible(true);
            current = downloadOperation;
        }
        notifyDownloadListener(current);
        return current;
    }
    void dismissDownloadOperation() {
        DownloadSnapshot current;
        synchronized (downloadLock) {
            if (downloadOperation == null) return;
            if (downloadOperation.phase == DownloadPhase.FAILED
                    || downloadOperation.phase == DownloadPhase.CANCELLED) downloadOperation = null;
            else downloadOperation = downloadOperation.withDialogVisible(false);
            current = downloadOperation;
        }
        notifyDownloadListener(current);
    }
    boolean startDownload(AppUpdateManager.UpdateInfo info) {
        DownloadSnapshot started;
        CancellationSignal attempt;
        boolean reused;
        synchronized (downloadLock) {
            if (shouldReuseDownload(downloadOperation, info)) {
                downloadOperation = downloadOperation.withDialogVisible(true);
                started = downloadOperation;
                reused = true;
            } else {
                started = new DownloadSnapshot(info, DownloadPhase.DOWNLOADING,
                        0, null, null, false, true);
                downloadOperation = started;
                downloadCancellation = new CancellationSignal();
                reused = false;
            }
            attempt = downloadCancellation;
        }
        if (reused) {
            notifyDownloadListener(started);
            return false;
        }
        autoCheck.setDownloading(true);
        notifyDownloadListener(started);
        downloads.execute(() -> {
            File file = null;
            Exception failure = null;
            try {
                file = manager.downloadAndVerify(context, info,
                        value -> main.post(() -> updateDownloadProgress(attempt, value)), attempt);
            } catch (Exception error) {
                failure = error;
            }
            File downloaded = file;
            Exception error = failure;
            // Finish worker-side cleanup before a retry can reuse the APK path.
            if (attempt.isCanceled() && downloaded != null) downloaded.delete();
            main.post(() -> finishDownload(attempt, downloaded, error));
        });
        return true;
    }

    boolean cancelDownload() {
        DownloadSnapshot cancelled;
        CancellationSignal attempt;
        synchronized (downloadLock) {
            if (downloadOperation == null
                    || downloadOperation.phase != DownloadPhase.DOWNLOADING) return false;
            attempt = downloadCancellation;
            cancelled = new DownloadSnapshot(downloadOperation.info, DownloadPhase.CANCELLED,
                    0, null, null, false, downloadOperation.dialogVisible);
            downloadOperation = cancelled;
        }
        // Mark the attempt first: any queued completion must lose the installer handoff.
        attempt.cancel();
        autoCheck.setDownloading(false);
        Log.i("UpdateHintRuntime", "update_download_cancelled version=" + cancelled.info.version);
        notifyDownloadListener(cancelled);
        return true;
    }
    boolean installReady(Activity activity) {
        DownloadSnapshot current;
        synchronized (downloadLock) {
            current = downloadOperation;
            if (current == null || current.phase != DownloadPhase.READY || current.file == null) {
                return false;
            }
            downloadOperation = current.withInitialInstallerHandoff(false, true)
                    .withPhase(DownloadPhase.INSTALLING, null, false);
            current = downloadOperation;
        }
        notifyDownloadListener(current);
        try {
            manager.install(activity, current.info, current.file);
            synchronized (downloadLock) {
                if (downloadOperation != null
                        && downloadOperation.info.resultId.equals(current.info.resultId)) {
                    downloadOperation = downloadOperation.withPhase(
                            DownloadPhase.READY, null, false);
                    current = downloadOperation;
                }
            }
            notifyDownloadListener(current);
            return true;
        } catch (Exception error) {
            synchronized (downloadLock) {
                if (downloadOperation != null
                        && downloadOperation.info.resultId.equals(current.info.resultId)) {
                    downloadOperation = downloadOperation.withPhase(
                            DownloadPhase.READY,
                            error.getMessage() == null ? error.getClass().getSimpleName()
                                    : error.getMessage(), true);
                    current = downloadOperation;
                }
            }
            notifyDownloadListener(current);
            return false;
        }
    }

    boolean claimInitialInstallerHandoff(String resultId) {
        synchronized (downloadLock) {
            DownloadSnapshot current = downloadOperation;
            if (current == null || current.phase != DownloadPhase.READY || current.file == null
                    || !current.dialogVisible || !current.initialInstallerHandoffEligible
                    || current.initialInstallerHandoffAttempted
                    || !current.info.resultId.equals(resultId)) return false;
            downloadOperation = current.withInitialInstallerHandoff(false, true);
            return true;
        }
    }

    boolean enabled() { return preferences.getBoolean(PREF_ENABLED, true); }

    boolean check(boolean force) {
        if (shutdown) return false;
        if (!force) {
            autoCheck.refresh();
            return autoCheck.isChecking();
        }
        boolean alreadyRunning = autoCheck.isChecking();
        boolean accepted = autoCheck.requestManual();
        if (accepted && alreadyRunning) {
            CheckListener observer = listener.get();
            if (observer != null) observer.onCheckStarted(true);
        }
        return accepted;
    }

    private void startCheck(UpdateAutoCheckRuntime.Request request) {
        if (request.manual) {
            screenEpoch++;
            physicalScreenVisible = false;
            applyEligibility();
        }
        reconcileDisplay();
        CheckListener startedObserver = listener.get();
        if (startedObserver != null) startedObserver.onCheckStarted(request.manual);
        checks.execute(() -> {
            AppUpdateManager.CheckResult result = null;
            Throwable failure = null;
            try { result = manager.checkForUpdate(); }
            catch (Exception error) { failure = error; }
            final AppUpdateManager.CheckResult completed = result;
            final Throwable error = failure;
            main.post(() -> {
                CheckListener observer = listener.get();
                boolean accepted = autoCheck.complete(request, error == null && completed != null);
                diagnostic("check_result", "request=" + request.id + " accepted=" + accepted
                        + " result=" + (error != null ? "error"
                        : completed != null && completed.available != null ? "available" : "none")
                        + " version=" + (completed != null && completed.available != null ? completed.available.version : "none")
                        + (error == null ? "" : "\n" + CellularRecovery.diagnosticDetail(error, "operation=release_check")));
                if (!accepted) {
                    manualCheckDialogRequested = false;
                    if (observer != null) observer.onCheckDiscarded();
                    return;
                }
                if (error == null && completed != null) {
                    if (completed.fresh) {
                        hide("new_result");
                        available = completed.available;
                    }
                    presentation.accept(
                            completed.available == null ? null : completed.available.resultId,
                            completed.fresh, enabled(),
                            Settings.canDrawOverlays(context));
                    if (request.manual && !manualCheckDialogRequested
                            && completed.available != null) {
                        presentation.consume(completed.available.resultId);
                    }
                    queuePendingHint();
                }
                if (request.manual) manualCheckDialogRequested = false;
                reconcileDisplay();
                if (observer != null) observer.onCheckFinished(
                        completed == null ? null : completed.available, error, request.manual);
            });
        });
    }

    private void updateDownloadProgress(CancellationSignal attempt, int progress) {
        DownloadSnapshot current;
        synchronized (downloadLock) {
            if (downloadOperation == null || downloadCancellation != attempt || attempt.isCanceled()
                    || downloadOperation.phase != DownloadPhase.DOWNLOADING) return;
            downloadOperation = downloadOperation.withProgress(progress);
            current = downloadOperation;
        }
        notifyDownloadListener(current);
    }

    private void finishDownload(CancellationSignal attempt, File file, Exception error) {
        DownloadSnapshot current;
        synchronized (downloadLock) {
            if (downloadOperation == null || downloadCancellation != attempt || attempt.isCanceled()
                    || downloadOperation.phase != DownloadPhase.DOWNLOADING) return;
            downloadOperation = error == null
                    ? new DownloadSnapshot(downloadOperation.info, DownloadPhase.READY,
                            100, file, null, false, downloadOperation.dialogVisible,
                            downloadOperation.dialogVisible, false)
                    : new DownloadSnapshot(downloadOperation.info, DownloadPhase.FAILED,
                            downloadOperation.progress, null,
                            error.getMessage() == null ? error.getClass().getSimpleName()
                                    : error.getMessage(), false, downloadOperation.dialogVisible);
            current = downloadOperation;
        }
        attempt.setOnCancelListener(null);
        autoCheck.setDownloading(false);
        notifyDownloadListener(current);
    }

    private void notifyDownloadListener(DownloadSnapshot snapshot) {
        DownloadListener observer = downloadListener.get();
        if (observer != null) observer.onDownloadChanged(snapshot);
    }

    AppUpdateManager.UpdateInfo pendingOffer() {
        return eligible ? manualOffer() : null;
    }

    AppUpdateManager.UpdateInfo manualOffer() {
        return !isChecking() && available != null && presentation.hasOffer(available.resultId)
                ? available : null;
    }

    void offerDecision(String reason) {
        String detail = "result=" + (available == null ? "none" : available.resultId) + " reason=" + reason;
        if (detail.equals(lastOfferDecision)) return;
        lastOfferDecision = detail;
        diagnostic("offer", detail);
    }

    void consume(String resultId) {
        if (!presentation.hasOffer(resultId)) return;
        presentation.consume(resultId);
        hide("offer_handled");
    }

    boolean acceptsIntent(String resultId) {
        return !shutdown && resultId != null && available != null
                && resultId.equals(available.resultId) && presentation.hasOffer(resultId);
    }

    /** Only called by a deliberate tap on our own card, never by IPC or check completion. */
    public void openResult(String resultId) {
        if (!acceptsIntent(resultId)) return;
        hide("tap");
        try {
            context.startActivity(new Intent(context, CameraProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_RESULT_ID, resultId));
        } catch (RuntimeException error) {
            Log.w("UpdateHintRuntime", "open_failed", error);
            diagnostic("open_failed", CellularRecovery.diagnosticDetail(error, "operation=open_update_offer"));
        }
    }

    void shutdown() {
        shutdown = true;
        screenEpoch++;
        main.removeCallbacks(pollDisplay);
        applyEligibility();
        autoCheck.shutdown();
        presentation.invalidate();
        available = null;
        hide("shutdown");
    }

    private void hide(String reason) {
        hintRemainingMs = 10_000L;
        main.removeCallbacks(deliverHint);
        if (presentation.cancelHint()) hintEvent("cancelled reason=" + reason);
        waitingForDisplay = false;
        UpdateHintOverlay.hide(reason);
    }

    static void onRuntimeWake(Context context, String action) {
        UpdateHintRuntime runtime = get(context);
        runtime.main.post(() -> runtime.runtimeWake(action));
    }

    private void runtimeWake(String action) {
        if (shutdown || GuardRecovery.isUserShutdownActive(context)
                || LegacySettingsImporter.blocksRuntime(context)) return;
        reconcileDisplay();
        diagnostic("wake", "action=" + action);
    }

    private boolean androidDisplayReady() {
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        return display != null && display.getState() == Display.STATE_ON;
    }

    private void reconcileDisplay() {
        main.removeCallbacks(pollDisplay);
        if (!observeDisplay() || screenReadActive) { applyEligibility(); return; }
        CameraRuntimeClient reader = displayReader;
        if (reader == null) {
            screenObserved(null, "Display reader not attached", screenEpoch);
            return;
        }
        screenReadActive = true;
        long epoch = screenEpoch;
        screenReads.execute(() -> {
            Bundle state = null;
            String failure = null;
            try { state = reader.readUpdateDisplayState(); }
            catch (Exception error) { failure = CellularRecovery.diagnosticDetail(error, "operation=update_display_ipc"); }
            Bundle observed = state;
            String error = failure;
            main.post(() -> {
                screenReadActive = false;
                screenObserved(observed, error, epoch);
            });
        });
    }

    void setDisplayReader(CameraRuntimeClient reader) {
        main.post(() -> {
            displayReader = reader;
            vehiclePowerKnown = false;
            screenEpoch++;
            physicalScreenVisible = false;
            applyEligibility();
            reconcileDisplay();
        });
    }

    void clearDisplayReader(CameraRuntimeClient reader) {
        main.post(() -> {
            if (displayReader != reader) return;
            displayReader = null;
            vehiclePowerKnown = false;
            physicalScreenVisible = false;
            screenEpoch++;
            main.removeCallbacks(pollDisplay);
            applyEligibility();
        });
    }

    void vehiclePowerChanged(CameraRuntimeClient source, boolean known, boolean off) {
        main.post(() -> {
            if (displayReader != source) return;
            if (vehiclePowerKnown == known && vehiclePowerOff == off) return;
            vehiclePowerKnown = known;
            vehiclePowerOff = off;
            screenEpoch++;
            physicalScreenVisible = false;
            diagnostic("vehicle_power", "known=" + known + " off=" + off);
            applyEligibility();
            reconcileDisplay();
        });
    }

    private boolean observeDisplay() {
        return !shutdown && vehiclePowerKnown && !vehiclePowerOff
                && (preferences.getBoolean(PREF_AUTO_CHECK, true) || autoCheck.isChecking()
                || presentation.hasPendingOffer());
    }

    private void screenObserved(Bundle state, String failure, long epoch) {
        if (epoch == screenEpoch) {
            physicalScreenVisible = state != null && state.getBoolean("known") && state.getBoolean("visible");
            String detail = failure != null ? failure : state == null ? "Display snapshot missing"
                    : "known=" + state.getBoolean("known") + " visible=" + state.getBoolean("visible")
                    + " display=" + state.getInt("display", -1) + " backlight=" + state.getInt("backlight", -1)
                    + " cover=" + state.getString("cover", "") + " capability=" + state.getString("capability", "oem")
                    + " capability_detail=" + state.getString("capability_detail", "")
                    + " error=" + state.getString("error", "");
            if (!detail.equals(lastScreenDiagnostic)) {
                lastScreenDiagnostic = detail;
                diagnostic("screen", detail);
            }
            applyEligibility();
        }
        if (observeDisplay()) main.postDelayed(pollDisplay, epoch == screenEpoch ? 1_000L : 0L);
    }

    private void applyEligibility() {
        boolean next = UpdateDisplayState.eligible(vehiclePowerKnown, vehiclePowerOff,
                physicalScreenVisible && androidDisplayReady()) && !shutdown
                && !GuardRecovery.isUserShutdownActive(context) && !LegacySettingsImporter.blocksRuntime(context);
        boolean changed = eligible != next;
        eligible = next;
        autoCheck.setEligible(next);
        if (!next) presentation.pauseAttempt();
        long remaining = UpdateHintOverlay.setEligible(next);
        if (remaining > 0L && available != null) {
            hintRemainingMs = remaining;
            presentation.resumeShown(available.resultId);
        }
        presentation.setDisplayReady(next);
        queuePendingHint();
        if (changed) {
            diagnostic("eligibility", "eligible=" + next + " power_known=" + vehiclePowerKnown
                    + " power_off=" + vehiclePowerOff + " screen_visible=" + physicalScreenVisible);
            CheckListener observer = listener.get();
            if (observer != null) observer.onUpdateEligibilityChanged();
        }
    }

    private void pauseHintForDisplay() {
        main.removeCallbacks(deliverHint);
        if (!presentation.hasPendingHint()) return;
        boolean preparing = presentation.isAttempting();
        presentation.setDisplayReady(false);
        if (preparing) UpdateHintOverlay.hide("display_wait");
        if (!waitingForDisplay) hintEvent("waiting_display");
        waitingForDisplay = true;
    }

    private boolean canDeliverHint() {
        if (!presentation.hasPendingHint()) return false;
        String reason = null;
        if (available == null || !presentation.hasOffer(available.resultId)) reason = "stale_result";
        else if (shutdown || GuardRecovery.isUserShutdownActive(context)) reason = "shutdown";
        else if (!enabled()) reason = "disabled";
        else if (!Settings.canDrawOverlays(context)) reason = "overlay_permission_missing";
        if (reason != null) {
            hide(reason);
            return false;
        }
        if (!eligible) {
            pauseHintForDisplay();
            return false;
        }
        presentation.setDisplayReady(true);
        waitingForDisplay = false;
        return true;
    }

    private void queuePendingHint() {
        main.removeCallbacks(deliverHint);
        if (!canDeliverHint()) return;
        long delay = presentation.delayUntilAttempt(SystemClock.elapsedRealtime());
        if (delay >= 0L) main.postDelayed(deliverHint, delay);
    }

    private void deliverPendingHint() {
        if (!canDeliverHint()) return;
        long attempt = presentation.beginAttempt(SystemClock.elapsedRealtime());
        if (attempt == 0L) return;
        hintEvent("attempt id=" + attempt + " failures=" + presentation.failureCount());
        UpdateHintOverlay.show(context, available.resultId, available.version, attempt, hintRemainingMs);
    }

    private void onHintDelivery(String id, long attempt, UpdateHintOverlay.DeliveryOutcome outcome) {
        boolean accepted;
        switch (outcome) {
            case SHOWN: accepted = presentation.shown(id, attempt); break;
            case DEFERRED: accepted = presentation.deferred(id, attempt); break;
            case FAILED:
                accepted = presentation.failed(id, attempt, SystemClock.elapsedRealtime());
                break;
            default: accepted = presentation.cancelled(id, attempt); break;
        }
        if (!accepted) return;
        hintEvent("delivery outcome=" + outcome + " attempt=" + attempt
                + " failures=" + presentation.failureCount());
        if (outcome == UpdateHintOverlay.DeliveryOutcome.FAILED) queuePendingHint();
        else if (outcome == UpdateHintOverlay.DeliveryOutcome.DEFERRED) pauseHintForDisplay();
    }

    private void hintEvent(String detail) {
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        diagnostic("hint", detail + " result="
                + (available == null ? "none" : available.resultId)
                + " display_state=" + (display == null ? "missing" : display.getState()));
    }

    private void diagnostic(String kind, String detail) {
        Log.i("UpdateHintRuntime", kind + " " + detail);
        AvasRecoveryJournal.event(context, "update_" + kind, "source", "update_runtime", "detail", detail);
    }

    @Override public void onActivityStarted(Activity activity) {
        shutdown = false;
        autoCheck.entry();
        reconcileDisplay();
    }
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivityDestroyed(Activity activity) {}
    @Override public void onActivityCreated(Activity activity, Bundle saved) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
}
