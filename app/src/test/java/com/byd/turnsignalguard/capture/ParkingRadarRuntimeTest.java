package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ParkingRadarRuntimeTest {
    @Test
    public void reflectionContractAndListenerLifecycleAreFixed() {
        assertEquals("android.hardware.bydauto.radar.BYDAutoRadarDevice",
                ParkingRadarRuntime.DEVICE_CLASS);
        assertEquals("android.hardware.IBYDAutoListener", ParkingRadarRuntime.LISTENER_CLASS);
        assertEquals("android.hardware.bydauto.adas.BYDAutoADASDevice",
                ParkingRadarRuntime.ADAS_DEVICE_CLASS);
        assertTrue(ParkingRadarRuntime.shouldCollect(true, true));
        assertFalse(ParkingRadarRuntime.shouldCollect(true, false));
        assertFalse(ParkingRadarRuntime.shouldCollect(false, true));
        assertTrue(ParkingRadarRuntime.isValidRaw(0));
        assertTrue(ParkingRadarRuntime.isValidRaw(155));
        assertTrue(ParkingRadarRuntime.isValidRaw(
                ParkingCameraProfile.RADAR_FID_RIGHT_OUTER, 255));
        assertFalse(ParkingRadarRuntime.isValidRaw(
                ParkingCameraProfile.RADAR_FID_RIGHT_OUTER, 256));
    }

    @Test
    public void shellProtocolVersionAndConfigureTransactionAreStable() {
        assertEquals(7, TurnSignalShellProtocol.VERSION);
        assertEquals(TurnSignalShellProtocol.TX_CONFIGURE_MUSIC + 1,
                TurnSignalShellProtocol.TX_CONFIGURE_PARKING_RADAR);
    }

    @Test
    public void initialSnapshotCommitStopsWhenCallbackErrorArrivesDuringReads() {
        assertTrue(ParkingRadarRuntime.canCommitInitialSnapshot(
                1L, 0L, 1L, true, true));
        assertFalse(ParkingRadarRuntime.canCommitInitialSnapshot(
                1L, 1L, 1L, true, true));
        assertFalse(ParkingRadarRuntime.canCommitInitialSnapshot(
                1L, 0L, 2L, true, true));
    }
}
