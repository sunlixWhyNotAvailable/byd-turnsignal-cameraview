package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WifiRetentionShellTest {
    @Test public void onlyConfirmedCarOffAndDisabledWifiCanEnable() {
        assertTrue(WifiRetentionShell.shouldEnableWifi(true, true, true, true));
        assertFalse(WifiRetentionShell.shouldEnableWifi(false, true, true, true));
        assertFalse(WifiRetentionShell.shouldEnableWifi(true, false, true, true));
        assertFalse(WifiRetentionShell.shouldEnableWifi(true, true, false, true));
        assertFalse(WifiRetentionShell.shouldEnableWifi(true, true, true, false));
        assertFalse(WifiRetentionShell.shouldEnableWifi(true, true, true, null));
    }

    @Test public void vehiclePowerParserAcceptsOnlyStrictRawReply() {
        assertEquals(Boolean.TRUE, WifiRetentionShell.parseVehiclePoweredOff(
                "Result: Parcel(00000000 00000000 '')"));
        assertEquals(Boolean.TRUE, WifiRetentionShell.parseVehiclePoweredOff(
                "Result: Parcel(0x00000000: 00000000 00000000 '')"));
        assertEquals(Boolean.FALSE, WifiRetentionShell.parseVehiclePoweredOff(
                "Result: Parcel(00000000 00000001 '')"));
        assertNull(WifiRetentionShell.parseVehiclePoweredOff(
                "Result: Parcel(00000000 80000000 '')"));
        assertNull(WifiRetentionShell.parseVehiclePoweredOff("Service autoservice not found"));
        assertNull(WifiRetentionShell.parseVehiclePoweredOff(
                "Result: Parcel(00000000 00000000 '')\nextra"));
        assertNull(WifiRetentionShell.parseVehiclePoweredOff(null));
    }
}
