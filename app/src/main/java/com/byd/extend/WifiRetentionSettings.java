package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

/** Persists the user's Wi-Fi-while-off preference and its recovery intent together. */
public final class WifiRetentionSettings {
    public static final String KEY_ENABLED = "keep_wifi_car_off";

    private final SharedPreferences preferences;

    public WifiRetentionSettings(Context context) {
        Context appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        preferences = (appContext == null ? context : appContext)
                .getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        synchronized (WifiPolicyState.LOCK) {
            WifiPolicyState.Journal current = WifiPolicyState.Journal.read(preferences);
            WifiPolicyState.Journal next = current.toggle(enabled);
            if (next == current) return;
            WifiPolicyState.commit(preferences, next, current,
                    "Wi-Fi retention selection and policy operation were not persisted"
                            + ": operation=" + next.operation + " generation=" + next.generation);
        }
    }
}
