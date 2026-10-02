package com.byd.extend;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.List;
import java.util.function.BiConsumer;

/** Observe playback, not logcat: foreign NAV can use stream 3 as well as OEM stream 15. */
final class AvasNavigationMonitor implements AutoCloseable {
    private final AudioManager manager;
    private final BiConsumer<Boolean, String> changed;
    private final HandlerThread thread = new HandlerThread("avas-navigation-priority");
    private final Handler handler;
    private volatile boolean enabled;
    private volatile boolean closed;
    private boolean registered;
    private final AudioManager.AudioPlaybackCallback callback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            if (enabled && !closed) inspect(configs);
        }
    };

    AvasNavigationMonitor(AudioManager manager, BiConsumer<Boolean, String> changed) {
        this.manager = manager;
        this.changed = changed;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void configure(boolean enabled) {
        if (closed || this.enabled == enabled) return;
        this.enabled = enabled;
        changed.accept(enabled, enabled ? "checking_playback" : "disabled");
        handler.post(() -> {
            if (closed) return;
            if (!this.enabled) {
                unregister();
                changed.accept(false, "disabled");
                return;
            }
            try {
                if (!registered) {
                    manager.registerAudioPlaybackCallback(callback, handler);
                    registered = true;
                }
                inspect(manager.getActivePlaybackConfigurations());
            } catch (Exception failure) {
                changed.accept(true, "observer_unavailable: " + failure);
            }
        });
    }

    void execute(Runnable action) { if (!closed) handler.post(action); }

    private void inspect(List<AudioPlaybackConfiguration> configs) {
        try {
            if (configs == null) throw new IllegalStateException("Playback snapshot unavailable");
            boolean foreign = false;
            for (AudioPlaybackConfiguration config : configs) {
                if (!Boolean.TRUE.equals(config.getClass().getMethod("isActive").invoke(config))) continue;
                AudioAttributes attributes = config.getAudioAttributes();
                if (attributes == null) throw new IllegalStateException("Playback attributes unavailable");
                if (MusicPlaybackSource.isOwned(config)) continue;
                int flags = ((Number) AudioAttributes.class.getMethod("getAllFlags")
                        .invoke(attributes)).intValue();
                int stream = attributes.getVolumeControlStream();
                foreign |= isNavigation(attributes.getUsage(), attributes.getContentType(), flags, stream);
            }
            changed.accept(foreign, foreign ? "foreign_navigation" : "navigation_finished");
        } catch (Exception failure) {
            changed.accept(true, "classification_unavailable: " + failure);
        }
    }

    static boolean isNavigation(int usage, int content, int flags, int stream) {
        return usage == 12 || content == 6 || stream == 15
                || (flags & 0xc000) != 0 // OEM FLAG_NAVI_UE / FLAG_NAVI_GAODE_VEHICLE
                || (flags & 0x20800) == 0x20800; // captured effective NAV attributes
    }

    private void unregister() {
        if (!registered) return;
        try { manager.unregisterAudioPlaybackCallback(callback); } catch (RuntimeException ignored) { }
        registered = false;
    }

    @Override public void close() {
        closed = true;
        unregister();
        thread.quitSafely();
    }
}
