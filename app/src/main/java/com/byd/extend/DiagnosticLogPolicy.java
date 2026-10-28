package com.byd.extend;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;

/** Persistence policy only. Functional callbacks must never be filtered by this policy. */
public final class DiagnosticLogPolicy {
    public static final String PREF_ENABLED = "extended_logs_enabled";
    private static volatile boolean extended;
    private static volatile long revision;
    private static final DiagnosticLogPolicy STDOUT = new DiagnosticLogPolicy();
    private static final Set<String> DETAIL = new HashSet<>(Arrays.asList(
            "telemetry_sample", "listener_event", "vehicle_state", "bsd_read", "reverse_steering_state",
            "bsd_event_ignored", "parking_radar_snapshot", "parking_radar_state",
            "parking_radar_event_ignored", "reverse_gear_read", "camera_source_hub_stats",
            "camera_dewarp_stats", "camera_overlay_frame", "reverse_overlay_frame",
            "camera_config_read", "camera_config_received", "camera_config_applied",
            "overlay_geometry", "camera_overlay_geometry", "lifetime_counters",
            "avm_event", "music_journal_snapshot", "music_metadata_publish",
            "avas_nav_state", "avas_route_call", "avas_audio_sample", "avas_audio_progress",
            "avas_telemetry_sample", "avas_telemetry_read", "avas_telemetry_callback",
            "avas_track_state", "avas_track_sample", "avas_track_timestamp", "avas_pcm_levels",
            "avas_play_call_timing", "avas_nav_source_get", "avas_nav_source_gate_timeline",
            "motion_vehicle_state",
            "camera_fps_window_stats", "camera_source_output_stats", "camera_texture_matrix",
            "camera_source_command_stats", "dewarp_mesh_applied",
            "reverse_camera_timing", "motion_checkup_frame", "motion_frame_layout",
            "motion_frame_performance",
            "weather_location_detail"));
    // These detail records also drive runtime state. Keep their full IPC delivery with logging OFF.
    private static final Set<String> FUNCTIONAL_DETAIL = new HashSet<>(Arrays.asList(
            "vehicle_state", "parking_radar_state", "camera_overlay_frame", "reverse_overlay_frame",
            "avm_event", "parking_radar_snapshot", "music_metadata_publish", "music_journal_snapshot",
            "lifetime_counters", "reverse_steering_state", "dewarp_mesh_applied"));
    private String lastError = "", lastKind = "";
    private long firstAt, lastAt, repeats;
    private boolean suppressed;
    private long stateRevision = -1;
    private final LinkedHashMap<String, String> states = new LinkedHashMap<>();
    private static final Set<String> STATE_EVENTS = new HashSet<>(Arrays.asList(
            "app_permission_readback", "app_accessibility_binding_readback",
            "weather_accessibility_binding_readback", "adb_connection_time_policy",
            "avas_recovery_daemon_reconcile", "avas_status", "helper_recovery_configured",
            "music_runtime_status", "music_metadata_status", "music_runtime_config",
            "music_awake_reconcile", "music_acc_reconcile", "music_metadata_awake",
            "motion_status", "motion_screen"));
    private static final Set<String> CLOCK_FIELDS = new HashSet<>(Arrays.asList(
            "t_ms", "wall_time", "wall_time_ms", "wall_ms", "elapsed_ms", "uptime_ms", "time"));

    public static synchronized void configure(boolean enabled) {
        if (extended != enabled) revision++;
        extended = enabled;
        OemLogPolicy.configure(enabled);
    }
    public static boolean extended() { return extended; }
    public static long revision() { return revision; }
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
    public static boolean shouldPersist(String kind) { return extended || !isDetail(kind); }
    public static boolean shouldProduce(String kind) {
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
        boolean errorKind = kind.contains("error") || kind.contains("failed")
                || kind.contains("failure") || kind.contains("unavailable");
        boolean stateKind = STATE_EVENTS.contains(kind);
        if (!errorKind && !stateKind)
            return null;
        String signature = line;
        try {
            JSONObject value = new JSONObject(line);
            for (String key : CLOCK_FIELDS) value.remove(key);
            String stateKey = kind + '|' + value.optString("source") + '|'
                    + value.optString("permission") + '|' + value.optString("profile");
            if (stateKind && !hasFailure(value)) {
                if (stateRevision != revision) { states.clear(); stateRevision = revision; }
                if (extended) return null;
                value.remove("reason"); value.remove("source_event"); value.remove("trigger");
                String state = canonical(value);
                suppressed = state.equals(states.put(stateKey, state));
                if (states.size() > 128) states.remove(states.keySet().iterator().next());
                return null;
            }
            if (stateKind) states.remove(stateKey); // Recovery to the previous healthy value is a new transition.
            // Include every diagnostic field: a changed nested cause must never be deduplicated away.
            signature = canonical(value);
        } catch (Exception ignored) { }
        if (signature.equals(lastError) && now - firstAt < 60_000) {
            repeats++; lastAt = now; suppressed = true; return null;
        }
        String result = finish();
        lastError = signature; lastKind = kind; firstAt = now; lastAt = now;
        return result;
    }
    private static boolean hasFailure(JSONObject value) {
        if (value.has("ok") && !value.optBoolean("ok", true)) return true;
        for (String key : new String[]{"state", "status"}) {
            String status = value.optString(key);
            if ("error".equals(status) || "failed".equals(status) || "unavailable".equals(status))
                return true;
        }
        for (String key : new String[]{"error", "failure", "exception_class", "stack_trace"}) {
            String field = value.optString(key, "");
            if (!field.isEmpty() && !"null".equals(field)) return true;
        }
        return false;
    }
    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) result.append(JSONObject.quote(key)).append(':')
                    .append(canonical(object.get(key))).append(',');
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) result.append(canonical(array.get(i))).append(',');
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
    String finish() {
        String result = repeats == 0 ? null : "{\"kind\":\"log_error_repeated\",\"event\":\""
                + lastKind + "\",\"count\":" + repeats + ",\"first_elapsed_ms\":" + firstAt
                + ",\"last_elapsed_ms\":" + lastAt + "}";
        repeats = 0; lastError = "";
        return result;
    }
}
