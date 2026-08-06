package com.byd.turnsignalguard.capture;

final class CameraFisheyeMapping {
    static final int SOURCE_WIDTH = 1920;
    static final int SOURCE_HEIGHT = 1300;
    static final int MESH_COLUMNS = 97;
    static final int MESH_ROWS = 65;

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
        int vertexCount = MESH_COLUMNS * MESH_ROWS;
        float[] vertices = new float[vertexCount * 4];
        int cursor = 0;
        for (int row = 0; row < MESH_ROWS; row++) {
            double outputY = row / (double) (MESH_ROWS - 1);
            for (int column = 0; column < MESH_COLUMNS; column++) {
                double outputX = column / (double) (MESH_COLUMNS - 1);
                double[] source = mapOutputToSource(
                        calibration, config.projection, config.fovDegrees,
                        outputWidth, outputHeight,
                        outputX, outputY);
                vertices[cursor++] = (float) (outputX * 2.0 - 1.0);
                vertices[cursor++] = (float) (1.0 - outputY * 2.0);
                vertices[cursor++] = (float) (source[0] / calibration.width);
                vertices[cursor++] = (float) (1.0 - source[1] / calibration.height);
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
                indices[cursor++] = (short) topLeft;
                indices[cursor++] = (short) bottomLeft;
                indices[cursor++] = (short) topRight;
                indices[cursor++] = (short) topRight;
                indices[cursor++] = (short) bottomLeft;
                indices[cursor++] = (short) bottomRight;
            }
        }
        return new Mesh(vertices, indices);
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
        return mapOutputToSource(
                calibration(lens), projection, fovDegrees,
                outputWidth, outputHeight, outputX, outputY);
    }

    private static double[] mapOutputToSource(
            Calibration calibration, int projection, int fovDegrees,
            int outputWidth, int outputHeight, double outputX, double outputY) {
        double fovRadians = Math.toRadians(fovDegrees);
        if (projection == CameraDewarpConfig.PROJECTION_CYLINDRICAL) {
            double yaw = (outputX - 0.5) * fovRadians;
            double cylinderY = (outputY - 0.5)
                    * outputHeight / outputWidth * fovRadians;
            return mapRayToSource(
                    calibration, Math.sin(yaw), cylinderY, Math.cos(yaw));
        }
        if (projection != CameraDewarpConfig.PROJECTION_RECTILINEAR) {
            throw new IllegalArgumentException("invalid camera projection");
        }
        double outputFocal = outputWidth / (2.0 * Math.tan(fovRadians / 2.0));
        double x = (outputX * outputWidth - outputWidth / 2.0) / outputFocal;
        double y = (outputY * outputHeight - outputHeight / 2.0) / outputFocal;
        return mapRayToSource(calibration, x, y, 1.0);
    }

    private static double[] mapRayToSource(
            Calibration calibration, double x, double y, double z) {
        double radius = Math.hypot(x, y);
        double scale = 1.0;
        if (radius > 1.0e-12) {
            double theta = Math.atan2(radius, z);
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
