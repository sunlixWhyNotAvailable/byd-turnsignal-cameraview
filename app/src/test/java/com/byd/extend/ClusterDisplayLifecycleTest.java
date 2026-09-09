package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ClusterDisplayLifecycleTest {
    @Test public void relevantRemovalInvalidatesButUnrelatedEventsDoNothing() {
        assertEquals(ClusterDisplayLifecycle.INVALIDATE, action(
                4, 7, 4, ClusterDisplayLifecycle.EVENT_REMOVED, true));
        assertEquals(ClusterDisplayLifecycle.IGNORE, action(
                4, 7, 7, ClusterDisplayLifecycle.EVENT_REMOVED, true));
        assertEquals(ClusterDisplayLifecycle.IGNORE, action(
                4, 7, 7, ClusterDisplayLifecycle.EVENT_ADDED, true));
    }

    @Test public void missingDestinationWakesOnSelectedReturnOnlyWhileEligible() {
        assertEquals(ClusterDisplayLifecycle.WAKE, action(
                -1, 4, 4, ClusterDisplayLifecycle.EVENT_ADDED, true));
        assertEquals(ClusterDisplayLifecycle.WAKE, action(
                -1, 4, 4, ClusterDisplayLifecycle.EVENT_CHANGED, true));
        assertEquals(ClusterDisplayLifecycle.IGNORE, action(
                -1, 4, 7, ClusterDisplayLifecycle.EVENT_ADDED, true));
        assertEquals(ClusterDisplayLifecycle.IGNORE, action(
                -1, 4, 4, ClusterDisplayLifecycle.EVENT_ADDED, false));
        assertEquals(ClusterDisplayLifecycle.IGNORE, action(
                -1, 4, 4, ClusterDisplayLifecycle.EVENT_CHANGED, false));
    }

    @Test public void sameDisplayChangeIsNotARestart() {
        assertEquals(ClusterDisplayLifecycle.NOTE_CHANGE, action(
                4, 4, 4, ClusterDisplayLifecycle.EVENT_CHANGED, true));
        assertFalse(ClusterDisplayLifecycle.NOTE_CHANGE
                == ClusterDisplayLifecycle.INVALIDATE);
    }

    @Test public void currentTabletChangesRebuildButUnrelatedEventsDoNothing() {
        assertEquals(ClusterDisplayLifecycle.INVALIDATE, tabletAction(
                0, 0, 0, ClusterDisplayLifecycle.EVENT_CHANGED, true));
        assertEquals(ClusterDisplayLifecycle.INVALIDATE, tabletAction(
                0, 0, 0, ClusterDisplayLifecycle.EVENT_CHANGED, false));
        assertEquals(ClusterDisplayLifecycle.INVALIDATE, tabletAction(
                0, -1, 0, ClusterDisplayLifecycle.EVENT_REMOVED, true));
        assertEquals(ClusterDisplayLifecycle.IGNORE, tabletAction(
                0, 0, 4, ClusterDisplayLifecycle.EVENT_CHANGED, true));
        assertEquals(ClusterDisplayLifecycle.IGNORE, tabletAction(
                0, 0, 4, ClusterDisplayLifecycle.EVENT_REMOVED, true));
    }

    @Test public void selectedTabletReturnWakesOnlyWhileEligible() {
        assertEquals(ClusterDisplayLifecycle.WAKE, tabletAction(
                -1, 0, 0, ClusterDisplayLifecycle.EVENT_ADDED, true));
        assertEquals(ClusterDisplayLifecycle.WAKE, tabletAction(
                -1, 0, 0, ClusterDisplayLifecycle.EVENT_CHANGED, true));
        assertEquals(ClusterDisplayLifecycle.IGNORE, tabletAction(
                -1, 0, 0, ClusterDisplayLifecycle.EVENT_ADDED, false));
    }

    @Test public void destinationErrorsRequireExactRequestAndGeneration() throws Exception {
        JSONObject event = new JSONObject()
                .put("kind", "camera_overlay_error")
                .put("stage", ClusterDisplayLifecycle.UNAVAILABLE_STAGE)
                .put("camera_id", CameraOverlayProfile.MIRROR_ID)
                .put("request_id", 17)
                .put("surface_generation", 4);
        assertTrue(ClusterDisplayLifecycle.matchesUnavailableError(event,
                CameraOverlayProfile.MIRROR_ID, 17, 4));
        assertFalse(ClusterDisplayLifecycle.matchesUnavailableError(event,
                CameraOverlayProfile.MIRROR_ID, 18, 4));
        assertFalse(ClusterDisplayLifecycle.matchesUnavailableError(event,
                CameraOverlayProfile.MIRROR_ID, 17, 5));
        assertFalse(ClusterDisplayLifecycle.matchesUnavailableError(event,
                CameraProfile.REAR_LEFT, 17, 4));
        event.put("stage", "surface_destroyed");
        assertFalse(ClusterDisplayLifecycle.matchesUnavailableError(event,
                CameraOverlayProfile.MIRROR_ID, 17, 4));
    }

    private static int action(int activeDisplayId, int selectedDisplayId,
            int eventDisplayId, int event, boolean eligible) {
        return ClusterDisplayLifecycle.action(CameraDisplayTarget.CLUSTER,
                activeDisplayId, selectedDisplayId, eventDisplayId, event, eligible);
    }

    private static int tabletAction(int activeDisplayId, int selectedDisplayId,
            int eventDisplayId, int event, boolean eligible) {
        return ClusterDisplayLifecycle.action(CameraDisplayTarget.TABLET,
                activeDisplayId, selectedDisplayId, eventDisplayId, event, eligible);
    }
}
