package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class TurnSignalGuardRecoveryIntegrationTest {
    @Test
    public void transientSamplesUseRecoveryWithoutMutatingSubscriptionHealth() throws Exception {
        String source = runtimeSource();
        String handler = between(source,
                "private void handleListenerEvent", "private void listenerSampleFailed");
        String transientFailure = between(source,
                "private void listenerSampleFailed", "private void listenerFailed");
        String hardFailure = between(source,
                "private void listenerFailed", "private ReadResult read");

        assertTrue(handler.contains("if (!listenerHealthy) return;"));
        assertTrue(handler.contains("listenerSampleFailed("));
        assertTrue(transientFailure.contains("listenerSampleRecovery.invalidate(signal)"));
        assertTrue(transientFailure.contains("resetGesture()"));
        assertTrue(transientFailure.contains("cancelSpeedDeferredSession("));
        assertTrue(transientFailure.contains("suppress(\"telemetry_gap_or_invalid\")"));
        assertFalse(transientFailure.contains("listenerHealthy = false"));
        assertTrue(hardFailure.contains("listenerHealthy = false"));
        assertFalse(hardFailure.contains("listenerSampleRecovery.reset()"));
    }

    @Test
    public void recoveryUsesFreshPollAndOnlySubscriptionLifecycleResetsIt() throws Exception {
        String source = runtimeSource();
        String poll = between(source, "private void pollOnce", "private void evaluateStartup");
        String stop = between(source, "private void stopOnHandler", "private void setManual");
        String register = between(source, "private void registerListener", "private void prepareControl");
        String configure = between(source, "private void configureOnHandler",
                "private void vehiclePowerStateChangedOnHandler");

        assertTrue(poll.contains("listenerSampleRecovery.validPoll(\n"
                + "                pollFresh(SystemClock.elapsedRealtime(), lastPollAt), stalk.raw)"));
        assertTrue(poll.contains("listenerSampleRecovery.acceptStalkObservation()"));
        assertTrue(stop.contains("listenerSampleRecovery.reset()"));
        assertTrue(stop.contains("resetGesture()"));
        assertTrue(register.contains("listenerSampleRecovery.reset()"));
        assertTrue(register.contains("resetGesture()"));
        assertFalse(configure.contains("listenerSampleRecovery.reset()"));
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
