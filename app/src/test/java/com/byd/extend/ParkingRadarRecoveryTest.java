package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class ParkingRadarRecoveryTest {
    private final int fid = ParkingCameraProfile.coreRadarFids()[0];

    @Test public void onlyObservedDataEnablesAndResetsThreeBoundedAttempts() {
        ParkingRadarRecovery recovery = new ParkingRadarRecovery();
        recovery.start(); recovery.registering(1);
        assertEquals(-1, recovery.failed(1, false, 0));
        recovery.data(1, fid, 150); // Valid OEM "no obstacle" is also evidence.
        assertEquals(1000, recovery.failed(1, false, 0));
        assertFalse(recovery.take(1, 999)); assertTrue(recovery.take(1, 1000));
        recovery.registering(2);
        recovery.data(1, fid, 50); // Stale callback cannot reset the budget.
        recovery.data(2, fid, 255); // Invalid data cannot reset it either.
        assertEquals(2000, recovery.failed(2, false, 1100));
        assertTrue(recovery.take(2, 3100)); recovery.registering(3);
        assertEquals(5000, recovery.failed(3, false, 3200));
        assertTrue(recovery.take(3, 8200)); recovery.registering(4);
        assertEquals(-1, recovery.failed(4, false, 8300));
        assertTrue(recovery.data(4, fid, 40));
        assertEquals(1000, recovery.failed(4, false, 8400));
    }

    @Test public void StopAndPermanentFailureDoNotRestartOrAffectAnotherFamily() {
        ParkingRadarRecovery core = new ParkingRadarRecovery(), side = new ParkingRadarRecovery();
        core.start(); side.start(); core.registering(1); side.registering(1);
        core.data(1, fid, 30); side.data(1, ParkingCameraProfile.sideRadarFids()[0], 251);
        assertEquals(-1, core.failed(1, true, 0));
        assertEquals(1000, side.failed(1, false, 0));
        side.stop(); assertFalse(side.take(1, 1000));
        side.start(); side.registering(2);
        assertEquals(-1, side.failed(2, false, 2000));
    }
}
