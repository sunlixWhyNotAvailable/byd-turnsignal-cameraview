package com.byd.extend;

import android.media.AudioAttributes;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** AudioAttributes tags used to distinguish AVAS tracks inside the OEM playback mix. */
public final class MusicPlaybackSource {
    public static final String ENGINE = "byd_extend.engine";
    public static final String EVENT = "byd_extend.event";
    public static final String MICROPHONE = "byd_extend.microphone";

    private static final Method ADD_TAG = method(AudioAttributes.Builder.class, "addTag");
    private static final Method GET_TAGS = method(AudioAttributes.class, "getTags");
    private static final AtomicBoolean TAGGING_FAILED = new AtomicBoolean();

    private MusicPlaybackSource() {}

    /** Adds a source marker when supported by the system framework and returns the same builder. */
    public static AudioAttributes.Builder attributes(
            AudioAttributes.Builder builder, String source) {
        if (builder == null) throw new IllegalArgumentException("audio attributes builder is null");
        if (!isKnownSource(source)) throw new IllegalArgumentException("unknown music source");
        if (ADD_TAG != null) {
            try {
                ADD_TAG.invoke(builder, source);
            } catch (Throwable ignored) {
                TAGGING_FAILED.set(true);
            }
        }
        return builder;
    }

    static boolean sourceTagsAvailable() {
        return ADD_TAG != null && GET_TAGS != null && !TAGGING_FAILED.get();
    }

    static boolean isEligibleForVisualization(
            AudioAttributes attributes, boolean engineVisualizationEnabled) {
        if (!sourceTagsAvailable()) return true;
        Set<String> tags = tags(attributes);
        if (tags == null) {
            TAGGING_FAILED.set(true);
            return true;
        }
        return isEligibleTags(tags, engineVisualizationEnabled);
    }

    static boolean isEligibleTags(Set<String> tags, boolean engineVisualizationEnabled) {
        if (tags == null) return false;
        if (tags.contains(EVENT) || tags.contains(MICROPHONE)) return false;
        return !tags.contains(ENGINE) || engineVisualizationEnabled;
    }

    private static Set<String> tags(AudioAttributes attributes) {
        if (attributes == null || GET_TAGS == null) return null;
        try {
            Object value = GET_TAGS.invoke(attributes);
            if (value instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> result = (Set<String>) value;
                return result;
            }
        } catch (Throwable ignored) {
            // The caller reports the loss of source isolation and keeps ordinary music working.
        }
        return null;
    }

    private static boolean isKnownSource(String source) {
        return ENGINE.equals(source) || EVENT.equals(source) || MICROPHONE.equals(source);
    }

    private static Method method(Class<?> owner, String name) {
        try {
            return "addTag".equals(name)
                    ? owner.getMethod(name, String.class) : owner.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
