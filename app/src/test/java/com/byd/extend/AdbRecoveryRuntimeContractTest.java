package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class AdbRecoveryRuntimeContractTest {
    @Test public void recoveryAttemptBeginsOnlyAfterFailedInitialProof() throws Exception {
        String source = source();
        int begin = source.indexOf("coordinator.begin(forceCycle, nowElapsed);");
        int initialProof = source.indexOf("if (freshClassicProof())", begin);
        int preparing = source.indexOf(
                "coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);", initialProof);
        assertTrue(begin >= 0 && initialProof > begin && preparing > initialProof);
        assertTrue(source.contains("\"outcome\", ready.readyOutcome()"));
    }

    @Test public void authenticatedOrdinaryEventsKeepSuccessfulOutcome() throws Exception {
        String source = source();
        int authenticated = source.indexOf("if (coordinator.snapshot().authenticated5555()");
        int begin = source.indexOf("coordinator.begin(forceCycle, nowElapsed);", authenticated);
        String earlyReturn = source.substring(authenticated, begin);
        assertTrue(earlyReturn.contains("reason != Reason.ADB_FAILURE"));
        assertTrue(earlyReturn.contains("reason != Reason.BOOT"));
        assertTrue(earlyReturn.contains("publish();"));
        assertTrue(earlyReturn.contains("return;"));
    }

    @Test public void wifiDetectionChecksAllAvailableNetworksNotOnlyDefault() throws Exception {
        String source = source();
        int start = source.indexOf("    private boolean wifiConnected()");
        int end = source.indexOf("    private synchronized void ensureNetworkCallback()", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("connectivity.getAllNetworks()"));
        assertTrue(method.contains("NetworkCapabilities.TRANSPORT_WIFI"));
        assertFalse(method.contains("getActiveNetwork()"));
        assertFalse(method.contains("NET_CAPABILITY_INTERNET"));
        assertFalse(method.contains("NET_CAPABILITY_VALIDATED"));
    }

    @Test public void tlsCandidateAndTransitionRecheckFeatureEnabled() throws Exception {
        String source = source();
        int start = source.indexOf("    private void tryTlsPort(int port)");
        int end = source.indexOf("    private boolean freshClassicProof()", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        String enabledRead = "settings.getBoolean(RECOVERY_ENABLED, true)";
        assertTrue(occurrences(method, enabledRead) >= 4);
        assertTrue(method.indexOf(enabledRead) < method.indexOf("LocalAdbTlsClient.connect"));
        assertTrue(method.indexOf(enabledRead, method.indexOf("LocalAdbTlsClient.connect"))
                < method.indexOf("tls.requestTcpip5555()"));
        assertTrue(method.contains("applyDisabled()"));
    }

    private static String source() throws Exception {
        Path path = Path.of("app/src/main/java/com/byd/extend/AdbRecoveryRuntime.java");
        if (!Files.exists(path)) path = Path.of("src/main/java/com/byd/extend/AdbRecoveryRuntime.java");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(value, index)) >= 0;
                index += value.length()) count++;
        return count;
    }
}
