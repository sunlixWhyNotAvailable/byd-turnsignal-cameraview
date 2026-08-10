package com.byd.turnsignalguard.capture;

import android.graphics.Color;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

final class CameraProbeSettingsPanel {
    private static final String PREF_CAMERA_CORNER_RADIUS =
            BlindSpotOverlayController.PREF_CORNER_RADIUS;

    private static final int DEFAULT_CAMERA_CORNER_RADIUS_DP = 10;
    private static final int MAX_CAMERA_CORNER_RADIUS_DP = 48;

    private final CameraProbeActivity activity;
    private final SharedPreferences preferences;
    private final LinearLayout root;
    private final Switch autoStartSwitch;
    private final Button backgroundStartSettingsButton;
    private final TextView serviceStatus;
    private final TextView adbStatus;
    private final Button adbRetryButton;
    private final Button updateButton;
    private final Button clearLogsButton;
    private final Button shareLogsButton;
    private final Button shutdownButton;
    private final boolean shareLogsAvailable;
    private boolean logExportInProgress;
    private boolean clearLogsAllowed = true;
    private boolean shareLogsAllowed;

    CameraProbeSettingsPanel(CameraProbeActivity activity, SharedPreferences preferences) {
        this(activity, preferences, null);
    }

    CameraProbeSettingsPanel(
            CameraProbeActivity activity,
            SharedPreferences preferences,
            Runnable shareLogsAction) {
        this.activity = activity;
        this.preferences = preferences;

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, activity.dp(18), activity.dp(16));

        autoStartSwitch = new Switch(activity);
        autoStartSwitch.setText("Авто-запуск");
        autoStartSwitch.setTextColor(Color.WHITE);
        autoStartSwitch.setTextSize(20);
        autoStartSwitch.setChecked(GuardRecovery.isAutoStartEnabled(activity));
        root.addView(autoStartSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(58)));

        backgroundStartSettingsButton = activity.button("Налаштувати фоновий запуск DiLink");
        backgroundStartSettingsButton.setOnClickListener(
                view -> activity.openBackgroundStartSettings("manual"));
        root.addView(backgroundStartSettingsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(50)));

        serviceStatus = activity.statusText("Служба запускається...");
        root.addView(serviceStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(30)));

        TextView adbTitle = activity.label("ADB/RSA");
        adbTitle.setPadding(0, activity.dp(14), 0, activity.dp(4));
        root.addView(adbTitle);
        adbStatus = activity.statusText("Очікування стану ADB...");
        root.addView(adbStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(30)));
        adbRetryButton = activity.button("Повторити ADB авторизацію");
        adbRetryButton.setOnClickListener(view -> activity.requestAdbAuthorization(
                "adb_authorization_retry_ui", "retry_adb_auth", false));
        root.addView(adbRetryButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(50)));

        TextView radiusTitle = activity.label("Заокруглення камер");
        radiusTitle.setPadding(0, activity.dp(18), 0, activity.dp(4));
        root.addView(radiusTitle);
        LinearLayout radiusRow = new LinearLayout(activity);
        radiusRow.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar radius = new SeekBar(activity);
        radius.setMax(MAX_CAMERA_CORNER_RADIUS_DP);
        int initialRadius = Math.max(0, Math.min(MAX_CAMERA_CORNER_RADIUS_DP,
                preferences.getInt(PREF_CAMERA_CORNER_RADIUS,
                        DEFAULT_CAMERA_CORNER_RADIUS_DP)));
        radius.setProgress(initialRadius);
        TextView radiusValue = activity.label(initialRadius + " dp");
        radiusValue.setGravity(Gravity.CENTER);
        radiusRow.addView(radius, new LinearLayout.LayoutParams(0, activity.dp(54), 1));
        radiusRow.addView(radiusValue, new LinearLayout.LayoutParams(activity.dp(90), activity.dp(54)));
        root.addView(radiusRow);
        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                radiusValue.setText(progress + " dp");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress();
                preferences.edit().putInt(PREF_CAMERA_CORNER_RADIUS, value).apply();
                activity.onCameraCornerRadiusChanged(value);
            }
        });

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        updateButton = activity.button("Оновлення");
        clearLogsButton = activity.button("Очистити старі логи");
        shutdownButton = activity.button("Shutdown");
        actions.addView(updateButton, new LinearLayout.LayoutParams(0, activity.dp(52), 1));
        actions.addView(clearLogsButton, new LinearLayout.LayoutParams(0, activity.dp(52), 1));
        actions.addView(shutdownButton, new LinearLayout.LayoutParams(0, activity.dp(52), 1));
        root.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(56)));

        shareLogsButton = activity.button("Поділитися логами");
        shareLogsAvailable = shareLogsAction != null;
        shareLogsAllowed = shareLogsAvailable;
        shareLogsButton.setEnabled(shareLogsAvailable);
        if (shareLogsAction != null) {
            shareLogsButton.setOnClickListener(view -> shareLogsAction.run());
        }
        root.addView(shareLogsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(52)));

        autoStartSwitch.setOnCheckedChangeListener((button, checked) ->
                activity.onSettingsAutoStartChanged(checked));
        updateButton.setOnClickListener(view -> activity.runManualUpdateCheck());
        clearLogsButton.setOnClickListener(view -> activity.clearCaptureLogs());
        shutdownButton.setOnClickListener(view -> activity.requestAppShutdown());
    }

    View view() {
        return root;
    }

    void setServiceStatus(String text) {
        serviceStatus.setText(text);
    }

    void setAdbStatus(String text) {
        adbStatus.setText(text);
    }

    boolean isAdbStatus(String text) {
        return text.contentEquals(adbStatus.getText());
    }

    void setControlsEnabled(
            boolean shutdownRequested, boolean backgroundStartEnabled,
            boolean adbRetryEnabled, boolean clearLogsEnabled) {
        autoStartSwitch.setEnabled(!shutdownRequested);
        backgroundStartSettingsButton.setEnabled(backgroundStartEnabled);
        shutdownButton.setEnabled(!shutdownRequested);
        adbRetryButton.setEnabled(adbRetryEnabled);
        clearLogsAllowed = clearLogsEnabled;
        shareLogsAllowed = shareLogsAvailable && !shutdownRequested;
        clearLogsButton.setEnabled(clearLogsAllowed && !logExportInProgress);
        shareLogsButton.setEnabled(shareLogsAllowed && !logExportInProgress);
    }

    void setLogExportInProgress(boolean inProgress) {
        logExportInProgress = inProgress;
        clearLogsButton.setEnabled(clearLogsAllowed && !inProgress);
        shareLogsButton.setEnabled(shareLogsAllowed && !inProgress);
    }

    void setUpdateButton(String text, boolean enabled) {
        updateButton.setText(text);
        updateButton.setEnabled(enabled);
    }
}
