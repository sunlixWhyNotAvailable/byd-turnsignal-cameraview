package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class AvasAudioDiagnosticsTest {
    @Test public void failureTimelineIsBoundedAndBelongsToOneRequest() {
        AvasAudioDiagnostics.Context first = new AvasAudioDiagnostics.Context(1, "power", "auto", 20, 1, 2);
        AvasAudioDiagnostics.Context second = new AvasAudioDiagnostics.Context(2, "lock", "auto", 20, 3, 4);
        first.record(10, "avas_audio_preparation", "phase", "begin");
        for (int i = 0; i < 48; i++) first.record(20 + i, "avas_route_call", "operation", "route");
        first.record(90, "avas_audio_sample", "head", 100); // Do not retain repeated samples.
        String timeline = first.timeline();
        assertFalse(timeline.contains("avas_audio_preparation"));
        assertFalse(timeline.contains("avas_audio_sample"));
        assertTrue(timeline.contains("20 avas_route_call"));
        assertTrue(timeline.contains("67 avas_route_call"));
        assertEquals("[]", second.timeline());
    }
}
