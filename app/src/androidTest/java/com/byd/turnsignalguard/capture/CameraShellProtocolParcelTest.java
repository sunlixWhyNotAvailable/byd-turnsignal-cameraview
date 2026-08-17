package com.byd.turnsignalguard.capture;

import android.os.Parcel;

import junit.framework.TestCase;

public final class CameraShellProtocolParcelTest extends TestCase {
    private static final float EPSILON = 0.0001f;

    public void testOverlayRoundTripPreservesRawFallbackAndDewarpOrder() {
        DirectCameraCrop raw = DirectCameraCrop.of(
                0.05f, 0.07f, 0.62f, 0.68f, DirectCameraCrop.ASPECT_FREE,
                -8, CameraRotation.MODE_FILL);
        CameraShellProtocol.OverlaySpec source = new CameraShellProtocol.OverlaySpec(
                CameraProfile.REAR_RIGHT, 41, CameraDisplayTarget.TABLET,
                640, 480, 18, 36,
                0.22f, 0.19f, 0.54f, 0.58f, DirectCameraCrop.ASPECT_FREE,
                12, CameraRotation.MODE_ALIGNED, 9,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, true, 117,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL), raw,
                CameraBufferQuality.BALANCED);

        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel);
            parcel.setDataPosition(0);
            CameraShellProtocol.OverlaySpec restored =
                    CameraShellProtocol.OverlaySpec.readFromParcel(parcel);
            restored.validate(1920, 1080);
            assertEquals(source.cameraId, restored.cameraId);
            assertEquals(source.requestId, restored.requestId);
            assertEquals(source.target, restored.target);
            assertEquals(source.width, restored.width);
            assertEquals(source.height, restored.height);
            assertEquals(source.x, restored.x);
            assertEquals(source.y, restored.y);
            assertCrop(source.crop(), restored.crop());
            assertCrop(raw, restored.rawFallbackCrop);
            assertDewarp(source.dewarp, restored.dewarp);
            assertEquals(CameraBufferQuality.BALANCED, restored.bufferQuality);
            assertEquals(0, parcel.dataAvail());
        } finally {
            parcel.recycle();
        }
    }

    public void testReverseRoundTripPreservesThreeRawCropsAndDewarpRecords() {
        ReverseCameraLayout active = ReverseCameraLayout.defaults();
        ReverseCameraLayout raw = active;
        for (int index = 1; index <= 3; index++) {
            ReverseCameraLayout.Pane pane = active.pane(index);
            active = ReverseCameraLayout.withPane(active, index, pane.destination,
                    ReverseCameraLayout.sourceCrop(
                            0.04f * index, 0.05f * index, 0.70f, 0.66f), index * 3);
            active = ReverseCameraLayout.withDisplayMode(
                    active, index, (index - 1) % 3);
            raw = ReverseCameraLayout.withPane(raw, index, pane.destination,
                    ReverseCameraLayout.sourceCrop(
                            0.02f * index, 0.03f * index, 0.76f, 0.72f), index * 3);
        }
        CameraShellProtocol.ReverseOverlaySpec source =
                new CameraShellProtocol.ReverseOverlaySpec(
                        52, active, raw, 11,
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR, true, 91,
                                CameraDewarpConfig.PROJECTION_CYLINDRICAL),
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 103,
                                CameraDewarpConfig.PROJECTION_RECTILINEAR),
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, false, 119,
                                CameraDewarpConfig.PROJECTION_CYLINDRICAL),
                        CameraBufferQuality.QUALITY);

        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel);
            parcel.setDataPosition(0);
            CameraShellProtocol.ReverseOverlaySpec restored =
                    CameraShellProtocol.ReverseOverlaySpec.readFromParcel(parcel);
            restored.validate(1920, 1080);
            assertEquals(source.requestId, restored.requestId);
            assertEquals(source.cornerRadiusDp, restored.cornerRadiusDp);
            assertDewarp(source.rearDewarp, restored.rearDewarp);
            assertDewarp(source.leftDewarp, restored.leftDewarp);
            assertDewarp(source.rightDewarp, restored.rightDewarp);
            assertEquals(CameraBufferQuality.QUALITY, restored.bufferQuality);
            for (int index = 1; index <= 3; index++) {
                assertRect(active.pane(index).sourceCrop,
                        restored.layout.pane(index).sourceCrop);
                assertRect(raw.pane(index).sourceCrop,
                        restored.rawFallbackLayout.pane(index).sourceCrop);
                assertEquals(active.pane(index).displayMode,
                        restored.rawFallbackLayout.pane(index).displayMode);
                assertEquals(active.pane(index).rotationDegrees,
                        restored.layout.pane(index).rotationDegrees);
                assertEquals(active.pane(index).displayMode,
                        restored.layout.pane(index).displayMode);
                assertEquals(active.pane(index).zOrder, restored.layout.pane(index).zOrder);
            }
            assertEquals(0, parcel.dataAvail());
        } finally {
            parcel.recycle();
        }
    }

    public void testOverlayWriterUsesFixedWireOrder() {
        DirectCameraCrop raw = DirectCameraCrop.of(
                0.11f, 0.12f, 0.51f, 0.52f, DirectCameraCrop.ASPECT_FREE,
                -17, CameraRotation.MODE_ALIGNED);
        CameraShellProtocol.OverlaySpec source = new CameraShellProtocol.OverlaySpec(
                CameraProfile.FRONT_RIGHT, 71, CameraDisplayTarget.CLUSTER,
                611, 477, 23, 41,
                0.21f, 0.22f, 0.53f, 0.54f, DirectCameraCrop.ASPECT_SIXTEEN_NINE,
                19, CameraRotation.MODE_FILL, 13,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, true, 123,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL), raw,
                CameraBufferQuality.ORIGINAL);

        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel);
            parcel.setDataPosition(0);
            assertEquals(CameraProfile.FRONT_RIGHT, parcel.readInt());
            assertEquals(71, parcel.readInt());
            assertEquals(CameraDisplayTarget.CLUSTER, parcel.readInt());
            assertEquals(611, parcel.readInt());
            assertEquals(477, parcel.readInt());
            assertEquals(23, parcel.readInt());
            assertEquals(41, parcel.readInt());
            assertEquals(0.21f, parcel.readFloat(), EPSILON);
            assertEquals(0.22f, parcel.readFloat(), EPSILON);
            assertEquals(0.53f, parcel.readFloat(), EPSILON);
            assertEquals(0.54f, parcel.readFloat(), EPSILON);
            assertEquals(DirectCameraCrop.ASPECT_SIXTEEN_NINE, parcel.readInt());
            assertEquals(19, parcel.readInt());
            assertEquals(CameraRotation.MODE_FILL, parcel.readInt());
            assertEquals(13, parcel.readInt());
            assertDewarpWire(parcel, CameraDewarpConfig.LENS_RIGHT, true, 123,
                    CameraDewarpConfig.PROJECTION_CYLINDRICAL);
            assertCropWire(parcel, raw);
            assertEquals(CameraBufferQuality.ORIGINAL, parcel.readInt());
            assertEquals(0, parcel.dataAvail());
        } finally {
            parcel.recycle();
        }
    }

    public void testReverseWriterUsesFixedWireOrder() {
        ReverseCameraLayout active = ReverseCameraLayout.defaults();
        ReverseCameraLayout raw = active;
        for (int index = 1; index <= 3; index++) {
            ReverseCameraLayout.Pane pane = active.pane(index);
            active = ReverseCameraLayout.withPane(active, index, pane.destination,
                    ReverseCameraLayout.sourceCrop(
                            0.06f * index, 0.07f * index, 0.61f, 0.62f), index * 5);
            active = ReverseCameraLayout.withDisplayMode(
                    active, index, (index - 1) % 3);
            raw = ReverseCameraLayout.withPane(raw, index, pane.destination,
                    ReverseCameraLayout.sourceCrop(
                            0.03f * index, 0.04f * index, 0.71f, 0.72f), index * 5);
        }
        CameraShellProtocol.ReverseOverlaySpec source =
                new CameraShellProtocol.ReverseOverlaySpec(
                        81, active, raw, 15,
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR, true, 92,
                                CameraDewarpConfig.PROJECTION_RECTILINEAR),
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, false, 104,
                                CameraDewarpConfig.PROJECTION_CYLINDRICAL),
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, true, 116,
                                CameraDewarpConfig.PROJECTION_RECTILINEAR),
                        CameraBufferQuality.BALANCED);

        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel);
            parcel.setDataPosition(0);
            assertEquals(81, parcel.readInt());
            assertRectWire(parcel, active.background);
            assertEquals(15, parcel.readInt());
            assertDewarpWire(parcel, CameraDewarpConfig.LENS_REAR, true, 92,
                    CameraDewarpConfig.PROJECTION_RECTILINEAR);
            assertDewarpWire(parcel, CameraDewarpConfig.LENS_LEFT, false, 104,
                    CameraDewarpConfig.PROJECTION_CYLINDRICAL);
            assertDewarpWire(parcel, CameraDewarpConfig.LENS_RIGHT, true, 116,
                    CameraDewarpConfig.PROJECTION_RECTILINEAR);
            for (int index = 1; index <= 3; index++) {
                assertRectWire(parcel, raw.pane(index).sourceCrop);
            }
            for (int index = 1; index <= 3; index++) {
                ReverseCameraLayout.Pane pane = active.pane(index);
                assertEquals(index, parcel.readInt());
                assertRectWire(parcel, pane.destination);
                assertRectWire(parcel, pane.sourceCrop);
                assertEquals(index * 5, parcel.readInt());
                assertEquals((index - 1) % 3, parcel.readInt());
                assertEquals(pane.zOrder, parcel.readInt());
            }
            assertEquals(CameraBufferQuality.BALANCED, parcel.readInt());
            assertEquals(0, parcel.dataAvail());
        } finally {
            parcel.recycle();
        }
    }

    public void testReverseReaderRejectsInvalidDisplayMode() {
        CameraShellProtocol.ReverseOverlaySpec source =
                new CameraShellProtocol.ReverseOverlaySpec(
                        91, ReverseCameraLayout.defaults(), 8);
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel);
            parcel.setDataPosition(0);
            parcel.readInt();
            for (int i = 0; i < 4; i++) parcel.readFloat();
            parcel.readInt();
            for (int i = 0; i < 12; i++) parcel.readInt();
            for (int i = 0; i < 12; i++) parcel.readFloat();
            parcel.readInt();
            for (int i = 0; i < 8; i++) parcel.readFloat();
            parcel.readInt();
            int displayModePosition = parcel.dataPosition();
            parcel.setDataPosition(displayModePosition);
            parcel.writeInt(99);
            parcel.setDataPosition(0);
            try {
                CameraShellProtocol.ReverseOverlaySpec.readFromParcel(parcel);
                fail("invalid reverse display mode accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("display mode"));
            }
        } finally {
            parcel.recycle();
        }
    }

    private void assertCrop(DirectCameraCrop expected, DirectCameraCrop actual) {
        assertEquals(expected.left, actual.left, EPSILON);
        assertEquals(expected.top, actual.top, EPSILON);
        assertEquals(expected.width, actual.width, EPSILON);
        assertEquals(expected.height, actual.height, EPSILON);
        assertEquals(expected.aspectMode, actual.aspectMode);
        assertEquals(expected.rotationDegrees, actual.rotationDegrees);
        assertEquals(expected.rotationMode, actual.rotationMode);
    }

    private void assertRect(
            ReverseCameraLayout.Rect expected, ReverseCameraLayout.Rect actual) {
        assertEquals(expected.left, actual.left, EPSILON);
        assertEquals(expected.top, actual.top, EPSILON);
        assertEquals(expected.width, actual.width, EPSILON);
        assertEquals(expected.height, actual.height, EPSILON);
    }

    private void assertDewarp(CameraDewarpConfig expected, CameraDewarpConfig actual) {
        assertEquals(expected.lens, actual.lens);
        assertEquals(expected.enabled, actual.enabled);
        assertEquals(expected.fovDegrees, actual.fovDegrees);
        assertEquals(expected.projection, actual.projection);
    }

    private void assertDewarpWire(
            Parcel parcel, int lens, boolean enabled, int fov, int projection) {
        assertEquals(lens, parcel.readInt());
        assertEquals(enabled ? 1 : 0, parcel.readInt());
        assertEquals(fov, parcel.readInt());
        assertEquals(projection, parcel.readInt());
    }

    private void assertCropWire(Parcel parcel, DirectCameraCrop crop) {
        assertEquals(crop.left, parcel.readFloat(), EPSILON);
        assertEquals(crop.top, parcel.readFloat(), EPSILON);
        assertEquals(crop.width, parcel.readFloat(), EPSILON);
        assertEquals(crop.height, parcel.readFloat(), EPSILON);
        assertEquals(crop.aspectMode, parcel.readInt());
        assertEquals(crop.rotationDegrees, parcel.readInt());
        assertEquals(crop.rotationMode, parcel.readInt());
    }

    private void assertRectWire(Parcel parcel, ReverseCameraLayout.Rect rect) {
        assertEquals(rect.left, parcel.readFloat(), EPSILON);
        assertEquals(rect.top, parcel.readFloat(), EPSILON);
        assertEquals(rect.width, parcel.readFloat(), EPSILON);
        assertEquals(rect.height, parcel.readFloat(), EPSILON);
    }
}
