package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CameraBufferQualityTest {
    @Test public void fullFrameFitAndUnmeasuredPanesPreserveCapturePolicy() {
        for (int quality = 0; quality <= 3; quality++) {
            for (int[] pane : new int[][]{{487, 220}, {1280, 720}, {1, 1}}) {
                assertArrayEquals(capture(pane[0], pane[1], quality),
                        output(pane[0], pane[1], quality, 1, 1, 0, CameraRotation.MODE_FIT));
            }
        }
    }

    @Test public void savedOwnerGeometryReducesAllSmallDisplayProfiles() {
        // Geometry from the saved camera preset; front/rear and raw/corrected are separate views.
        assertOutput(576, 357, .59938854f, .57738447f, 32, 1, 738, 500); // rear Blind raw
        assertOutput(576, 357, .65902162f, .72866434f, 32, 1, 1034, 700); // rear Blind corrected
        assertOutput(576, 357, .46608025f, .50742710f, 45, 2, 1034, 700); // front Blind raw
        assertOutput(576, 357, .41666844f, .44145489f, 45, 2, 886, 600); // front Blind corrected
        assertOutput(576, 357, .88f, .85f, 0, 1, 1772, 1200); // Reverse center, front and rear
        assertOutput(487, 220, .38412699f, .55f, 35, 1, 738, 500); // Reverse rear side raw
        assertOutput(487, 220, .45387408f, .63409293f, 35, 1, 886, 600); // Reverse rear side corrected
        assertOutput(487, 220, .45903558f, .90345520f, -40, 1, 886, 600); // integrated front raw
        assertOutput(487, 220, .84360945f, .98990417f, -40, 1, 1329, 900); // integrated front corrected
        assertOutput(576, 357, .77090323f, .56080025f, 0, 1, 1182, 800); // rear Mirror
        assertOutput(576, 357, .77090323f, .72621381f, 0, 1, 1625, 1100); // front Mirror
        assertArrayEquals(new int[]{1920, 1300}, capture(487, 220, 3));
    }

    @Test public void fillUsesVisibleSamplesWhileFitRetainsWholeRotatedCrop() {
        int[] fill = output(576, 357, 3, .65902162f, .72866434f, 32, CameraRotation.MODE_FILL);
        int[] fit = output(576, 357, 3, .65902162f, .72866434f, 32, CameraRotation.MODE_FIT);
        assertArrayEquals(new int[]{1920, 1300}, fit);
        assertTrue(fill[0] < fit[0] && fill[1] < fit[1]);
        assertArrayEquals(fill, output(576, 357, 3, .65902162f, .72866434f, -32, CameraRotation.MODE_FILL));
        assertArrayEquals(output(576, 357, 3, .41666844f, .44145489f, 0, CameraRotation.MODE_ALIGNED),
                output(576, 357, 3, .41666844f, .44145489f, 45, CameraRotation.MODE_ALIGNED));
    }

    @Test public void screenAxesAndQualityPresetBoundFinalBuffer() {
        assertArrayEquals(new int[]{1081, 732}, output(1080, 200, 3, .1f, .1f, 0, 1));
        assertArrayEquals(new int[]{1329, 900}, output(300, 900, 3, .1f, .1f, 0, 1));
        for (int quality = 0; quality < 3; quality++) {
            int[] before = capture(487, 220, quality);
            int[] after = output(487, 220, quality, .84360945f, .98990417f, -40, 1);
            assertTrue(after[0] <= before[0] && after[1] <= before[1]);
        }
    }

    @Test public void reverseSelectionUsesOnlyActiveCorrectionAndPositionDoesNotInflateIt() {
        ReverseCameraLayout small = layout(.2f, .2f, 0f);
        ReverseCameraLayout large = layout(.7f, .7f, 0f);
        CameraDewarpConfig off = CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
        CameraDewarpConfig on = CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 100);
        DirectCameraCrop corrected = ReverseCameraCompositionView.outputCrop(small, large, on, 2);
        DirectCameraCrop raw = ReverseCameraCompositionView.outputCrop(small, large, off, 2);
        assertEquals(.2f, corrected.width, 0f);
        assertEquals(.7f, raw.width, 0f);
        DirectCameraCrop shifted = ReverseCameraCompositionView.outputCrop(layout(.2f, .2f, .5f), large, on, 2);
        assertArrayEquals(size(corrected), size(shifted));
        assertTrue(size(corrected)[1] < size(raw)[1]);
    }

    @Test public void invalidDimensionsFailInsteadOfProducingAnAllocation() {
        for (float value : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, 2}) {
            assertThrows(IllegalArgumentException.class, () -> output(576, 357, 3, value, .5f, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> output(576, 357, 3, .5f, value, 0, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> output(576, 357, 3, .5f, .5f, 181, 1));
        assertThrows(IllegalArgumentException.class, () -> output(576, 357, 3, .5f, .5f, 0, 3));
    }

    private static void assertOutput(int pw, int ph, float cw, float ch, int angle, int mode, int w, int h) {
        assertArrayEquals(new int[]{w, h}, output(pw, ph, 3, cw, ch, angle, mode));
        assertArrayEquals(new int[]{w, h}, output(pw, ph, 3, cw, ch, -angle, mode));
    }

    private static int[] size(DirectCameraCrop crop) {
        return output(487, 220, 3, crop.width, crop.height, crop.rotationDegrees, crop.rotationMode);
    }

    private static int[] capture(int width, int height, int quality) {
        return CameraBufferQuality.bufferSizeForPane(width, height, 1920, 1300, quality);
    }

    private static int[] output(int pw, int ph, int quality, float cw, float ch, int angle, int mode) {
        int nativeHeight = CameraBufferQuality.cropNativeHeight(1920, 1300, cw, ch, angle, mode, pw, ph);
        return CameraBufferQuality.outputSizeForPane(pw, ph, 1920, 1300, quality, nativeHeight);
    }

    private static ReverseCameraLayout layout(float width, float height, float left) {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        for (ReverseCameraLayout.Pane pane : layout.panes()) {
            layout = ReverseCameraLayout.withPane(layout, pane.cameraIndex, pane.destination,
                    ReverseCameraLayout.sourceCrop(left, 0, width, height), 0);
        }
        return layout;
    }
}
