package com.byd.extend;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Persistent output choices; package names survive reinstalls and UID changes. */
public final class MusicSettings {
    public static final String ARTWORK = "music_show_artwork";
    public static final String PLAYER_ICON = "music_show_player_icon";
    public static final boolean DEFAULT_SHOW_ARTWORK = true;
    public static final boolean DEFAULT_SHOW_PLAYER_ICON = true;
    public static final String EXCLUDED = "music_visualization_excluded_apps";

    private MusicSettings() {}

    public static Set<String> excluded(SharedPreferences preferences) {
        return decode(preferences.getString(EXCLUDED, "[]"));
    }

    public static Set<String> decode(String json) {
        try {
            JSONArray array = new JSONArray(json);
            Set<String> result = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object value = array.get(i);
                if (!(value instanceof String) || !MusicMediaKeyPolicy.isValidPackage((String) value))
                    throw new IllegalArgumentException("Invalid visualization exclusion package");
                result.add((String) value);
            }
            return Collections.unmodifiableSet(result);
        } catch (JSONException failure) {
            throw new IllegalArgumentException("Invalid visualization exclusions", failure);
        }
    }

    public static String encode(Set<String> packages) {
        String json = new JSONArray(new java.util.TreeSet<>(packages)).toString();
        decode(json);
        return json;
    }

    static boolean excludesPlaying(Set<String> playing, Set<String> excluded) {
        // shortcut: sessions identify players, not individual tracks in the OEM audio mix.
        return !playing.isEmpty() && excluded.containsAll(playing);
    }
}
