package com.byd.extend;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class ReverseSteeringSampleTest {
    @Test public void expiresWithoutTreatingUnknownOrFutureTimeAsStraightSteering() throws Exception {
        JSONObject event = new JSONObject().put("valid", true).put("angle_degrees", 30)
                .put("minimum_degrees", -100).put("maximum_degrees", 200).put("observed_ms", 1000);
        ReverseSteeringSample sample = ReverseSteeringSample.fromEvent(event);
        assertEquals(30, sample.freshAngle(1000), 0);
        assertEquals(30, sample.freshAngle(1000 + ReverseSteeringSample.MAX_AGE_MS), 0);
        assertTrue(Float.isNaN(sample.freshAngle(1001 + ReverseSteeringSample.MAX_AGE_MS)));
        assertTrue(Float.isNaN(sample.freshAngle(999)));
        assertTrue(Float.isNaN(ReverseSteeringSample.UNKNOWN.freshAngle(1000)));
        assertTrue(Float.isNaN(ReverseSteeringSample.fromEvent(event.put("valid", false)).freshAngle(1000)));
        assertTrue(Float.isNaN(new ReverseSteeringSample(300, -100, 200, 1000).freshAngle(1000)));
    }

    @Test public void steeringCallbacksRemainFunctionalWithDetailedLogsOff() {
        DiagnosticLogPolicy.configure(false);
        assertFalse(DiagnosticLogPolicy.shouldPersist("reverse_steering_state"));
        assertTrue(DiagnosticLogPolicy.shouldProduce("reverse_steering_state"));
    }
}
