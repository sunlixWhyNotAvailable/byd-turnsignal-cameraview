package com.byd.turnsignalguard.capture;

/** Builds the Activity record fields for one renderer metrics interval. */
final class CameraDewarpStatsEvent {
    private CameraDewarpStatsEvent() {}

    static boolean shouldRecord(
            boolean activityResumed, boolean requestedOpen,
            boolean activePreviewMatches, int requestId) {
        return activityResumed && requestedOpen && activePreviewMatches && requestId > 0;
    }

    static Object[] calibration(
            int requestId, int cameraId, CameraDewarpConfig config,
            CameraDewarpRenderer.Stats stats) {
        return fields("camera_calibration", "direct_crop_calibration", requestId,
                "camera_id", cameraId, config, stats);
    }

    static Object[] reverse(
            int requestId, int reverseCameraIndex, CameraDewarpConfig config,
            CameraDewarpRenderer.Stats stats) {
        return fields("reverse", "reverse_preview", requestId,
                "reverse_camera_index", reverseCameraIndex, config, stats);
    }

    private static Object[] fields(
            String target, String renderer, int requestId,
            String identityKey, int identityValue,
            CameraDewarpConfig config, CameraDewarpRenderer.Stats stats) {
        if (config == null) throw new IllegalArgumentException("dewarp config required");
        if (stats == null) throw new IllegalArgumentException("dewarp stats required");
        return new Object[]{
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "target", target,
                "renderer", renderer,
                "request_id", requestId,
                identityKey, identityValue,
                "lens", config.lens,
                "correction_enabled", config.enabled,
                "projection", CameraDewarpConfig.projectionLabel(config.projection),
                "interval_ms", stats.intervalMs,
                "callbacks", stats.callbacks,
                "completed_swaps", stats.completedSwaps,
                "average_render_ms", stats.averageRenderMs,
                "max_render_ms", stats.maxRenderMs,
                "max_swap_ms", stats.maxSwapMs,
                "max_callback_gap_ms", stats.maxCallbackGapMs,
                "last_frame_age_ms", stats.lastFrameAgeMs,
                "max_frame_age_ms", stats.maxFrameAgeMs,
                "process_max_concurrent_renders", stats.processMaxConcurrentRenders,
                "buffer_width", stats.bufferWidth,
                "buffer_height", stats.bufferHeight
        };
    }
}
