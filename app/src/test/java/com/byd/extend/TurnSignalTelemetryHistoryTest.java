package com.byd.extend;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class TurnSignalTelemetryHistoryTest {
    @Test public void retainsLatest64ReceiptsAndDistinguishesPendingFromApplied() throws Exception {
        TurnSignalTelemetryHistory history = new TurnSignalTelemetryHistory();
        for (int i = 1; i <= 70; i++) {
            history.received(1004, TurnSignalTelemetryController.STALK_FID, 2, i, 3, i);
        }
        history.processed(1004, TurnSignalTelemetryController.STALK_FID, 3, 69, 80, "applied");
        JSONObject snapshot = new JSONObject(history.snapshot(90, 20));
        JSONArray entries = snapshot.getJSONArray("control_callbacks");
        assertEquals(64, entries.length());
        assertEquals(7, entries.getJSONObject(0).getLong("sequence"));
        assertEquals(11, entries.getJSONObject(62).getLong("dispatch_age_ms"));
        assertEquals("applied", entries.getJSONObject(62).getString("disposition"));
        assertEquals(-1, entries.getJSONObject(63).getLong("processed_t_ms"));
        assertEquals("queued", entries.getJSONObject(63).getString("disposition"));
    }

    @Test public void generationSeparatesSequenceReuseAndNumericTrafficDoesNotEvictControls()
            throws Exception {
        TurnSignalTelemetryHistory history = new TurnSignalTelemetryHistory();
        history.received(1004, TurnSignalTelemetryController.BLINK_FID, 1, 10, 1, 1);
        history.received(1004, TurnSignalTelemetryController.BLINK_FID, 2, 20, 2, 1);
        history.processed(1004, TurnSignalTelemetryController.BLINK_FID, 2, 1, 25, "applied");
        for (int i = 1; i <= 100; i++) {
            history.received(1013, TurnSignalTelemetryController.SPEED_FID,
                    Float.floatToRawIntBits(i), 25 + i, 2, i);
        }
        JSONObject snapshot = new JSONObject(history.snapshot(130, 10));
        assertEquals(2, snapshot.getJSONArray("control_callbacks").length());
        assertEquals(-1, snapshot.getJSONArray("control_callbacks").getJSONObject(0)
                .getLong("processed_t_ms"));
        assertEquals(25, snapshot.getJSONArray("control_callbacks").getJSONObject(1)
                .getLong("processed_t_ms"));
        assertEquals(100, snapshot.getLong("speed_count"));
        assertEquals(Float.floatToRawIntBits(100), snapshot.getJSONObject("speed_latest").getInt("raw"));
    }

    @Test public void separatesGetQueueDelayFromReadDurationAndClearsAtRestart() throws Exception {
        TurnSignalTelemetryHistory history = new TurnSignalTelemetryHistory();
        history.getStarted(1000, 1260);
        assertEquals("in_progress", new JSONObject(history.snapshot(1260, 1260)).getString("get_result"));
        history.getFinished(1280, "stalk=1,blink=1");
        JSONObject snapshot = new JSONObject(history.snapshot(1280, 1280));
        assertEquals(260, snapshot.getLong("get_queue_lag_ms"));
        assertEquals(20, snapshot.getLong("get_duration_ms"));
        assertEquals(1280, snapshot.getLong("last_successful_get_age_ms"));
        history.clear();
        JSONObject cleared = new JSONObject(history.snapshot(2000, -1));
        assertEquals(-1, cleared.getLong("get_start_t_ms"));
        assertEquals("not_started", cleared.getString("get_result"));
        assertEquals(0, cleared.getJSONArray("control_callbacks").length());
    }
}
