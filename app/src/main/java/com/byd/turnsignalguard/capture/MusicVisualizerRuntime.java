package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiConsumer;

final class MusicVisualizerRuntime {
    static final String MEDIA_SELECTOR = "com.byd.mediacenter";
    static final long STOP_DEBOUNCE_MS = 3_000;
    private static final long STOP_RETRY_MS = 1_000;
    static final int MAX_STOP_RETRIES = 3;

    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final AudioManager audioManager;
    private final MusicMetadataRuntime metadataRuntime;
    private final AudioManager.AudioPlaybackCallback playbackCallback;
    private final Runnable deferredStop = this::finishDeferredStop;
    private final Runnable retryStop = this::retryStop;

    private boolean enabled;
    private boolean awake;
    private boolean callbackRegistered;
    private boolean mediaActive;
    private boolean outputActive;
    private boolean stopPending;
    private boolean stopRetryPending;
    private boolean stopRetryMustComplete;
    private int stopRetryAttempts;
    private String error = "";

    MusicVisualizerRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.handler = handler;
        this.eventSink = eventSink;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        metadataRuntime = new MusicMetadataRuntime(context, handler, eventSink);
        playbackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(
                    List<AudioPlaybackConfiguration> configurations) {
                runOnHandler(() -> applyPlaybackConfigurations(configurations, "callback"));
            }
        };
        selfCheck();
    }

    void configure(boolean value) {
        runOnHandler(() -> configureOnHandler(value));
    }

    void powerStateChanged(boolean interactive) {
        runOnHandler(() -> powerStateChangedOnHandler(interactive));
    }

    void stop() {
        runOnHandler(() -> {
            boolean wasEnabled = enabled;
            metadataRuntime.stop();
            enabled = false;
            awake = false;
            deactivate("shutdown");
            if (wasEnabled) emitConfig("shutdown");
        });
    }

    void reportStatus() {
        runOnHandler(() -> {
            emitStatus("report");
            metadataRuntime.reportStatus();
        });
    }

    private void configureOnHandler(boolean value) {
        metadataRuntime.configure(value);
        if (enabled == value) {
            if (enabled && awake && !callbackRegistered) activate("configure_retry");
            return;
        }
        enabled = value;
        if (enabled) {
            emitConfig("configure");
            if (awake) activate("configure");
        } else {
            deactivate("disabled");
            emitConfig("configure");
        }
    }

    private void powerStateChangedOnHandler(boolean interactive) {
        metadataRuntime.powerStateChanged(interactive);
        if (awake == interactive) return;
        awake = interactive;
        if (awake && enabled) activate("wake");
        else if (!awake) deactivate("sleep");
    }

    private void activate(String reason) {
        if (!enabled || !awake || callbackRegistered) return;
        if (audioManager == null) {
            setRuntimeError("audio_manager_unavailable", reason);
            return;
        }
        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
            callbackRegistered = true;
            clearError();
        } catch (Throwable failure) {
            callbackRegistered = false;
            setMediaActive(false, "registration_error");
            cancelDeferredStop("registration_error");
            stopOutput("registration_error");
            setRuntimeError("callback_registration: " + summary(failure), reason);
            return;
        }
        try {
            applyPlaybackConfigurations(
                    audioManager.getActivePlaybackConfigurations(), "initial");
        } catch (Throwable failure) {
            setMediaActive(false, "initial_query_error");
            setRuntimeError("initial_playback_query: " + summary(failure), reason);
        }
    }

    private void deactivate(String reason) {
        cancelDeferredStop(reason);
        setMediaActive(false, reason);
        stopOutput(reason);
        unregisterCallback(reason);
    }

    private void unregisterCallback(String reason) {
        if (!callbackRegistered) return;
        callbackRegistered = false;
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        } catch (Throwable failure) {
            setRuntimeError("callback_unregistration: " + summary(failure), reason);
        }
    }

    private void applyPlaybackConfigurations(
            List<AudioPlaybackConfiguration> configurations, String source) {
        if (!callbackRegistered || !enabled || !awake) return;
        metadataRuntime.audioPlaybackChanged(source);
        boolean nextActive = hasMediaPlayback(configurations);
        if (nextActive == mediaActive) {
            if (!nextActive && outputActive) scheduleDeferredStop(source);
            return;
        }
        if (nextActive) {
            cancelDeferredStop(source);
            cancelStopRetry();
            setMediaActive(true, source);
            startOutput(source);
        } else {
            setMediaActive(false, source);
            if (outputActive) scheduleDeferredStop(source);
        }
    }

    private void setMediaActive(boolean value, String reason) {
        if (mediaActive == value) return;
        mediaActive = value;
        emit("music_playback_state",
                "active", value, "source_event", reason,
                "session_active", outputActive, "stop_pending", stopPending);
    }

    private void startOutput(String reason) {
        if (!shouldStartOutput(enabled, awake, callbackRegistered, mediaActive, outputActive)) {
            return;
        }
        outputActive = true;
        try {
            Method startAudioOutput = audioManager.getClass().getMethod(
                    "startAudioOutput", String.class);
            startAudioOutput.invoke(audioManager, MEDIA_SELECTOR);
            clearError();
            emit("music_visualizer_start",
                    "ok", true, "selector", MEDIA_SELECTOR,
                    "source_event", reason, "session_active", true, "error", "");
        } catch (Throwable failure) {
            String value = "startAudioOutput: " + summary(failure);
            emit("music_visualizer_start",
                    "ok", false, "selector", MEDIA_SELECTOR,
                    "source_event", reason, "session_active", true,
                    "error", value);
            stopOutput("start_failed_cleanup");
            setRuntimeError(outputActive ? value + "; cleanup_pending" : value, reason);
        }
    }

    private void stopOutput(String reason) {
        if (!outputActive) return;
        try {
            Method stopAudioOutput = audioManager.getClass().getMethod(
                    "stopAudioOutput", String.class);
            stopAudioOutput.invoke(audioManager, MEDIA_SELECTOR);
            outputActive = false;
            stopRetryPending = false;
            stopRetryMustComplete = false;
            stopRetryAttempts = 0;
            handler.removeCallbacks(retryStop);
            clearError();
            emit("music_visualizer_stop",
                    "ok", true, "selector", MEDIA_SELECTOR,
                    "source_event", reason, "session_active", false, "error", "");
        } catch (Throwable failure) {
            if ("start_failed_cleanup".equals(reason)) stopRetryMustComplete = true;
            String value = "stopAudioOutput: " + summary(failure);
            if (!error.equals(value)) {
                error = value;
                emit("music_visualizer_stop",
                        "ok", false, "selector", MEDIA_SELECTOR,
                        "source_event", reason, "session_active", true,
                        "error", error);
            }
            if (shouldScheduleStopRetry(stopRetryPending, stopRetryAttempts)) {
                stopRetryAttempts++;
                stopRetryPending = true;
                handler.postDelayed(retryStop, STOP_RETRY_MS);
            } else if (!stopRetryPending && stopRetryAttempts >= MAX_STOP_RETRIES) {
                emit("music_runtime_error", "error", "stop_retry_exhausted",
                        "source_event", reason, "session_active", true,
                        "retry_attempts", stopRetryAttempts);
                handler.postDelayed(() -> Process.killProcess(Process.myPid()), 100);
            }
        }
    }

    private void retryStop() {
        stopRetryPending = false;
        if (shouldCancelStopRetry(
                mediaActive, enabled, awake, stopRetryMustComplete)) {
            stopRetryAttempts = 0;
            return;
        }
        stopOutput("stop_retry");
    }

    private void cancelStopRetry() {
        if (!stopRetryPending || stopRetryMustComplete) return;
        stopRetryPending = false;
        stopRetryAttempts = 0;
        handler.removeCallbacks(retryStop);
    }

    private void scheduleDeferredStop(String reason) {
        if (!shouldScheduleStop(outputActive, mediaActive, stopPending)) return;
        stopPending = true;
        handler.postDelayed(deferredStop, STOP_DEBOUNCE_MS);
        emit("music_visualizer_stop_pending",
                "delay_ms", STOP_DEBOUNCE_MS, "source_event", reason,
                "session_active", outputActive, "stop_pending", true);
    }

    private void cancelDeferredStop(String reason) {
        if (!stopPending) return;
        stopPending = false;
        handler.removeCallbacks(deferredStop);
    }

    private void finishDeferredStop() {
        if (!stopPending) return;
        stopPending = false;
        if (!mediaActive) stopOutput("debounce_elapsed");
    }

    private void setRuntimeError(String value, String reason) {
        if (error.equals(value)) return;
        error = value;
        emit("music_runtime_error", "error", error, "source_event", reason,
                "enabled", enabled, "awake", awake,
                "callback_registered", callbackRegistered,
                "playback_active", mediaActive, "session_active", outputActive,
                "stop_pending", stopPending, "selector", MEDIA_SELECTOR);
    }

    private void clearError() {
        error = "";
    }

    private void emitConfig(String reason) {
        emit("music_runtime_config", "enabled", enabled,
                "source_event", reason, "awake", awake,
                "callback_registered", callbackRegistered,
                "playback_active", mediaActive, "session_active", outputActive,
                "stop_pending", stopPending, "error", error);
    }

    private void emitStatus(String reason) {
        emit("music_runtime_status",
                "source_event", reason, "enabled", enabled, "awake", awake,
                "callback_registered", callbackRegistered,
                "playback_active", mediaActive,
                "session_active", outputActive,
                "stop_pending", stopPending,
                "selector", MEDIA_SELECTOR,
                "stop_debounce_ms", STOP_DEBOUNCE_MS,
                "error", error);
    }

    static boolean hasMediaPlayback(List<AudioPlaybackConfiguration> configurations) {
        if (configurations == null) return false;
        for (AudioPlaybackConfiguration configuration : configurations) {
            if (configuration == null) continue;
            AudioAttributes attributes = configuration.getAudioAttributes();
            if (attributes != null && isMediaPlayback(
                    isActive(configuration),
                    attributes.getUsage(), attributes.getContentType())) return true;
        }
        return false;
    }

    static boolean isMediaPlayback(boolean active, int usage, int contentType) {
        return active && isMusicAttributes(usage, contentType);
    }

    static boolean isMusicAttributes(int usage, int contentType) {
        return usage == AudioAttributes.USAGE_MEDIA
                || contentType == AudioAttributes.CONTENT_TYPE_MUSIC;
    }

    static boolean shouldStartOutput(
            boolean enabled, boolean awake, boolean callbackRegistered,
            boolean mediaActive, boolean outputActive) {
        return enabled && awake && callbackRegistered && mediaActive && !outputActive;
    }

    static boolean shouldScheduleStop(
            boolean outputActive, boolean mediaActive, boolean stopPending) {
        return outputActive && !mediaActive && !stopPending;
    }

    static boolean shouldScheduleStopRetry(boolean pending, int attempts) {
        return !pending && attempts < MAX_STOP_RETRIES;
    }

    static boolean shouldCancelStopRetry(
            boolean mediaActive, boolean enabled, boolean awake, boolean mustComplete) {
        return !mustComplete && mediaActive && enabled && awake;
    }

    private static boolean isActive(AudioPlaybackConfiguration configuration) {
        try {
            Method isActive = configuration.getClass().getMethod("isActive");
            return Boolean.TRUE.equals(isActive.invoke(configuration));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void runOnHandler(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private static void selfCheck() {
        if (!isMediaPlayback(
                        true, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN)
                || isMediaPlayback(
                        false, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)
                || !isMusicAttributes(
                        AudioAttributes.USAGE_UNKNOWN, AudioAttributes.CONTENT_TYPE_MUSIC)
                || isMusicAttributes(
                        AudioAttributes.USAGE_ALARM, AudioAttributes.CONTENT_TYPE_SONIFICATION)) {
            throw new IllegalStateException("Music playback filter self-check failed");
        }
    }

    private static String summary(Throwable error) {
        Throwable root = error;
        while (root instanceof InvocationTargetException && root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
