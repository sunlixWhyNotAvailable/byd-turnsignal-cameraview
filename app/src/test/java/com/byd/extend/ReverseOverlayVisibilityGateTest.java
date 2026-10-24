package com.byd.extend;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ReverseOverlayVisibilityGateTest {
    @Test public void hideCancelsQueuedShowAndDispatchesFalseOnce() {
        TurnSignalController.ReverseOverlayVisibilityGate gate =
                new TurnSignalController.ReverseOverlayVisibilityGate();
        List<Boolean> results = new ArrayList<>();
        TurnSignalController.ReverseOverlayVisibilityGate.Show show = newShow(
                12, CameraDisplayTarget.TABLET, new int[]{4, 5, 6}, 2, 0, results::add);
        assertNull(gate.queue(show));

        TurnSignalController.ReverseOverlayVisibilityGate.Show[] canceled =
                gate.invalidate(12, CameraDisplayTarget.TABLET, 0, "hide_requested");

        assertArrayEquals(new TurnSignalController.ReverseOverlayVisibilityGate.Show[]{show},
                canceled);
        assertEquals("hide_requested", show.cancelReason());
        assertFalse(gate.begin(show, 2, 0));
        assertTrue(show.completeCancelled(
                (completion, success) -> completion.accept(success)));
        assertFalse(show.completeCancelled(
                (completion, success) -> completion.accept(success)));
        assertEquals(Arrays.asList(false), results);
    }

    @Test public void displayTargetsCancelIndependentlyAndInflightShowIsRetained() {
        TurnSignalController.ReverseOverlayVisibilityGate gate =
                new TurnSignalController.ReverseOverlayVisibilityGate();
        TurnSignalController.ReverseOverlayVisibilityGate.Show tablet = newShow(
                21, CameraDisplayTarget.TABLET, new int[]{7, 8, 9}, 4, 3, ignored -> {});
        TurnSignalController.ReverseOverlayVisibilityGate.Show cluster = newShow(
                21, CameraDisplayTarget.CLUSTER, new int[]{10, 11, 12}, 4, 3, ignored -> {});
        assertNull(gate.queue(tablet));
        assertNull(gate.queue(cluster));

        assertTrue(gate.begin(tablet, 4, 3));
        TurnSignalController.ReverseOverlayVisibilityGate.Show[] canceled =
                gate.invalidate(21, CameraDisplayTarget.CLUSTER, 0, "hide_requested");

        assertArrayEquals(
                new TurnSignalController.ReverseOverlayVisibilityGate.Show[]{cluster}, canceled);
        assertFalse(gate.begin(cluster, 4, 3));
        assertTrue(tablet.isInFlight());
    }

    @Test public void surfaceAndHelperReplacementsRejectTheOldShow() {
        TurnSignalController.ReverseOverlayVisibilityGate gate =
                new TurnSignalController.ReverseOverlayVisibilityGate();
        int[] generations = {13, 14, 15};
        TurnSignalController.ReverseOverlayVisibilityGate.Show first = newShow(
                30, CameraDisplayTarget.TABLET, generations, 8, 5, ignored -> {});
        assertNull(gate.queue(first));
        generations[0] = 99;
        TurnSignalController.ReverseOverlayVisibilityGate.Show replacement = newShow(
                30, CameraDisplayTarget.TABLET, new int[]{16, 17, 18}, 8, 5, ignored -> {});

        assertSame(first, gate.queue(replacement));
        assertEquals("replaced", first.cancelReason());
        assertArrayEquals(new int[]{13, 14, 15}, first.generations);
        assertFalse(gate.begin(first, 8, 5));
        TurnSignalController.ReverseOverlayVisibilityGate.Show[] helperCanceled =
                gate.invalidate(0, -1, 8, "helper_replaced");
        assertArrayEquals(
                new TurnSignalController.ReverseOverlayVisibilityGate.Show[]{replacement},
                helperCanceled);
        assertFalse(gate.begin(replacement, 8, 5));
        assertEquals("helper_replaced", replacement.cancelReason());

        TurnSignalController.ReverseOverlayVisibilityGate.Show wrongEpoch = newShow(
                31, CameraDisplayTarget.TABLET, new int[]{19, 20, 21}, 8, 5, ignored -> {});
        assertNull(gate.queue(wrongEpoch));
        assertFalse(gate.begin(wrongEpoch, 9, 5));
        assertEquals("helper_generation_changed", wrongEpoch.cancelReason());

        TurnSignalController.ReverseOverlayVisibilityGate.Show rebound = newShow(
                32, CameraDisplayTarget.TABLET, new int[]{22, 23, 24}, 9, 6, ignored -> {});
        gate.queue(rebound);
        assertTrue(gate.isCurrent(rebound, 9, 6));
        assertTrue(gate.begin(rebound, 9, 6));
    }

    @Test public void firstHelperBindKeepsThePreparedShowEligible() {
        TurnSignalController.ReverseOverlayVisibilityGate gate =
                new TurnSignalController.ReverseOverlayVisibilityGate();
        TurnSignalController.ReverseOverlayVisibilityGate.Show show = newShow(
                41, CameraDisplayTarget.TABLET, new int[]{1, 2, 3}, 0, 0, ignored -> {});
        assertNull(gate.queue(show));

        assertTrue(gate.isCurrent(show, 0, 0));
        assertTrue(gate.begin(show, 1, 0));
        assertEquals(1, show.helperEpoch());
    }

    private static TurnSignalController.ReverseOverlayVisibilityGate.Show newShow(
            int requestId, int displayTarget, int[] generations,
            long helperEpoch, long invalidationGeneration,
            java.util.function.Consumer<Boolean> completion) {
        return new TurnSignalController.ReverseOverlayVisibilityGate.Show(
                requestId, displayTarget, generations,
                helperEpoch, invalidationGeneration, completion);
    }
}
