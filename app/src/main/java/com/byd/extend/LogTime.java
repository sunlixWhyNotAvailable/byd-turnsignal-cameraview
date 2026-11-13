package com.byd.extend;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Human-readable local time for diagnostic lines and log file names. */
final class LogTime {
    private LogTime() { }

    /** ISO-8601 local time with offset, e.g. 2026-11-13T16:02:20.411+05:00. */
    static String iso(long wallMs) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date(wallMs));
    }

    /** Local time for log file names, e.g. 20261113-160220. */
    static String fileStamp(long wallMs) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(wallMs));
    }

    /**
     * Adds {@code wall_time} to a JSON line that lacks it, right after {@code kind}. The line's own
     * top-level {@code wall_time_ms} or {@code wall_ms} wins; otherwise {@code fallbackWallMs} is used.
     * Non-JSON lines (protocol handshakes) are returned unchanged.
     */
    static String withWallTime(String line, long fallbackWallMs) {
        if (line == null || !line.startsWith("{") || line.contains("\"wall_time\":")) return line;
        try {
            JSONObject source = new JSONObject(line);
            long wallMs = source.optLong("wall_time_ms", source.optLong("wall_ms", fallbackWallMs));
            JSONObject stamped = new JSONObject();
            if (source.has("kind")) stamped.put("kind", source.get("kind"));
            stamped.put("wall_time", iso(wallMs));
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"kind".equals(key)) stamped.put(key, source.get(key));
            }
            return stamped.toString();
        } catch (JSONException malformed) {
            return line;
        }
    }
}
