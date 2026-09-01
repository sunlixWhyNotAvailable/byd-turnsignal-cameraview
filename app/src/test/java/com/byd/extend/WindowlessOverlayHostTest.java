package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import android.view.WindowManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WindowlessOverlayHostTest {
    @Test
    public void transparencyMapsToOpaqueAlpha() {
        assertEquals(1.0f, WindowlessOverlayHost.alphaForTransparency(0), 0.0f);
        assertEquals(0.5f, WindowlessOverlayHost.alphaForTransparency(50), 0.0f);
        assertEquals(0.0f, WindowlessOverlayHost.alphaForTransparency(100), 0.0f);
    }

    @Test
    public void cameraLayersStayAboveReverse() {
        assertEquals(Integer.MAX_VALUE - 32, WindowlessOverlayHost.REVERSE_LAYER);
        assertEquals(Integer.MAX_VALUE - 16, WindowlessOverlayHost.cameraLayer(0));
        assertEquals(Integer.MAX_VALUE - 5, WindowlessOverlayHost.cameraLayer(11));
        assertTrue(WindowlessOverlayHost.cameraLayer(0) > WindowlessOverlayHost.REVERSE_LAYER);
        assertTrue(WindowlessOverlayHost.REVERSE_CONTROL_LAYER
                > WindowlessOverlayHost.cameraLayer(11));
    }

    @Test
    public void selectorHostsAreTouchableWithoutMakingCameraRootTouchable() {
        int cameraFlags = WindowlessOverlayHost.windowFlags(false);
        int selectorFlags = WindowlessOverlayHost.windowFlags(true);
        assertTrue((cameraFlags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertFalse((selectorFlags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertTrue((selectorFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        assertTrue((selectorFlags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0);
    }

    @Test
    public void invalidTransparencyAndLayerAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.alphaForTransparency(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.alphaForTransparency(101));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.cameraLayer(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowlessOverlayHost.cameraLayer(12));
    }

    @Test
    public void normalLifecycleUsesQuiesceAndNeverDestructiveRelease() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/WindowlessOverlayHost.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/WindowlessOverlayHost.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("synchronized void quiesce()"));
        assertFalse(text.contains("void release()"));
        assertFalse(text.contains("setWindowStopped"));
        assertFalse(text.contains("host.release()"));
        assertFalse(text.contains("package.release()"));
        assertFalse(text.contains("root_removed"));
    }

    @Test
    public void cameraPrepareAndVisibilityTransactionsHaveStableWireCodes() {
        assertEquals(0, CameraShellProtocol.PREPARE_OK);
        assertEquals(1, CameraShellProtocol.PREPARE_RESTART_REQUIRED);
        assertEquals(android.os.IBinder.FIRST_CALL_TRANSACTION + 17,
                CameraShellProtocol.TX_REVERSE_UPDATE_VISIBILITY);
    }

    @Test
    public void tabletOverlayDoesNotCoerceStoredOutputMode() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/ShellCameraOverlay.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/ShellCameraOverlay.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse(text.contains("tabletAspectPreservingCrop"));
        assertTrue(text.contains("applyDirectCameraCrop(spec.crop())"));
        assertTrue(text.contains("applyRawFallbackCrop(spec.rawFallbackCrop)"));
    }

    @Test
    public void reverseVisibilityTransactionGuardsCallerAndCurrentRequest() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperMain.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/CameraHelperMain.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int branch = text.indexOf("if (code == TX_UPDATE_REVERSE_VISIBILITY)");
        assertTrue(branch >= 0);
        String handler = text.substring(branch, Math.min(text.length(), branch + 1_600));
        assertTrue(handler.contains("CameraShellProtocol.isCallerAllowed"));
        assertTrue(handler.contains("requireActivityRequestId(requestId)"));
        assertTrue(handler.contains("activeReverseControllerRequestId"));
    }

    @Test
    public void attachFailuresEscalateToBoundedShellRestart() throws Exception {
        Path cameraPath = Path.of("app/src/main/java/com/byd/extend/ShellCameraOverlay.java");
        Path reversePath = Path.of("app/src/main/java/com/byd/extend/ShellReverseCameraOverlay.java");
        if (!Files.exists(cameraPath)) {
            cameraPath = Path.of("src/main/java/com/byd/extend/ShellCameraOverlay.java");
            reversePath = Path.of("src/main/java/com/byd/extend/ShellReverseCameraOverlay.java");
        }
        String camera = new String(Files.readAllBytes(cameraPath),
                StandardCharsets.UTF_8);
        String reverse = new String(Files.readAllBytes(reversePath),
                StandardCharsets.UTF_8);
        assertTrue(camera.contains("PrepareRestartRequired(\"overlay_attach_failed\")"));
        assertTrue(reverse.contains("PrepareRestartRequired(\"reverse_attach_failed\")"));
        assertTrue(reverse.contains("PrepareRestartRequired(\"selector_attach_failed\")"));
        assertTrue(reverse.contains("frontControl != null || rearControl != null"));
        assertFalse(camera.contains("windowless.release"));
        assertFalse(reverse.contains("windowless.release"));
    }

    @Test
    public void transparentSelectorHostsForwardPressStateAndReset() throws Exception {
        Path overlayPath = Path.of(
                "app/src/main/java/com/byd/extend/ShellReverseCameraOverlay.java");
        Path selectorPath = Path.of(
                "app/src/main/java/com/byd/extend/ReverseSideSelectorView.java");
        if (!Files.exists(overlayPath)) {
            overlayPath = Path.of("src/main/java/com/byd/extend/ShellReverseCameraOverlay.java");
            selectorPath = Path.of("src/main/java/com/byd/extend/ReverseSideSelectorView.java");
        }
        String overlay = new String(Files.readAllBytes(overlayPath), StandardCharsets.UTF_8);
        String selector = new String(Files.readAllBytes(selectorPath), StandardCharsets.UTF_8);
        assertTrue(overlay.contains("button.setOnTouchListener"));
        assertTrue(overlay.contains("currentRoot.setSelectorPressed(mode, true)"));
        assertTrue(overlay.contains("currentRoot.setSelectorPressed(mode, false)"));
        assertTrue(overlay.contains("MotionEvent.ACTION_CANCEL"));
        assertTrue(selector.contains("setExternalPressedMode"));
        assertTrue(selector.contains("canvas.scale(0.97f, 0.97f"));
        assertTrue(selector.contains("PRESSED_BUTTON_COLOR"));
    }
}
