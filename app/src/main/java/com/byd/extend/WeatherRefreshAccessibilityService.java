package com.byd.extend;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Listens for the stock WeatherData refresh click and global camera-button bindings. */
public final class WeatherRefreshAccessibilityService extends AccessibilityService {
    public static final String WEATHER_PACKAGE = "com.byd.weatherdata";
    public static final String REFRESH_VIEW_ID = "com.byd.weatherdata:id/iv_refresh";

    private static volatile WeatherRefreshAccessibilityService activeService;
    private final Handler steeringHandler = new Handler(Looper.getMainLooper());
    private final CameraButtonGesturePolicy steeringGestures = new CameraButtonGesturePolicy(
            ViewConfiguration.getLongPressTimeout(), getMultiPressTimeout());
    private final Runnable steeringTimeout = this::handleSteeringTimeout;
    private SharedPreferences steeringPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener steeringPreferenceListener =
            (preferences, key) -> {
                if (!isSteeringPreference(key)) return;
                steeringGestures.bindingsChanged(currentAssignments(preferences));
                syncSteeringTimeout();
            };

    static boolean beginSteeringButtonLearning(Context context) {
        return beginCameraButtonLearning(context, CameraButtonBindings.Action.ReverseSource);
    }

    static boolean beginCameraButtonLearning(
            Context context, CameraButtonBindings.Action action) {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null || action == null || GuardRecovery.isUserShutdownActive(service)
                || LegacySettingsImporter.blocksRuntime(service)) return false;
        service.steeringHandler.removeCallbacks(service.steeringTimeout);
        service.steeringGestures.beginLearning(action);
        return true;
    }

    static void cancelSteeringButtonLearning() {
        cancelCameraButtonLearning();
    }

    static void cancelCameraButtonLearning() {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null) return;
        service.steeringGestures.cancelLearning();
        service.syncSteeringTimeout();
    }

    static boolean isSteeringButtonLearning() {
        return isCameraButtonLearning(CameraButtonBindings.Action.ReverseSource);
    }

    static boolean isCameraButtonLearning(CameraButtonBindings.Action action) {
        WeatherRefreshAccessibilityService service = activeService;
        return service != null && service.steeringGestures.learningAction() == action;
    }

    static boolean isCameraButtonLearning() {
        WeatherRefreshAccessibilityService service = activeService;
        return service != null && service.steeringGestures.isLearning();
    }

    private void clearSteeringState() {
        steeringHandler.removeCallbacks(steeringTimeout);
        steeringGestures.cancelLearning();
        steeringGestures.cancelActions();
    }

    private void resetSteeringState() {
        steeringHandler.removeCallbacks(steeringTimeout);
        steeringGestures.reset();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        resetSteeringState();
        if (steeringPreferences != null) {
            steeringPreferences.unregisterOnSharedPreferenceChangeListener(
                    steeringPreferenceListener);
        }
        steeringPreferences = getSharedPreferences("settings", MODE_PRIVATE);
        steeringPreferences.registerOnSharedPreferenceChangeListener(steeringPreferenceListener);
        activeService = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null
                || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED
                || event.getPackageName() == null
                || !WEATHER_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        try {
            if (!REFRESH_VIEW_ID.equals(source.getViewIdResourceName())) return;
            CameraHelperService.weatherRefreshRequested(this, "oem_button", null);
        } catch (RuntimeException ignored) {
            // A host callback must never kill AccessibilityService.
        } finally {
            source.recycle();
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        if (GuardRecovery.isUserShutdownActive(this)
                || LegacySettingsImporter.blocksRuntime(this)) {
            // Stop accepting new cycles, but finish consuming any cycle this filter already owns.
            clearSteeringState();
            return dispatchSteeringResult(steeringGestures.onKey(
                    event.getKeyCode(), event.getAction(), event.getRepeatCount(),
                    event.isCanceled(), event.getDownTime(), event.getEventTime(),
                    SystemClock.uptimeMillis(), new ArrayList<>()), preferences());
        }
        SharedPreferences preferences = preferences();
        CameraButtonGesturePolicy.Result result = steeringGestures.onKey(
                event.getKeyCode(), event.getAction(), event.getRepeatCount(),
                event.isCanceled(), event.getDownTime(), event.getEventTime(),
                SystemClock.uptimeMillis(), currentAssignments(preferences));
        syncSteeringTimeout();
        return dispatchSteeringResult(result, preferences);
    }

    private void handleSteeringTimeout() {
        if (GuardRecovery.isUserShutdownActive(this)
                || LegacySettingsImporter.blocksRuntime(this)) {
            clearSteeringState();
            return;
        }
        SharedPreferences preferences = preferences();
        dispatchSteeringResult(steeringGestures.advance(
                SystemClock.uptimeMillis(), currentAssignments(preferences)), preferences);
        syncSteeringTimeout();
    }

    private boolean dispatchSteeringResult(CameraButtonGesturePolicy.Result result,
            SharedPreferences preferences) {
        if (result.learnedAction != null) {
            CameraButtonBindings.Binding current = CameraButtonBindings.load(
                    preferences, result.learnedAction);
            CameraButtonBindings.save(preferences, result.learnedAction,
                    new CameraButtonBindings.Binding(result.learnedKeyCode, current.press));
            CameraProbeActivity.publishCameraSteeringButtonCaptured(result.learnedAction);
        }
        if (result.actions.contains(CameraButtonBindings.Action.ReverseSource)) {
            CameraHelperService.requestReverseSteeringToggle(this);
        }
        boolean source = result.actions.contains(CameraButtonBindings.Action.MirrorSource);
        boolean visibility = result.actions.contains(
                CameraButtonBindings.Action.MirrorVisibility);
        if (source || visibility) {
            CameraHelperService.requestMirrorButtonAction(this, source, visibility);
        }
        return result.consumed;
    }

    private void syncSteeringTimeout() {
        steeringHandler.removeCallbacks(steeringTimeout);
        long deadline = steeringGestures.nextDeadline();
        if (deadline != Long.MAX_VALUE) steeringHandler.postAtTime(steeringTimeout, deadline);
    }

    private SharedPreferences preferences() {
        return steeringPreferences != null
                ? steeringPreferences : getSharedPreferences("settings", MODE_PRIVATE);
    }

    private List<CameraButtonGesturePolicy.Assignment> currentAssignments(
            SharedPreferences preferences) {
        List<CameraButtonGesturePolicy.Assignment> result = new ArrayList<>(3);
        addAssignment(result, preferences, CameraButtonBindings.Action.ReverseSource,
                CameraProbeActivity.reverseOwnerEpochSnapshot());
        boolean mirrorEnabled = RearviewMirrorSettings.enabled(preferences);
        if (mirrorEnabled && RearviewMirrorSettings.frontIntegrated(preferences)) {
            addAssignment(result, preferences, CameraButtonBindings.Action.MirrorSource, 3L);
        }
        if (mirrorEnabled) {
            addAssignment(result, preferences, CameraButtonBindings.Action.MirrorVisibility, 1L);
        }
        return result;
    }

    private static void addAssignment(List<CameraButtonGesturePolicy.Assignment> result,
            SharedPreferences preferences, CameraButtonBindings.Action action, long contextToken) {
        CameraButtonBindings.Binding binding = CameraButtonBindings.load(preferences, action);
        if (binding.isAssigned()) {
            result.add(new CameraButtonGesturePolicy.Assignment(action, binding, contextToken));
        }
    }

    private static boolean isSteeringPreference(String key) {
        return ReverseSteeringButtonPreferences.KEY_CODE.equals(key)
                || CameraButtonBindings.MIRROR_SOURCE_KEY_CODE.equals(key)
                || CameraButtonBindings.MIRROR_SOURCE_PRESS.equals(key)
                || CameraButtonBindings.MIRROR_VISIBILITY_KEY_CODE.equals(key)
                || CameraButtonBindings.MIRROR_VISIBILITY_PRESS.equals(key)
                || RearviewMirrorSettings.PREF_ENABLED.equals(key)
                || RearviewMirrorSettings.PREF_FRONT_INTEGRATED.equals(key);
    }

    private static int getMultiPressTimeout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ViewConfiguration.getMultiPressTimeout();
        }
        return ViewConfiguration.getDoubleTapTimeout();
    }

    @Override
    public void onInterrupt() {
        clearSteeringState();
    }

    @Override
    public void onDestroy() {
        resetSteeringState();
        if (steeringPreferences != null) {
            steeringPreferences.unregisterOnSharedPreferenceChangeListener(
                    steeringPreferenceListener);
            steeringPreferences = null;
        }
        if (activeService == this) activeService = null;
        super.onDestroy();
    }
}
