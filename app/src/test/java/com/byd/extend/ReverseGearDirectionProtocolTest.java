package com.byd.extend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReverseGearDirectionProtocolTest {
    @Test
    public void directionTransactionUsesTheReservedNextWireSlot() {
        assertEquals(19, CameraShellProtocol.TX_REVERSE_SET_MODE
                - android.os.IBinder.FIRST_CALL_TRANSACTION);
    }

    @Test
    public void automaticModeDoesNotRequireVisibleSelectorWidget() throws Exception {
        String source = read("ShellReverseCameraOverlay.java");
        int method = source.indexOf("void setSideMode(int expectedRequestId, int mode)");
        assertTrue(method >= 0);
        int end = source.indexOf("private void quiesce", method);
        assertTrue(end > method);
        String body = source.substring(method, end);
        assertTrue(body.contains("requireRequest(expectedRequestId)"));
        assertTrue(body.contains("if (!active || closing) return"));
        assertTrue(body.contains("root.sideMode() == mode"));
        assertTrue(body.contains("automatic"));
        assertTrue(!body.contains("widgetVisible"));
    }

    @Test
    public void rearEdgeIsNotDroppedWhileFrontAcknowledgementIsPending() {
        ReverseCameraController.DirectionState state = new ReverseCameraController.DirectionState();
        assertTrue(state.shouldRequest(1));
        state.requested(1);
        assertTrue(state.shouldRequest(0));
        state.requested(0);
        state.accept(1, true, false);
        assertEquals(0, state.effectiveMode());
        assertFalse(state.shouldRequest(0));
        state.accept(0, true, false);
        assertEquals(0, state.effectiveMode());
    }

    @Test
    public void olderMatchingModeDoesNotAcknowledgeASecondLaterRequest() {
        ReverseCameraController.DirectionState state = new ReverseCameraController.DirectionState();
        state.requested(1);
        state.requested(0);
        state.requested(1);
        state.accept(1, true, false);
        state.accept(0, true, false);
        assertEquals(1, state.effectiveMode());
        assertTrue(state.shouldRequest(0));
        state.requested(0);
        state.accept(1, true, false);
        assertEquals(0, state.effectiveMode());
        state.accept(0, true, false);
        assertFalse(state.shouldRequest(0));
    }

    @Test
    public void manualEventsDoNotConsumeAutomaticAcknowledgements() {
        ReverseCameraController.DirectionState state = new ReverseCameraController.DirectionState();
        state.requested(1);
        state.requested(0);
        state.accept(1, false, false);
        assertEquals(0, state.effectiveMode());
        state.accept(1, true, false);
        state.accept(0, true, false);
        state.accept(1, false, false);
        assertFalse(state.shouldRequest(1));
        assertTrue(state.shouldRequest(0));
    }

    @Test
    public void blockedFrontRequestRetainsConfirmedRearAndResetClearsOutstanding() {
        ReverseCameraController.DirectionState state = new ReverseCameraController.DirectionState();
        state.requested(1);
        state.accept(1, true, true);
        assertEquals(0, state.effectiveMode());
        assertTrue(state.shouldRequest(1));
        state.requested(1);
        state.reset();
        assertEquals(0, state.effectiveMode());
        assertTrue(state.shouldRequest(1));
    }

    @Test
    public void controllerRecordsOnlyAcceptedRequestsBeforeSelectorCallbacks() throws Exception {
        String source = read("ReverseCameraController.java");
        assertTrue(source.contains("if (!activeHelper.setReverseSideMode(activeRequestId, mode)) return;"));
        assertTrue(source.indexOf("direction.requested(mode)") > source.indexOf(
                "if (!activeHelper.setReverseSideMode(activeRequestId, mode)) return;"));
        assertTrue(source.contains("activeRequestId > 0 && requestId == activeRequestId"));
        assertTrue(source.contains("direction.accept(selected,"));
    }

    private static String read(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
