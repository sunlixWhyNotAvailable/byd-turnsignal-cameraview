package com.byd.extend;

import android.os.IBinder;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AvasShellProtocolTest {
    @Test
    public void protocolNineAddsOnlyFixedTypedAvasLifecycleTransactions() {
        assertEquals(9, TurnSignalShellProtocol.VERSION);
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 9,
                TurnSignalShellProtocol.TX_CONFIGURE_AVAS);
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 10,
                TurnSignalShellProtocol.TX_INSTALL_AVAS_ASSET);
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 11,
                TurnSignalShellProtocol.TX_START_AVAS_MANUAL);
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 12,
                TurnSignalShellProtocol.TX_STOP_AVAS_MANUAL);
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 13,
                TurnSignalShellProtocol.TX_REPORT_AVAS_STATUS);
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 14,
                TurnSignalShellProtocol.TX_SHUTDOWN_KEEPING_AVAS);
        for (String profile : AvasConfig.PROFILE_IDS) {
            assertTrue(TurnSignalShellProtocol.isAvasProfileAllowed(profile));
        }
        assertFalse(TurnSignalShellProtocol.isAvasProfileAllowed("custom"));
        assertTrue(TurnSignalShellProtocol.isAvasAssetAllowed(
                "0123456789abcdef0123456789abcdef"));
        assertFalse(TurnSignalShellProtocol.isAvasAssetAllowed("../asset"));
        assertFalse(TurnSignalShellProtocol.isAvasAssetAllowed(
                "0123456789ABCDEF0123456789ABCDEF"));
    }

    @Test
    public void avasAloneUsesTheProvenShellAttributedContextAndAsyncClose() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/TurnSignalShellMain.java")),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("new AvasRuntime("));
        assertTrue(source.contains("avasShellContext(context), appUid"));
        assertTrue(source.contains("createPackageContext(\"com.android.shell\", 0)"));
        assertTrue(source.contains("\"createAppContext\", threadClass"));
        assertTrue(source.contains("avasCloseWorker.execute"));
        int asyncClose = source.indexOf("avasCloseWorker.execute");
        assertTrue(source.indexOf("closeAvasOnce();", asyncClose)
                < source.indexOf("terminateProcessOnce();", asyncClose));
    }

    @Test
    public void detachStopsUnrelatedRuntimesWithoutClosingAvas() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/TurnSignalShellMain.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf(
                "if (code == TurnSignalShellProtocol.TX_SHUTDOWN_KEEPING_AVAS)");
        int end = source.indexOf("return false;", start);
        String detach = source.substring(start, end);

        assertTrue(detach.contains("musicRuntime.configure(false)"));
        assertFalse(detach.contains("musicRuntime.stop()"));
        assertFalse(detach.contains("musicRuntime.powerStateChanged(false)"));
        assertTrue(detach.contains("parkingRadarRuntime.stop()"));
        assertTrue(detach.contains("reverseGearRuntime.stop()"));
        assertTrue(detach.contains("warningRuntime.stop()"));
        assertTrue(detach.contains("runtime.stop()"));
        assertFalse(detach.contains("closeAvasOnce()"));
        assertFalse(detach.contains("terminateProcessOnce()"));

        int attachStart = source.indexOf("private synchronized void attachController");
        int attachEnd = source.indexOf("private void controllerDied", attachStart);
        String attach = source.substring(attachStart, attachEnd);
        assertTrue(attach.contains("if (nonAvasStopped)"));
        assertTrue(attach.contains("runtime.start()"));
        assertTrue(attach.contains("warningRuntime.start()"));
        assertTrue(attach.contains("reverseGearRuntime.start()"));
        assertTrue(attach.contains("parkingRadarRuntime.start()"));
    }

    @Test
    public void temporaryMusicDisableKeepsExecutorAndAwakeStateForConfigReplay() throws Exception {
        String visualizer = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/MusicVisualizerRuntime.java")),
                StandardCharsets.UTF_8);
        String configure = visualizer.substring(
                visualizer.indexOf("private void configureOnHandler"),
                visualizer.indexOf("private void powerStateChangedOnHandler"));
        assertTrue(configure.contains("metadataRuntime.configure(value)"));
        assertTrue(configure.contains("if (awake) activate(\"configure\")"));
        assertTrue(configure.contains("deactivate(\"disabled\")"));
        assertFalse(configure.contains("metadataRuntime.stop()"));
        assertFalse(configure.contains("awake ="));

        String metadata = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/MusicMetadataRuntime.java")),
                StandardCharsets.UTF_8);
        String metadataConfigure = metadata.substring(metadata.indexOf("void configure(boolean value)"),
                metadata.indexOf("void powerStateChanged"));
        assertTrue(metadataConfigure.contains("startObservers(\"configure\")"));
        assertTrue(metadataConfigure.contains("stopObservers(\"disabled\", awake)"));
        assertFalse(metadataConfigure.contains("terminalStop"));
        assertFalse(metadataConfigure.contains("writerExecutor.shutdown"));

        String shell = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/TurnSignalShellMain.java")),
                StandardCharsets.UTF_8);
        String terminal = shell.substring(shell.indexOf("if (code == TurnSignalShellProtocol.TX_SHUTDOWN)"),
                shell.indexOf("if (code == TurnSignalShellProtocol.TX_SHUTDOWN_KEEPING_AVAS)"));
        assertTrue(terminal.contains("musicRuntime.stop()"));
    }
}
