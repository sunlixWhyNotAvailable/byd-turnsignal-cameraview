package com.byd.extend.remote;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Socket-write progress, not confirmation of delivery to the browser. No request secrets. */
final class ViewerHttpHistory {
    private static final Pattern MEDIA = Pattern.compile(
            "^/media/([0-9a-f-]{36})/(?:live(?:-[0-9]{1,18})?\\.m3u8|init\\.mp4|segment/([0-9]{1,19})\\.m4s)$");
    private final LinkedHashMap<Long, Entry> active = new LinkedHashMap<>();
    private final ArrayDeque<Entry> completed = new ArrayDeque<>();
    private long sequence;

    static final class Entry {
        final long id, accepted;
        volatile long start = -1, parsed = -1, headers = -1, bodyStart = -1, bodyEnd = -1, end = -1;
        volatile long promised, written, revision = -1, segment = -1, rangeStart = -1;
        volatile int status;
        volatile String operation = "queued", stream = "", failure = "";
        Entry(long id, long accepted) { this.id = id; this.accepted = accepted; }
        void request(String path, String operation, long time) {
            this.operation = operation;
            parsed = time;
            Matcher match = MEDIA.matcher(path);
            if (!match.matches()) return;
            stream = match.group(1);
            try { if (match.group(2) != null) segment = Long.parseLong(match.group(2)); }
            catch (NumberFormatException ignored) { /* Invalid sequence remains unclassified. */ }
        }
        JSONObject json(long now) throws org.json.JSONException {
            return new JSONObject().put("id", id).put("operation", operation).put("stream", stream)
                    .put("segment", segment).put("revision_at_request", revision).put("status", status)
                    .put("accepted_ms", accepted).put("start_ms", start).put("parsed_ms", parsed)
                    .put("queue_ms", start < 0 ? now - accepted : start - accepted)
                    .put("headers_ms", headers).put("body_start_ms", bodyStart).put("body_end_ms", bodyEnd)
                    .put("end_ms", end).put("age_ms", (end < 0 ? now : end) - accepted)
                    .put("promised", promised).put("written", written).put("range_start", rangeStart)
                    .put("failure_type", failure);
        }
    }

    synchronized Entry accept(long time) {
        Entry entry = new Entry(++sequence, time);
        active.put(entry.id, entry);
        return entry;
    }
    synchronized void finish(Entry entry, long time) {
        entry.end = time;
        active.remove(entry.id);
        completed.addLast(entry);
        while (completed.size() > 32) completed.removeFirst();
    }
    synchronized String snapshot(long time, int workers, int queued) {
        try {
            JSONArray running = new JSONArray(), recent = new JSONArray();
            for (Entry entry : active.values()) running.put(entry.json(time));
            for (Entry entry : completed) recent.put(entry.json(time));
            return new JSONObject().put("at_ms", time).put("workers", workers).put("queued", queued)
                    .put("inflight", running).put("completed", recent).toString();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
