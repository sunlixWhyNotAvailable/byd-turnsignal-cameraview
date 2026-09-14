package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;

import org.junit.Test;

public final class AdbTlsDiscoveryTest {
    @Test public void terminalStartFailureAllowsNextConsentTickToRetryDiscovery() {
        AdbTlsDiscovery.StartGate gate = new AdbTlsDiscovery.StartGate();
        assertTrue(gate.begin(false));
        assertFalse(gate.begin(false));

        gate.failed();

        assertTrue(gate.begin(false));
    }

    @Test public void closedDiscoveryNeverStarts() {
        AdbTlsDiscovery.StartGate gate = new AdbTlsDiscovery.StartGate();
        assertFalse(gate.begin(true));
    }

    @Test public void nativeStartAndCloseUseTheSameInstanceMonitor() throws Exception {
        assertTrue(Modifier.isSynchronized(AdbTlsDiscovery.class
                .getDeclaredMethod("start").getModifiers()));
        assertTrue(Modifier.isSynchronized(AdbTlsDiscovery.class
                .getDeclaredMethod("close").getModifiers()));
    }
}
