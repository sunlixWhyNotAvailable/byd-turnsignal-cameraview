package com.byd.turnsignalguard.capture;

final class CameraFisheyeMapping {
    static final int SOURCE_WIDTH = 1920;
    static final int SOURCE_HEIGHT = 1300;
    static final int MESH_COLUMNS = 97;
    static final int MESH_ROWS = 65;
    // First positive root of 1 + 3*k1*t^2 + 5*k2*t^4 + 7*k3*t^6 + 9*k4*t^8
    // for the fixed stock calibration: the end of its invertible radial branch.
    private static final double MAX_INVERTIBLE_THETA = 2.00078250962346;
    private static final int INVERSE_ITERATIONS = 48;

    private static final Calibration[] STOCK_BY_LENS = {
            null, stockCalibration(), stockCalibration(), stockCalibration(), stockCalibration()
    };

    private CameraFisheyeMapping() {}

    static Mesh buildMesh(CameraDewarpConfig config, int outputWidth, int outputHeight) {
        if (config == null) throw new IllegalArgumentException("dewarp config is required");
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("output size must be positive");
        }
        if (!config.enabled) return identityMesh();

        Calibration calibration = calibration(config.lens);
        Basis basis = basisForSource(
                calibration, config.roiCenterX, config.roiCenterY);
        int vertexCount = MESH_COLUMNS * MESH_ROWS;
        float[] vertices = new float[vertexCount * 4];
        boolean[] validRays = new boolean[vertexCount];
        int cursor = 0;
        for (int row = 0; row < MESH_ROWS; row++) {
            double outputY = row / (double) (MESH_ROWS - 1);
            for (int column = 0; column < MESH_COLUMNS; column++) {
                double outputX = column / (double) (MESH_COLUMNS - 1);
                double[] source = mapOutputToSource(
                        calibration, config.projection, config.fovDegrees,
                        outputWidth, outputHeight, basis,
                        outputX, outputY);
                vertices[cursor++] = (float) (outputX * 2.0 - 1.0);
                vertices[cursor++] = (float) (1.0 - outputY * 2.0);
                vertices[cursor++] = (float) (source[0] / calibration.width);
                vertices[cursor++] = (float) (1.0 - source[1] / calibration.height);
                validRays[row * MESH_COLUMNS + column] = !isInvalidRay(
                        calibration, source);
            }
        }

