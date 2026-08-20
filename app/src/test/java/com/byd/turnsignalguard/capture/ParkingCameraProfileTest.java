package com.byd.turnsignalguard.capture;

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
        assertArrayEquals(new int[]{2, 4, 3, 3, 1, 2}, new int[]{
                ParkingCameraProfile.of(0).physicalCameraIndex,
                ParkingCameraProfile.of(1).physicalCameraIndex,
                ParkingCameraProfile.of(2).physicalCameraIndex,
                ParkingCameraProfile.of(3).physicalCameraIndex,
                ParkingCameraProfile.of(4).physicalCameraIndex,
                ParkingCameraProfile.of(5).physicalCameraIndex});
        assertArrayEquals(new int[]{
                ParkingCameraProfile.RADAR_FID_FL,
                ParkingCameraProfile.RADAR_FID_FRONT_LEFT,
                ParkingCameraProfile.RADAR_FID_FRONT_RIGHT,
                ParkingCameraProfile.RADAR_FID_FR,
                ParkingCameraProfile.RADAR_FID_RR,
                ParkingCameraProfile.RADAR_FID_REAR_LEFT,
                ParkingCameraProfile.RADAR_FID_REAR_RIGHT,
                ParkingCameraProfile.RADAR_FID_RL}, ParkingCameraProfile.allRadarFids());
    }

    @Test
    public void radarRawRangeIsInclusiveAndCentralOwnershipIsDirectional() {
        assertTrue(ParkingCameraProfile.isValidRadarRaw(0));
        assertTrue(ParkingCameraProfile.isValidRadarRaw(155));
        assertFalse(ParkingCameraProfile.isValidRadarRaw(-1));
        assertFalse(ParkingCameraProfile.isValidRadarRaw(156));
        assertEquals(ParkingCameraProfile.FRONT,
                ParkingCameraProfile.of(ParkingCameraProfile.FL).additiveCentralId());
        assertEquals(ParkingCameraProfile.REAR,
                ParkingCameraProfile.of(ParkingCameraProfile.RL).additiveCentralId());
    }
}
