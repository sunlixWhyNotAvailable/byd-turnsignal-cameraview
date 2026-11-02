package com.byd.extend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled telemetry/time drives real admission; no HTTP responses or OEM results are fabricated. */
public class UpdateConditionsTest {
    @Test public void onlyKnownOnVehicleAndVisibleScreenAdmitAnImmediateRequest() {
        for (boolean on : new boolean[]{false, true}) for (boolean visible : new boolean[]{false, true}) {
            Map<Runnable, Long> scheduled = new HashMap<>();
            List<UpdateAutoCheckRuntime.Request> requests = new ArrayList<>();
            UpdateAutoCheckRuntime runtime = new UpdateAutoCheckRuntime(() -> 100L,
                    scheduled::put, scheduled::remove, () -> true, requests::add, ignored -> {});
            runtime.entry();
            assertTrue(scheduled.isEmpty());
            runtime.setEligible(UpdateDisplayState.eligible(false, !on, visible));
            assertTrue(scheduled.isEmpty());
            runtime.setEligible(UpdateDisplayState.eligible(true, !on, visible));
            if (on && visible) assertEquals(Long.valueOf(0), scheduled.get(runtime));
            else assertTrue(scheduled.isEmpty());
            runtime.run();
            assertEquals(on && visible ? 1 : 0, requests.size());
            runtime.setEligible(UpdateDisplayState.eligible(true, !on, visible));
            runtime.entry();
            runtime.run();
            assertEquals(on && visible ? 1 : 0, requests.size());
        }
    }

    @Test public void eitherTransitionCanCompleteConditionsAndDuplicateTelemetryDoesNotReschedule() {
        for (boolean screenFirst : new boolean[]{false, true}) {
            Map<Runnable, Long> scheduled = new HashMap<>();
            List<UpdateAutoCheckRuntime.Request> requests = new ArrayList<>();
            List<String> events = new ArrayList<>();
            UpdateAutoCheckRuntime runtime = new UpdateAutoCheckRuntime(() -> 100L,
                    scheduled::put, scheduled::remove, () -> true, requests::add, events::add);
            runtime.setEligible(UpdateDisplayState.eligible(true, screenFirst, screenFirst));
            assertTrue(scheduled.isEmpty());
            runtime.setEligible(UpdateDisplayState.eligible(true, false, true));
            assertEquals(Long.valueOf(0), scheduled.get(runtime));
            runtime.setEligible(true);
            assertEquals(1, events.stream().filter(e -> e.startsWith("scheduled ")).count());
            runtime.run();
            assertFalse(requests.get(0).manual);
            runtime.setEligible(false);
            runtime.setEligible(true);
            runtime.run();
            assertEquals("Unfinished HTTP retains its slot", 1, requests.size());
        }
    }

    @Test public void settingsShutdownAndDownloadsKeepTheirAdmissionRulesAndManualBypassesEligibility() {
        Map<Runnable, Long> scheduled = new HashMap<>();
        List<UpdateAutoCheckRuntime.Request> requests = new ArrayList<>();
        AtomicBoolean enabled = new AtomicBoolean(false);
        UpdateAutoCheckRuntime runtime = new UpdateAutoCheckRuntime(() -> 100L,
                scheduled::put, scheduled::remove, enabled::get, requests::add, ignored -> {});
        runtime.setEligible(true);
        assertTrue(scheduled.isEmpty());
        enabled.set(true);
        runtime.refresh();
        assertEquals(Long.valueOf(0), scheduled.get(runtime));
        runtime.setDownloading(true);
        runtime.run();
        assertTrue(requests.isEmpty());
        runtime.setEligible(false);
        runtime.setDownloading(false);
        runtime.shutdown();
        assertFalse(runtime.requestManual());
        runtime.entry();
        assertTrue(runtime.requestManual());
        assertTrue(requests.get(0).manual);
        assertEquals(30_000, UpdateAutoCheckRuntime.retryDelay(0));
        assertEquals(60_000, UpdateAutoCheckRuntime.retryDelay(1));
        assertEquals(120_000, UpdateAutoCheckRuntime.retryDelay(2));
        assertEquals(300_000, UpdateAutoCheckRuntime.retryDelay(3));
    }

    @Test public void resumedHintUsesRemainingVisibleTimeInCoordinatorProtocol() {
        UpdateHintState initial = new UpdateHintState(1, "com.byd.extend", "session", 1,
                "result-attempt-1", UpdateHintState.PENDING, 1_000_000_000L, 0, 0, 100, 400, 100);
        UpdateHintState first = initial.visible(1_000, 2);
        long remaining = UpdateHintLifetime.remainingMs(first.expiresAtElapsedMs, 4_000);
        assertEquals(7_000L, remaining);
        UpdateHintState resumed = new UpdateHintState(1, "com.byd.extend", "session", 4,
                "result-attempt-2", UpdateHintState.PENDING, 60_000_000_000L, 0, 0, 100, 400, 100)
                .visible(60_000, remaining, 5);
        UpdateHintState.SessionRecord peer = new UpdateHintState.SessionRecord();
        assertTrue(peer.accept(first));
        assertTrue(peer.accept(UpdateHintState.none("com.byd.extend", "session", 3)));
        assertTrue(peer.accept(resumed));
        assertTrue(resumed.isActiveAt(66_999_000_000L, 66_999));
        assertFalse(resumed.isActiveAt(67_000_000_000L, 67_000));
    }
}
