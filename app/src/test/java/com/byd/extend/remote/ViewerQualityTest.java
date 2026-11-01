package com.byd.extend.remote;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class ViewerQualityTest {
    @Test public void autoImprovesOnlyAfterStableDeliveryAndDropsOnPressure() {
        ViewerQuality quality = new ViewerQuality(0);
        assertEquals(500_000, quality.target());
        for (long t = 0; t <= 8_000; t += 2_000)
            assertEquals(500_000, quality.sample(t, t, false, true, 1500, 2000, false, 4_000_000));
        assertEquals(1_000_000, quality.sample(10_000, 10_000, false, true, 1500, 2000, false, 4_000_000));
        assertEquals(1_000_000, quality.sample(12_000, 12_000, false, true, 300, 3500, true, 700_000));
        assertEquals(500_000, quality.sample(14_000, 14_000, false, true, 300, 3500, true, 700_000));
        for (long t = 16_000; t <= 38_000; t += 2_000)
            quality.sample(t, t, false, true, 1500, 2000, false, -1); // Native HLS has no download statistics.
        assertEquals(2_000_000, quality.target());
        assertEquals(1_000_000, quality.sample(40_000, 40_000, true, false, 0, 5000, false, -1));
    }

    @Test public void staleFeedbackAndInsufficientThroughputDoNotPromoteQuality() {
        ViewerQuality quality = new ViewerQuality(0);
        quality.sample(0, 0, false, true, 1500, 2000, false, 4_000_000);
        quality.sample(20_000, 0, false, true, 1500, 2000, false, 4_000_000);
        assertEquals(500_000, quality.target());
        for (long t = 22_000; t <= 40_000; t += 2_000)
            quality.sample(t, t, false, true, 1500, 2000, false, 1_000_000);
        assertEquals(500_000, quality.target());
        quality.sample(100_000, 100_000, false, true, 1500, 2000, false, 4_000_000);
        assertEquals(500_000, quality.target());
    }

    @Test public void fixedQualityNeverAdaptsAndAutoRestartBeginsLow() {
        ViewerQuality quality = new ViewerQuality(3);
        for (long t = 0; t < 30_000; t += 2_000)
            assertEquals(2_000_000, quality.sample(t, t, true, false, 0, 10_000, true, 100_000));
        quality.reset(0);
        assertEquals(500_000, quality.target());
        assertThrows(IllegalArgumentException.class, () -> quality.reset(4));
    }

    @Test public void feedbackParserRejectsInvalidTypesAndUnboundedValues() throws Exception {
        JSONObject input = new JSONObject().put("streamId", "12345678-1234-1234-1234-123456789012")
                .put("revision", 1).put("atMs", 2000).put("quality", 0).put("bufferAheadMs", 1000)
                .put("lagMs", 2000).put("throughputBps", -1).put("stalled", false)
                .put("advancing", true).put("downloadPressure", false);
        assertEquals(1000, new ViewerPlaybackFeedback(input).bufferMs);
        input.put("stalled", "false");
        assertThrows(IllegalArgumentException.class, () -> new ViewerPlaybackFeedback(input));
        input.put("stalled", false).put("lagMs", 4_000_000);
        assertThrows(IllegalArgumentException.class, () -> new ViewerPlaybackFeedback(input));
    }
}
