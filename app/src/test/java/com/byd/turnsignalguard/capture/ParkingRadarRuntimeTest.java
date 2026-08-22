package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
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

    @Test
    public void diagnosticFamiliesContainEveryKnownReadOnlyCandidate() {
        assertArrayEquals(new int[]{
                        0x99000061, 0x99000062, 0x99000063, 0x99000064,
                        0x99000068, 0x99000066, 0x99000067, 0x99000065},
                ParkingRadarDiagnosticRuntime.radarDistanceFids());
        assertArrayEquals(new int[]{
                        0x99000071, 0x99000072, 0x99000073, 0x99000074,
                        0x99000075, 0x99000076, 0x99000077, 0x99000078},
                ParkingRadarDiagnosticRuntime.probeStateFids());
        assertArrayEquals(new int[]{
                        0x36500008, 0x36500010, 0x36500018, 0x36500020,
                        0x36500028, 0x36500030, 0x36500038, 0x36500040},
                ParkingRadarDiagnosticRuntime.sdwDistanceFids());
        assertEquals(8, ParkingRadarDiagnosticRuntime.radarDistanceFids().length);
        assertEquals(8, ParkingRadarDiagnosticRuntime.probeStateFids().length);
        assertEquals(8, ParkingRadarDiagnosticRuntime.sdwDistanceFids().length);
    }
}
