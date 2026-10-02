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
        int initialProof = source.indexOf("AdbRecoveryPolicy.Proof proof = freshClassicProof();", begin);
        int preparing = source.indexOf(
                "coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);", initialProof);
        assertTrue(begin >= 0 && initialProof > begin && preparing > initialProof);
        String decisions = source.substring(initialProof, preparing);
        assertTrue(decisions.contains("proof == AdbRecoveryPolicy.Proof.AUTHORIZATION_REQUIRED"));
        assertTrue(decisions.contains("proof != AdbRecoveryPolicy.Proof.TRANSPORT_UNAVAILABLE"));
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
        int start = source.indexOf("    private void tryTlsPort(int port, long generation)");
        int end = source.indexOf("    private AdbRecoveryPolicy.Proof freshClassicProof()", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        String enabledRead = "settings.getBoolean(RECOVERY_ENABLED, true)";
        assertTrue(occurrences(method, enabledRead) >= 4);
        assertTrue(method.indexOf(enabledRead) < method.indexOf("LocalAdbTlsClient.connect"));
        assertTrue(method.indexOf(enabledRead, method.indexOf("LocalAdbTlsClient.connect"))
                < method.indexOf("tls.requestTcpip5555(operation)"));
        assertTrue(method.contains("applyDisabled()"));
    }

    @Test public void tlsTransportOutcomeNeverBypassesFreshClassicProof() throws Exception {
        String source = source();
        int start = source.indexOf("    private void tryTlsPort(int port, long generation)");
        int end = source.indexOf("    private AdbRecoveryPolicy.Proof freshClassicProof()", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        int request = method.indexOf("tls.requestTcpip5555(operation)");
        int verifying = method.indexOf("AdbRecoverySnapshot.Stage.VERIFYING_5555", request);
        int proof = method.indexOf("AdbRecoveryPolicy.Proof proof = freshClassicProof();", verifying);
        int ready = method.indexOf("finishClassicRecovery()", proof);
        assertTrue(request >= 0 && verifying > request && proof > verifying && ready > proof);
        assertTrue(method.contains("adb_tls_tcpip_result"));
        assertTrue(method.contains("\"category\", result.category"));
        assertTrue(method.contains("LocalAdbTlsClient.failureCategory(error)"));
        assertTrue(method.contains("LocalAdbTlsClient.failureReason(error)"));
        assertTrue(method.contains("adb_5555_transition_unverified"));
    }

    @Test public void bootstrapAndLateCallbacksUseSameAuthorizationGate() throws Exception {
        String source = source();
        for (String entry : new String[]{"private void recover(Reason reason)",
                "private void tryTlsPort(int port, long generation)"}) {
            int start = source.indexOf(entry);
            int gate = source.indexOf("deferForPrerequisite()", start);
            int admit = source.indexOf("LocalAdbClient.AUTHORIZATION.beginRecovery()", start);
            assertTrue(start >= 0 && gate > start && admit > gate);
        }
        assertTrue(source.contains("LocalAdbClient.hasAuthorizedIdentity(context)"));
        assertTrue(source.contains("AppPermissionProvisioner.hasWriteSecureSettings(context)"));
        assertTrue(source.contains("operation.write(() ->"));
        int callback = source.indexOf("private void tryTlsPort(int port, long generation)");
        int admission = source.indexOf("operation = admitted;", callback);
        int generationCheck = source.indexOf("if (generation != discoveryGeneration) return;", admission);
        int connect = source.indexOf("tryTlsPortAdmitted(port);", admission);
        assertTrue(generationCheck > admission && connect > generationCheck);
        Path clientPath = Path.of("app/src/main/java/com/byd/extend/LocalAdbClient.java");
        if (!Files.exists(clientPath)) clientPath = Path.of("src/main/java/com/byd/extend/LocalAdbClient.java");
        String client = new String(Files.readAllBytes(clientPath), StandardCharsets.UTF_8);
        assertFalse(client.contains("AUTO_PROMPT_KEY"));
        assertTrue(client.contains("AUTHORIZATION.claimPrompt"));
        assertTrue(client.contains("saved.equals(currentFingerprint(context))"));
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
