package com.byd.extend.remote;

import org.json.JSONObject;

/** Bounded input, authenticated by the HTTP server and rechecked against the live lease by its owner. */
public final class ViewerPlaybackFeedback {
    public final String streamId;
    public final long revision, atMs, bufferMs, lagMs, throughputBps;
    public final int quality;
    public final boolean stalled, advancing, downloadPressure;

    public ViewerPlaybackFeedback(JSONObject value) {
        Object stream = value.opt("streamId");
        if (!(stream instanceof String) || !((String) stream).matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("invalid feedback stream");
        streamId = (String) stream;
        revision = number(value, "revision", 0, 9_007_199_254_740_991L);
        atMs = number(value, "atMs", 0, 9_007_199_254_740_991L);
        quality = (int) number(value, "quality", 0, 3);
        bufferMs = number(value, "bufferAheadMs", 0, 3_600_000);
        lagMs = number(value, "lagMs", -1, 3_600_000);
        throughputBps = number(value, "throughputBps", -1, 1_000_000_000);
        stalled = bool(value, "stalled"); advancing = bool(value, "advancing");
        downloadPressure = bool(value, "downloadPressure");
    }
    private static long number(JSONObject value, String key, long min, long max) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("invalid feedback " + key);
        double n = ((Number) raw).doubleValue();
        if (!Double.isFinite(n) || n < min || n > max || n != Math.floor(n))
            throw new IllegalArgumentException("invalid feedback " + key);
        return (long) n;
    }
    private static boolean bool(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof Boolean)) throw new IllegalArgumentException("invalid feedback " + key);
        return (Boolean) raw;
    }
}
