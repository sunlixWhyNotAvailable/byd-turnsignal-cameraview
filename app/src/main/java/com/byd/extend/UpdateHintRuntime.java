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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
    private final AppUpdateManager manager = new AppUpdateManager();
    private final UpdateResultPresentation presentation = new UpdateResultPresentation();
    private final Set<Activity> started = Collections.newSetFromMap(new IdentityHashMap<>());
    private WeakReference<CheckListener> listener = new WeakReference<>(null);
    private AppUpdateManager.UpdateInfo available;
    private boolean shutdown;
    private final DisplayManager displays;
    private boolean waitingForDisplay;
    private final Runnable deliverHint = this::deliverPendingHint;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private final UpdateAutoCheckRuntime autoCheck;
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                autoCheck.sleep();
                pauseHintForDisplay();
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
                detail -> Log.i("UpdateHintRuntime", "update_check " + detail));
        settingsListener = (prefs, key) -> {
            // SharedPreferences dispatches on main: do not coalesce a rapid OFF/ON pair.
            if (PREF_AUTO_CHECK.equals(key)) {
                autoCheck.refresh();
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
        autoCheck.onDisplayState(displayReady());
        autoCheck.refresh();
    }

    void setCheckListener(CheckListener value) { listener = new WeakReference<>(value); }
    void removeCheckListener(CheckListener value) {
        if (listener.get() == value) listener.clear();
    }
    boolean isChecking() { return autoCheck.isChecking(); }
    void setDownloadInFlight(boolean value) { autoCheck.setDownloading(value); }
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
                if (!autoCheck.complete(request, error == null && completed != null)) {
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
                            completed.fresh, !started.isEmpty(), enabled(),
                            Settings.canDrawOverlays(context));
                    queuePendingHint();
                }
                Log.i("UpdateHintRuntime", "check_finished result=" + (error != null ? "error"
                        : completed != null && completed.available != null ? "available" : "none"));
                if (observer != null) observer.onCheckFinished(
                        completed == null ? null : completed.available, error, request.manual);
            });
        });
    }

    AppUpdateManager.UpdateInfo pendingOffer() {
        return !isChecking() && available != null && presentation.hasOffer(available.resultId)
                ? available : null;
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
        }
    }

    void shutdown() {
        shutdown = true;
        autoCheck.shutdown();
        presentation.invalidate();
        available = null;
        hide("shutdown");
    }

    private void hide(String reason) {
        main.removeCallbacks(deliverHint);
        if (presentation.cancelHint()) hintEvent("cancelled reason=" + reason);
        waitingForDisplay = false;
        UpdateHintOverlay.hide(reason);
    }

    static void onRuntimeWake(Context context, String action) {
        get(context).runtimeWake(action);
    }

    private void runtimeWake(String action) {
        if (shutdown || GuardRecovery.isUserShutdownActive(context)
                || LegacySettingsImporter.blocksRuntime(context)) return;
        reconcileDisplay();
        autoCheck.wake(action);
    }

    private boolean displayReady() {
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        return display != null && display.getState() == Display.STATE_ON;
    }

    private void reconcileDisplay() {
        boolean ready = displayReady();
        if (!shutdown && !GuardRecovery.isUserShutdownActive(context)
                && !LegacySettingsImporter.blocksRuntime(context)) autoCheck.onDisplayState(ready);
        if (ready) UpdateHintOverlay.reconcileExpiry();
        queuePendingHint();
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
        else if (LegacySettingsImporter.blocksRuntime(context)) reason = "startup_blocked";
        else if (!started.isEmpty()) reason = "own_ui_visible";
        else if (!enabled()) reason = "disabled";
        else if (!Settings.canDrawOverlays(context)) reason = "overlay_permission_missing";
        if (reason != null) {
            hide(reason);
            return false;
        }
        if (!displayReady()) {
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
        UpdateHintOverlay.show(context, available.resultId, available.version, attempt);
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
        Log.i("UpdateHintRuntime", "hint " + detail + " result="
                + (available == null ? "none" : available.resultId)
                + " display_state=" + (display == null ? "missing" : display.getState()));
    }

    @Override public void onActivityStarted(Activity activity) {
        started.add(activity);
        shutdown = false;
        hide("own_ui_visible");
        autoCheck.onDisplayState(displayReady());
        autoCheck.entry(displayReady(), presentation.hasPendingOffer());
    }
    @Override public void onActivityStopped(Activity activity) { started.remove(activity); }
    @Override public void onActivityDestroyed(Activity activity) { started.remove(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle saved) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
}
