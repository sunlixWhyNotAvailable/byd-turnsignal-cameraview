package com.byd.extend;

/** Shared editor/renderer units. Aspect is the correction buffer, never the destination frame. */
public final class CameraCorrectionGeometry {
    private CameraCorrectionGeometry() { }

    public static double diagonalFov(double horizontal, double aspect) {
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(horizontal) / 2)
                * Math.hypot(1, 1 / aspect)));
    }

    public static float horizontalFov(double diagonal, double aspect) {
        double value = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(diagonal) / 2)
                / Math.hypot(1, 1 / aspect)));
        return (float) Math.max(CameraDewarpConfig.MIN_FOV_DEGREES,
                Math.min(CameraDewarpConfig.MAX_FOV_DEGREES, value));
    }

    public static boolean strengthEditable(boolean enabled, int projection, double horizontal, double aspect) {
        return enabled && projection == CameraDewarpConfig.PROJECTION_RECTILINEAR
                && diagonalFov(horizontal, aspect) <= 160.0 + 0.00001;
    }

    static int effectiveStrength(CameraDewarpConfig config, double aspect) {
        return strengthEditable(config.enabled, config.projection, config.horizontalFovDegrees(), aspect)
                ? config.strengthPercent : 100;
    }
}
