package com.byd.extend;

import org.json.JSONObject;

/** Event policy for camera destinations; callers own the actual camera lifecycle. */
final class ClusterDisplayLifecycle {
    static final int EVENT_ADDED = 1;
    static final int EVENT_REMOVED = 2;
    static final int EVENT_CHANGED = 3;

    static final int IGNORE = 0;
    static final int WAKE = 1;
    static final int INVALIDATE = 2;
    static final int NOTE_CHANGE = 3;

    static final String UNAVAILABLE_STAGE = "cluster_destination_unavailable";

    private ClusterDisplayLifecycle() {}

    static int action(int configuredTarget, int activeDisplayId, int selectedDisplayId,
            int eventDisplayId, int event, boolean eligible) {
        if (!CameraDisplayTarget.isValid(configuredTarget)) return IGNORE;
        if (event == EVENT_REMOVED) {
            return activeDisplayId == eventDisplayId ? INVALIDATE : IGNORE;
        }
        if (event == EVENT_CHANGED && activeDisplayId == eventDisplayId) {
            if (configuredTarget == CameraDisplayTarget.TABLET) return INVALIDATE;
            return eligible ? NOTE_CHANGE : IGNORE;
        }
        if (!eligible) return IGNORE;
        if (activeDisplayId < 0 && selectedDisplayId == eventDisplayId
                && (event == EVENT_ADDED || event == EVENT_CHANGED)) {
            return WAKE;
        }
        return IGNORE;
    }

    static boolean isUnavailableError(JSONObject event) {
        return event != null
                && "camera_overlay_error".equals(event.optString("kind"))
                && UNAVAILABLE_STAGE.equals(event.optString("stage"));
    }

    static boolean matchesUnavailableError(JSONObject event, int cameraId,
            int requestId, int generation) {
        return isUnavailableError(event) && requestId > 0
                && event.optInt("camera_id", -1) == cameraId
                && event.optInt("request_id", -1) == requestId
                && event.optInt("surface_generation", -1) == generation;
    }
}
