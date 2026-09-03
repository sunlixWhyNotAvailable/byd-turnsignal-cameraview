package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Focused source/wire regressions for the learned Reverse steering-button command. */
public final class CameraShellToggleProtocolTest {
    @Test
    public void toggleTransactionAndCapabilityKeepStableWireValues() throws Exception {
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 18,
                CameraShellProtocol.TX_REVERSE_TOGGLE_MODE);
        assertEquals(1, CameraShellProtocol.CAP_REVERSE_TOGGLE_MODE);
        assertEquals(27, CameraShellProtocol.VERSION);

        String shell = readMainSource("CameraShellMain.java");
        int ping = shell.indexOf("if (code == CameraShellProtocol.TX_PING)");
        int pid = shell.indexOf("reply.writeInt(Process.myPid())", ping);
        int capability = shell.indexOf(
                "reply.writeInt(CameraShellProtocol.CAP_REVERSE_TOGGLE_MODE)", pid);
        assertTrue(ping >= 0);
        assertTrue(pid > ping);
        assertTrue(capability > pid);

        int transaction = shell.indexOf(
                "if (code == CameraShellProtocol.TX_REVERSE_TOGGLE_MODE)");
        String branch = shell.substring(transaction,
                shell.indexOf("if (code == CameraShellProtocol.TX_REVERSE_CLOSE)", transaction));
        assertTrue(transaction > capability);
        assertTrue(branch.contains("requestId <= 0"));
        assertTrue(branch.contains("runOnMain"));
        assertTrue(branch.contains("reverseOverlay.toggleSideMode(requestId)"));
    }

    @Test
    public void oldPingRepliesAreUnsupportedAndToggleUsesCachedEpochOnly() throws Exception {
        String controller = readMainSource("TurnSignalController.java");
        int ping = controller.indexOf("private int cameraHelperPid(IBinder value)");
        int pingEnd = controller.indexOf("private void transactCameraCallback", ping);
        String pingMethod = controller.substring(ping, pingEnd);
        assertTrue(pingMethod.contains("reply.dataAvail() < 4"));
        assertTrue(pingMethod.contains("CAP_REVERSE_TOGGLE_MODE"));

        int toggle = controller.indexOf("void toggleReverseSideMode(int requestId, long ownerEpoch)");
        int toggleEnd = controller.indexOf("private void postCompletion", toggle);
        String toggleMethod = controller.substring(toggle, toggleEnd);
        assertTrue(toggle >= 0);
        assertTrue(toggleMethod.contains("cameraHelper"));
        assertTrue(toggleMethod.contains("cameraHelperEpoch"));
        int capturedBinder = toggleMethod.indexOf("value = cameraHelper");
        int enqueue = toggleMethod.indexOf("worker.execute");
        assertTrue(capturedBinder >= 0);
        assertTrue(enqueue > capturedBinder);
        assertTrue(toggleMethod.contains("CameraProbeActivity.reverseOwnerStillAbsent(ownerEpoch)"));
        assertTrue(toggleMethod.contains("transactReverseToggle"));
        assertFalse(toggleMethod.contains("ensureCameraHelper"));
        assertFalse(toggleMethod.contains("resolveCameraHelper"));
        assertFalse(toggleMethod.contains("restartCameraHelper"));
    }

    @Test
    public void helperAndShellRequireCurrentRequestAndVisibleIntegratedWidget() throws Exception {
        String helper = readMainSource("CameraHelperMain.java");
        int bridge = helper.indexOf(
                "synchronized void toggleReverseSideMode(int requestId, long ownerEpoch)");
        int bridgeEnd = helper.indexOf("synchronized void emitControllerEvent", bridge);
        String bridgeMethod = helper.substring(bridge, bridgeEnd);
        assertTrue(bridgeMethod.contains("requestId <= 0"));
        assertTrue(bridgeMethod.contains("ownerEpoch < 0"));
        assertTrue(bridgeMethod.contains("activeReverseControllerRequestId != requestId"));
        assertTrue(bridgeMethod.contains(
                "turnController.toggleReverseSideMode(requestId, ownerEpoch)"));

        String overlay = readMainSource("ShellReverseCameraOverlay.java");
        int toggle = overlay.indexOf("void toggleSideMode(int expectedRequestId)");
        int toggleEnd = overlay.indexOf("private void quiesce", toggle);
        String toggleMethod = overlay.substring(toggle, toggleEnd);
        assertTrue(toggleMethod.contains("requireRequest(expectedRequestId)"));
        assertTrue(toggleMethod.contains("!active || !visible || closing"));
        assertTrue(toggleMethod.contains("!widgetVisible"));
        assertTrue(toggleMethod.contains("!widgetAvailable"));
        assertTrue(toggleMethod.contains("!frontIntegrationAvailable"));
        assertTrue(toggleMethod.contains("scheduleSelectorAction(next)"));
    }

    private static String readMainSource(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
