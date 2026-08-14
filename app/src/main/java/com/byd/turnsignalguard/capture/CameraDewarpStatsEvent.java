package com.byd.turnsignalguard.capture;

/** Builds one renderer metrics event from the immutable completed interval. */
final class CameraDewarpStatsEvent {
    private CameraDewarpStatsEvent() {}

    static boolean shouldRecord(
            boolean activityResumed, boolean requestedOpen,
            boolean activePreviewMatches, int requestId) {
        return activityResumed && requestedOpen && activePreviewMatches && requestId > 0;
    }

    static Object[] calibration(
            int requestId, int cameraId, CameraDewarpRenderer.Stats stats) {
        return fields(new Object[]{
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "target", "camera_calibration",
                "renderer", "direct_crop_calibration",
                "request_id", requestId,
                "camera_id", cameraId
        }, stats);
    }

    static Object[] reverse(
            int requestId, int reverseCameraIndex, CameraDewarpRenderer.Stats stats) {
        return fields(new Object[]{
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "target", "reverse",
                "renderer", "reverse_preview",
                "request_id", requestId,
                "reverse_camera_index", reverseCameraIndex
        }, stats);
    }

    static Object[] overlay(
            int cameraId, String cameraProfile, CameraDewarpRenderer.Stats stats) {
        return fields(new Object[]{
                "camera_owner", CameraHelperMain.CAMERA_OWNER_OVERLAY,
                "camera_id", cameraId,
                "camera_profile", cameraProfile,
                "request_id", stats.requestId,
                "surface_generation", stats.contextGeneration
        }, stats);
    }

    static Object[] shellReverse(
            int cameraIndex, CameraDewarpRenderer.Stats stats) {
        return fields(new Object[]{
                "camera_owner", CameraHelperMain.CAMERA_OWNER_REVERSE,
                "camera_index", cameraIndex,
                "request_id", stats.requestId,
                "surface_generation", stats.contextGeneration
        }, stats);
    }

    private static Object[] fields(Object[] identity, CameraDewarpRenderer.Stats stats) {
        if (stats == null) throw new IllegalArgumentException("dewarp stats required");
        CameraDewarpConfig config = stats.mapping;
        if (config == null) throw new IllegalArgumentException("dewarp mapping required");
        Object[] metrics = {
                "input_generation", stats.inputGeneration,
                "surface_generation", stats.contextGeneration,
                "mapping_request_token", stats.mappingRequestToken,
                "lens", config.lens,
                "correction_enabled", config.enabled,
                "projection", CameraDewarpConfig.projectionLabel(config.projection),
                "interval_ms", stats.intervalMs,
                "callbacks", stats.callbacks,
                "update_samples", stats.updateSamples,
                "callback_fps", stats.callbackFps,
                "completed_swaps", stats.completedSwaps,
                "completed_swap_fps", stats.completedSwapFps,
                "average_render_ms", stats.averageRenderMs,
                "max_render_ms", stats.maxRenderMs,
                "max_swap_ms", stats.maxSwapMs,
                "update_tex_image_avg_ms", stats.averageUpdateTexImageMs,
                "update_tex_image_max_ms", stats.maxUpdateTexImageMs,
                "pre_swap_avg_ms", stats.averagePreSwapMs,
                "pre_swap_max_ms", stats.maxPreSwapMs,
                "swap_wait_avg_ms", stats.averageSwapWaitMs,
                "swap_wait_max_ms", stats.maxSwapWaitMs,
                "raw_mirror_swaps", stats.rawMirrorSwaps,
                "corrected_mirror_swaps", stats.correctedMirrorSwaps,
                "mirror_pre_swap_avg_ms", stats.averageMirrorPreSwapMs,
                "mirror_pre_swap_max_ms", stats.maxMirrorPreSwapMs,
                "mirror_swap_wait_avg_ms", stats.averageMirrorSwapWaitMs,
                "mirror_swap_wait_max_ms", stats.maxMirrorSwapWaitMs,
                "max_callback_gap_ms", stats.maxCallbackGapMs,
                "last_frame_age_ms", stats.lastFrameAgeMs,
                "max_frame_age_ms", stats.maxFrameAgeMs,
                "process_max_concurrent_renders", stats.processMaxConcurrentRenders,
                "process_active_renderers", stats.processActiveRenderers,
                "renderer_id", stats.rendererId,
                "buffer_width", stats.bufferWidth,
                "buffer_height", stats.bufferHeight,
                "view_width", stats.viewWidth,
                "view_height", stats.viewHeight
        };
        Object[] result = new Object[identity.length + metrics.length];
        System.arraycopy(identity, 0, result, 0, identity.length);
        System.arraycopy(metrics, 0, result, identity.length, metrics.length);
        return result;
    }
}
