package com.byd.extend;

/** Geometry shared by the radial correction model and its mode availability policy. */
public final class CameraCorrectionGeometry {
    private CameraCorrectionGeometry() { }

    public static double diagonalFov(double horizontal, double aspect) {
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(horizontal) / 2)
                * Math.hypot(1, 1 / aspect)));
    }

    public static boolean strengthEditable(boolean enabled, int projection) {
        return enabled && projection == CameraDewarpConfig.PROJECTION_RECTILINEAR;
    }

    static int effectiveStrength(CameraDewarpConfig config) {
        return strengthEditable(config.enabled, config.projection)
                ? config.strengthPercent : 100;
    }
}
