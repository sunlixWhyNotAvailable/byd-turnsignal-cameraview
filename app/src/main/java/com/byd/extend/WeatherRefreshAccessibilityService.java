package com.byd.extend;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Listens for the stock WeatherData refresh click and the optional global Reverse key binding. */
public final class WeatherRefreshAccessibilityService extends AccessibilityService {
    public static final String WEATHER_PACKAGE = "com.byd.weatherdata";
    public static final String REFRESH_VIEW_ID = "com.byd.weatherdata:id/iv_refresh";

    private static volatile WeatherRefreshAccessibilityService activeService;
    private final ReverseSteeringButtonPolicy.State steeringState =
            new ReverseSteeringButtonPolicy.State();

    static boolean beginSteeringButtonLearning(Context context) {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null || GuardRecovery.isUserShutdownActive(service)
                || LegacySettingsImporter.blocksRuntime(service)) return false;
        service.steeringState.beginLearning();
        return true;
    }

    static void cancelSteeringButtonLearning() {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null) return;
        service.steeringState.cancel();
    }

    static boolean isSteeringButtonLearning() {
        WeatherRefreshAccessibilityService service = activeService;
        if (service == null) return false;
        return service.steeringState.isLearning();
    }

    private void clearSteeringState() {
        steeringState.cancel();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
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
            // Explicit shutdown/legacy handover ends interception entirely.  Clear both capture
            // and held-tail state so a later reconnect cannot replay a stale physical press.
            clearSteeringState();
            return false;
        }
        int configured = ReverseSteeringButtonPreferences.load(
                getSharedPreferences("settings", MODE_PRIVATE));
        ReverseSteeringButtonPolicy.Decision decision = steeringState.apply(
                event.getAction(), event.getKeyCode(), event.getRepeatCount(),
                event.getDownTime(), configured);
        if (decision == ReverseSteeringButtonPolicy.Decision.LEARNED) {
            ReverseSteeringButtonPreferences.save(
                    getSharedPreferences("settings", MODE_PRIVATE), event.getKeyCode());
            CameraProbeActivity.publishReverseSteeringButtonCaptured();
            return true;
        }
        if (decision == ReverseSteeringButtonPolicy.Decision.TOGGLE) {
            CameraHelperService.requestReverseSteeringToggle(this);
            return true;
        }
        return decision == ReverseSteeringButtonPolicy.Decision.CONSUME;
    }

    @Override
    public void onInterrupt() {
        clearSteeringState();
    }

    @Override
    public void onDestroy() {
        clearSteeringState();
        if (activeService == this) activeService = null;
        super.onDestroy();
    }
}
