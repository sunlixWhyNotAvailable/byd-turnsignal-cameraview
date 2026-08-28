package com.byd.extend;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Small embedded controls for the local Open-Meteo weather adapter. */
public final class CameraProbeWeatherPanel {
    public static final String PREF_ENABLED = "weather_enabled";
    public static final String PREF_INTERVAL_MINUTES = "weather_interval_minutes";
    public static final int DEFAULT_INTERVAL_MINUTES = 15;
    public static final int MIN_INTERVAL_MINUTES = 5;
    public static final int MAX_INTERVAL_MINUTES = 180;
    public static final String OPEN_METEO_URL = "https://open-meteo.com/";

    public interface Listener {
        void onEnableRequested(boolean enabled);
        void onIntervalChanged(int intervalMinutes);
        void onManualRefresh();
    }

    private final CameraProbeActivity activity;
    private final SharedPreferences preferences;
    private final Listener listener;
    private final Switch enabledSwitch;
    private final EditText intervalInput;
    private final Button updateButton;
    private int intervalMinutes;
    private boolean busy;
    private boolean hostControlsEnabled = true;
    private boolean locationPermissionPending;
    private boolean suppressEnabledCallback;

    public CameraProbeWeatherPanel(
            CameraProbeActivity activity,
            SharedPreferences preferences,
            Listener listener) {
        this.activity = activity;
        this.preferences = preferences;
        this.listener = listener;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, activity.dp(12), activity.dp(18), activity.dp(12));

        TextView title = activity.label(activity.getString(R.string.weather_title));
        title.setTextSize(20);
        title.setContentDescription("Weather");
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(42)));

        enabledSwitch = new Switch(activity);
        enabledSwitch.setText(activity.getString(R.string.weather_enable));
        enabledSwitch.setTextColor(Color.WHITE);
        enabledSwitch.setTextSize(18);
        enabledSwitch.setChecked(preferences.getBoolean(PREF_ENABLED, false));
        root.addView(enabledSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(56)));

        LinearLayout intervalRow = new LinearLayout(activity);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView intervalLabel = activity.label(
                activity.getString(R.string.weather_interval_minutes));
        intervalRow.addView(intervalLabel, new LinearLayout.LayoutParams(0, activity.dp(54), 1));
        intervalInput = new EditText(activity);
        intervalInput.setTextColor(Color.WHITE);
        intervalInput.setTextSize(18);
        intervalInput.setGravity(Gravity.CENTER);
        intervalInput.setSingleLine(true);
        intervalInput.setSelectAllOnFocus(true);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        int storedIntervalMinutes = preferences.getInt(
                PREF_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES);
        intervalMinutes = clampInterval(storedIntervalMinutes);
        intervalInput.setText(Integer.toString(intervalMinutes));
        if (storedIntervalMinutes != intervalMinutes) {
            preferences.edit().putInt(PREF_INTERVAL_MINUTES, intervalMinutes).apply();
        }
        intervalRow.addView(intervalInput, new LinearLayout.LayoutParams(activity.dp(92), activity.dp(54)));
        root.addView(intervalRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(56)));

        updateButton = activity.button(activity.getString(R.string.weather_update_now));
        updateButton.setOnClickListener(view -> {
            commitInterval();
            if (!busy && listener != null) listener.onManualRefresh();
        });
        root.addView(updateButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(52)));

        TextView attribution = activity.label(activity.getString(R.string.weather_attribution));
        attribution.setTextColor(Color.CYAN);
        attribution.setPaintFlags(attribution.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        attribution.setClickable(true);
        attribution.setFocusable(true);
        attribution.setContentDescription("Open-Meteo attribution");
        attribution.setOnClickListener(view -> openAttribution());
        root.addView(attribution, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(38)));

        enabledSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (suppressEnabledCallback) return;
            updateWeatherControls();
            if (listener != null) listener.onEnableRequested(enabled);
        });
        intervalInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitInterval();
                return true;
            }
            return false;
        });
        intervalInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) commitInterval();
        });

        this.root = root;
        updateWeatherControls();
    }

    private final LinearLayout root;

    public View view() {
        return root;
    }

    public int intervalMinutes() {
        return intervalMinutes;
    }

    public boolean isEnabled() {
        return enabledSwitch.isChecked();
    }

    /** Changes the switch without invoking the listener (for a denied permission request). */
    public void setEnabledState(boolean enabled) {
        activity.runOnUiThread(() -> {
            if (!enabled) {
                busy = false;
                updateButton.setText(activity.getString(R.string.weather_update_now));
            }
            suppressEnabledCallback = true;
            enabledSwitch.setChecked(enabled);
            suppressEnabledCallback = false;
            preferences.edit().putBoolean(PREF_ENABLED, enabled).apply();
            updateWeatherControls();
        });
    }

    /** Disables all weather controls while the host is shutting down or unavailable. */
    public void setControlsEnabled(boolean enabled) {
        activity.runOnUiThread(() -> {
            hostControlsEnabled = enabled;
            enabledSwitch.setEnabled(enabled);
            updateWeatherControls();
        });
    }

    /** Locks the switch while the foreground Android permission dialog is active. */
    public void setLocationPermissionPending(boolean pending) {
        activity.runOnUiThread(() -> {
            locationPermissionPending = pending;
            updateWeatherControls();
        });
    }

    public void setBusy(boolean busy) {
        activity.runOnUiThread(() -> {
            this.busy = busy;
            updateButton.setText(busy
                    ? activity.getString(R.string.weather_updating)
                    : activity.getString(R.string.weather_update_now));
            updateWeatherControls();
        });
    }

    public void reportResult(boolean success, String message) {
        activity.runOnUiThread(() -> {
            busy = false;
            updateButton.setText(activity.getString(R.string.weather_update_now));
            updateWeatherControls();
            String text = message == null || message.isEmpty()
                    ? (success ? "Оновлено" : "Помилка оновлення") : message;
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        });
    }

    public static int clampInterval(int value) {
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, value));
    }

    private void commitInterval() {
        int value = intervalMinutes;
        try {
            String text = intervalInput.getText().toString().trim();
            if (!text.isEmpty()) value = Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        value = clampInterval(value);
        intervalInput.setText(Integer.toString(value));
        if (value == intervalMinutes) return;
        intervalMinutes = value;
        preferences.edit().putInt(PREF_INTERVAL_MINUTES, value).apply();
        if (listener != null) listener.onIntervalChanged(value);
    }

    private void openAttribution() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_METEO_URL)));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(activity, OPEN_METEO_URL, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateWeatherControls() {
        boolean enabled = hostControlsEnabled && enabledSwitch.isChecked();
        enabledSwitch.setEnabled(hostControlsEnabled && !locationPermissionPending);
        intervalInput.setEnabled(enabled && !locationPermissionPending);
        updateButton.setEnabled(enabled && !busy && !locationPermissionPending);
    }
}
