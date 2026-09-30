package com.byd.extend;

import android.content.SharedPreferences;

/** Only preferences persist. A live microphone session never survives a process or power cycle. */
public final class AvasMicrophoneSettings {
    public static final String PROFILE = "microphone";
    public static final String ENABLED = "avas_microphone_enabled";
    public static final String VOLUME = "avas_microphone_volume";
    private AvasMicrophoneSettings() {}

    public static boolean enabled(SharedPreferences settings) {
        return settings.getBoolean(ENABLED, false);
    }

    public static int volume(SharedPreferences settings) {
        return Math.max(0, Math.min(100, settings.getInt(VOLUME, 15)));
    }
}
