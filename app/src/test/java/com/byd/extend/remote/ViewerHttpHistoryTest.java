package com.byd.extend.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewerHttpHistoryTest {
    @Test public void boundedHistoryRetainsInflightRequestsAndNeverRetainsRawPaths() throws Exception {
        ViewerHttpHistory history = new ViewerHttpHistory();
        for (int i = 0; i < 40; i++) {
            ViewerHttpHistory.Entry entry = history.accept(i);
            entry.request("/media/private-token/segment/7.m4s?secret=value", "media", i);
            history.finish(entry, i + 1);
        }
        ViewerHttpHistory.Entry queued = history.accept(50);
        String text = history.snapshot(70, 4, 1);
        assertFalse(text.contains("private-token"));
        assertFalse(text.contains("secret"));
        JSONObject snapshot = new JSONObject(text);
        JSONArray completed = snapshot.getJSONArray("completed");
        assertEquals(32, completed.length());
        assertEquals(9, completed.getJSONObject(0).getLong("id"));
        JSONObject active = snapshot.getJSONArray("inflight").getJSONObject(0);
        assertEquals(queued.id, active.getLong("id"));
        assertEquals(20, active.getLong("queue_ms"));
        assertEquals(-1, active.getLong("body_end_ms"));
        queued.request("/media/12345678-1234-1234-1234-123456789012/segment/17.m4s", "media", 72);
        history.finish(queued, 80);
        snapshot = new JSONObject(history.snapshot(90, 0, 0));
        assertEquals(0, snapshot.getJSONArray("inflight").length());
        assertEquals(17, snapshot.getJSONArray("completed").getJSONObject(31).getLong("segment"));
    }
}
