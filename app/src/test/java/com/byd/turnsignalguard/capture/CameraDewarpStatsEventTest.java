package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraDewarpStatsEventTest {
    private static final CameraDewarpRenderer.Stats STATS =
            new CameraDewarpRenderer.Stats(
                    2_000_000_000L, 12, 10,
                    20_000_000L, 5_000_000L, 9_000_000L,
                    7_000_000L, 11_000_000L, 13_000_000L,
                    2, 1920, 990);

    @Test
    public void shouldRecordOnlyForActiveMatchingPreview() {
        assertFalse(CameraDewarpStatsEvent.shouldRecord(false, true, true, 4));
        assertFalse(CameraDewarpStatsEvent.shouldRecord(true, false, true, 4));
        assertFalse(CameraDewarpStatsEvent.shouldRecord(true, true, false, 4));
        assertFalse(CameraDewarpStatsEvent.shouldRecord(true, true, true, 0));
        assertTrue(CameraDewarpStatsEvent.shouldRecord(true, true, true, 4));
    }

    @Test
    public void calibrationUsesActivityIdentityAndAllRendererStats() {
        assertArrayEquals(new Object[]{
                "camera_owner", "activity",
                "target", "camera_calibration",
                "renderer", "direct_crop_calibration",
                "request_id", 7,
                "camera_id", CameraProfile.REAR_LEFT,
                "lens", CameraDewarpConfig.LENS_LEFT,
                "correction_enabled", true,
                "projection", "Cylindrical",
                "interval_ms", 2_000L,
                "callbacks", 12L,
                "completed_swaps", 10L,
                "average_render_ms", 2.0d,
                "max_render_ms", 5.0d,
                "max_swap_ms", 9.0d,
                "max_callback_gap_ms", 7.0d,
                "last_frame_age_ms", 11.0d,
                "max_frame_age_ms", 13.0d,
                "process_max_concurrent_renders", 2,
                "buffer_width", 1920,
                "buffer_height", 990
        }, CameraDewarpStatsEvent.calibration(
                7, CameraProfile.REAR_LEFT,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 110,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL), STATS));
    }

    @Test
    public void reverseUsesActivityIdentityAndReverseIndex() {
        Object[] fields = CameraDewarpStatsEvent.reverse(
                11, 3,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR), STATS);

        assertArrayEquals(new Object[]{
                "camera_owner", "activity",
                "target", "reverse",
                "renderer", "reverse_preview",
                "request_id", 11,
                "reverse_camera_index", 3,
                "lens", CameraDewarpConfig.LENS_REAR,
                "correction_enabled", false,
                "projection", "Rectilinear",
                "interval_ms", 2_000L,
                "callbacks", 12L,
                "completed_swaps", 10L,
                "average_render_ms", 2.0d,
                "max_render_ms", 5.0d,
                "max_swap_ms", 9.0d,
                "max_callback_gap_ms", 7.0d,
                "last_frame_age_ms", 11.0d,
                "max_frame_age_ms", 13.0d,
                "process_max_concurrent_renders", 2,
                "buffer_width", 1920,
                "buffer_height", 990
        }, fields);
    }
}
