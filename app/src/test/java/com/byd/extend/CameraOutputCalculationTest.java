package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

public final class CameraOutputCalculationTest {
    @Test public void vehiclePowerOffSuppressesOrdinaryDemandButKeepsIndependentOwners() {
        int demand = CameraSourceDemand.bit(0) | CameraSourceDemand.bit(1)
                | CameraSourceDemand.bit(2) | CameraSourceDemand.bit(3)
                | CameraSourceDemand.bit(4);
        String[] ordinaryOwners = {
                CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                CameraHelperMain.CAMERA_OWNER_OVERLAY,
                CameraHelperMain.CAMERA_OWNER_REVERSE,
                CameraHelperMain.CAMERA_OWNER_PARKING,
                CameraHelperMain.CAMERA_OWNER_MIRROR
        };
        for (String owner : ordinaryOwners) {
            assertEquals(0, CameraHelperMain.HelperBinder.PersistentSession
                    .effectiveGroupSourceDemandMask(true, owner, demand));
            assertEquals(demand, CameraHelperMain.HelperBinder.PersistentSession
                    .effectiveGroupSourceDemandMask(false, owner, demand));
        }
        for (String owner : new String[]{
                "motion_recording", "motion_preview_front", "camera_viewing"}) {
            assertEquals(demand, CameraHelperMain.HelperBinder.PersistentSession
                    .effectiveGroupSourceDemandMask(true, owner, demand));
        }
    }

    @Test public void onlyOffOrdinaryDirectDetachDefersSharedProducerTeardown() {
        assertTrue(CameraHelperMain.HelperBinder.PersistentSession
                .shouldPreserveSharedProducerForOffDirectDetach(
                        true, CameraHelperMain.CAMERA_OWNER_ACTIVITY, true));
        assertFalse(CameraHelperMain.HelperBinder.PersistentSession
                .shouldPreserveSharedProducerForOffDirectDetach(
                        false, CameraHelperMain.CAMERA_OWNER_ACTIVITY, true));
        assertFalse(CameraHelperMain.HelperBinder.PersistentSession
                .shouldPreserveSharedProducerForOffDirectDetach(
                        true, CameraHelperMain.CAMERA_OWNER_ACTIVITY, false));
        assertFalse(CameraHelperMain.HelperBinder.PersistentSession
                .shouldPreserveSharedProducerForOffDirectDetach(
                        true, "motion_recording", true));
    }

    @Test public void idleRetirementWaitsForDemandAndTheCurrentReverseLease() throws Exception {
        CameraHelperMain.HelperBinder.PersistentSession session =
                new CameraHelperMain.HelperBinder.PersistentSession();
        assertTrue(session.isIdle());
        session.setBlindStandbyDemand(null, CameraSourceDemand.blindStandby(true, false));
        session.setMirrorDemand(null, CameraSourceDemand.mirror(true, true));
        session.setBlindStandbyDemand(null, 0);
        assertFalse(session.isIdle()); // Hiding Blind must not retire a visible Mirror.
        session.setMirrorDemand(null, 0);
        assertTrue(session.isIdle());

        CameraSourceDemand.ReverseWarmLease lease = session.reverseWarmLease;
        long previous = lease.arm(10, 2, CameraSourceDemand.bit(1));
        assertFalse(session.isIdle());
        long current = lease.arm(11, 2, CameraSourceDemand.bit(1));
        assertFalse(session.expireReverseDemand(null, previous, 10, 2));
        assertFalse(session.expireReverseDemand(null, current, 11, 1));
        assertFalse(session.isIdle());
        session.setBlindStandbyDemand(null, CameraSourceDemand.blindStandby(false, true));
        assertTrue(session.expireReverseDemand(null, current, 11, 2));
        assertFalse(session.isIdle()); // Reverse expiry must leave warm Blind sources alone.
        session.setBlindStandbyDemand(null, 0);
        assertTrue(session.isIdle());
    }

