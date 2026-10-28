package com.byd.extend.remote;

import org.json.JSONObject;

/** Only bounded browser codes, never messages, media URLs or credentials. */
public final class ViewerStopReason {
    public static final int WEB_REVISION = 12;
    public static final String REVISION_HEADER = "X-Byd-Viewer-Revision";
    private ViewerStopReason() {}

    public static String describe(JSONObject value) {
        String origin = value.optString("origin");
        if (!origin.equals("manual") && !origin.equals("pagehide") && !origin.equals("playback_error")) origin = "unspecified";
        StringBuilder result = new StringBuilder("origin=").append(origin);
        if (!origin.equals("playback_error")) return result.toString();
        for (String key : new String[] {"category", "exception", "hlsType", "hlsDetails"}) {
            String code = value.optString(key);
            if (code.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) result.append(' ').append(key).append('=').append(code);
        }
        number(result, value, "mediaCode", 1, 4);
        number(result, value, "fragment", 0, Integer.MAX_VALUE);
        number(result, value, "httpStatus", 100, 599);
        number(result, value, "clientRevision", 1, 1_000_000);
        return result.toString();
    }

    public static String revision(String value) {
        return value != null && value.matches("[1-9][0-9]{0,5}|1000000") ? value : "unknown";
    }

    /** Cumulative browser transport measurements; no caller-supplied messages or URLs. */
    public static String transport(JSONObject value) {
        String reason = value.optString("reason");
        if (!reason.matches("start|progress|stall|playing|recovery")) return null;
        StringBuilder result = new StringBuilder("reason=").append(reason);
        for (String key : new String[] {"revision", "atMs", "requests", "completed", "aborted", "failed",
                "receivedBytes", "completedBytes", "loadMs", "firstByteMs", "firstBytes", "currentMs", "bufferEndMs",
                "bufferedRecoveries", "missingFragments", "lastMissingSequence", "recoveryFromMs", "recoveryTargetMs"})
            number(result, value, key, 0, 9_007_199_254_740_991L);
        number(result, value, "playbackRatePermille", 0, 16_000);
        number(result, value, "lastStatus", 0, 599);
        number(result, value, "ready", 0, 4);
        number(result, value, "seeking", 0, 1);
        number(result, value, "pending", 0, 1);
        return result.toString();
    }

    /** Classify actual requests without retaining URLs, credentials or stream identifiers. */
    public static String mediaRequest(String path, String currentPlaylist, String clientRevision) {
        String resource = path;
        String generation = "unknown";
        if (path.startsWith("/media/")) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                    "^/media/([0-9a-f-]{36})(/.*)$").matcher(path);
            if (match.matches()) {
                resource = match.group(2);
                String playlist = "/media/" + match.group(1) + "/live.m3u8";
                generation = currentPlaylist == null ? "none" : playlist.equals(currentPlaylist) ? "current" : "stale";
            } else generation = "invalid";
        }
        String kind = resource.equals("/live.m3u8") ? "playlist"
                : resource.equals("/init.mp4") ? "init"
                : resource.matches("/segment/[0-9]{1,19}\\.m4s") ? "segment" : "other";
        if (generation.equals("unknown") && !kind.equals("other")) generation = "legacy";
        return "resource=" + kind + " generation=" + generation
                + " clientRevision=" + revision(clientRevision) + " serverRevision=" + WEB_REVISION;
    }

    private static void number(StringBuilder result, JSONObject value, String key, long min, long max) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) return;
        double number = ((Number) raw).doubleValue();
        if (number >= min && number <= max && number == Math.floor(number))
            result.append(' ').append(key).append('=').append((long) number);
    }
}
