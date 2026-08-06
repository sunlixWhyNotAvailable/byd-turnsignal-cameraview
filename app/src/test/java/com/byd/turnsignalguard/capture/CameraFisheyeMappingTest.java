package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CameraFisheyeMappingTest {
    private static final double EPSILON = 0.000001;
    private static final float[] CAPTURED_LEFT = {
            0.04235294f, 0.2617985f, 0.66430944f, 0.5547673f
    };
    private static final float[] CAPTURED_RIGHT = {
            0.2958041f, 0.26005435f, 0.6641959f, 0.5541817f
    };

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
                    CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, fov,
                            CameraDewarpConfig.PROJECTION_RECTILINEAR),
                    1920, 1300);
            assertMesh(mesh, true);
            assertTrue(maxInterpolationError(mesh,
                    CameraDewarpConfig.PROJECTION_RECTILINEAR, fov) < 0.25);
        }

        CameraFisheyeMapping.Mesh wide = CameraFisheyeMapping.buildMesh(
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 170,
                        CameraDewarpConfig.PROJECTION_RECTILINEAR), 1920, 1300);
        assertMesh(wide, false);
        assertTrue(hasOutOfBoundsUv(wide));
        assertTrue(maxInterpolationError(wide,
                CameraDewarpConfig.PROJECTION_RECTILINEAR, 170) < 3.0);
    }

    @Test
    public void cylindricalMappingHasStableWideAngleGeometry() {
        double[] center = CameraFisheyeMapping.mapOutputToSource(
                CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                100, 1920, 1300, 0.5, 0.5);
        assertEquals(960.0, center[0], EPSILON);
        assertEquals(650.0, center[1], EPSILON);
        double[] leftEdge = CameraFisheyeMapping.mapOutputToSource(
                CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                100, 1920, 1300, 0.0, 0.5);
        assertEquals(526.971629, leftEdge[0], EPSILON);
        assertEquals(650.0, leftEdge[1], EPSILON);
        double[] rightEdge = CameraFisheyeMapping.mapOutputToSource(
                CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                100, 1920, 1300, 1.0, 0.5);
        assertEquals(1920.0, leftEdge[0] + rightEdge[0], EPSILON);

        for (int fov : new int[]{60, 100, 170}) {
            CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(
                    CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, fov,
                            CameraDewarpConfig.PROJECTION_CYLINDRICAL),
                    1920, 1300);
            assertMesh(mesh, true);
            assertTrue(maxInterpolationError(mesh,
                    CameraDewarpConfig.PROJECTION_CYLINDRICAL, fov) < 0.25);
        }
    }

    @Test
    public void capturedRawRoiCentersBecomeOutputCentersForBothProjections() {
        assertRoiCenter(CameraDewarpConfig.LENS_LEFT, CAPTURED_LEFT);
        assertRoiCenter(CameraDewarpConfig.LENS_RIGHT, CAPTURED_RIGHT);
    }

    @Test
    public void offCenterRoiMeshStaysFinite() {
        CameraDewarpConfig left = configFor(
                CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.PROJECTION_RECTILINEAR,
                120, CAPTURED_LEFT);
        CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(left, 1920, 1300);
        assertMesh(mesh, false);
        assertTrue(maxInterpolationError(mesh, left) < 1.0);
    }

    @Test
    public void extremeValidRoiCentersRoundTripAndMeshesStayFinite() {
        float[][] crops = {
                {0.0f, 0.0f, 0.098f, 0.098f},
                {0.902f, 0.0f, 0.098f, 0.098f},
                {0.0f, 0.902f, 0.098f, 0.098f},
                {0.902f, 0.902f, 0.098f, 0.098f}
        };
        for (float[] crop : crops) {
            assertRoiCenter(CameraDewarpConfig.LENS_LEFT, crop);
        }

        for (int projection : new int[]{
                CameraDewarpConfig.PROJECTION_RECTILINEAR,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL}) {
            for (float[] crop : crops) {
                CameraDewarpConfig extreme = configFor(
                        CameraDewarpConfig.LENS_LEFT, projection, 170, crop);
                assertMesh(CameraFisheyeMapping.buildMesh(extreme, 1920, 1300), false);
            }
        }
    }

    @Test
    public void raysPastInvertibleDomainStayOutsideTextureInExactAndMeshMapping() {
        float[] nearCorner = {0.0f, 0.0f, 0.098f, 0.098f};
        int[] projections = {
                CameraDewarpConfig.PROJECTION_RECTILINEAR,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL
        };
        double[][] outputs = {{0.451, 0.451}, {0.451, 0.549}};
        for (int index = 0; index < projections.length; index++) {
            CameraDewarpConfig config = configFor(
                    CameraDewarpConfig.LENS_LEFT, projections[index], 170, nearCorner);
            double outputX = outputs[index][0];
            double outputY = outputs[index][1];
            assertFalse(isSampleable(CameraFisheyeMapping.mapOutputToSource(
                    config, 1920, 1300, outputX, outputY)));

            CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(
                    config, 1920, 1300);
            assertFalse(isSampleable(interpolateTriangle(
                    mesh, outputX, outputY)));
        }
    }

    @Test
    public void denseScanFindsNoOutOfDomainRayInsideRenderedTriangles() {
        float[] nearCorner = {0.0f, 0.0f, 0.098f, 0.098f};
        for (int projection : new int[]{
                CameraDewarpConfig.PROJECTION_RECTILINEAR,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL}) {
            CameraDewarpConfig config = configFor(
                    CameraDewarpConfig.LENS_LEFT, projection, 170, nearCorner);
            CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(
                    config, 1920, 1300);
            Set<Long> renderedTriangles = renderedTriangles(mesh);
            int invalidRays = 0;
            for (int y = 0; y <= 1000; y++) {
                double outputY = y / 1000.0;
                for (int x = 0; x <= 1000; x++) {
                    double outputX = x / 1000.0;
                    double[] exact = CameraFisheyeMapping.mapOutputToSource(
                            config, 1920, 1300, outputX, outputY);
                    if (!isDomainSentinel(exact)) continue;
                    invalidRays++;
                    assertFalse(renderedTriangles.contains(
                            triangleKeyAt(outputX, outputY)));
                }
            }
            assertTrue(invalidRays > 0);
        }
    }

    @Test
    public void minimumOnePercentCornerCropsFailClosedOutsideCalibratedDomain() {
        float[][] crops = {
                {0.0f, 0.0f, 0.01f, 0.01f},
                {0.99f, 0.0f, 0.01f, 0.01f},
                {0.0f, 0.99f, 0.01f, 0.01f},
                {0.99f, 0.99f, 0.01f, 0.01f}
        };
        for (int projection : new int[]{
                CameraDewarpConfig.PROJECTION_RECTILINEAR,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL}) {
            for (float[] crop : crops) {
                CameraDewarpConfig outsideDomain = configFor(
                        CameraDewarpConfig.LENS_LEFT, projection, 170, crop);
                assertThrows(CameraFisheyeMapping.CalibratedDomainException.class,
                        () -> CameraFisheyeMapping.mapOutputToSource(
                                outsideDomain, 1920, 1300, 0.5, 0.5));
                assertThrows(CameraFisheyeMapping.CalibratedDomainException.class,
                        () -> CameraFisheyeMapping.buildMesh(
                                outsideDomain, 1920, 1300));
            }
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

    private static void assertRoiCenter(int lens, float[] crop) {
        double expectedX = (crop[0] + crop[2] / 2.0) * CameraFisheyeMapping.SOURCE_WIDTH;
        double expectedY = (crop[1] + crop[3] / 2.0) * CameraFisheyeMapping.SOURCE_HEIGHT;
        for (int projection : new int[]{
                CameraDewarpConfig.PROJECTION_RECTILINEAR,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL}) {
            CameraDewarpConfig config = configFor(lens, projection, 120, crop);
            double[] mapped = CameraFisheyeMapping.mapOutputToSource(
                    config, 1920, 1300, 0.5, 0.5);
            assertEquals(expectedX, mapped[0], 0.0001);
            assertEquals(expectedY, mapped[1], 0.0001);
        }
    }

    private static CameraDewarpConfig configFor(
            int lens, int projection, int fov, float[] crop) {
        return CameraDewarpConfig.of(lens, true, fov, projection).withRoiCenter(
                crop[0] + crop[2] / 2.0f,
                crop[1] + crop[3] / 2.0f);
    }

    private static void assertMesh(
            CameraFisheyeMapping.Mesh mesh, boolean requireSourceBounds) {
        assertEquals(CameraFisheyeMapping.MESH_COLUMNS
                * CameraFisheyeMapping.MESH_ROWS, mesh.vertexCount());
        int maximumIndexCount = (CameraFisheyeMapping.MESH_COLUMNS - 1)
                * (CameraFisheyeMapping.MESH_ROWS - 1) * 6;
        assertTrue(mesh.indices.length > 0);
        assertTrue(mesh.indices.length <= maximumIndexCount);
        assertEquals(0, mesh.indices.length % 3);
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int offset = vertex * 4;
            for (int field = 0; field < 4; field++) {
                assertTrue(Float.isFinite(mesh.vertices[offset + field]));
            }
            if (requireSourceBounds) {
                assertTrue(mesh.vertices[offset + 2] >= 0.0f
                        && mesh.vertices[offset + 2] <= 1.0f);
                assertTrue(mesh.vertices[offset + 3] >= 0.0f
                        && mesh.vertices[offset + 3] <= 1.0f);
            }
        }
        for (short index : mesh.indices) {
            int vertex = Short.toUnsignedInt(index);
            assertTrue(vertex < mesh.vertexCount());
            assertFalse(mesh.vertices[vertex * 4 + 2] == -1.0f
                    && mesh.vertices[vertex * 4 + 3] == 2.0f);
        }
    }

    private static boolean hasOutOfBoundsUv(CameraFisheyeMapping.Mesh mesh) {
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int offset = vertex * 4;
            if (mesh.vertices[offset + 2] < 0.0f || mesh.vertices[offset + 2] > 1.0f
                    || mesh.vertices[offset + 3] < 0.0f
                    || mesh.vertices[offset + 3] > 1.0f) {
                return true;
            }
        }
        return false;
    }

    private static double maxInterpolationError(
            CameraFisheyeMapping.Mesh mesh, int projection, int fov) {
        return maxInterpolationError(mesh, CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, fov, projection));
    }

    private static double maxInterpolationError(
            CameraFisheyeMapping.Mesh mesh, CameraDewarpConfig config) {
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
                                config, 1920, 1300, outputX, outputY);
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

    private static double[] interpolateTriangle(
            CameraFisheyeMapping.Mesh mesh, double outputX, double outputY) {
        double meshX = outputX * (CameraFisheyeMapping.MESH_COLUMNS - 1);
        double meshY = outputY * (CameraFisheyeMapping.MESH_ROWS - 1);
        int column = Math.min((int) meshX, CameraFisheyeMapping.MESH_COLUMNS - 2);
        int row = Math.min((int) meshY, CameraFisheyeMapping.MESH_ROWS - 2);
        double localX = meshX - column;
        double localY = meshY - row;
        int topLeft = row * CameraFisheyeMapping.MESH_COLUMNS + column;
        int topRight = topLeft + 1;
        int bottomLeft = topLeft + CameraFisheyeMapping.MESH_COLUMNS;
        int bottomRight = bottomLeft + 1;
        double u;
        double flippedV;
        if (localX + localY <= 1.0) {
            u = triangleField(mesh, topLeft, bottomLeft, topRight,
                    1.0 - localX - localY, localY, localX, 2);
            flippedV = triangleField(mesh, topLeft, bottomLeft, topRight,
                    1.0 - localX - localY, localY, localX, 3);
        } else {
            u = triangleField(mesh, topRight, bottomLeft, bottomRight,
                    1.0 - localY, 1.0 - localX, localX + localY - 1.0, 2);
            flippedV = triangleField(mesh, topRight, bottomLeft, bottomRight,
                    1.0 - localY, 1.0 - localX, localX + localY - 1.0, 3);
        }
        return new double[]{u * CameraFisheyeMapping.SOURCE_WIDTH,
                (1.0 - flippedV) * CameraFisheyeMapping.SOURCE_HEIGHT};
    }

    private static Set<Long> renderedTriangles(CameraFisheyeMapping.Mesh mesh) {
        Set<Long> result = new HashSet<>();
        for (int offset = 0; offset < mesh.indices.length; offset += 3) {
            result.add(triangleKey(
                    Short.toUnsignedInt(mesh.indices[offset]),
                    Short.toUnsignedInt(mesh.indices[offset + 1]),
                    Short.toUnsignedInt(mesh.indices[offset + 2])));
        }
        return result;
    }

    private static long triangleKeyAt(double outputX, double outputY) {
        double meshX = outputX * (CameraFisheyeMapping.MESH_COLUMNS - 1);
        double meshY = outputY * (CameraFisheyeMapping.MESH_ROWS - 1);
        int column = Math.min((int) meshX, CameraFisheyeMapping.MESH_COLUMNS - 2);
        int row = Math.min((int) meshY, CameraFisheyeMapping.MESH_ROWS - 2);
        double localX = meshX - column;
        double localY = meshY - row;
        int topLeft = row * CameraFisheyeMapping.MESH_COLUMNS + column;
        int topRight = topLeft + 1;
        int bottomLeft = topLeft + CameraFisheyeMapping.MESH_COLUMNS;
        int bottomRight = bottomLeft + 1;
        if (localX + localY <= 1.0) {
            return triangleKey(topLeft, bottomLeft, topRight);
        }
        return triangleKey(topRight, bottomLeft, bottomRight);
    }

    private static long triangleKey(int first, int second, int third) {
        return ((long) first << 32) | ((long) second << 16) | third;
    }

    private static double triangleField(
            CameraFisheyeMapping.Mesh mesh, int first, int second, int third,
            double firstWeight, double secondWeight, double thirdWeight, int field) {
        return mesh.vertices[first * 4 + field] * firstWeight
                + mesh.vertices[second * 4 + field] * secondWeight
                + mesh.vertices[third * 4 + field] * thirdWeight;
    }

    private static boolean isSampleable(double[] source) {
        return source[0] >= 0.0 && source[0] <= CameraFisheyeMapping.SOURCE_WIDTH
                && source[1] >= 0.0 && source[1] <= CameraFisheyeMapping.SOURCE_HEIGHT;
    }

    private static boolean isDomainSentinel(double[] source) {
        return source[0] == -CameraFisheyeMapping.SOURCE_WIDTH
                && source[1] == -CameraFisheyeMapping.SOURCE_HEIGHT;
    }

    private static double bilinear(
            double topLeft, double topRight, double bottomLeft, double bottomRight,
            double x, double y) {
        return (topLeft * (1.0 - x) + topRight * x) * (1.0 - y)
                + (bottomLeft * (1.0 - x) + bottomRight * x) * y;
    }
}
