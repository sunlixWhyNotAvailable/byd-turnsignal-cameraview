package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraDewarpStatsEventTest {
    private static final CameraDewarpRenderer.Stats STATS =
            new CameraDewarpRenderer.Stats(
                    2_000_000_000L, 12, 9, 10,
                    20_000_000L, 5_000_000L,
                    9_000_000L, 7_000_000L,
                    10_000_000L, 6_000_000L,
                    30_000_000L, 9_000_000L,
                    12_000_000L,
                    4, 6,
                    15_000_000L, 4_000_000L,
                    25_000_000L, 8_000_000L,
                    7_000_000L, 11_000_000L, 13_000_000L,
                    2, 3, 99, 7, 4, CameraProfile.REAR_LEFT,
                    1920, 990, 1280, 660,
                    new CameraDewarpRenderer.MappingRequest(
                            CameraDewarpConfig.of(
                                    CameraDewarpConfig.LENS_LEFT, true, 110,
                                    CameraDewarpConfig.PROJECTION_CYLINDRICAL), 44L));

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
                "input_generation", 4,
                "surface_generation", CameraProfile.REAR_LEFT,
                "mapping_request_token", 44L,
                "lens", CameraDewarpConfig.LENS_LEFT,
                "correction_enabled", true,
                "projection", "Cylindrical",
                "interval_ms", 2_000L,
                "callbacks", 12L,
                "update_samples", 9L,
                "callback_fps", 6.0d,
                "completed_swaps", 10L,
                "completed_swap_fps", 5.0d,
                "average_render_ms", 2.0d,
                "max_render_ms", 5.0d,
                "max_swap_ms", 12.0d,
                "update_tex_image_avg_ms", 1.0d,
                "update_tex_image_max_ms", 7.0d,
                "pre_swap_avg_ms", 1.0d,
                "pre_swap_max_ms", 6.0d,
                "swap_wait_avg_ms", 3.0d,
                "swap_wait_max_ms", 9.0d,
                "raw_mirror_swaps", 4L,
                "corrected_mirror_swaps", 6L,
                "mirror_pre_swap_avg_ms", 1.5d,
                "mirror_pre_swap_max_ms", 4.0d,
                "mirror_swap_wait_avg_ms", 2.5d,
                "mirror_swap_wait_max_ms", 8.0d,
                "max_callback_gap_ms", 7.0d,
                "last_frame_age_ms", 11.0d,
                "max_frame_age_ms", 13.0d,
                "process_max_concurrent_renders", 2,
                "process_active_renderers", 3,
                "renderer_id", 99,
                "buffer_width", 1920,
                "buffer_height", 990,
                "view_width", 1280,
                "view_height", 660
        }, CameraDewarpStatsEvent.calibration(
                7, CameraProfile.REAR_LEFT, STATS));
    }

    @Test
    public void reverseUsesActivityIdentityAndReverseIndex() {
        Object[] fields = CameraDewarpStatsEvent.reverse(
                11, 3, STATS);

        assertArrayEquals(new Object[]{
                "camera_owner", "activity",
                "target", "reverse",
                "renderer", "reverse_preview",
                "request_id", 11,
                "reverse_camera_index", 3,
                "input_generation", 4,
                "surface_generation", CameraProfile.REAR_LEFT,
                "mapping_request_token", 44L,
                "lens", CameraDewarpConfig.LENS_LEFT,
                "correction_enabled", true,
                "projection", "Cylindrical",
                "interval_ms", 2_000L,
                "callbacks", 12L,
                "update_samples", 9L,
                "callback_fps", 6.0d,
                "completed_swaps", 10L,
                "completed_swap_fps", 5.0d,
                "average_render_ms", 2.0d,
                "max_render_ms", 5.0d,
                "max_swap_ms", 12.0d,
                "update_tex_image_avg_ms", 1.0d,
                "update_tex_image_max_ms", 7.0d,
                "pre_swap_avg_ms", 1.0d,
                "pre_swap_max_ms", 6.0d,
                "swap_wait_avg_ms", 3.0d,
                "swap_wait_max_ms", 9.0d,
                "raw_mirror_swaps", 4L,
                "corrected_mirror_swaps", 6L,
                "mirror_pre_swap_avg_ms", 1.5d,
                "mirror_pre_swap_max_ms", 4.0d,
                "mirror_swap_wait_avg_ms", 2.5d,
                "mirror_swap_wait_max_ms", 8.0d,
                "max_callback_gap_ms", 7.0d,
                "last_frame_age_ms", 11.0d,
                "max_frame_age_ms", 13.0d,
                "process_max_concurrent_renders", 2,
                "process_active_renderers", 3,
                "renderer_id", 99,
                "buffer_width", 1920,
                "buffer_height", 990,
                "view_width", 1280,
                "view_height", 660
        }, fields);
    }

    @Test
    public void shellSerializersKeepImmutableRequestAndGeneration() {
        Object[] overlay = CameraDewarpStatsEvent.overlay(
                CameraProfile.REAR_LEFT, "rear_left", STATS);
        Object[] reverse = CameraDewarpStatsEvent.shellReverse(2, STATS);

        assertField(overlay, "request_id", 7);
        assertField(overlay, "surface_generation", CameraProfile.REAR_LEFT);
        assertField(overlay, "mapping_request_token", 44L);
        assertField(reverse, "request_id", 7);
        assertField(reverse, "surface_generation", CameraProfile.REAR_LEFT);
        assertField(reverse, "camera_index", 2);
        assertMissingField(overlay, "visible");
        assertMissingField(reverse, "visible");
    }

    private static void assertField(Object[] fields, String name, Object expected) {
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (name.equals(fields[i])) {
                org.junit.Assert.assertEquals(expected, fields[i + 1]);
                return;
            }
        }
        org.junit.Assert.fail("Missing field: " + name);
    }

    private static void assertMissingField(Object[] fields, String name) {
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (name.equals(fields[i])) {
                org.junit.Assert.fail("Unexpected mutable field: " + name);
            }
        }
    }
}
