package com.byd.extend;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process ownership only. The existing Activity scheduler decides when a check may start. */
public final class UpdateHintRuntime implements Application.ActivityLifecycleCallbacks {
    public static final String PREF_ENABLED = "update_hint_enabled";
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
    private boolean checking;
    private boolean shutdown;
    private long generation;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener;

    interface CheckListener {
        void onCheckFinished(AppUpdateManager.UpdateInfo available, Throwable error, boolean force);
    }

    public static synchronized UpdateHintRuntime get(Context context) {
        if (instance == null) instance = new UpdateHintRuntime(context.getApplicationContext());
        return instance;
    }

    private UpdateHintRuntime(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        settingsListener = (prefs, key) -> main.post(() -> {
            if (PREF_ENABLED.equals(key) && !enabled()) hide("disabled");
            else if (AppLanguage.KEY.equals(key) || "ui_dark_theme".equals(key)) {
                UpdateHintOverlay.refreshAppearance();
            }
        });
        preferences.registerOnSharedPreferenceChangeListener(settingsListener);
        UpdateHintOverlay.setCallback(this::openResult);
        ((Application) context).registerActivityLifecycleCallbacks(this);
    }

    void setCheckListener(CheckListener value) { listener = new WeakReference<>(value); }
    void removeCheckListener(CheckListener value) {
        if (listener.get() == value) listener.clear();
    }
    boolean isChecking() { return checking; }
    boolean enabled() { return preferences.getBoolean(PREF_ENABLED, true); }

    boolean check(boolean force) {
        if (checking || shutdown) return false;
        checking = true;
        final long ticket = generation;
        checks.execute(() -> {
            AppUpdateManager.CheckResult result = null;
            Throwable failure = null;
            try { result = manager.checkForUpdate(context, force); }
            catch (Exception error) { failure = error; }
            final AppUpdateManager.CheckResult completed = result;
            final Throwable error = failure;
            main.post(() -> {
                checking = false;
                if (ticket != generation || shutdown) return;
                if (error == null && completed != null) {
                    if (completed.fresh) {
                        hide("new_result");
                        available = completed.available;
                    }
                    boolean show = presentation.accept(
                            completed.available == null ? null : completed.available.resultId,
                            completed.fresh, !started.isEmpty(), enabled(),
                            Settings.canDrawOverlays(context));
                    if (show) UpdateHintOverlay.show(context, available.resultId, available.version);
                }
                Log.i("UpdateHintRuntime", "check_finished result=" + (error != null ? "error"
                        : completed != null && completed.available != null ? "available" : "none"));
                CheckListener observer = listener.get();
                if (observer != null) observer.onCheckFinished(
                        completed == null ? null : completed.available, error, force);
            });
        });
        return true;
    }

    AppUpdateManager.UpdateInfo pendingOffer() {
        return !checking && available != null && presentation.hasOffer(available.resultId)
                ? available : null;
    }

    void consume(String resultId) {
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
        generation++;
        presentation.invalidate();
        available = null;
        hide("shutdown");
    }

    private void hide(String reason) { UpdateHintOverlay.hide(reason); }

    @Override public void onActivityStarted(Activity activity) {
        started.add(activity);
        shutdown = false;
        hide("own_ui_visible");
    }
    @Override public void onActivityStopped(Activity activity) { started.remove(activity); }
    @Override public void onActivityDestroyed(Activity activity) { started.remove(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle saved) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
}
