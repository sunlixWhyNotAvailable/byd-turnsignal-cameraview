package com.byd.extend;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/** App-owned live capture. Only a bounded OS pipe crosses into the authenticated helper. */
final class AvasMicrophoneCapture {
    static final String ACTION_STOP = "com.byd.extend.action.STOP_MICROPHONE";
    private static final String CHANNEL = "avas_microphone";
    private static final int NOTIFICATION = 8714;
    private static volatile String state = "stopped";
    private final WeatherRefreshAccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session session;

    private static final class Session {
        final String id = UUID.randomUUID().toString().replace("-", "");
        volatile boolean cancelled;
        volatile AudioRecord recorder;
        volatile OutputStream output;
        volatile boolean noiseSuppression;
        volatile boolean echoCancellation;
        View indicator;
        WindowManager indicatorWindows;
        Runnable timeout;
    }

    AvasMicrophoneCapture(WeatherRefreshAccessibilityService service) { this.service = service; }
    static String state() { return state; }
    static boolean busy() { return "starting".equals(state) || "active".equals(state); }

    boolean owns(String id) {
        Session current = session;
        return current != null && !current.cancelled && current.id.equals(id);
    }

    void toggle() {
        if (session != null) stop(false); else start();
    }

    void start() {
        if (session != null) return;
        if (!AvasMicrophoneSettings.enabled(service.getSharedPreferences("settings", 0))
                || GuardRecovery.isUserShutdownActive(service)
                || LegacySettingsImporter.blocksRuntime(service)) return;
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setState("error");
            toast(R.string.avas_mic_permission);
            return;
        }
        Session current = new Session();
        session = current;
        processing(service.getSharedPreferences("settings", 0));
        setState("starting");
        try {
            notification(R.string.avas_mic_starting);
            current.timeout = () -> fail(current, new IOException("helper_start_timeout"));
            main.postDelayed(current.timeout, 10_000);
            new Thread(() -> capture(current), "avas-microphone-capture").start();
        } catch (RuntimeException failure) {
            fail(current, failure);
        }
    }

    private void capture(Session current) {
        AudioRecord recorder = null;
        ParcelFileDescriptor readEnd = null;
        AvasMicrophoneEffects effects = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("capture_format_unavailable");
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, 3_200));
            current.recorder = recorder;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IOException("capture_not_initialized");
            if (current.cancelled) return;
            effects = new AvasMicrophoneEffects(recorder.getAudioSessionId(),
                    label -> main.post(() -> {
                        if (session != current || current.cancelled) return;
                        Context locale = localized();
                        Toast.makeText(service, locale.getString(R.string.avas_mic_effect_failed,
                                locale.getString(label)), Toast.LENGTH_LONG).show();
                    }));
            effects.apply(current.noiseSuppression, current.echoCancellation);
            if (current.cancelled) return;
            if (Build.VERSION.SDK_INT >= 29) {
                recorder.registerAudioRecordingCallback(main::post,
                        new AudioManager.AudioRecordingCallback() {
                            @Override public void onRecordingConfigChanged(
                                    List<AudioRecordingConfiguration> configurations) {
                                if (current.cancelled || session != current) return;
                                try {
                                    AudioRecord active = current.recorder;
                                    AudioRecordingConfiguration config = active == null ? null
                                            : active.getActiveRecordingConfiguration();
                                    if (config != null && config.isClientSilenced())
                                        fail(current, new IOException("capture_silenced_by_system"));
                                } catch (RuntimeException unavailable) { fail(current, unavailable); }
                            }
                        });
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("capture_not_recording");
            if (Build.VERSION.SDK_INT >= 29) {
                AudioRecordingConfiguration config = recorder.getActiveRecordingConfiguration();
                if (config != null && config.isClientSilenced())
                    throw new IOException("capture_silenced_by_system");
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readEnd = pipe[0];
            current.output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            if (current.cancelled) return;
            ParcelFileDescriptor transferred = readEnd;
            readEnd = null; // CameraHelperService owns this end even when submission fails.
            if (!CameraHelperService.startAvasMicrophone(current.id, transferred,
                    AvasMicrophoneSettings.volume(service.getSharedPreferences("settings", 0))))
                throw new IOException("helper_unavailable");
            byte[] pcm = new byte[640]; // 20 ms; pipe backpressure bounds all queued voice data.
            while (!current.cancelled) {
                effects.apply(current.noiseSuppression, current.echoCancellation);
                int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) throw new IOException("capture_read_" + count);
                if (!current.cancelled) current.output.write(pcm, 0, count);
            }
        } catch (Exception failure) {
            if (!current.cancelled) main.post(() -> fail(current, failure));
        } finally {
            close(readEnd);
            close(current.output);
            current.output = null;
            if (effects != null) effects.close();
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) {}
                try { recorder.release(); } catch (RuntimeException ignored) {}
                current.recorder = null;
            }
        }
    }

    void accept(JSONObject event) {
        main.post(() -> {
            Session current = session;
            if (current == null) return;
            JSONObject status = "avas_status".equals(event.optString("kind"))
                    ? event.optJSONObject("microphone") : event;
            if (status == null || !owns(status.optString("session_id"))) return;
            String next = status.optString("state");
            if ("active".equals(next)) {
                main.removeCallbacks(current.timeout);
                setState("active");
                try { notification(R.string.avas_mic_active); }
                catch (RuntimeException failure) {
                    fail(current, failure);
                    return;
                }
                showBroadcastIndicator(current);
            } else if ("error".equals(next)) {
                fail(current, new IOException("helper_microphone_error"));
            } else if ("stopped".equals(next) || "stopping".equals(next)) stop(false);
        });
    }

    void volume(int volume) {
        Session current = session;
        if (current != null) CameraHelperService.setAvasMicrophoneVolume(current.id, volume);
    }

    void processing(SharedPreferences preferences) {
        Session current = session;
        if (current != null) {
            current.noiseSuppression = AvasMicrophoneSettings.noiseSuppression(preferences);
            current.echoCancellation = AvasMicrophoneSettings.echoCancellation(preferences);
        }
    }

    void unavailable(String sessionId) {
        main.post(() -> {
            Session current = session;
            if (current != null && current.id.equals(sessionId))
                fail(current, new IOException("helper_unavailable"));
        });
    }

    void stop(boolean error) {
        Session current = session;
        session = null;
        if (current != null) {
            current.cancelled = true;
            if (current.timeout != null) main.removeCallbacks(current.timeout);
            removeBroadcastIndicator(current);
            CameraHelperService.stopAvasMicrophone(current.id);
            close(current.output);
            AudioRecord recorder = current.recorder;
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) {}
            }
        }
        service.stopForeground(true);
        setState(error ? "error" : "stopped");
    }

    private void fail(Session current, Exception failure) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> fail(current, failure));
            return;
        }
        if (session != current || current.cancelled) return;
        // Diagnostic state only: never log samples or save a recording.
        Log.w("BydAvasMicrophone", "Capture stopped", failure);
        stop(true);
        toast(R.string.avas_mic_unavailable);
    }

    private void setState(String value) {
        state = value;
        CameraProbeActivity.publishAvasMicrophoneChanged();
    }

    private Context localized() {
        return AppLanguage.localizedContext(service,
                AppLanguage.read(service.getSharedPreferences("settings", 0)));
    }

    private void toast(int message) {
        Toast.makeText(service, localized().getString(message), Toast.LENGTH_LONG).show();
    }

    private void notification(int text) {
        Context locale = localized();
        NotificationManager manager = service.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                locale.getString(R.string.avas_mic_title), NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(service, 8714,
                new Intent(service, WeatherRefreshAccessibilityService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(service, 8714,
                new Intent(service, CameraProbeActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(service, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(locale.getString(R.string.avas_mic_title))
                .setContentText(locale.getString(text)).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,
                        locale.getString(R.string.avas_mic_stop), stop).build()).build();
        if (Build.VERSION.SDK_INT >= 30)
            service.startForeground(NOTIFICATION, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else service.startForeground(NOTIFICATION, notification);
    }

    private void showBroadcastIndicator(Session current) {
        if (session != current || current.cancelled || current.indicator != null) return;
        try {
            Context locale = localized();
            TextView indicator = new TextView(locale);
            indicator.setText(locale.getString(R.string.avas_mic_broadcast_indicator));
            indicator.setTextColor(Color.WHITE);
            indicator.setTextSize(14);
            indicator.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            indicator.setGravity(Gravity.CENTER_VERTICAL);
            int horizontal = dp(16);
            indicator.setPadding(horizontal, dp(8), horizontal, dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE620242B);
            background.setCornerRadius(dp(24));
            indicator.setBackground(background);
            Drawable microphone = service.getDrawable(android.R.drawable.ic_btn_speak_now).mutate();
            microphone.setTint(Color.WHITE);
            indicator.setCompoundDrawablesRelativeWithIntrinsicBounds(microphone, null, null, null);
            indicator.setCompoundDrawablePadding(dp(8));

            WindowManager windows = service.getSystemService(WindowManager.class);
            if (windows == null) throw new IllegalStateException("window_manager_unavailable");
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = topInset(windows) + dp(8);
            params.setTitle("BYD Extend microphone broadcast indicator");
            current.indicator = indicator;
            current.indicatorWindows = windows;
            windows.addView(indicator, params);
        } catch (RuntimeException failure) {
            removeBroadcastIndicator(current);
            Log.w("BydAvasMicrophone", "Broadcast indicator window failed", failure);
            try { toast(R.string.avas_mic_indicator_unavailable); }
            catch (RuntimeException toastFailure) {
                Log.w("BydAvasMicrophone", "Indicator failure toast unavailable", toastFailure);
            }
        }
    }

    private void removeBroadcastIndicator(Session current) {
        View indicator = current.indicator;
        WindowManager windows = current.indicatorWindows;
        current.indicator = null;
        current.indicatorWindows = null;
        if (indicator == null || windows == null) return;
        try {
            windows.removeViewImmediate(indicator);
        } catch (IllegalArgumentException notAdded) {
            // addView may have failed before WindowManager registered the view.
        } catch (RuntimeException failure) {
            Log.w("BydAvasMicrophone", "Broadcast indicator removal failed", failure);
        }
    }

    private int topInset(WindowManager windows) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windows.getMaximumWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout()).top;
        }
        int id = service.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : service.getResources().getDimensionPixelSize(id);
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    private static void close(Closeable value) {
        if (value != null) try { value.close(); } catch (IOException ignored) {}
    }
}
