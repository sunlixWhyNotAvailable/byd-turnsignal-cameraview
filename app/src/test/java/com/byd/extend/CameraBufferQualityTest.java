package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CameraBufferQualityTest {
    @Test public void fullFramePreservesEveryPresetIncludingRotatedAndUnmeasuredPanes() {
        for (int quality = 0; quality <= 3; quality++) {
            for (int[] pane : new int[][]{{487, 220}, {1280, 720}, {1, 1}}) {
                for (int rotation : new int[]{0, 35, 90, -180}) {
                    int nativeHeight = nativeHeight(1f, 1f, rotation);
                    assertArrayEquals(capture(pane[0], pane[1], quality),
                            output(pane[0], pane[1], quality, nativeHeight));
                }
            }
        }
    }

    @Test public void croppedOriginalReducesOnlyFinalBufferAndAccountsForRotation() {
        assertArrayEquals(new int[]{1182, 800}, output(487, 220, 3, nativeHeight(.384127f, .55f, 0)));
        assertArrayEquals(new int[]{1625, 1100}, output(487, 220, 3, nativeHeight(.384127f, .55f, 35)));
        assertArrayEquals(new int[]{1625, 1100}, output(487, 220, 3, nativeHeight(.384127f, .55f, -35)));
        assertArrayEquals(new int[]{1920, 1300}, capture(487, 220, 3));
        assertArrayEquals(new int[]{1920, 1300}, output(487, 220, 3, nativeHeight(1f, .85f, 0)));
        assertEquals(1100, nativeHeight(.2f, .8f, 0));
        assertEquals(800, nativeHeight(.2f, .8f, 90));
    }

    @Test public void bothScreenAxesLimitReductionAndExistingPresetStillCapsOutput() {
        assertArrayEquals(new int[]{1081, 732}, output(1080, 200, 3, nativeHeight(.1f, .1f, 0)));
        assertArrayEquals(new int[]{1329, 900}, output(300, 900, 3, nativeHeight(.1f, .1f, 0)));
        for (int quality = 0; quality < 3; quality++) {
            int[] before = capture(487, 220, quality);
            assertArrayEquals(before, output(487, 220, quality, nativeHeight(.384127f, .55f, 0)));
            int[] smaller = output(200, 100, quality, nativeHeight(.1f, .1f, 0));
            assertTrue(smaller[0] < before[0]);
            assertTrue(smaller[1] < before[1]);
        }
        assertEquals(700, nativeHeight(.25f, .5f, 0));
        assertEquals(700, nativeHeight(.25f, .51f, 0));
    }

    @Test public void rawFallbackAndIntegratedFrontShareTheLargerDemandWithoutSteeringResizes() {
        ReverseCameraLayout small = layout(.2f, .2f, 0f);
        ReverseCameraLayout large = layout(.7f, .7f, 0f);
        CameraDewarpConfig off = CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
        CameraDewarpConfig on = CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 100);
        int[] rearOnly = ReverseCameraCompositionView.nativeOutputHeights(
                small, small, large, large, off, off, off, off, off, off, false, false);
        int[] integrated = ReverseCameraCompositionView.nativeOutputHeights(
                small, small, large, large, off, off, off, off, off, off, true, true);
        assertEquals(300, rearOnly[1]);
        assertEquals(1000, integrated[1]);
        assertEquals(1000, integrated[2]);
        int[] fallback = ReverseCameraCompositionView.nativeOutputHeights(
                small, large, small, small, on, on, on, on, on, on, false, false);
        assertEquals(1000, fallback[1]);
        int[] corrected = ReverseCameraCompositionView.nativeOutputHeights(
                large, small, small, small, on, on, on, on, on, on, false, false);
        assertEquals(1000, corrected[1]);
        int[] shifted = ReverseCameraCompositionView.nativeOutputHeights(
                layout(.2f, .2f, .5f), layout(.2f, .2f, .5f),
                large, large, off, off, off, off, off, off, false, false);
        assertArrayEquals(rearOnly, shifted);
    }

    @Test public void invalidDimensionsFailInsteadOfProducingAnAllocation() {
        for (float value : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, 2}) {
            assertThrows(IllegalArgumentException.class, () -> nativeHeight(value, .5f, 0));
            assertThrows(IllegalArgumentException.class, () -> nativeHeight(.5f, value, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> nativeHeight(.5f, .5f, 181));
        assertThrows(IllegalArgumentException.class, () -> output(100, 100, 3, 1301));
    }

    private static int nativeHeight(float width, float height, int rotation) {
        return CameraBufferQuality.cropNativeHeight(1920, 1300, width, height, rotation);
    }

    private static int[] capture(int width, int height, int quality) {
        return CameraBufferQuality.bufferSizeForPane(width, height, 1920, 1300, quality);
    }

    private static int[] output(int width, int height, int quality, int nativeHeight) {
        return CameraBufferQuality.outputSizeForPane(width, height, 1920, 1300, quality, nativeHeight);
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
