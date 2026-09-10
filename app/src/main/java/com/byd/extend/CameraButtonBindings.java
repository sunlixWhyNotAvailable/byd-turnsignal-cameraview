package com.byd.extend;

import android.content.SharedPreferences;

/** Persisted global camera-button assignments. */
public final class CameraButtonBindings {
    public static final int UNASSIGNED = -1;
    public static final String MIRROR_SOURCE_KEY_CODE =
            "mirror_source_steering_key_code";
    public static final String MIRROR_SOURCE_PRESS =
            "mirror_source_steering_press";
    public static final String MIRROR_VISIBILITY_KEY_CODE =
            "mirror_visibility_steering_key_code";
    public static final String MIRROR_VISIBILITY_PRESS =
            "mirror_visibility_steering_press";

    public enum Action { ReverseSource, MirrorSource, MirrorVisibility }

    public enum Press { Single, Hold, Double }

    public static final class Binding {
        public final int keyCode;
        public final Press press;

        public Binding(int keyCode, Press press) {
            this.keyCode = keyCode < 0 ? UNASSIGNED : canonicalKeyCode(keyCode);
            this.press = press == null ? Press.Single : press;
        }

        public boolean isAssigned() {
            return keyCode >= 0;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Binding)) return false;
            Binding value = (Binding) other;
            return keyCode == value.keyCode && press == value.press;
        }

        @Override
        public int hashCode() {
            return 31 * keyCode + press.hashCode();
        }
    }

    private CameraButtonBindings() {
    }

    public static Binding load(SharedPreferences preferences, Action action) {
        if (action == null) return new Binding(UNASSIGNED, Press.Single);
        if (action == Action.ReverseSource) {
            return new Binding(ReverseSteeringButtonPreferences.load(preferences), Press.Single);
        }
        String keyCodeKey = keyCodeKey(action);
        String pressKey = pressKey(action);
        int keyCode = UNASSIGNED;
        Press press = Press.Single;
        if (preferences != null) {
            try {
                keyCode = preferences.getInt(keyCodeKey, UNASSIGNED);
            } catch (RuntimeException ignored) {
                keyCode = UNASSIGNED;
            }
            try {
                press = Press.valueOf(preferences.getString(pressKey, Press.Single.name()));
            } catch (RuntimeException ignored) {
                press = Press.Single;
            }
        }
        return new Binding(keyCode, press);
    }

    public static void save(SharedPreferences preferences, Action action, Binding binding) {
        if (preferences == null || action == null) return;
        Binding value = binding == null ? new Binding(UNASSIGNED, Press.Single) : binding;
        if (action == Action.ReverseSource) {
            ReverseSteeringButtonPreferences.save(preferences, value.keyCode);
            return;
        }
        preferences.edit()
                .putInt(keyCodeKey(action), value.keyCode)
                .putString(pressKey(action), value.press.name())
                .apply();
    }

    public static void reset(SharedPreferences preferences, Action action) {
        save(preferences, action, new Binding(UNASSIGNED, load(preferences, action).press));
    }

    public static void reset(SharedPreferences preferences) {
        if (preferences == null) return;
        ReverseSteeringButtonPreferences.reset(preferences);
        preferences.edit()
                .putInt(MIRROR_SOURCE_KEY_CODE, UNASSIGNED)
                .putString(MIRROR_SOURCE_PRESS, Press.Single.name())
                .putInt(MIRROR_VISIBILITY_KEY_CODE, UNASSIGNED)
                .putString(MIRROR_VISIBILITY_PRESS, Press.Single.name())
                .apply();
    }

    private static String keyCodeKey(Action action) {
        return action == Action.MirrorSource
                ? MIRROR_SOURCE_KEY_CODE : MIRROR_VISIBILITY_KEY_CODE;
    }

    private static String pressKey(Action action) {
        return action == Action.MirrorSource
                ? MIRROR_SOURCE_PRESS : MIRROR_VISIBILITY_PRESS;
    }

    private static int canonicalKeyCode(int keyCode) {
        if (keyCode == 303) return 88;
        if (keyCode == 302) return 87;
        if (keyCode == 306) return 305;
        if (keyCode == 312) return 304;
        return keyCode;
    }
}
