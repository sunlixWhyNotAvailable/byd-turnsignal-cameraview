package com.byd.extend;

import android.content.SharedPreferences;

/** Persisted global button assignments. */
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
    public static final String MIRROR_SOURCE_SOURCE =
            "mirror_source_steering_source";
    public static final String MIRROR_VISIBILITY_SOURCE =
            "mirror_visibility_steering_source";

    public static final String MICROPHONE_KEY_CODE = "avas_microphone_key_code";
    public static final String MICROPHONE_PRESS = "avas_microphone_press";
    public static final String MICROPHONE_SOURCE = "avas_microphone_source";
    public static final String ENGINE_KEY_CODE = "avas_engine_key_code";
    public static final String ENGINE_PRESS = "avas_engine_press";
    public static final String ENGINE_SOURCE = "avas_engine_source";
    public static final String HAZARD_THANKS_KEY_CODE = "hazard_thanks_steering_key_code";
    public static final String HAZARD_THANKS_PRESS = "hazard_thanks_steering_press";
    public static final String HAZARD_THANKS_SOURCE = "hazard_thanks_steering_source";

    public enum Action {
        ReverseSource, MirrorSource, MirrorVisibility, AvasMicrophone, AvasEngine, HazardThanks
    }

    public enum Press { Single, Hold, Double }

    public enum Source {
        AndroidKey(R.string.calls_source_android_key),
        Sos(R.string.calls_source_sos),
        Help(R.string.calls_source_help);

        public final int labelResourceId;

        Source(int labelResourceId) {
            this.labelResourceId = labelResourceId;
        }
    }

    public static final class Binding {
        public final Source source;
        public final int keyCode;
        public final Press press;

        public Binding(int keyCode, Press press) {
            this(Source.AndroidKey, keyCode, press);
        }

        public Binding(Source source, Press press) {
            this(source, UNASSIGNED, press);
        }

        public Binding(Source source, int keyCode, Press press) {
            this.source = source == null ? Source.AndroidKey : source;
            this.keyCode = this.source == Source.AndroidKey
                    ? (keyCode < 0 ? UNASSIGNED : canonicalKeyCode(keyCode)) : UNASSIGNED;
            this.press = press == null ? Press.Single : press;
        }

        public boolean isAssigned() {
            return source != Source.AndroidKey || keyCode >= 0;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Binding)) return false;
            Binding value = (Binding) other;
            return source == value.source && keyCode == value.keyCode && press == value.press;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * source.hashCode() + keyCode) + press.hashCode();
        }
    }

    private CameraButtonBindings() {
    }

    public static Binding load(SharedPreferences preferences, Action action) {
        return load(preferences, action, null);
    }

    public static Binding loadForSource(SharedPreferences preferences, Action action, Source source) {
        return load(preferences, action, source);
    }

    private static Binding load(SharedPreferences preferences, Action action, Source requestedSource) {
        if (action == null) return new Binding(UNASSIGNED, Press.Single);
        if (action == Action.ReverseSource) {
            return new Binding(ReverseSteeringButtonPreferences.load(preferences), Press.Single);
        }
        String keyCodeKey = keyCodeKey(action);
        String pressKey = pressKey(action);
        String sourceKey = sourceKey(action);
        int keyCode = UNASSIGNED;
        Press press = Press.Single;
        Source source = Source.AndroidKey;
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
            try {
                source = Source.valueOf(preferences.getString(sourceKey, Source.AndroidKey.name()));
            } catch (RuntimeException ignored) {
                source = Source.AndroidKey;
            }
            if (requestedSource == Source.AndroidKey && source != Source.AndroidKey) {
                try {
                    press = Press.valueOf(preferences.getString(steeringPressKey(action), press.name()));
                } catch (RuntimeException ignored) {
                    press = Press.Single;
                }
            }
        }
        if (requestedSource != null) {
            source = requestedSource;
            if (!supports(action, source, press)) press = Press.Single;
        }
        return new Binding(source, keyCode,
                action == Action.AvasMicrophone ? Press.Single : press);
    }

    public static void save(SharedPreferences preferences, Action action, Binding binding) {
        if (preferences == null || action == null) return;
        Binding value = binding == null
                ? new Binding(Source.AndroidKey, UNASSIGNED, Press.Single) : binding;
        if (value.source == Source.AndroidKey
                && (action == Action.ReverseSource || action == Action.AvasMicrophone)) {
            value = new Binding(Source.AndroidKey, value.keyCode, Press.Single);
        }
        if (!supports(action, value.source, value.press)) return;
        if (action == Action.ReverseSource) {
            ReverseSteeringButtonPreferences.save(preferences, value.keyCode);
            return;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putString(pressKey(action), (action == Action.AvasMicrophone
                        ? Press.Single : value.press).name())
                .putString(sourceKey(action), value.source.name());
        if (value.source == Source.AndroidKey) {
            editor.putInt(keyCodeKey(action), value.keyCode)
                    .putString(steeringPressKey(action), value.press.name());
        } else {
            // Rescue sources must not overwrite the learned steering key or its press type.
            Binding previous = load(preferences, action);
            if (previous.source == Source.AndroidKey) {
                editor.putString(steeringPressKey(action), previous.press.name());
            }
        }
        editor.apply();
    }

    public static void reset(SharedPreferences preferences, Action action) {
        save(preferences, action, new Binding(Source.AndroidKey, UNASSIGNED,
                load(preferences, action).press));
    }

    public static void reset(SharedPreferences preferences) {
        if (preferences == null) return;
        ReverseSteeringButtonPreferences.reset(preferences);
        preferences.edit()
                .putInt(MIRROR_SOURCE_KEY_CODE, UNASSIGNED)
                .putString(MIRROR_SOURCE_PRESS, Press.Single.name())
                .putString(MIRROR_SOURCE_SOURCE, Source.AndroidKey.name())
                .putInt(MIRROR_VISIBILITY_KEY_CODE, UNASSIGNED)
                .putString(MIRROR_VISIBILITY_PRESS, Press.Single.name())
                .putString(MIRROR_VISIBILITY_SOURCE, Source.AndroidKey.name())
                .putInt(MICROPHONE_KEY_CODE, UNASSIGNED)
                .putString(MICROPHONE_PRESS, Press.Single.name())
                .putString(MICROPHONE_SOURCE, Source.AndroidKey.name())
                .putInt(ENGINE_KEY_CODE, UNASSIGNED)
                .putString(ENGINE_PRESS, Press.Single.name())
                .putString(ENGINE_SOURCE, Source.AndroidKey.name())
                .putInt(HAZARD_THANKS_KEY_CODE, UNASSIGNED)
                .putString(HAZARD_THANKS_PRESS, Press.Single.name())
                .putString(HAZARD_THANKS_SOURCE, Source.AndroidKey.name())
                .remove(steeringPressKey(Action.MirrorSource))
                .remove(steeringPressKey(Action.MirrorVisibility))
                .remove(steeringPressKey(Action.AvasMicrophone))
                .remove(steeringPressKey(Action.AvasEngine))
                .remove(steeringPressKey(Action.HazardThanks))
                .apply();
    }

    public static Source[] sourcesFor(Action action) {
        if (action == null) return new Source[0];
        if (action == Action.AvasMicrophone || action == Action.MirrorSource
                || action == Action.MirrorVisibility || action == Action.HazardThanks) {
            return new Source[] { Source.AndroidKey, Source.Sos, Source.Help };
        }
        return new Source[] { Source.AndroidKey };
    }

    public static Press[] pressesFor(Action action, Source source) {
        if (action == null || source == null) return new Press[0];
        if (source != Source.AndroidKey) {
            if (action == Action.AvasMicrophone) return new Press[] { Press.Single };
            if (action == Action.MirrorSource || action == Action.MirrorVisibility) {
                return new Press[] { Press.Single, Press.Double };
            }
            if (action == Action.HazardThanks) return new Press[] { Press.Single, Press.Double };
            return new Press[0];
        }
        if (action == Action.ReverseSource || action == Action.AvasMicrophone) {
            return new Press[] { Press.Single };
        }
        return new Press[] { Press.Single, Press.Hold, Press.Double };
    }

    public static boolean supports(Action action, Source source, Press press) {
        if (action == null || source == null || press == null) return false;
        for (Press candidate : pressesFor(action, source)) {
            if (candidate == press) return true;
        }
        return false;
    }

    public static boolean hasRescueConflict(SharedPreferences preferences, Action editedAction,
            Binding candidate) {
        if (preferences == null || candidate == null || candidate.source == Source.AndroidKey
                || !candidate.isAssigned() || !supports(editedAction, candidate.source,
                        candidate.press)) return false;
        for (Action action : Action.values()) {
            if (action == editedAction) continue;
            Binding existing = load(preferences, action);
            if (existing.isAssigned() && existing.source == candidate.source
                    && existing.press == candidate.press
                    && supports(action, existing.source, existing.press)) return true;
        }
        return false;
    }

    public static boolean hasAssignedRescueBinding(SharedPreferences preferences) {
        if (preferences == null) return false;
        for (Action action : Action.values()) {
            Binding binding = load(preferences, action);
            if (binding.isAssigned() && binding.source != Source.AndroidKey
                    && supports(action, binding.source, binding.press)) return true;
        }
        return false;
    }

    private static String keyCodeKey(Action action) {
        if (action == Action.AvasEngine) return ENGINE_KEY_CODE;
        if (action == Action.AvasMicrophone) return MICROPHONE_KEY_CODE;
        if (action == Action.HazardThanks) return HAZARD_THANKS_KEY_CODE;
        return action == Action.MirrorSource
                ? MIRROR_SOURCE_KEY_CODE : MIRROR_VISIBILITY_KEY_CODE;
    }

    private static String pressKey(Action action) {
        if (action == Action.AvasEngine) return ENGINE_PRESS;
        if (action == Action.AvasMicrophone) return MICROPHONE_PRESS;
        if (action == Action.HazardThanks) return HAZARD_THANKS_PRESS;
        return action == Action.MirrorSource
                ? MIRROR_SOURCE_PRESS : MIRROR_VISIBILITY_PRESS;
    }

    private static String sourceKey(Action action) {
        if (action == Action.AvasEngine) return ENGINE_SOURCE;
        if (action == Action.AvasMicrophone) return MICROPHONE_SOURCE;
        if (action == Action.HazardThanks) return HAZARD_THANKS_SOURCE;
        return action == Action.MirrorSource
                ? MIRROR_SOURCE_SOURCE : MIRROR_VISIBILITY_SOURCE;
    }

    private static String steeringPressKey(Action action) {
        return pressKey(action) + "_android";
    }

    private static int canonicalKeyCode(int keyCode) {
        if (keyCode == 303) return 88;
        if (keyCode == 302) return 87;
        if (keyCode == 306) return 305;
        if (keyCode == 312) return 304;
        return keyCode;
    }
}
