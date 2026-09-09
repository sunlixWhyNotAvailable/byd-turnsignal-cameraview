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

/** Listens for the stock WeatherData refresh click and the optional global Reverse key binding. */
public final class WeatherRefreshAccessibilityService extends AccessibilityService {
    public static final String WEATHER_PACKAGE = "com.byd.weatherdata";
    public static final String REFRESH_VIEW_ID = "com.byd.weatherdata:id/iv_refresh";

    private static volatile WeatherRefreshAccessibilityService activeService;
    private final ReverseSteeringButtonPolicy.State steeringState =
            new ReverseSteeringButtonPolicy.State();
    private final Handler steeringHandler = new Handler(Looper.getMainLooper());
    private final Runnable steeringTimeout = this::handleSteeringTimeout;
    private SharedPreferences steeringPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener steeringPreferenceListener =
            (preferences, key) -> {
                if (!ReverseSteeringButtonPreferences.KEY_CODE.equals(key)) return;
                steeringState.bindingChanged();
                steeringHandler.removeCallbacks(steeringTimeout);
            };

    static boolean beginSteeringButtonLearning(Context context) {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null || GuardRecovery.isUserShutdownActive(service)
                || LegacySettingsImporter.blocksRuntime(service)) return false;
        service.steeringHandler.removeCallbacks(service.steeringTimeout);
        service.steeringState.beginLearning();
        return true;
    }

    static void cancelSteeringButtonLearning() {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null) return;
        service.steeringHandler.removeCallbacks(service.steeringTimeout);
        service.steeringState.cancel();
    }

    static boolean isSteeringButtonLearning() {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null) return false;
        return service.steeringState.isLearning();
    }

    private void clearSteeringState() {
        steeringHandler.removeCallbacks(steeringTimeout);
        steeringState.cancel();
    }

    private void resetSteeringState() {
        steeringHandler.removeCallbacks(steeringTimeout);
        steeringState.reset();
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
            return steeringState.consumeOwnedTail(
                    event.getAction(), event.getKeyCode(), event.getDownTime());
        }
        SharedPreferences preferences = steeringPreferences != null
                ? steeringPreferences : getSharedPreferences("settings", MODE_PRIVATE);
        int configured = ReverseSteeringButtonPreferences.load(preferences);
        long contextToken = CameraProbeActivity.reverseOwnerEpochSnapshot();
        dispatchSteeringDecision(
                steeringState.advanceTime(event.getEventTime(), configured, contextToken),
                preferences);
        configured = ReverseSteeringButtonPreferences.load(preferences);
        ReverseSteeringButtonPolicy.Decision decision = steeringState.apply(
                event.getAction(), event.getKeyCode(), event.getRepeatCount(),
                event.getDownTime(), event.getEventTime(), event.getFlags(), configured,
                contextToken, ViewConfiguration.getLongPressTimeout(), getMultiPressTimeout());
        syncSteeringTimeout();
        dispatchSteeringDecision(decision, preferences);
        return decision != ReverseSteeringButtonPolicy.Decision.PASS;
    }

    private void handleSteeringTimeout() {
        if (GuardRecovery.isUserShutdownActive(this)
                || LegacySettingsImporter.blocksRuntime(this)) {
            clearSteeringState();
            return;
        }
        SharedPreferences preferences = steeringPreferences != null
                ? steeringPreferences : getSharedPreferences("settings", MODE_PRIVATE);
        int configured = ReverseSteeringButtonPreferences.load(preferences);
        dispatchSteeringDecision(steeringState.advanceTime(SystemClock.uptimeMillis(), configured,
                CameraProbeActivity.reverseOwnerEpochSnapshot()), preferences);
        syncSteeringTimeout();
    }

    private void dispatchSteeringDecision(ReverseSteeringButtonPolicy.Decision decision,
            SharedPreferences preferences) {
        if (decision == ReverseSteeringButtonPolicy.Decision.LEARNED) {
            ReverseSteeringButtonPreferences.save(
                    preferences, steeringState.getConfirmedKeyCode());
            CameraProbeActivity.publishReverseSteeringButtonCaptured();
        } else if (decision == ReverseSteeringButtonPolicy.Decision.TOGGLE) {
            CameraHelperService.requestReverseSteeringToggle(this);
        }
    }

    private void syncSteeringTimeout() {
        steeringHandler.removeCallbacks(steeringTimeout);
        long deadline = steeringState.getDeadline();
        if (deadline >= 0L) steeringHandler.postAtTime(steeringTimeout, deadline);
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
