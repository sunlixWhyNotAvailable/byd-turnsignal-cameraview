package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CameraRuntimeBorderTest {
    @Test public void ordinaryOverlayBuildersCarryIndependentOpaqueFrames() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraBorderSettings.writeBlind(settings, CameraProfile.REAR_LEFT,
                new CameraBorderSettings.Border(4, 0xFF123456));
        CameraBorderSettings.writeParking(settings, ParkingCameraProfile.FRONT,
                new CameraBorderSettings.Border(5, 0xFF234567));

        CameraShellProtocol.OverlaySpec blind = BlindSpotOverlayController.buildOverlaySpec(
                settings, CameraProfile.of(CameraProfile.REAR_LEFT), 1,
                CameraDisplayTarget.TABLET, 1920, 1080, 0, 0, 0);
        CameraShellProtocol.OverlaySpec parking = ParkingCameraController.buildOverlaySpec(
                ParkingCameraProfile.of(ParkingCameraProfile.FRONT), 2,
                CameraDisplayTarget.TABLET, 1920, 1080, settings);

        assertEquals(4, blind.borderDp);
        assertEquals(0xFF123456, blind.borderArgb);
        assertEquals(5, parking.borderDp);
        assertEquals(0xFF234567, parking.borderArgb);
        blind.validate(1920, 1080);
        parking.validate(1920, 1080);
    }

    @Test public void reverseBuilderCarriesAllRearFrontAndElementFrames() {
        TestSharedPreferences settings = new TestSharedPreferences();
        for (int camera = ReverseCameraLayout.REAR_CAMERA_INDEX;
                camera <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX; camera++) {
            CameraBorderSettings.writeReverse(settings, camera, false,
                    new CameraBorderSettings.Border(camera, 0xFF100000 + camera));
            CameraBorderSettings.writeReverse(settings, camera, true,
                    new CameraBorderSettings.Border(camera + 3, 0xFF200000 + camera));
        }
        CameraBorderSettings.writeReverseElement(settings,
                ReverseCameraLayout.BACKGROUND_PANE_ID,
                new CameraBorderSettings.Border(7, 0xFF300001));
        CameraBorderSettings.writeReverseElement(settings,
                ReverseCameraLayout.WIDGET_PANE_ID,
                new CameraBorderSettings.Border(8, 0xFF300002));

        CameraShellProtocol.ReverseOverlaySpec spec =
                ReverseCameraController.buildOverlaySpec(settings, 3);
        assertBorder(spec, CameraShellProtocol.ReverseOverlaySpec.BORDER_BACKGROUND,
                7, 0xFF300001);
        assertBorder(spec, CameraShellProtocol.ReverseOverlaySpec.BORDER_WIDGET,
                8, 0xFF300002);
        for (int camera = ReverseCameraLayout.REAR_CAMERA_INDEX;
                camera <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX; camera++) {
            assertBorder(spec, CameraShellProtocol.ReverseOverlaySpec.borderIndex(camera, false),
                    camera, 0xFF100000 + camera);
            assertBorder(spec, CameraShellProtocol.ReverseOverlaySpec.borderIndex(camera, true),
                    camera + 3, 0xFF200000 + camera);
        }
        spec.validate(1920, 1080);
    }

    @Test public void protocolRejectsInvalidWidthColorAndReverseIndex() {
        CameraShellProtocol.OverlaySpec overlay = BlindSpotOverlayController.buildOverlaySpec(
                new TestSharedPreferences(), CameraProfile.of(CameraProfile.REAR_LEFT), 1,
                CameraDisplayTarget.TABLET, 1920, 1080, 0, 0, 0);
        overlay.borderDp = 17;
        assertThrows(IllegalArgumentException.class, () -> overlay.validate(1920, 1080));
        overlay.borderDp = 1;
        overlay.borderArgb = 0x7F000000;
        assertThrows(IllegalArgumentException.class, () -> overlay.validate(1920, 1080));

        CameraShellProtocol.ReverseOverlaySpec reverse =
                new CameraShellProtocol.ReverseOverlaySpec(1, ReverseCameraLayout.defaults());
        assertThrows(IllegalArgumentException.class,
                () -> reverse.setBorder(-1, 1, 0xFF000000));
        assertThrows(IllegalArgumentException.class,
                () -> reverse.setBorder(0, 1, 0x7F000000));
    }

    @Test public void protocolSerializationAndRenderingKeepFrameAndTouchBoundaries()
            throws Exception {
        String protocol = source("CameraShellProtocol.java");
        assertTrue(protocol.contains("parcel.writeInt(borderDp[index]);\n"
                + "                parcel.writeInt(borderArgb[index]);"));
        assertTrue(protocol.contains("result.setBorder(index, parcel.readInt(), parcel.readInt())"));

        String overlay = source("ShellCameraOverlay.java");
        String createWindow = overlay.substring(overlay.indexOf("private void createWindow("),
                overlay.indexOf("private void updateWindow("));
        assertTrue(createWindow.indexOf("applyBorder(nextRoot, spec);") >= 0
                && createWindow.indexOf("if (CameraOverlayProfile.isMirror(cameraId)) {") > createWindow.indexOf("applyBorder(nextRoot, spec);"));
        assertTrue(createWindow.contains(
                "nextRoot.setOnTouchListener((view, event) -> onMirrorTouch(event))"));
        String reverse = source("ReverseCameraCompositionView.java");
        assertTrue(reverse.contains("effectiveSourceIsFront(pane.sourceIndex, sideMode,"));
        assertTrue(reverse.contains("pane.setBorder(borderDp[index], borderArgb[index]);"));
        String editor = source("ReverseCameraEditorView.java");
        assertTrue(editor.indexOf("drawConfiguredFrame(canvas, rect,") >= 0
                && editor.indexOf("stroke.setColor(color);") > editor.indexOf("drawConfiguredFrame(canvas, rect,"));
    }

    private static void assertBorder(CameraShellProtocol.ReverseOverlaySpec spec,
            int index, int width, int color) {
        assertEquals(width, spec.borderDp[index]);
        assertEquals(color, spec.borderArgb[index]);
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }
}