    @Test public void legacyTsRenderingIsLimitedToTheConfirmedUkedModel() {
        assertTrue(new StockAvmPreview.Config(1, 286, 1920, 1300,
                "ocean", "uked", "uked").usesLegacyTsRenderer());
        assertFalse(new StockAvmPreview.Config(1, 235, 1920, 1300,
                "ocean", "shh", "shh").usesLegacyTsRenderer());
        assertFalse(new StockAvmPreview.Config(1, 285, 1920, 1300,
                "ocean", "uked", "uked").usesLegacyTsRenderer());
        assertFalse(new StockAvmPreview.Config(1, 286, 1920, 1300,
                "ocean", "ukeb", "uked").usesLegacyTsRenderer());
        assertFalse(new StockAvmPreview.Config(1, 286, 1920, 1300,
                "ocean", "uked", "ukeb").usesLegacyTsRenderer());
    }

    @Test public void oemCanvasFitsWithoutCroppingOrStretchingInEitherOrientation() {
        for (boolean horizontal : new boolean[]{true, false}) {
            int[] canvas = BydAvmDisplayContract.canvas(2560, 1440, horizontal);
            assertEquals(horizontal ? 2560 : 1440, canvas[0]);
            assertEquals(horizontal ? 1440 : 2560, canvas[1]);
            for (int[] host : new int[][]{{1200, 500}, {400, 700}, {2560, 1440}}) {
                float[] fit = BydAvmDisplayContract.fitScale(canvas[0], canvas[1], host[0], host[1]);
                float visibleWidth = fit[0] * host[0], visibleHeight = fit[1] * host[1];
                assertTrue(visibleWidth <= host[0] + .001f);
                assertTrue(visibleHeight <= host[1] + .001f);
                assertEquals(visibleWidth / canvas[0], visibleHeight / canvas[1], .00001f);
                assertTrue(Math.abs(visibleWidth - host[0]) < .001f
                        || Math.abs(visibleHeight - host[1]) < .001f);
            }
        }
        assertArrayEquals(new int[]{1920, 1920}, BydAvmDisplayContract.canvas(1920, 1920, false));
    }

    @Test public void invalidOemGeometryIsRejectedInsteadOfAllocatingUnboundedBuffers() {
        assertThrows(IllegalArgumentException.class, () -> BydAvmDisplayContract.canvas(0, 1440, true));
        assertThrows(IllegalArgumentException.class, () -> BydAvmDisplayContract.canvas(2560, 8192, false));
        assertThrows(IllegalArgumentException.class, () -> BydAvmDisplayContract.fitScale(2560, 1440, 0, 100));
    }

    @Test public void outputStatisticsExcludeHiddenTimeAndStartAtFirstLoggedFrame() {
        DirectCameraSourceHub.StatsWindow stats = new DirectCameraSourceHub.StatsWindow();
        assertNull(outputFrame(stats, 1_000));
        assertNull(outputFrame(stats, 1_050));
        stats.restart(); // Target hidden, then shown much later.
        assertNull(outputFrame(stats, 195_000));
        DirectCameraSourceHub.Stats report = null;
        for (long ms = 195_050; ms <= 200_000; ms += 50) report = outputFrame(stats, ms);
        assertNotNull(report);
        assertEquals(5_000_000_000L, report.intervalNs);
        assertEquals(101, report.renderedFrames);
        assertEquals(50_000_000L, report.callbackGapMaxNs);
        assertEquals(101, report.swaps);

        stats.restart(); // Logging disabled; no callbacks until it is enabled again.
        assertNull(outputFrame(stats, 400_000));
        for (long ms = 400_050; ms <= 405_000; ms += 50) report = outputFrame(stats, ms);
        assertNotNull(report);
        assertEquals(5_000_000_000L, report.intervalNs);
        assertEquals(50_000_000L, report.callbackGapMaxNs);
        assertEquals(0, report.producerTimestampRepeated);
    }

    @Test public void fpsMeasurementWaitsForNewBuffersAndDoesNotCountTheReconnectGap() {
        long second = 1_000_000_000L;
        DirectCameraSourceHub.FpsMeasurementWindow window =
                new DirectCameraSourceHub.FpsMeasurementWindow(1, 15, second);
        assertFalse(window.start(2 * second, 0));
        assertFalse(window.start(second - 1, 42));
        assertEquals(0, window.startedNs);
        assertTrue(window.start(4 * second, 42));
        assertFalse(window.start(5 * second, 43));
        assertEquals(4 * second, window.startedNs);
        assertEquals(19 * second, window.untilNs);
        assertFalse(DirectCameraSourceHub.isFpsStatsWindowActive(
                3 * second, 1, 1, window.startedNs, window.untilNs));
        assertTrue(DirectCameraSourceHub.isFpsStatsWindowActive(
                18 * second, 1, 1, window.startedNs, window.untilNs));
        assertFalse(DirectCameraSourceHub.isFpsStatsWindowActive(
                19 * second, 1, 1, window.startedNs, window.untilNs));
        assertFalse(DirectCameraSourceHub.isFpsStatsWindowActive(
                18 * second, 1, 2, window.startedNs, window.untilNs));
    }

