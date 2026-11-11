package com.byd.extend.artwork;

import java.util.Objects;

/** Shared with the isolated OEM writer; no Android services or mutable track duration. */
public final class MusicArtworkPolicy {
    private MusicArtworkPolicy() {}

    public static String trackKey(String session, String mediaId, String title, String artist) {
        return part(session) + (mediaId == null || mediaId.isEmpty()
                ? "text:" + part(title) + part(artist) : "id:" + part(mediaId));
    }

    private static String part(String value) {
        String text = value == null ? "" : value;
        return text.length() + ":" + text;
    }

    // OEM MediaInfo.qml: 176px cover, 44px badge canvas, 10px right/bottom inset.
    public static int badgeSize(int side) { return Math.round(side * 44f / 176f); }
    public static int badgeInset(int side) { return Math.round(side * 10f / 176f); }

    public static boolean owns(String prefix, String path) {
        return path != null && path.startsWith(prefix)
                && path.substring(prefix.length()).matches("[a-f0-9]{64}\\.png");
    }

    public enum Restore { ORIGINAL, DEFAULT, LEAVE }

    public static Restore restore(int device, int source, String originalTrack,
            int currentDevice, int currentSource, String currentCover,
            String ownPrefix, String currentTrack) {
        if (device != currentDevice || !owns(ownPrefix, currentCover)) return Restore.LEAVE;
        return source == currentSource && originalTrack != null
                && Objects.equals(originalTrack, currentTrack) ? Restore.ORIGINAL : Restore.DEFAULT;
    }

    public static boolean mayPublish(int device, int currentDevice, int currentSource,
            String track, String confirmedTrack, String cover, String confirmedCover) {
        return device == currentDevice && currentSource == 26 && track != null && cover != null
                && track.equals(confirmedTrack) && cover.equals(confirmedCover);
    }
}
