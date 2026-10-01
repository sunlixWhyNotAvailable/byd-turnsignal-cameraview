package com.byd.extend;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Persistence policy only. Functional callbacks must never be filtered by this policy. */
final class DiagnosticLogPolicy {
    static final String PREF_ENABLED = "extended_logs_enabled";
    private static volatile boolean extended;
    private static final DiagnosticLogPolicy STDOUT = new DiagnosticLogPolicy();
    private static final Set<String> DETAIL = new HashSet<>(Arrays.asList(
            "telemetry_sample", "listener_event", "vehicle_state", "bsd_read",
            "bsd_event_ignored", "parking_radar_snapshot", "parking_radar_state",
            "parking_radar_event_ignored", "reverse_gear_read", "camera_source_hub_stats",
            "camera_dewarp_stats", "camera_overlay_frame", "reverse_overlay_frame",
            "camera_config_read", "camera_config_received", "camera_config_applied",
            "overlay_geometry", "camera_overlay_geometry", "lifetime_counters",
            "avm_event", "music_journal_snapshot", "music_metadata_publish",
            "avas_nav_state", "avas_route_call", "avas_audio_sample", "avas_audio_progress",
            "avas_telemetry_sample", "avas_telemetry_read", "avas_telemetry_callback",
            "avas_track_state", "avas_track_sample", "avas_track_timestamp", "avas_pcm_levels",
            "avas_play_call_timing", "avas_nav_source_get", "avas_nav_source_gate_timeline"));
    // These detail records also drive runtime state. Keep their full IPC delivery with logging OFF.
    private static final Set<String> FUNCTIONAL_DETAIL = new HashSet<>(Arrays.asList(
            "vehicle_state", "parking_radar_state", "camera_overlay_frame", "reverse_overlay_frame",
            "avm_event", "parking_radar_snapshot", "music_metadata_publish", "music_journal_snapshot",
            "lifetime_counters"));
    private String lastError = "", lastKind = "";
    private long firstAt, lastAt, repeats;
    private boolean suppressed;

    static void configure(boolean enabled) { extended = enabled; }
    static boolean extended() { return extended; }
    static void print(String line) {
        String kind = kind(line);
        if (!shouldPersist(kind)) return;
        String summary;
        boolean suppress;
        synchronized (STDOUT) {
            summary = STDOUT.before(line, kind, System.nanoTime() / 1_000_000L);
            suppress = STDOUT.suppressed();
        }
        if (summary != null) System.out.println(summary);
        if (!suppress) System.out.println(line);
    }
    static void flushOutput() {
        String summary;
        synchronized (STDOUT) { summary = STDOUT.finish(); }
        if (summary != null) System.out.println(summary);
        System.out.flush();
    }
    static boolean isDetail(String kind) { return DETAIL.contains(kind); }
    static boolean shouldPersist(String kind) { return extended || !isDetail(kind); }
    static boolean shouldProduce(String kind) {
        return shouldPersist(kind) || FUNCTIONAL_DETAIL.contains(kind);
    }
    static String kind(String line) {
        // All internal writers use an unescaped ASCII event name. Avoid parsing every telemetry JSON.
        int key = line.indexOf("\"kind\"");
        int colon = key < 0 ? -1 : line.indexOf(':', key + 6);
        int start = colon < 0 ? -1 : line.indexOf('"', colon + 1);
        int end = start < 0 ? -1 : line.indexOf('"', start + 1);
        return end < 0 ? "" : line.substring(start + 1, end);
    }
    boolean suppressed() { return suppressed; }
    String before(String line, String kind, long now) {
        suppressed = false;
        if (!(kind.contains("error") || kind.contains("failed") || kind.contains("unavailable")))
            return null;
        String signature = line;
        try {
            JSONObject value = new JSONObject(line);
            signature = kind + '|' + value.optString("source") + '|' + value.optString("error")
                    + '|' + value.optString("reason") + '|' + value.optString("operation")
                    + '|' + value.optString("status") + '|' + value.optString("request")
                    + '|' + value.optString("request_id") + '|' + value.optString("camera_id")
                    + '|' + value.optString("profile") + '|' + value.optString("device")
                    + '|' + value.optString("fid") + '|' + value.optString("stage");
        } catch (Exception ignored) { }
        if (signature.equals(lastError) && now - firstAt < 60_000) {
            repeats++; lastAt = now; suppressed = true; return null;
        }
        String result = finish();
        lastError = signature; lastKind = kind; firstAt = now; lastAt = now;
        return result;
    }
    String finish() {
        String result = repeats == 0 ? null : "{\"kind\":\"log_error_repeated\",\"event\":\""
                + lastKind + "\",\"count\":" + repeats + ",\"first_elapsed_ms\":" + firstAt
                + ",\"last_elapsed_ms\":" + lastAt + "}";
        repeats = 0; lastError = "";
        return result;
    }
}