    @Test public void fpsCountsRejectRepeatedAndInvalidProducerTimestampsAcrossReports() {
        DirectCameraSourceHub.StatsWindow stats = new DirectCameraSourceHub.StatsWindow();
        assertNull(sourceFrame(stats, 1_000, 10));
        assertNull(sourceFrame(stats, 2_000, 10));
        assertNull(sourceFrame(stats, 3_000, 0));
        DirectCameraSourceHub.Stats first = sourceFrame(stats, 6_000, 11);
        assertEquals(4, first.callbacks);
        assertEquals(1, first.producerTimestampRepeated);
        assertEquals(1, first.producerTimestampInvalid);
        assertNull(sourceFrame(stats, 7_000, 11));
        DirectCameraSourceHub.Stats second = sourceFrame(stats, 11_000, 12);
        assertEquals(2, second.callbacks);
        assertEquals(1, second.producerTimestampRepeated);
        assertEquals(0, second.producerTimestampInvalid);
        assertEquals(4_000_000_000L, second.callbackGapMaxNs);
    }

    private static DirectCameraSourceHub.Stats sourceFrame(
            DirectCameraSourceHub.StatsWindow stats, long ms, long timestamp) {
        return stats.record(ms * 1_000_000L, timestamp, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, null, 0, 1920, 1300);
    }

    private static DirectCameraSourceHub.Stats outputFrame(
            DirectCameraSourceHub.StatsWindow stats, long ms) {
        long ns = ms * 1_000_000L;
        return stats.record(ns, ns, 0, 1_000_000L, 1_000_000L,
                2_000_000L, 2_000_000L, 3_000_000L, 1, 3_000_000L,
                1, 527L * 357, 527, 357, new int[]{527}, new int[]{357}, 1, 1920, 1300);
    }

    @Test public void directOutputKeepsIndependentQualitySizedMappingsDuringLiveChanges() {
        DirectCameraSourceHub.MappingMesh left = new DirectCameraSourceHub.MappingMesh();
        DirectCameraSourceHub.MappingMesh right = new DirectCameraSourceHub.MappingMesh();
        CameraDewarpConfig original = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 100);
        left.prepare(new CameraDewarpRenderer.MappingRequest(original, 1), 527, 357);
        right.prepare(new CameraDewarpRenderer.MappingRequest(original, 2), 800, 357);
        float[] rightBefore = meshVertices(right);
        CameraDewarpConfig moved = original.withRoiCenter(0.6f, 0.45f);
        CameraDewarpRenderer.MappingRequest update = new CameraDewarpRenderer.MappingRequest(moved, 3);
        assertEquals(update, left.prepare(update, 527, 357));
        assertFalse(left.rawFallback);
        assertArrayEquals(CameraFisheyeMapping.buildMesh(moved, 527, 357).vertices,
                meshVertices(left), 0f);
        assertArrayEquals(rightBefore, meshVertices(right), 0f);
        java.nio.FloatBuffer retained = left.vertices;
        left.prepare(new CameraDewarpRenderer.MappingRequest(moved, 4), 527, 357);
        assertTrue(retained == left.vertices); // Acknowledgement-only change keeps the mesh.
        CameraDewarpConfig raw = CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
        left.prepare(new CameraDewarpRenderer.MappingRequest(raw, 5), 527, 357);
        assertArrayEquals(CameraFisheyeMapping.buildMesh(raw, 527, 357).vertices,
                meshVertices(left), 0f);
    }

    private static float[] meshVertices(DirectCameraSourceHub.MappingMesh mesh) {
        float[] result = new float[mesh.vertices.capacity()];
        java.nio.FloatBuffer copy = mesh.vertices.duplicate();
        copy.position(0);
        copy.get(result);
        return result;
    }
}
