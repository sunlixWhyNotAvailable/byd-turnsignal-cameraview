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
                "camera_source_command_stats", "camera_dewarp_stats", "motion_frame_performance"}) {
            assertFalse(kind, DiagnosticLogPolicy.shouldPersist(kind));
            assertFalse(kind, DiagnosticLogPolicy.shouldProduce(kind));
        }
        for (String kind : new String[]{"reverse_steering_state", "vehicle_state", "dewarp_mesh_applied",
                "camera_overlay_frame", "reverse_overlay_frame", "music_metadata_publish"}) {
            assertFalse(kind, DiagnosticLogPolicy.shouldPersist(kind));
            assertTrue(kind, DiagnosticLogPolicy.shouldProduce(kind));
        }
        assertTrue(DiagnosticLogPolicy.shouldPersist("camera_source_command_failed"));
        assertTrue(DiagnosticLogPolicy.shouldPersist("a_new_event"));
        DiagnosticLogPolicy.configure(true);
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
}
