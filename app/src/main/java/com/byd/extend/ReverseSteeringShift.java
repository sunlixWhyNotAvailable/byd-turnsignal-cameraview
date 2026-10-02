package com.byd.extend;

/** Pure source-X crop travel for transient Reverse steering input. */
final class ReverseSteeringShift {
    private ReverseSteeringShift() {}

    static boolean hasValidBounds(float minimumDegrees, float maximumDegrees) {
        return Float.isFinite(minimumDegrees) && Float.isFinite(maximumDegrees)
                && minimumDegrees < 0.0f && maximumDegrees > 0.0f;
    }

    static boolean isValidAngle(
            float angleDegrees, float minimumDegrees, float maximumDegrees) {
        return hasValidBounds(minimumDegrees, maximumDegrees)
                && Float.isFinite(angleDegrees)
                && angleDegrees >= minimumDegrees && angleDegrees <= maximumDegrees;
    }

    static boolean usesCentralFrontSource(
            boolean frontMode, boolean centralFrontIntegrated, boolean centralFrontSourceEnabled) {
        return frontMode && centralFrontIntegrated && centralFrontSourceEnabled;
    }

    static ReverseCameraLayout.Rect apply(
            ReverseCameraLayout.Rect savedCrop,
            float angleDegrees, float minimumDegrees, float maximumDegrees) {
        if (savedCrop == null) throw new IllegalArgumentException("saved crop is required");
        if (!isValidAngle(angleDegrees, minimumDegrees, maximumDegrees)) return savedCrop;
        if (angleDegrees == 0.0f || savedCrop.width == 1.0f) return savedCrop;

        float q = angleDegrees < 0.0f
                ? angleDegrees / -minimumDegrees
                : angleDegrees / maximumDegrees;
        float left = savedCrop.left + q * (q < 0.0f
                ? savedCrop.left
                : 1.0f - savedCrop.width - savedCrop.left);
        return ReverseCameraLayout.sourceCrop(
                left, savedCrop.top, savedCrop.width, savedCrop.height);
    }
}
