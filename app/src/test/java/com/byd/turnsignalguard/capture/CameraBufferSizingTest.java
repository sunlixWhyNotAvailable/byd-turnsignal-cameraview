package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraBufferSizingTest {
    @Test
    public void paneBuffersPreserveSourceAspectWithinDisplayBounds() {
        assertArrayEquals(new int[]{527, 357},
                BlindSpotCameraView.paneBoundedBufferSize(576, 357));
        assertArrayEquals(new int[]{539, 365},
                BlindSpotCameraView.paneBoundedBufferSize(539, 377));
        assertArrayEquals(new int[]{907, 614},
                BlindSpotCameraView.paneBoundedBufferSize(1085, 614));
        assertArrayEquals(new int[]{1462, 990},
                BlindSpotCameraView.paneBoundedBufferSize(1920, 990));
        assertArrayEquals(new int[]{1920, 1300},
                BlindSpotCameraView.paneBoundedBufferSize(4000, 3000));
    }

    @Test
    public void qualityPresetsScalePaneBuffersAndRestoreOriginalSource() {
        assertArrayEquals(new int[]{527, 357},
                BlindSpotCameraView.paneBoundedBufferSize(
                        576, 357, CameraBufferQuality.PERFORMANCE));
        assertArrayEquals(new int[]{792, 536},
                BlindSpotCameraView.paneBoundedBufferSize(
                        576, 357, CameraBufferQuality.BALANCED));
        assertArrayEquals(new int[]{1055, 714},
                BlindSpotCameraView.paneBoundedBufferSize(
                        576, 357, CameraBufferQuality.QUALITY));
        assertArrayEquals(new int[]{1920, 1300},
                BlindSpotCameraView.paneBoundedBufferSize(
                        576, 357, CameraBufferQuality.ORIGINAL));
    }

    @Test
    public void qualityPreferenceDefaultsSafelyAndPersistsGlobalSelection() {
        TestSharedPreferences settings = new TestSharedPreferences();
        assertEquals(CameraBufferQuality.PERFORMANCE,
                CameraBufferQuality.load(settings));

        settings.putInt(CameraBufferQuality.PREF_QUALITY, CameraBufferQuality.BALANCED);
        assertEquals(CameraBufferQuality.BALANCED,
                CameraBufferQuality.load(settings));

        settings.putInt(CameraBufferQuality.PREF_QUALITY, 99);
        assertEquals(CameraBufferQuality.PERFORMANCE,
                CameraBufferQuality.load(settings));

        settings.putString(CameraBufferQuality.PREF_QUALITY, "invalid");
        assertEquals(CameraBufferQuality.PERFORMANCE,
                CameraBufferQuality.load(settings));
    }

    @Test
    public void settingsSelectionPersistsEachPresetAndNotifiesOnlyOnChange() {
        TestSharedPreferences settings = new TestSharedPreferences();
        AtomicInteger changes = new AtomicInteger();

        assertFalse(CameraProbeSettingsPanel.applyQualitySelection(
                settings, CameraBufferQuality.PERFORMANCE,
                changes::incrementAndGet));
        int[] selections = {
                CameraBufferQuality.BALANCED,
                CameraBufferQuality.QUALITY,
                CameraBufferQuality.ORIGINAL,
                CameraBufferQuality.PERFORMANCE
        };
        for (int selection : selections) {
            assertTrue(CameraProbeSettingsPanel.applyQualitySelection(
                    settings, selection, changes::incrementAndGet));
            assertEquals(selection, CameraBufferQuality.load(settings));
        }
        assertEquals(selections.length, changes.get());
    }

    @Test
    public void everyQualityTransitionChangesOverlayAndReverseBuffers() {
        int[][] reverseBounds = ReverseCameraCompositionView.paneBounds(
                ReverseCameraLayout.defaults(), 1920, 990);
        int[] modes = {
                CameraBufferQuality.PERFORMANCE,
                CameraBufferQuality.BALANCED,
                CameraBufferQuality.QUALITY,
                CameraBufferQuality.ORIGINAL
        };
        int[] previousOverlay = null;
        int[] previousReverse = null;
        for (int mode : modes) {
            int[] overlay = BlindSpotCameraView.paneBoundedBufferSize(576, 357, mode);
            int[] reverse = BlindSpotCameraView.paneBoundedBufferSize(
                    reverseBounds[0][0], reverseBounds[0][1], mode);
            if (previousOverlay != null) {
                assertFalse(java.util.Arrays.equals(previousOverlay, overlay));
                assertFalse(java.util.Arrays.equals(previousReverse, reverse));
            }
            previousOverlay = overlay;
            previousReverse = reverse;
        }
    }

    @Test
    public void unmeasuredPaneKeepsSafeFullSourceFallback() {
        assertArrayEquals(new int[]{1920, 1300},
                BlindSpotCameraView.paneBoundedBufferSize(1, 1));
        assertArrayEquals(new int[]{1920, 1300},
                BlindSpotCameraView.paneBoundedBufferSize(0, 990));
    }

    @Test
    public void reverseBoundsUseEachDestinationPane() {
        int[][] bounds = ReverseCameraCompositionView.paneBounds(
                ReverseCameraLayout.defaults(), 1920, 990);

        assertArrayEquals(new int[]{1920, 495}, bounds[0]);
        assertArrayEquals(new int[]{960, 495}, bounds[1]);
        assertArrayEquals(new int[]{960, 495}, bounds[2]);
        for (int[] bound : bounds) {
            assertArrayEquals(new int[]{731, 495},
                    BlindSpotCameraView.paneBoundedBufferSize(bound[0], bound[1]));
        }
    }

    @Test
    public void overlayPaneResizeRebuildsWhenRoundedBufferIsUnchanged() {
        assertArrayEquals(
                BlindSpotCameraView.paneBoundedBufferSize(100, 68),
                BlindSpotCameraView.paneBoundedBufferSize(101, 68));
        assertFalse(ShellCameraOverlay.samePaneSize(100, 68, 101, 68));
        assertTrue(ShellCameraOverlay.samePaneSize(100, 68, 100, 68));
    }

    @Test
    public void reversePaneAndViewportResizesRebuildWhenBuffersAreUnchanged() {
        ReverseCameraLayout current = ReverseCameraLayout.defaults();
        ReverseCameraLayout.Pane left = current.rearLeft;
        ReverseCameraLayout resized = ReverseCameraLayout.withPane(
                current, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.destination(
                        left.destination.left, left.destination.top,
                        0.51f, left.destination.height),
                left.sourceCrop);
        int[][] currentBounds = ReverseCameraCompositionView.paneBounds(
                current, 1920, 990);
        int[][] resizedBounds = ReverseCameraCompositionView.paneBounds(
                resized, 1920, 990);
        assertArrayEquals(
                BlindSpotCameraView.paneBoundedBufferSize(
                        currentBounds[1][0], currentBounds[1][1]),
                BlindSpotCameraView.paneBoundedBufferSize(
                        resizedBounds[1][0], resizedBounds[1][1]));
        assertFalse(ReverseCameraCompositionView.samePaneGeometry(
                current, 1920, 990, resized, 1920, 990));

        ReverseCameraLayout moved = ReverseCameraLayout.move(
                current, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 0.02f, 0.0f);
        assertTrue(ReverseCameraCompositionView.samePaneGeometry(
                current, 1920, 990, moved, 1920, 990));

        int[][] large = ReverseCameraCompositionView.paneBounds(current, 4000, 3000);
        int[][] wider = ReverseCameraCompositionView.paneBounds(current, 4001, 3000);
        assertArrayEquals(
                BlindSpotCameraView.paneBoundedBufferSize(large[0][0], large[0][1]),
                BlindSpotCameraView.paneBoundedBufferSize(wider[0][0], wider[0][1]));
        assertFalse(ReverseCameraCompositionView.samePaneGeometry(
                current, 4000, 3000, current, 4001, 3000));
    }

    @Test
    public void cleanRebuildRequiresFreshRawAndCorrectedFrames() {
        CameraDewarpConfig[] pipelines = {
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT),
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 120)
        };
        for (int i = 0; i < pipelines.length; i++) {
            assertEquals(i == 1, pipelines[i].usesGpu());
            BlindSpotCameraView.InputGeneration generation =
                    new BlindSpotCameraView.InputGeneration();
            int retired = generation.next();
            int rebuilt = generation.next();

            assertEquals(retired, generation.frame());
            assertEquals(rebuilt, generation.frame());
            assertFalse(ShellCameraOverlay.isFramePastStaleBuffer(1));
            assertTrue(ShellCameraOverlay.isFramePastStaleBuffer(2));
        }
    }
}
