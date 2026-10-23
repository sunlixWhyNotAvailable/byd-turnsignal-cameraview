package com.byd.extend;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class CameraChargeTelemetryFenceTest {
    @Test public void callbackArrivalInvalidatesOnlyAnOlderReadForThatSignal() {
        CameraChargeTelemetry.SignalReadFence soc = new CameraChargeTelemetry.SignalReadFence();
        CameraChargeTelemetry.SignalReadFence voltage = new CameraChargeTelemetry.SignalReadFence();
        long socRead = soc.beginRead();
        long voltageRead = voltage.beginRead();

        soc.callbackArrived(); // Arrival is recorded before its Handler callback can apply.

        assertFalse(soc.isCurrent(socRead));
        assertTrue(voltage.isCurrent(voltageRead));
    }

    @Test public void closedSubscriptionLifetimeRejectsLateCallbackAfterRestart() {
        CameraChargeTelemetry.CallbackLifetime lifetime = new CameraChargeTelemetry.CallbackLifetime();
        long closedSubscription = lifetime.open();
        assertTrue(lifetime.isCurrent(closedSubscription));

        lifetime.invalidate();
        long restartedSubscription = lifetime.open();

        assertFalse(lifetime.isCurrent(closedSubscription));
        assertTrue(lifetime.isCurrent(restartedSubscription));
    }

    @Test public void failureDiagnosticRetainsStackCauseAndSuppressedCleanupFailure() {
        IllegalStateException failure = new IllegalStateException("typed read failed",
                new IllegalArgumentException("OEM code 42"));
        failure.addSuppressed(new IOException("subscription close failed"));

        String diagnostic = CameraChargeTelemetry.fullDiagnostic(failure);

        assertTrue(diagnostic.contains("IllegalStateException: typed read failed"));
        assertTrue(diagnostic.contains("Caused by: java.lang.IllegalArgumentException: OEM code 42"));
        assertTrue(diagnostic.contains("Suppressed: java.io.IOException: subscription close failed"));
        assertTrue(diagnostic.contains("CameraChargeTelemetryFenceTest"));
    }
}
