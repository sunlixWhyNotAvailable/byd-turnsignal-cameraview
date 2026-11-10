package com.byd.extend;

import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Captures data on the producer; formatting belongs to the diagnostic consumer. */
final class DiagnosticEvent {
    private static final class CameraLog {
        static final AsyncServiceLog LOG = new AsyncServiceLog(line ->
                com.byd.extend.remote.CameraViewingDiagnostics.detail(DiagnosticLogPolicy.kind(line), "camera", line), null);
    }
    static void camera(String kind, Object... fields) {
        if (DiagnosticLogPolicy.shouldPersist(kind)) CameraLog.LOG.appendEvent(new DiagnosticEvent("camera_pipeline", kind, fields));
    }
    static void flushCamera(Runnable completion) { CameraLog.LOG.flush(completion); }
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long SESSION = System.currentTimeMillis();
    final String kind, source;
    final long elapsedMs, wallMs, sequence, revision;
    final int originTid = Process.myTid(), originUid = Process.myUid();
    final Object[] fields;
    final int estimatedBytes;

    DiagnosticEvent(String source, String kind, Object... fields) {
        this.source = source; this.kind = kind;
        elapsedMs = SystemClock.elapsedRealtime(); wallMs = System.currentTimeMillis();
        sequence = SEQUENCE.incrementAndGet(); revision = DiagnosticLogPolicy.revision();
        this.fields = (Object[]) freeze(fields == null ? new Object[0] : fields);
        estimatedBytes = (int) Math.min(Integer.MAX_VALUE, 512L + estimate(this.fields));
    }

    private static Object freeze(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); copy.put(key, freeze(object.opt(key))); }
            return copy;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            Object[] copy = new Object[array.length()];
            for (int i = 0; i < copy.length; i++) copy[i] = freeze(array.opt(i));
            return copy;
        }
        if (value.getClass().isArray()) {
            Object[] copy = new Object[Array.getLength(value)];
            for (int i = 0; i < copy.length; i++) copy[i] = freeze(Array.get(value, i));
            return copy;
        }
        if (value instanceof java.util.Map) {
            java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
            ((java.util.Map<?, ?>) value).forEach((key, item) -> copy.put(String.valueOf(key), freeze(item)));
            return copy;
        }
        if (value instanceof java.util.Collection) return freeze(((java.util.Collection<?>) value).toArray());
        return value;
    }

    private static long estimate(Object value) {
        if (value instanceof String) return 16L + ((String) value).length() * 6L;
        if (value instanceof Object[]) {
            long bytes = 32;
            for (Object item : (Object[]) value) bytes += estimate(item);
            return bytes;
        }
        if (value instanceof java.util.Map) {
            long bytes = 32;
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet())
                bytes += estimate(entry.getKey()) + estimate(entry.getValue());
            return bytes;
        }
        return 64;
    }

    String line() {
        try {
            JSONObject event = new JSONObject().put("kind", kind).put("source", source)
                    .put("t_ms", elapsedMs).put("wall_time_ms", wallMs)
                    .put("wall_time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date(wallMs)))
                    .put("event_sequence", sequence).put("process_id", Process.myPid()).put("process_session", SESSION)
                    .put("origin_tid", originTid).put("origin_uid", originUid);
            for (int i = 0; i + 1 < fields.length; i += 2)
                event.put(String.valueOf(fields[i]), JSONObject.wrap(fields[i + 1]));
            return event.toString();
        } catch (Throwable failure) {
            JSONObject retained = new JSONObject(), errors = new JSONObject();
            for (int i = 0; i + 1 < fields.length; i += 2) {
                String key = String.valueOf(fields[i]);
                try { retained.put(key, JSONObject.wrap(fields[i + 1])); }
                catch (Throwable fieldFailure) {
                    try { errors.put(key, com.byd.extend.remote.CameraViewingDiagnostics.describe(fieldFailure)); }
                    catch (org.json.JSONException ignored) { failure.addSuppressed(fieldFailure); }
                }
            }
            return "{\"kind\":\"event_serialization_failed\",\"source\":" + JSONObject.quote(source)
                    + ",\"original_kind\":" + JSONObject.quote(kind) + ",\"t_ms\":" + elapsedMs
                    + ",\"event_sequence\":" + sequence + ",\"original_fields\":" + retained
                    + ",\"field_errors\":" + errors + ",\"error\":" + JSONObject.quote(
                    com.byd.extend.remote.CameraViewingDiagnostics.describe(failure)) + "}";
        }
    }
}
