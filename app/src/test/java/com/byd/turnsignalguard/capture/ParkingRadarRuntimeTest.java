package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ParkingRadarRuntimeTest {
    @Test
    public void reflectionContractAndRefreshCadenceAreFixed() {
        assertEquals("android.hardware.bydauto.radar.BYDAutoRadarDevice",
                ParkingRadarRuntime.DEVICE_CLASS);
        assertEquals("android.hardware.IBYDAutoListener", ParkingRadarRuntime.LISTENER_CLASS);
        assertEquals(500L, ParkingRadarRuntime.REFRESH_PERIOD_MS);
        assertTrue(ParkingRadarRuntime.shouldRefresh(true, true));
        assertFalse(ParkingRadarRuntime.shouldRefresh(true, false));
        assertFalse(ParkingRadarRuntime.shouldRefresh(false, true));
        assertTrue(ParkingRadarRuntime.isValidRaw(0));
        assertTrue(ParkingRadarRuntime.isValidRaw(155));
    }

    @Test
    public void unhealthyConfiguredListenerIsCleanedBeforeRetry() {
        List<String> actions = new ArrayList<>();

        assertTrue(ParkingRadarRuntime.recoverRegistrationIfNeeded(
                true, true, false,
                () -> actions.add("cleanup"), () -> actions.add("register")));
        assertEquals(Arrays.asList("cleanup", "register"), actions);

        actions.clear();
        assertFalse(ParkingRadarRuntime.recoverRegistrationIfNeeded(
                true, true, true,
                () -> actions.add("cleanup"), () -> actions.add("register")));
        assertTrue(actions.isEmpty());
    }

    @Test
    public void shellProtocolVersionAndConfigureTransactionAreStable() {
        assertEquals(7, TurnSignalShellProtocol.VERSION);
        assertEquals(TurnSignalShellProtocol.TX_CONFIGURE_MUSIC + 1,
                TurnSignalShellProtocol.TX_CONFIGURE_PARKING_RADAR);
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
        assertArrayEquals(new int[]{
                        0x1EC00008, 0x1EC00010, 0x1EC00018, 0x1EC00020,
                        0x1EC00028, 0x1EC00030, 0x1EC00038, 0x1EC00040,
                        0x1EC00048, 0x1EC00050, 0x1EC00058, 0x1EC00060,
                        0x1EC00068, 0x1EC00070, 0x1EC00078, 0x1EC00080},
                ParkingRadarDiagnosticRuntime.sectionDistanceFids());
    }
}
