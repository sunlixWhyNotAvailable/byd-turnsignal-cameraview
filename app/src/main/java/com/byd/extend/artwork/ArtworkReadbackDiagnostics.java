package com.byd.extend.artwork;

import org.json.JSONObject;
import org.json.JSONException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Transition-only evidence; provider contents and media identifiers never leave as plain text. */
public final class ArtworkReadbackDiagnostics {
    private String track, expected, actual;
    private int device, source, actualDevice, actualSource, result, count;
    private long began, updateBegin, updateEnd, readBegin, readEnd;
    private boolean pending;

    public JSONObject written(String track, String expected, int device, int source,
            int actualDevice, int actualSource, String actual, int result,
            long updateBegin, long updateEnd, long readBegin, long readEnd) throws JSONException {
        this.actual = actual; this.actualDevice = actualDevice; this.actualSource = actualSource;
        this.result = result; this.updateBegin = updateBegin; this.updateEnd = updateEnd;
        this.readBegin = readBegin; this.readEnd = readEnd;
        if (device == actualDevice && source == actualSource && Objects.equals(expected, actual))
            return pending ? finish("recovered", readEnd) : null;
        if (pending) { count++; return null; }
        this.track = track; this.expected = expected; this.device = device; this.source = source;
        began = readEnd; count = 1; pending = true;
        return snapshot("mismatch", readEnd);
    }

    public JSONObject observed(String track, String expected, int device, int source,
            String actual, long readBegin, long readEnd) throws JSONException {
        if (!pending) return null;
        if (!Objects.equals(this.track, track) || !Objects.equals(this.expected, expected))
            return finish("superseded", readEnd);
        this.actual = actual; actualDevice = device; actualSource = source;
        this.readBegin = readBegin; this.readEnd = readEnd;
        if (this.device != device || this.source != source) return finish("owner_changed", readEnd);
        if (Objects.equals(expected, actual)) return finish("recovered", readEnd);
        count++;
        return null;
    }

    public JSONObject finish(String reason, long now) throws JSONException {
        if (!pending) return null;
        JSONObject value = snapshot(reason, now);
        pending = false;
        track = expected = actual = null;
        return value;
    }

    private JSONObject snapshot(String state, long now) throws JSONException {
        return new JSONObject().put("event", "readback_state").put("state", state)
                .put("track_sha256", fingerprint(track)).put("expected_ref_sha256", fingerprint(expected))
                .put("actual_ref_sha256", fingerprint(actual)).put("device", device).put("source", source)
                .put("actual_device", actualDevice).put("actual_source", actualSource)
                .put("provider_result", result).put("mismatch_observations", count)
                .put("first_mismatch_ms", began).put("duration_ms", Math.max(0, now - began))
                .put("update_begin_ms", updateBegin).put("update_end_ms", updateEnd)
                .put("read_begin_ms", readBegin).put("read_end_ms", readEnd);
    }

    private static String fingerprint(String text) {
        if (text == null) return "absent";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
