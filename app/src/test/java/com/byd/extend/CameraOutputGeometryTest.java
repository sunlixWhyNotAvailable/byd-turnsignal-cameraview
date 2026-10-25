package com.byd.extend;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CameraOutputGeometryTest {
    @Test public void identityMirrorAndQuarterTurnHaveKnownClipCoordinates() {
        float[] identity = CameraRotation.gpuTransformValues(
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, 815, 504);
        assertArrayEquals(CameraGpuOutput.IDENTITY, identity, 0f);
        float[] mirror = CameraRotation.gpuTransformValues(
                new float[]{-1, 0, 815, 0, 1, 0, 0, 0, 1}, 815, 504);
        assertArrayEquals(new float[]{1, 1}, project(mirror, -1, 1), .00001f);
        float[] clockwise = CameraRotation.gpuTransformValues(
                new float[]{0, -1, 200, 1, 0, 0, 0, 0, 1}, 200, 200);
        assertArrayEquals(new float[]{1, 1}, project(clockwise, -1, 1), .00001f);
        assertArrayEquals(new float[]{-1, -1}, project(clockwise, 1, -1), .00001f);
    }

    @Test public void gpuPreservesExistingCropRotationFitFillAndMirrorAcrossPaneSizes() {
        for (int[] size : new int[][]{{815, 504}, {486, 220}, {576, 356}, {900, 700}}) {
            int w = size[0], h = size[1];
            for (int angle : new int[]{-180, -35, 0, 35, 90}) {
                for (boolean mirror : new boolean[]{false, true}) {
                    for (int mode : new int[]{CameraRotation.MODE_FIT, CameraRotation.MODE_FILL}) {
                        float[] view = CameraRotation.sourceAwareProportionalTransformValues(
                                .12f, .23f, .7f, .5f, 0, 0, w, h,
                                angle, mode, 1920, 1300, w, h, mirror);
                        float[] gpu = CameraRotation.gpuTransformValues(view, w, h);
                        // Compare the actual legacy pixel transform with the new clip-space output.
                        for (float[] point : new float[][]{{0, 0}, {w, h},
                                {.12f * w, .23f * h}, {.82f * w, .73f * h},
                                {.47f * w, .48f * h}}) {
                            float[] result = project(gpu, 2f * point[0] / w - 1f,
                                    1f - 2f * point[1] / h);
                            assertEquals(view[0] * point[0] + view[1] * point[1] + view[2],
                                    (result[0] + 1f) * w / 2f, .002f);
                            assertEquals(view[3] * point[0] + view[4] * point[1] + view[5],
                                    (1f - result[1]) * h / 2f, .002f);
                        }
                        assertArrayEquals(new float[]{0, 0},
                                project(gpu, -.06f, .04f), .00001f);
                    }
                }
            }
        }
    }

    @Test public void stretchHomogeneousComponentAndIndependentOutputsArePreserved() {
        float[] first = CameraRotation.gpuTransformValues(
                new float[]{1, .2f, 20, .3f, 1, 10, .001f, .002f, 1}, 200, 100);
        float[] saved = first.clone();
        float[] other = CameraRotation.gpuTransformValues(
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, 486, 220);
        // Input pixel (100,50) => (130,90)/1.2 => clip (1/12,-1/2).
        assertArrayEquals(new float[]{1f / 12f, -.5f}, project(first, 0, 0), .00001f);
        assertArrayEquals(saved, first, 0f);
        assertArrayEquals(CameraGpuOutput.IDENTITY, other, 0f);
    }

    @Test public void invalidViewGeometryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CameraRotation.gpuTransformValues(new float[9], 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> CameraRotation.gpuTransformValues(new float[8], 100, 100));
        assertThrows(IllegalArgumentException.class, () -> CameraRotation.gpuTransformValues(
                new float[]{1, 0, Float.NaN, 0, 1, 0, 0, 0, 1}, 100, 100));
    }

    @Test public void reversePrewarmUsesLiveCropFitRotationAndSteeringGeometry() {
        ReverseCameraLayout base = ReverseCameraLayout.defaults();
        int camera = ReverseCameraLayout.REAR_CAMERA_INDEX;
        ReverseCameraLayout calibrated = ReverseCameraLayout.withPane(base, camera,
                base.pane(camera).destination,
                ReverseCameraLayout.sourceCrop(.1f, .1f, .4f, .85f), 0);
        calibrated = ReverseCameraLayout.withDisplayMode(
                calibrated, camera, ReverseCameraLayout.DISPLAY_MODE_FIT);
        ReverseCameraLayout rawFallback = ReverseCameraLayout.withPane(base, camera,
                base.pane(camera).destination,
                ReverseCameraLayout.sourceCrop(.2f, .1f, .7f, .85f));
        CameraDewarpConfig config = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_REAR, true, 100);

        ReverseCameraLayout.Rect configuredCrop =
                ReverseCameraCompositionView.effectiveSourceCrop(
                        calibrated, rawFallback, config, camera, false,
                        true, 20f, -30f, 30f);
        ReverseCameraLayout.Rect fallbackCrop =
                ReverseCameraCompositionView.effectiveSourceCrop(
                        calibrated, rawFallback, config, camera, true,
                        true, 20f, -30f, 30f);
        assertEquals(.7f, fallbackCrop.width, .00001f);
        assertEquals(.4f, configuredCrop.width, .00001f);
        assertTrue(configuredCrop.left != .1f);

        ReverseCameraLayout.PixelRect fit0 =
                ReverseCameraCompositionView.fittedTextureBounds(
                        configuredCrop, 1000, 500, 0, ReverseCameraLayout.DISPLAY_MODE_FIT);
        ReverseCameraLayout.PixelRect fit90 =
                ReverseCameraCompositionView.fittedTextureBounds(
                        configuredCrop, 1000, 500, 90, ReverseCameraLayout.DISPLAY_MODE_FIT);
        ReverseCameraLayout.PixelRect fill =
                ReverseCameraCompositionView.fittedTextureBounds(
                        configuredCrop, 1000, 500, 90, ReverseCameraLayout.DISPLAY_MODE_FILL);
        assertEquals(348, fit0.width); // 500 * (0.4 * 1920) / (0.85 * 1300), rounded.
        assertEquals(500, fit0.height);
        assertEquals(719, fit90.width);
        assertEquals(500, fit90.height);
        assertEquals(1000, fill.width);
        assertEquals(500, fill.height);

        ReverseCameraLayout.PixelRect fallbackFit =
                ReverseCameraCompositionView.fittedTextureBounds(
                        fallbackCrop, 1000, 500, 0, ReverseCameraLayout.DISPLAY_MODE_FIT);
        assertEquals(ReverseCameraLayout.fitSourceCrop(
                        fallbackCrop, 1000, 500, ReverseCameraCompositionView.SOURCE_WIDTH,
                        ReverseCameraCompositionView.SOURCE_HEIGHT, 0).width,
                fallbackFit.width);
        assertEquals(ReverseCameraLayout.fitSourceCrop(
                        fallbackCrop, 1000, 500, ReverseCameraCompositionView.SOURCE_WIDTH,
                        ReverseCameraCompositionView.SOURCE_HEIGHT, 0).height,
                fallbackFit.height);
    }

    private static float[] project(float[] m, float x, float y) {
        float w = m[3] * x + m[7] * y + m[15];
        return new float[]{(m[0] * x + m[4] * y + m[12]) / w,
                (m[1] * x + m[5] * y + m[13]) / w};
    }
}
