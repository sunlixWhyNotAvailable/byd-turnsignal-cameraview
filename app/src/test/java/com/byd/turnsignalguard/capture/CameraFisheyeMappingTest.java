package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CameraFisheyeMappingTest {
    private static final double EPSILON = 0.000001;

    @Test
    public void stockMappingMatchesGoldenPointsAndSymmetry() {
        double[] center = map(0.5, 0.5);
        assertEquals(960.0, center[0], EPSILON);
        assertEquals(650.0, center[1], EPSILON);
        double[] topLeft = map(0.0, 0.0);
        assertEquals(556.425604, topLeft[0], EPSILON);
        assertEquals(376.746503, topLeft[1], EPSILON);
        double[] bottomRight = map(1.0, 1.0);
        assertEquals(1920.0, topLeft[0] + bottomRight[0], EPSILON);
        assertEquals(1300.0, topLeft[1] + bottomRight[1], EPSILON);
        for (int lens = CameraDewarpConfig.LENS_LEFT;
                lens <= CameraDewarpConfig.LENS_FRONT; lens++) {
            double[] lensCenter = CameraFisheyeMapping.mapOutputToSource(
                    lens, 100, 1920, 1300, 0.5, 0.5);
            assertEquals(center[0], lensCenter[0], EPSILON);
            assertEquals(center[1], lensCenter[1], EPSILON);
        }
    }

    @Test
    public void meshIsFiniteIndexedAndTracksExactMapping() {
        for (int fov : new int[]{60, 100, 140}) {
            CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(
                    CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, fov),
                    1920, 1300);
            assertEquals(CameraFisheyeMapping.MESH_COLUMNS
                    * CameraFisheyeMapping.MESH_ROWS, mesh.vertexCount());
            assertEquals((CameraFisheyeMapping.MESH_COLUMNS - 1)
                    * (CameraFisheyeMapping.MESH_ROWS - 1) * 6, mesh.indices.length);
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                int offset = vertex * 4;
                for (int field = 0; field < 4; field++) {
                    assertTrue(Float.isFinite(mesh.vertices[offset + field]));
                }
                assertTrue(mesh.vertices[offset + 2] >= 0.0f
                        && mesh.vertices[offset + 2] <= 1.0f);
                assertTrue(mesh.vertices[offset + 3] >= 0.0f
                        && mesh.vertices[offset + 3] <= 1.0f);
            }
            for (short index : mesh.indices) {
                assertTrue(Short.toUnsignedInt(index) < mesh.vertexCount());
            }
            assertTrue(maxInterpolationError(mesh, fov) < 0.25);
        }
    }

    @Test
    public void disabledMappingIsIdentityWithExpectedOrientation() {
        CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT), 1920, 1300);
        assertEquals(4, mesh.vertexCount());
        assertEquals(-1.0f, mesh.vertices[0], 0.0f);
        assertEquals(-1.0f, mesh.vertices[1], 0.0f);
        assertEquals(0.0f, mesh.vertices[2], 0.0f);
        assertEquals(0.0f, mesh.vertices[3], 0.0f);
        assertEquals(1.0f, mesh.vertices[12], 0.0f);
        assertEquals(1.0f, mesh.vertices[13], 0.0f);
        assertEquals(1.0f, mesh.vertices[14], 0.0f);
        assertEquals(1.0f, mesh.vertices[15], 0.0f);
    }

    private static double[] map(double x, double y) {
        return CameraFisheyeMapping.mapOutputToSource(
                CameraDewarpConfig.LENS_LEFT, 100, 1920, 1300, x, y);
    }

    private static double maxInterpolationError(CameraFisheyeMapping.Mesh mesh, int fov) {
        double max = 0.0;
        double[] offsets = {0.17, 0.43, 0.79};
        for (int row = 0; row < CameraFisheyeMapping.MESH_ROWS - 1; row += 7) {
            for (int column = 0; column < CameraFisheyeMapping.MESH_COLUMNS - 1;
                    column += 11) {
                for (double localY : offsets) {
                    for (double localX : offsets) {
                        double outputX = (column + localX)
                                / (CameraFisheyeMapping.MESH_COLUMNS - 1.0);
                        double outputY = (row + localY)
                                / (CameraFisheyeMapping.MESH_ROWS - 1.0);
                        double[] exact = CameraFisheyeMapping.mapOutputToSource(
                                CameraDewarpConfig.LENS_LEFT, fov,
                                1920, 1300, outputX, outputY);
                        double[] interpolated = interpolate(mesh, column, row, localX, localY);
                        max = Math.max(max, Math.hypot(
                                exact[0] - interpolated[0], exact[1] - interpolated[1]));
                    }
                }
            }
        }
        return max;
    }

    private static double[] interpolate(
            CameraFisheyeMapping.Mesh mesh, int column, int row,
            double localX, double localY) {
        int topLeft = row * CameraFisheyeMapping.MESH_COLUMNS + column;
        int topRight = topLeft + 1;
        int bottomLeft = topLeft + CameraFisheyeMapping.MESH_COLUMNS;
        int bottomRight = bottomLeft + 1;
        double u = bilinear(mesh.vertices[topLeft * 4 + 2],
                mesh.vertices[topRight * 4 + 2],
                mesh.vertices[bottomLeft * 4 + 2],
                mesh.vertices[bottomRight * 4 + 2], localX, localY);
        double flippedV = bilinear(mesh.vertices[topLeft * 4 + 3],
                mesh.vertices[topRight * 4 + 3],
                mesh.vertices[bottomLeft * 4 + 3],
                mesh.vertices[bottomRight * 4 + 3], localX, localY);
        return new double[]{u * CameraFisheyeMapping.SOURCE_WIDTH,
                (1.0 - flippedV) * CameraFisheyeMapping.SOURCE_HEIGHT};
    }

    private static double bilinear(
            double topLeft, double topRight, double bottomLeft, double bottomRight,
            double x, double y) {
        return (topLeft * (1.0 - x) + topRight * x) * (1.0 - y)
                + (bottomLeft * (1.0 - x) + bottomRight * x) * y;
    }
}