        short[] indices = new short[(MESH_COLUMNS - 1) * (MESH_ROWS - 1) * 6];
        cursor = 0;
        for (int row = 0; row < MESH_ROWS - 1; row++) {
            for (int column = 0; column < MESH_COLUMNS - 1; column++) {
                int topLeft = row * MESH_COLUMNS + column;
                int topRight = topLeft + 1;
                int bottomLeft = topLeft + MESH_COLUMNS;
                int bottomRight = bottomLeft + 1;
                if (validRays[topLeft] && validRays[bottomLeft]
                        && validRays[topRight]) {
                    indices[cursor++] = (short) topLeft;
                    indices[cursor++] = (short) bottomLeft;
                    indices[cursor++] = (short) topRight;
                }
                if (validRays[topRight] && validRays[bottomLeft]
                        && validRays[bottomRight]) {
                    indices[cursor++] = (short) topRight;
                    indices[cursor++] = (short) bottomLeft;
                    indices[cursor++] = (short) bottomRight;
                }
            }
        }
        return new Mesh(vertices, java.util.Arrays.copyOf(indices, cursor));
    }

    static double[] mapOutputToSource(
            int lens, int fovDegrees, int outputWidth, int outputHeight,
            double outputX, double outputY) {
        return mapOutputToSource(lens, CameraDewarpConfig.PROJECTION_RECTILINEAR,
                fovDegrees, outputWidth, outputHeight, outputX, outputY);
    }

    static double[] mapOutputToSource(
            int lens, int projection, int fovDegrees, int outputWidth, int outputHeight,
            double outputX, double outputY) {
        Calibration calibration = calibration(lens);
        return mapOutputToSource(
                calibration, projection, fovDegrees, outputWidth, outputHeight,
                basisForSource(calibration, 0.5, 0.5), outputX, outputY);
    }

    static double[] mapOutputToSource(
            CameraDewarpConfig config, int outputWidth, int outputHeight,
            double outputX, double outputY) {
        if (config == null) throw new IllegalArgumentException("dewarp config is required");
        Calibration calibration = calibration(config.lens);
        return mapOutputToSource(
                calibration, config.projection, config.fovDegrees,
                outputWidth, outputHeight,
                basisForSource(calibration, config.roiCenterX, config.roiCenterY),
                outputX, outputY);
    }

    private static double[] mapOutputToSource(
            Calibration calibration, int projection, int fovDegrees,
            int outputWidth, int outputHeight, Basis basis,
            double outputX, double outputY) {
        double fovRadians = Math.toRadians(fovDegrees);
        if (projection == CameraDewarpConfig.PROJECTION_CYLINDRICAL) {
            double yaw = (outputX - 0.5) * fovRadians;
            double cylinderY = (outputY - 0.5)
                    * outputHeight / outputWidth * fovRadians;
            return mapLocalRayToSource(
                    calibration, basis, Math.sin(yaw), cylinderY, Math.cos(yaw));
        }
        if (projection != CameraDewarpConfig.PROJECTION_RECTILINEAR) {
            throw new IllegalArgumentException("invalid camera projection");
        }
        double outputFocal = outputWidth / (2.0 * Math.tan(fovRadians / 2.0));
        double x = (outputX * outputWidth - outputWidth / 2.0) / outputFocal;
        double y = (outputY * outputHeight - outputHeight / 2.0) / outputFocal;
        return mapLocalRayToSource(calibration, basis, x, y, 1.0);
    }

    private static double[] mapLocalRayToSource(
            Calibration calibration, Basis basis, double x, double y, double z) {
        return mapRayToSource(calibration,
                basis.rightX * x + basis.downX * y + basis.forwardX * z,
                basis.rightY * x + basis.downY * y + basis.forwardY * z,
                basis.rightZ * x + basis.downZ * y + basis.forwardZ * z);
    }

    private static double[] mapRayToSource(
            Calibration calibration, double x, double y, double z) {
        double radius = Math.hypot(x, y);
        double scale = 1.0;
        if (radius > 1.0e-12) {
            double theta = Math.atan2(radius, z);
            if (theta > MAX_INVERTIBLE_THETA) {
                return new double[]{-calibration.width, -calibration.height};
            }
            double theta2 = theta * theta;
            double theta4 = theta2 * theta2;
            double theta6 = theta4 * theta2;
            double theta8 = theta4 * theta4;
            double thetaDistorted = theta * (1.0
                    + calibration.k1 * theta2
                    + calibration.k2 * theta4
                    + calibration.k3 * theta6
                    + calibration.k4 * theta8);
            scale = thetaDistorted / radius;
        }
        return new double[]{
                calibration.fx * x * scale + calibration.cx,
                calibration.fy * y * scale + calibration.cy
        };
    }

    private static boolean isInvalidRay(Calibration calibration, double[] source) {
        return source[0] == -calibration.width && source[1] == -calibration.height;
    }

    private static Basis basisForSource(
            Calibration calibration, double normalizedX, double normalizedY) {
        double dx = (normalizedX * calibration.width - calibration.cx) / calibration.fx;
        double dy = (normalizedY * calibration.height - calibration.cy) / calibration.fy;
        double thetaDistorted = Math.hypot(dx, dy);
        if (thetaDistorted <= 1.0e-12) return Basis.identity();

        double maxInvertibleRadius = distortedTheta(
                calibration, MAX_INVERTIBLE_THETA);
        if (thetaDistorted > maxInvertibleRadius) {
            throw new CalibratedDomainException(
                    "dewarp ROI center is outside calibrated fisheye domain");
        }
        double low = 0.0;
        double high = MAX_INVERTIBLE_THETA;
        for (int i = 0; i < INVERSE_ITERATIONS; i++) {
            double middle = (low + high) / 2.0;
            if (distortedTheta(calibration, middle) < thetaDistorted) low = middle;
            else high = middle;
        }
        double theta = (low + high) / 2.0;
        double radialScale = Math.sin(theta) / thetaDistorted;
        double forwardX = dx * radialScale;
        double forwardY = dy * radialScale;
        double forwardZ = Math.cos(theta);
        double rightLength = Math.hypot(forwardX, forwardZ);
        double rightX;
        double rightZ;
        if (rightLength <= 1.0e-12) {
            rightX = 1.0;
            rightZ = 0.0;
        } else {
            rightX = forwardZ / rightLength;
            rightZ = -forwardX / rightLength;
        }
        return new Basis(
                rightX, 0.0, rightZ,
                forwardY * rightZ,
                forwardZ * rightX - forwardX * rightZ,
                -forwardY * rightX,
                forwardX, forwardY, forwardZ);
    }

    private static double distortedTheta(Calibration calibration, double theta) {
        double theta2 = theta * theta;
        double theta4 = theta2 * theta2;
        double theta6 = theta4 * theta2;
        double theta8 = theta4 * theta4;
        return theta * (1.0
                + calibration.k1 * theta2
                + calibration.k2 * theta4
                + calibration.k3 * theta6
                + calibration.k4 * theta8);
    }

    private static Calibration calibration(int lens) {
        if (!CameraDewarpConfig.isValidLens(lens)) {
            throw new IllegalArgumentException("invalid camera lens");
        }
        // Stock AutoVideo carries four named records. Their bundled defaults are identical.
        return STOCK_BY_LENS[lens];
    }

    private static Calibration stockCalibration() {
        return new Calibration(
                SOURCE_WIDTH, SOURCE_HEIGHT,
                433.0, 433.0, 960.0, 650.0,
                0.252969, -0.096487, 0.023289, -0.002927);
    }

    private static Mesh identityMesh() {
        return new Mesh(new float[]{
                -1.0f, -1.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,
                 1.0f,  1.0f, 1.0f, 1.0f
        }, new short[]{0, 1, 2, 2, 1, 3});
    }

    static final class Mesh {
        final float[] vertices;
        final short[] indices;

        Mesh(float[] vertices, short[] indices) {
            this.vertices = vertices;
            this.indices = indices;
        }

        int vertexCount() {
            return vertices.length / 4;
        }
    }

    static final class CalibratedDomainException extends IllegalArgumentException {
        CalibratedDomainException(String message) {
            super(message);
        }
    }

    private static final class Basis {
        final double rightX;
        final double rightY;
        final double rightZ;
        final double downX;
        final double downY;
        final double downZ;
        final double forwardX;
        final double forwardY;
        final double forwardZ;

        Basis(
                double rightX, double rightY, double rightZ,
                double downX, double downY, double downZ,
                double forwardX, double forwardY, double forwardZ) {
            this.rightX = rightX;
            this.rightY = rightY;
            this.rightZ = rightZ;
            this.downX = downX;
            this.downY = downY;
            this.downZ = downZ;
            this.forwardX = forwardX;
            this.forwardY = forwardY;
            this.forwardZ = forwardZ;
        }

        static Basis identity() {
            return new Basis(1.0, 0.0, 0.0,
                    0.0, 1.0, 0.0,
                    0.0, 0.0, 1.0);
        }
    }

    private static final class Calibration {
        final int width;
        final int height;
        final double fx;
        final double fy;
        final double cx;
        final double cy;
        final double k1;
        final double k2;
        final double k3;
        final double k4;

        Calibration(
                int width, int height, double fx, double fy, double cx, double cy,
                double k1, double k2, double k3, double k4) {
            this.width = width;
            this.height = height;
            this.fx = fx;
            this.fy = fy;
            this.cx = cx;
            this.cy = cy;
            this.k1 = k1;
            this.k2 = k2;
            this.k3 = k3;
            this.k4 = k4;
        }
    }
}
