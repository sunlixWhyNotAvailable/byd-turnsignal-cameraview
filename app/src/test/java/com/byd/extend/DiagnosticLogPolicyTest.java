package com.byd.extend;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises actual persistence decisions; no Android/OEM outcome is substituted. */
public class DiagnosticLogPolicyTest {
    private final boolean previous = DiagnosticLogPolicy.extended();
    @After public void restore() { DiagnosticLogPolicy.configure(previous); }

    @Test public void ordinaryModeKeepsFunctionalDeliveryAndUnknownEventsWithoutFps() {
        DiagnosticLogPolicy.configure(false);
        for (String kind : new String[]{"camera_fps_window_stats", "camera_source_output_stats",
                "camera_source_command_stats", "camera_dewarp_stats", "motion_frame_performance",
                "motion_process_cpu"}) {
            assertFalse(kind, DiagnosticLogPolicy.shouldPersist(kind));
            assertFalse(kind, DiagnosticLogPolicy.shouldProduce(kind));
        }
        for (String kind : new String[]{"reverse_steering_state", "vehicle_state", "dewarp_mesh_applied",
                "camera_overlay_frame", "reverse_overlay_frame", "music_metadata_publish", "motion_vehicle_state"}) {
            assertFalse(kind, DiagnosticLogPolicy.shouldPersist(kind));
            assertTrue(kind, DiagnosticLogPolicy.shouldProduce(kind));
            assertFalse(kind, DiagnosticLogPolicy.diagnosticOnly(kind));
        }
        assertTrue(DiagnosticLogPolicy.shouldPersist("camera_source_command_failed"));
        assertTrue(DiagnosticLogPolicy.shouldPersist("a_new_event"));
        DiagnosticLogPolicy.configure(true);
        assertTrue(DiagnosticLogPolicy.shouldProduce("motion_process_cpu"));
        assertTrue(DiagnosticLogPolicy.shouldProduce("camera_fps_window_stats"));
    }

    @Test public void unchangedHealthyReadbacksAreSuppressedButChangesAndNewModesAreKept() throws Exception {
        DiagnosticLogPolicy.configure(false);
        DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
        JSONObject event = new JSONObject().put("kind", "app_permission_readback")
                .put("permission", "wss").put("granted", true).put("t_ms", 10);
        policy.before(event.toString(), "app_permission_readback", 10);
        assertFalse(policy.suppressed());
        event.put("t_ms", 20).put("reason", "service_entry");
        policy.before(event.toString(), "app_permission_readback", 20);
        assertTrue(policy.suppressed());
        event.put("granted", false);
        policy.before(event.toString(), "app_permission_readback", 30);
        assertFalse(policy.suppressed());
        event.put("granted", true);
        policy.before(event.toString(), "app_permission_readback", 40);
        assertFalse(policy.suppressed());
        DiagnosticLogPolicy.configure(true);
        policy.before(event.toString(), "app_permission_readback", 50);
        assertFalse(policy.suppressed());
        DiagnosticLogPolicy.configure(false);
        policy.before(event.toString(), "app_permission_readback", 60);
        assertFalse(policy.suppressed());
        event.put("ok", false).put("error", "readback denied");
        policy.before(event.toString(), "app_permission_readback", 70);
        assertFalse(policy.suppressed());
        event.remove("ok"); event.remove("error");
        policy.before(event.toString(), "app_permission_readback", 80);
        assertFalse("recovery to the previous healthy state", policy.suppressed());
    }

    @Test public void everyChangedCauseAndSecondaryFailureSurvivesDeduplication() throws Exception {
        DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
        JSONObject event = new JSONObject().put("kind", "camera_source_command_failed")
                .put("error", "attach failed").put("cause", new JSONObject().put("oem_status", -1))
                .put("stack_trace", "original stack").put("t_ms", 1);
        policy.before(event.toString(), "camera_source_command_failed", 1);
        assertFalse(policy.suppressed());
        event.put("t_ms", 2);
        policy.before(event.toString(), "camera_source_command_failed", 2);
        assertTrue(policy.suppressed());
        event.getJSONObject("cause").put("oem_status", -2);
        assertNotNull(policy.before(event.toString(), "camera_source_command_failed", 3));
        assertFalse(policy.suppressed());
        event.put("suppressed", "release failed with different cause");
        policy.before(event.toString(), "camera_source_command_failed", 4);
        assertFalse(policy.suppressed());
        policy.before(event.toString(), "camera_source_command_failed", 60_005);
        assertFalse(policy.suppressed());
    }

    @Test public void extendedTelemetrySummarizesRepeatsButRetainsSemanticChanges() throws Exception {
        DiagnosticLogPolicy.configure(true);
        DiagnosticLogPolicy policy = new DiagnosticLogPolicy();
        JSONObject event = new JSONObject().put("kind", "reverse_steering_state").put("source", "helper")
                .put("valid", true).put("angle_degrees", 0).put("source_event", "callback")
                .put("model", "native").put("observed_ms", 100).put("t_ms", 100);
        assertNull(policy.before(event.toString(), "reverse_steering_state", 100));
        assertFalse(policy.suppressed());
        event.put("observed_ms", 200).put("t_ms", 200).put("event_sequence", 2);
        assertNull(policy.before(event.toString(), "reverse_steering_state", 200));
        assertTrue(policy.suppressed());
        event.put("observed_ms", 30_100).put("t_ms", 30_100).put("event_sequence", 3);
        JSONObject summary = new JSONObject(policy.before(event.toString(), "reverse_steering_state", 30_100));
        assertTrue(policy.suppressed());
        assertEquals(2, summary.getInt("count"));
        assertEquals(200, summary.getLong("first_observed_ms"));
        assertEquals(30_100, summary.getLong("last_observed_ms"));
        assertEquals(100, summary.getLong("state_t_ms"));
        event.put("observed_ms", 30_200).put("t_ms", 30_200);
        assertNull(policy.before(event.toString(), "reverse_steering_state", 30_200));
        event.put("angle_degrees", 1);
        assertEquals(1,new JSONObject(policy.before(event.toString(), "reverse_steering_state", 30_300)).getInt("count"));
        assertFalse(policy.suppressed());
        Object[] changes = {false,"initial_read","fallback","OEM -1"};
        String[] fields = {"valid","source_event","model","error"};
        for (int i = 0; i < fields.length; i++) {
            event.put(fields[i], changes[i]);
            policy.before(event.toString(), "reverse_steering_state", 30_400);
            assertFalse(fields[i],policy.suppressed());
        }
        event.remove("error");
        policy.before(event.toString(), "reverse_steering_state", 30_410);
        policy.before(event.toString(), "reverse_steering_state", 30_420);
        assertTrue(policy.suppressed());
        DiagnosticLogPolicy.configure(false); DiagnosticLogPolicy.configure(true);
        policy.before(event.toString(), "reverse_steering_state", 30_500);
        assertFalse(policy.suppressed());
    }
}
