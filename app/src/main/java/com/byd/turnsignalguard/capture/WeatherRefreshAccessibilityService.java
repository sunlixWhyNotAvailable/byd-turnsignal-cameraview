package com.byd.turnsignalguard.capture;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Listens only for the stock WeatherData refresh ImageView click. */
public final class WeatherRefreshAccessibilityService extends AccessibilityService {
    public static final String WEATHER_PACKAGE = "com.byd.weatherdata";
    public static final String REFRESH_VIEW_ID = "com.byd.weatherdata:id/iv_refresh";

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
    public void onInterrupt() {
    }
}
