package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class TurnTelemetryLogPolicyTest {
    @Test public void suppressesRepeatsButKeepsChangedOemOrExceptionCause() throws Exception {
        DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
        String kind = "turn_telemetry_get_error";
        JSONObject diagnostic = new JSONObject().put("kind", kind).put("operation", "GET")
                .put("device", 1004).put("fid", 321912876).put("phase", "reply_parse")
                .put("oem_status", -1);
        policy.before(diagnostic.toString(), kind, 100);
        assertFalse(policy.suppressed());
        diagnostic.put("t_ms", 200);
        policy.before(diagnostic.toString(), kind, 200);
        assertTrue(policy.suppressed());
        diagnostic.put("oem_status", -2);
        assertNotNull(policy.before(diagnostic.toString(), kind, 300));
        assertFalse(policy.suppressed());
        diagnostic.put("exception_class", "java.lang.IllegalStateException")
                .put("exception_message", "service disconnected")
                .put("stack_trace", "first cause");
        policy.before(diagnostic.toString(), kind, 400);
        assertFalse(policy.suppressed());
        diagnostic.put("stack_trace", "changed underlying cause");
        policy.before(diagnostic.toString(), kind, 500);
        assertFalse(policy.suppressed());
        assertTrue(DiagnosticLogPolicy.shouldProduce("turn_telemetry_freshness_failure"));
    }
}
