package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class CameraShellRecoveryGateTest {
    @Test
    public void newerAttachClaimsRecoveryOnlyOnce() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.onDeath(4, true));
        assertTrue(gate.pending());
        assertFalse(gate.claim(4, true));
        assertTrue(gate.claim(5, true));
        assertFalse(gate.pending());
        assertFalse(gate.claim(5, true));
        assertFalse(gate.claim(6, true));
    }

    @Test
    public void staleEventsAndLostIntentDoNotRecover() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.isNewDeath(3));
        assertFalse(gate.onDeath(3, false));
        assertFalse(gate.isNewDeath(3));
        assertFalse(gate.onDeath(3, true));
        assertFalse(gate.claim(4, true));
        assertTrue(gate.onDeath(5, true));
        assertFalse(gate.claim(6, false));
        assertFalse(gate.claim(7, true));
    }

    @Test
    public void laterDeathCanScheduleAnotherEpoch() {
        CameraShellRecoveryGate gate = new CameraShellRecoveryGate();

        assertTrue(gate.onDeath(1, true));
        assertTrue(gate.claim(2, true));
        assertTrue(gate.onDeath(2, true));
        assertTrue(gate.claim(3, true));
        gate.clear();
        assertFalse(gate.pending());
    }

    @Test
    public void cameraRestartUsesCameraProtocolAndContinuesAfterShutdownFailure()
            throws Exception {
        String source = readMainSource("TurnSignalController.java");
        int restart = source.indexOf("private IBinder restartCameraHelper(");
        int shutdown = source.indexOf(
                "transactCameraNoArgs(expected, CameraShellProtocol.TX_SHUTDOWN);", restart);
        int shutdownFailure = source.indexOf(
                "emit(\"shell_shutdown_failed\", \"helper\", \"camera\"", shutdown);
        int clear = source.indexOf("clearCameraHelper(expected, expectedEpoch);", shutdownFailure);
        int fresh = source.indexOf("ensureCameraHelper(true, expected)", clear);
        int cameraTransaction = source.indexOf("private static void transactCameraNoArgs(");
        int requireTransaction = source.indexOf("private static void requireTransact(", cameraTransaction);
        String cameraTransactionBody = source.substring(cameraTransaction, requireTransaction);

        assertTrue(restart >= 0);
        assertTrue(shutdown > restart);
        assertTrue(shutdownFailure > shutdown);
        assertTrue(clear > shutdownFailure);
        assertTrue(fresh > clear);
        assertTrue(cameraTransactionBody.contains("CameraShellProtocol.DESCRIPTOR"));
        assertFalse(cameraTransactionBody.contains("TurnSignalShellProtocol.DESCRIPTOR"));
        assertTrue(source.contains(
                "transactNoArgs(value, TurnSignalShellProtocol.TX_SHUTDOWN);"));
    }

    private static String readMainSource(String name) throws Exception {
        Path path = Paths.get("src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Paths.get("app/src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
