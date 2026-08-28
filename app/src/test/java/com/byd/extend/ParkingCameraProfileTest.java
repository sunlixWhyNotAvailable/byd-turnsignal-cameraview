package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ParkingCameraProfileTest {
    @Test
    public void contractOrderAndCameraMappingsAreFixed() {
        assertEquals("FL", ParkingCameraProfile.of(0).wireName);
        assertEquals("Front", ParkingCameraProfile.of(1).wireName);
        assertEquals("FR", ParkingCameraProfile.of(2).wireName);
        assertEquals("RR", ParkingCameraProfile.of(3).wireName);
        assertEquals("Rear", ParkingCameraProfile.of(4).wireName);
        assertEquals("RL", ParkingCameraProfile.of(5).wireName);
        assertEquals("Left", ParkingCameraProfile.of(6).wireName);
        assertEquals("Right", ParkingCameraProfile.of(7).wireName);
        assertEquals(8, ParkingCameraProfile.COUNT);
        assertArrayEquals(new int[]{2, 4, 3, 3, 1, 2, 2, 3}, new int[]{
                ParkingCameraProfile.of(0).physicalCameraIndex,
                ParkingCameraProfile.of(1).physicalCameraIndex,
                ParkingCameraProfile.of(2).physicalCameraIndex,
                ParkingCameraProfile.of(3).physicalCameraIndex,
                ParkingCameraProfile.of(4).physicalCameraIndex,
                ParkingCameraProfile.of(5).physicalCameraIndex,
                ParkingCameraProfile.of(6).physicalCameraIndex,
                ParkingCameraProfile.of(7).physicalCameraIndex});
        assertEquals("left", ParkingCameraProfile.of(ParkingCameraProfile.LEFT).lens);
        assertEquals("right", ParkingCameraProfile.of(ParkingCameraProfile.RIGHT).lens);
        assertArrayEquals(new int[]{
                ParkingCameraProfile.RADAR_FID_FL,
                ParkingCameraProfile.RADAR_FID_FRONT_LEFT,
                ParkingCameraProfile.RADAR_FID_FRONT_RIGHT,
                ParkingCameraProfile.RADAR_FID_FR,
                ParkingCameraProfile.RADAR_FID_RR,
                ParkingCameraProfile.RADAR_FID_REAR_LEFT,
                ParkingCameraProfile.RADAR_FID_REAR_RIGHT,
                ParkingCameraProfile.RADAR_FID_RL,
                ParkingCameraProfile.RADAR_FID_LEFT_FRONT,
                ParkingCameraProfile.RADAR_FID_LEFT_MIDDLE,
                ParkingCameraProfile.RADAR_FID_LEFT_REAR,
                ParkingCameraProfile.RADAR_FID_LEFT_OUTER,
                ParkingCameraProfile.RADAR_FID_RIGHT_FRONT,
                ParkingCameraProfile.RADAR_FID_RIGHT_MIDDLE,
                ParkingCameraProfile.RADAR_FID_RIGHT_REAR,
                ParkingCameraProfile.RADAR_FID_RIGHT_OUTER}, ParkingCameraProfile.allRadarFids());
        assertArrayEquals(new int[]{
                ParkingCameraProfile.RADAR_FID_LEFT_FRONT,
                ParkingCameraProfile.RADAR_FID_LEFT_MIDDLE,
                ParkingCameraProfile.RADAR_FID_LEFT_REAR,
                ParkingCameraProfile.RADAR_FID_LEFT_OUTER,
                ParkingCameraProfile.RADAR_FID_RIGHT_FRONT,
                ParkingCameraProfile.RADAR_FID_RIGHT_MIDDLE,
                ParkingCameraProfile.RADAR_FID_RIGHT_REAR,
                ParkingCameraProfile.RADAR_FID_RIGHT_OUTER},
                ParkingCameraProfile.sideRadarFids());
    }

    @Test
    public void radarRawRangeIsInclusiveAndCentralOwnershipIsDirectional() {
        assertTrue(ParkingCameraProfile.isValidRadarRaw(0));
        assertTrue(ParkingCameraProfile.isValidRadarRaw(155));
        assertFalse(ParkingCameraProfile.isValidRadarRaw(-1));
        assertFalse(ParkingCameraProfile.isValidRadarRaw(156));
        assertTrue(ParkingCameraProfile.isValidRadarRaw(
                ParkingCameraProfile.RADAR_FID_LEFT_FRONT, 255));
        assertFalse(ParkingCameraProfile.isValidRadarRaw(
                ParkingCameraProfile.RADAR_FID_LEFT_FRONT, 256));
        assertFalse(ParkingCameraProfile.isValidRadarRaw(
                ParkingCameraProfile.RADAR_FID_FL, 156));
        assertEquals(ParkingCameraProfile.FRONT,
                ParkingCameraProfile.of(ParkingCameraProfile.FL).additiveCentralId());
        assertEquals(ParkingCameraProfile.REAR,
                ParkingCameraProfile.of(ParkingCameraProfile.RL).additiveCentralId());
        assertFalse(ParkingCameraProfile.of(ParkingCameraProfile.LEFT).central());
        assertFalse(ParkingCameraProfile.of(ParkingCameraProfile.LEFT).corner());
        assertFalse(ParkingCameraProfile.of(ParkingCameraProfile.RIGHT).central());
        assertFalse(ParkingCameraProfile.of(ParkingCameraProfile.RIGHT).corner());
        assertEquals(-1, ParkingCameraProfile.of(ParkingCameraProfile.LEFT).additiveCentralId());
        assertEquals(-1, ParkingCameraProfile.of(ParkingCameraProfile.RIGHT).additiveCentralId());
    }
}
