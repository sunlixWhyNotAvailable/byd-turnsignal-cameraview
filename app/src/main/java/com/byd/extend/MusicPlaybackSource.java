package com.byd.extend;

import android.media.AudioAttributes;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** AVAS player identities survive Android's anonymized playback callbacks; tags are a fallback. */
public final class MusicPlaybackSource {
    public static final String ENGINE = "byd_extend.engine";
    public static final String EVENT = "byd_extend.event";
    public static final String MICROPHONE = "byd_extend.microphone";

    private static final Method ADD_TAG = method(AudioAttributes.Builder.class, "addTag");
    private static final Method GET_TAGS = method(AudioAttributes.class, "getTags");
    private static final AtomicBoolean TAGGING_FAILED = new AtomicBoolean();
    private static final Method TRACK_ID = method(AudioTrack.class, "getPlayerIId");
    private static final Method CONFIG_ID = method(AudioPlaybackConfiguration.class, "getPlayerInterfaceId");
    private static final AtomicBoolean IDENTITY_FAILED = new AtomicBoolean();
    private static final Map<Integer, String> PLAYERS = new HashMap<>();
    // Retain released identities for queued callbacks, without growing over a long helper session.
    private static final Map<Integer, String> RELEASED = new LinkedHashMap<>();

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

    static boolean playerIdentityAvailable() {
        return TRACK_ID != null && CONFIG_ID != null && !IDENTITY_FAILED.get();
    }

    static int register(AudioTrack track, String source) {
        int id = playerId(TRACK_ID, track);
        register(id, source);
        return id;
    }

    static synchronized void register(int id, String source) {
        if (!isKnownSource(source)) throw new IllegalArgumentException("unknown music source");
        if (id <= 0) return;
        PLAYERS.put(id, source);
        RELEASED.remove(id);
    }

    static void released(AudioTrack track) {
        if (track != null) released(playerId(TRACK_ID, track));
    }

    static synchronized void released(int id) {
        String source = PLAYERS.remove(id);
        if (source == null) return;
        RELEASED.put(id, source);
        // ponytail: last 64 retired players cover delayed callbacks; active players are never evicted.
        if (RELEASED.size() > 64) RELEASED.remove(RELEASED.keySet().iterator().next());
    }

    static boolean isOwned(AudioPlaybackConfiguration configuration) {
        int id = playerId(CONFIG_ID, configuration);
        Set<String> tags = sourceTagsAvailable() ? tags(configuration.getAudioAttributes()) : null;
        if (isOwnedPlayer(id, tags)) return true;
        if ((id <= 0 || !playerIdentityAvailable()) && tags == null) {
            throw new IllegalStateException("Playback identity unavailable");
        }
        return false;
    }

    static boolean isOwnedPlayer(int id, Set<String> tags) {
        return source(id) != null || tags != null
                && (tags.contains(ENGINE) || tags.contains(EVENT) || tags.contains(MICROPHONE));
    }

    static boolean isEligibleForVisualization(
            AudioPlaybackConfiguration configuration, boolean engineVisualizationEnabled) {
        int id = playerId(CONFIG_ID, configuration);
        Set<String> tags = sourceTagsAvailable() ? tags(configuration.getAudioAttributes()) : null;
        if (tags == null) TAGGING_FAILED.set(true);
        return isEligiblePlayer(id, tags, engineVisualizationEnabled);
    }

    static boolean isEligiblePlayer(int id, Set<String> tags, boolean engineVisualizationEnabled) {
        String source = source(id);
        return source == null ? tags == null || isEligibleTags(tags, engineVisualizationEnabled)
                : ENGINE.equals(source) && engineVisualizationEnabled;
    }

    private static synchronized String source(int id) {
        String source = PLAYERS.get(id);
        return source != null ? source : RELEASED.get(id);
    }

    private static int playerId(Method getter, Object player) {
        if (getter == null || player == null) return -1;
        try {
            int id = ((Number) getter.invoke(player)).intValue();
            if (id > 0) return id;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            IDENTITY_FAILED.set(true);
        }
        return -1;
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
