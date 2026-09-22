package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class TurnSignalGuardRecoveryIntegrationTest {
    @Test public void runtimeUsesFixedCallbackControllerWithoutPerCycleSignalGets()
            throws Exception {
        String source = runtimeSource();
        String scheduler = between(source,
                "private void pollAndSchedule", "private void applyTelemetrySnapshot");
        String snapshot = between(source,
                "private void applyTelemetrySnapshot", "private void evaluateTelemetryDeadlines");

        assertTrue(source.contains("new TurnSignalTelemetryTransport(context)"));
        assertTrue(scheduler.contains("telemetryController.tick()"));
        assertFalse(scheduler.contains("read("));
        assertFalse(snapshot.contains("read(STALK)"));
        assertFalse(snapshot.contains("read(STEERING)"));
        assertFalse(snapshot.contains("read(BLINK)"));
        assertFalse(snapshot.contains("read(SPEED)"));
    }

    @Test public void reconciliationCannotInventGesturesOrCorrections() throws Exception {
        String source = runtimeSource();
        String snapshot = between(source,
                "private void applyTelemetrySnapshot", "private void evaluateTelemetryDeadlines");
        String conflict = between(source,
                "private void cancelForReconciliationConflict", "private void telemetryModeChanged");

        assertTrue(snapshot.contains("Source.RECONCILE && conflict"));
        assertTrue(snapshot.contains("boolean live = source == TurnSignalTelemetryController.Source.CALLBACK"));
        assertTrue(snapshot.contains("if (live && listenerHealthy"));
        assertTrue(snapshot.contains("liveMask & TurnSignalTelemetryController.LIVE_STALK"));
        assertTrue(snapshot.contains("liveMask & TurnSignalTelemetryController.LIVE_STEERING"));
        assertTrue(snapshot.contains("liveMask & TurnSignalTelemetryController.LIVE_BLINK"));
        assertTrue(conflict.contains("resetGesture()"));
        assertTrue(conflict.contains("suppress(\"reconciliation_conflict\")"));
    }

    @Test public void diagnosticSamplesAreGatedButVehicleStateRemainsFunctional()
            throws Exception {
        String source = runtimeSource();
        String snapshot = between(source,
                "private void applyTelemetrySnapshot", "private void evaluateTelemetryDeadlines");
        String camera = between(source, "private void emitCameraState", "private void runOnHandler");

        assertTrue(snapshot.contains("DiagnosticLogPolicy.extended()"));
        assertTrue(snapshot.contains("emit(\"telemetry_sample\""));
        assertTrue(camera.contains("emit(\"vehicle_state\""));
        assertFalse(camera.contains("DiagnosticLogPolicy"));
    }

    @Test public void wakeAndSubscriptionLossInvalidateHistoricalControlOwnership()
            throws Exception {
        String source = runtimeSource();
        String wake = between(source, "private void vehiclePowerStateChangedOnHandler",
                "private void armStartupCleanupIfNeeded");
        String mode = between(source, "private void telemetryModeChanged",
                "private void invalidateTelemetryOwnership");
        String invalidation = between(source, "private void invalidateTelemetryOwnership",
                "private void evaluateStartupAwakeSessionCleanup");

        int invalidate = wake.indexOf("invalidateTelemetryOwnership(\"wake_reseed\")");
        int reseed = wake.indexOf("telemetryController.reseed()");
        assertTrue(invalidate >= 0 && invalidate < reseed);
        assertTrue(mode.contains("invalidateTelemetryOwnership(\"listener_error\")"));
        assertTrue(invalidation.contains("resetGesture()"));
        assertTrue(invalidation.contains("cancelHazardCleanup(reason)"));
        assertTrue(invalidation.contains("cancelSpeedDeferredSession(reason)"));
        assertTrue(invalidation.contains("suppress(reason)"));
    }

    private static String runtimeSource() throws Exception {
        Path path = Paths.get("src/main/java/com/byd/extend/TurnSignalGuardRuntime.java");
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main/java/com/byd/extend/TurnSignalGuardRuntime.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }
}
