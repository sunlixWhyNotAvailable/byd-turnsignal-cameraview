package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
}
