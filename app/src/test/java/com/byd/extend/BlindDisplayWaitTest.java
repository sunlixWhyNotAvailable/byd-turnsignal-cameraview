package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class BlindDisplayWaitTest {
    @Test
    public void offWaitsOnceAndUnknownStillAttempts() {
        assertFalse(BlindSpotOverlayController.displayUsable(false, Display.STATE_UNKNOWN));
        assertFalse(BlindSpotOverlayController.displayUsable(true, Display.STATE_OFF));
        assertTrue(BlindSpotOverlayController.displayUsable(true, Display.STATE_UNKNOWN));

        assertEquals(BlindSpotOverlayController.DISPLAY_WAIT,
                BlindSpotOverlayController.displayTransition(false, false));
        assertEquals(BlindSpotOverlayController.DISPLAY_UNCHANGED,
                BlindSpotOverlayController.displayTransition(true, false));
    }

    @Test
    public void displayTransitionsDependOnWaitingStateAndAvailability() {
        assertEquals(BlindSpotOverlayController.DISPLAY_RESUME,
                BlindSpotOverlayController.displayTransition(true, true));
        assertEquals(BlindSpotOverlayController.DISPLAY_UNCHANGED,
                BlindSpotOverlayController.displayTransition(false, true));
        assertEquals(BlindSpotOverlayController.DISPLAY_WAIT,
                BlindSpotOverlayController.displayTransition(false, false));
    }

    @Test
    public void invalidationRejectsStaleFrameAndTimeoutCallbacks() {
        BlindSpotOverlayController.FrameFreshness freshness =
                new BlindSpotOverlayController.FrameFreshness();
        int stale = freshness.arm();
        freshness.invalidate();
        int current = freshness.arm();

        assertFalse(freshness.accept(stale));
        assertFalse(freshness.shouldTimeout(stale));
        assertTrue(freshness.accept(current));
        assertFalse(freshness.shouldTimeout(current));

        BlindSpotOverlayController.CameraRetryState retry =
                new BlindSpotOverlayController.CameraRetryState();
        assertTrue(retry.schedule("surface_timeout"));
        assertEquals("surface_timeout", retry.cancel());
        assertEquals(null, retry.consume());
    }

    @Test
    public void offDuringPreparationIgnoresOldTimeoutAndOnPreparesExactlyOnce() {
        assertTrue(BlindSpotOverlayController.ignoreSurfaceTimeout(true, true, false));
        assertTrue(BlindSpotOverlayController.ignoreSurfaceTimeout(false, false, false));
        assertFalse(BlindSpotOverlayController.ignoreSurfaceTimeout(false, true, false));

        assertEquals(BlindSpotOverlayController.RESUME_PREPARE,
                BlindSpotOverlayController.resumePreparationDecision(
                        false, false, false, 0));
        assertEquals(BlindSpotOverlayController.RESUME_WAIT_CALLBACK,
                BlindSpotOverlayController.resumePreparationDecision(
                        false, false, false, 41));
        assertEquals(BlindSpotOverlayController.RESUME_REUSE_SURFACE,
                BlindSpotOverlayController.resumePreparationDecision(
                        true, true, true, 41));
    }

    @Test
    public void sameDisplayCanReuseButRemovedOrReplacedDisplayCannot() {
        assertFalse(BlindSpotOverlayController.displayBindingChanged(4, 4));
        assertFalse(BlindSpotOverlayController.displayBindingChanged(-1, 4));
        assertTrue(BlindSpotOverlayController.displayBindingChanged(4, -1));
        assertTrue(BlindSpotOverlayController.displayBindingChanged(4, 5));
    }

    @Test
    public void retryCancellationRequiresAllRequiredTargetsToWait() {
        assertFalse(BlindSpotOverlayController.allRequiredTargetsWaiting(
                true, false, true, true));
        assertFalse(BlindSpotOverlayController.allRequiredTargetsWaiting(
                true, true, true, false));
        assertTrue(BlindSpotOverlayController.allRequiredTargetsWaiting(
                true, true, true, true));
        assertTrue(BlindSpotOverlayController.allRequiredTargetsWaiting(
                true, true, false, false));
        assertTrue(BlindSpotOverlayController.allRequiredTargetsWaiting(
                false, false, true, true));
        assertFalse(BlindSpotOverlayController.allRequiredTargetsWaiting(
                false, false, false, false));
    }

    @Test
    public void sourceReconciliationUpdatesAllTargetsBeforeRetryCancellation() throws Exception {
        String controller = source("BlindSpotOverlayController.java");
        String reconcile = section(controller, "private void reconcileDisplays(",
                "private void rebuild(");
        assertTrue(reconcile.indexOf("invalidateTarget(target)") >= 0
                && reconcile.indexOf("if (changed)") > reconcile.indexOf("invalidateTarget(target)"));
        assertTrue(reconcile.indexOf("if (changed)") >= 0
                && reconcile.indexOf("cancelCameraRetry(") > reconcile.indexOf("if (changed)"));
        String invalidate = section(controller, "private void invalidateTarget(",
                "private boolean invalidateTargetBinding(");
        assertFalse(invalidate.contains("cancelCameraRetry("));
    }

    @Test
    public void runtimePausesAndReusesSurfaceWithoutDestroyingProducer() throws Exception {
        String controller = source("BlindSpotOverlayController.java");
        String invalidate = section(controller, "private void invalidateTarget(",
                "private boolean invalidateTargetBinding(");
        assertFalse(invalidate.contains("cancelCameraRetry("));
        assertTrue(invalidate.contains("pane.freshness.invalidate()"));
        assertTrue(invalidate.contains("!pane.attached && pane.pendingSurface == null"));
        assertTrue(invalidate.contains("pane.requestId = 0"));
        assertTrue(invalidate.contains("helper.setOverlayTargetActive(pane.surface, false)"));
        assertFalse(invalidate.contains("destroyAll("));
        assertFalse(invalidate.contains("pane.surface = null"));

        String resume = section(controller, "private void resumePane(",
                "private void preparePane(");
        assertTrue(resume.contains("helper.setOverlayTargetActive(pane.surface, true)"));
        assertTrue(resume.contains("pane.freshness.arm()"));
        assertTrue(resume.contains("firstFrameTimedOut(arm)"));

        String openController = section(controller, "private void maybeOpenCamera()",
                "private void cameraOpened(");
        String noAvailableTarget = section(openController, "if (expected <= 0)",
                "int decision = preparationDecision");
        assertTrue(noAvailableTarget.indexOf("if (!rebindRequired) return;") >= 0
                && noAvailableTarget.indexOf("closeOverlayCamera") > noAvailableTarget.indexOf("if (!rebindRequired) return;"));
        assertTrue(noAvailableTarget.contains("if (ready.isEmpty())"));
        assertTrue(noAvailableTarget.contains("expected = resolved = ready.size()"));
        assertFalse(noAvailableTarget.contains("destroyAll"));
        assertTrue(controller.contains("allRequiredTargetsWaiting() ? \"display_wait\" : null"));
        String evaluate = section(controller, "private void evaluate()", "private void setVisible(");
        assertTrue(evaluate.contains("!displayWaiting[pane.target]"));
        String timeout = section(controller, "private void surfaceTimedOut(",
                "private void overlaySurfaceAvailable(");
        assertTrue(timeout.indexOf("!CameraDisplayTarget.isValid(pane.target)") >= 0
                && timeout.indexOf("displayWaiting[pane.target]") > timeout.indexOf("!CameraDisplayTarget.isValid(pane.target)"));

        String helper = source("CameraHelperMain.java");
        String open = section(helper, "String openOverlayDirectCameras(",
                "String openReverseCamera(");
        assertTrue(open.contains("attachPersistentGroup("));
        String attach = section(helper, "void attach(\n                    PersistentCameraPort port, ConsumerGroup target,",
                "/** Adds optional stock input 0" );
        assertTrue(attach.contains("previous.releaseExcept(surfaces)"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("missing start: " + start, from >= 0);
        assertTrue("missing end: " + end, to > from);
        return source.substring(from, to);
    }
}
