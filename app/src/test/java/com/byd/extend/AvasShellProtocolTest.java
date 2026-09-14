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
    public void protocolTenAddsOnlyFixedTypedAvasAuditionTransactions() {
        assertEquals(10, TurnSignalShellProtocol.VERSION);
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
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 15,
                TurnSignalShellProtocol.TX_START_AVAS_AUDITION);
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 16,
                TurnSignalShellProtocol.TX_STOP_AVAS_AUDITION);
        assertTrue(TurnSignalShellProtocol.isAvasSessionAllowed(
                "0123456789abcdef0123456789abcdef"));
        assertFalse(TurnSignalShellProtocol.isAvasSessionAllowed(null));
        assertFalse(TurnSignalShellProtocol.isAvasSessionAllowed("../session"));
        assertFalse(TurnSignalShellProtocol.isAvasSessionAllowed(""));
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
    public void temporaryMusicDisableKeepsExecutorAndReenableReconcilesAwake() throws Exception {
        String visualizer = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/MusicVisualizerRuntime.java")),
                StandardCharsets.UTF_8);
        String configure = visualizer.substring(
                visualizer.indexOf("private void configureOnHandler"),
                visualizer.indexOf("private void applyPowerState"));
        assertTrue(configure.contains("metadataRuntime.configure(value)"));
        assertTrue(configure.contains("reconcileAwakeFromSystem(\"configure\")"));
        assertTrue(configure.contains(
                "activate(changed ? \"configure\" : \"configure_retry\")"));
        assertTrue(configure.contains("deactivate(\"disabled\")"));
        assertFalse(configure.contains("metadataRuntime.stop()"));
        assertFalse(configure.contains("awake ="));

        String sessionReconcile = visualizer.substring(
                visualizer.indexOf("private void reconcileMetadataEvent"),
                visualizer.indexOf("private void refreshCurrentState"));
        assertTrue(sessionReconcile.contains("reconcileAwakeFromSystem(reason)"));
        assertTrue(sessionReconcile.contains(
                "if (awakeChanged && awake) queryPlaybackConfigurations(\"wake\")"));

        String metadata = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/MusicMetadataRuntime.java")),
                StandardCharsets.UTF_8);
        String metadataConfigure = metadata.substring(metadata.indexOf("void configure(boolean value)"),
                metadata.indexOf("void powerStateChanged"));
        assertTrue(metadataConfigure.contains("if (enabled) startObservers(\"configure\")"));
        assertTrue(metadataConfigure.contains("stopObservers(\"disabled\", awake)"));
        assertTrue(metadataConfigure.contains("if (terminalStop) return"));
        assertFalse(metadataConfigure.contains("writerExecutor.shutdown"));

        String shell = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/TurnSignalShellMain.java")),
                StandardCharsets.UTF_8);
        String terminal = shell.substring(shell.indexOf("if (code == TurnSignalShellProtocol.TX_SHUTDOWN)"),
                shell.indexOf("if (code == TurnSignalShellProtocol.TX_SHUTDOWN_KEEPING_AVAS)"));
        assertTrue(terminal.contains("musicRuntime.stop()"));

        String sleep = metadata.substring(metadata.indexOf("void powerStateChanged"),
                metadata.indexOf("void audioPlaybackChanged"));
        assertTrue(sleep.contains("pausePublishing()"));
        assertFalse(sleep.contains("stopObservers"));

        String attach = shell.substring(shell.indexOf("private synchronized void attachController"),
                shell.indexOf("private void controllerDied"));
        assertTrue(attach.contains("musicRuntime.reconcilePowerState("));
    }

    @Test
    public void retainedMusicObserverCannotBypassSleepStopCleanup() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/MusicVisualizerRuntime.java")),
                StandardCharsets.UTF_8);
        String callback = source.substring(source.indexOf("public void onPlaybackConfigChanged"),
                source.indexOf("selfCheck();"));
        int reconciliation = callback.indexOf("reconcileAwakeFromSystem(\"playback_callback\")");
        assertTrue(reconciliation >= 0
                && reconciliation < callback.indexOf("applyPlaybackConfigurations("));

        String activity = source.substring(source.indexOf("private void applyPlaybackConfigurations"),
                source.indexOf("private void setMediaActive"));
        int sleepGate = activity.indexOf(
                "if (!shouldProcessPlayback(enabled, awake, callbackRegistered)) return;");
        assertTrue(sleepGate >= 0 && sleepGate < activity.indexOf("cancelStopRetry();"));

        String activation = source.substring(source.indexOf("private void activate"),
                source.indexOf("private void queryPlaybackConfigurations"));
        int rearm = activation.indexOf(
                "if (shouldRearmStopRetries(enabled, awake, stopRetryExhausted))");
        int reset = activation.indexOf("stopRetryExhausted = false;");
        int registrationGate = activation.indexOf(
                "if (!shouldRegisterPlaybackObserver(enabled, callbackRegistered)) return;");
        assertTrue(rearm >= 0 && reset > rearm && registrationGate > reset);

        String sleep = source.substring(source.indexOf("private void suspendOutput"),
                source.indexOf("private void deactivate"));
        assertTrue(sleep.contains("stopOutput(reason)"));
        assertFalse(sleep.contains("unregisterCallback"));
    }

    @Test
    public void callbackReplacementOwnsExactlyOneDeathRecipient() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/byd/extend/TurnSignalShellMain.java")),
                StandardCharsets.UTF_8);
        String registration = source.substring(source.indexOf(
                        "private synchronized void registerCallback"),
                source.indexOf("private synchronized void clearCallback"));
        assertTrue(registration.indexOf("value.linkToDeath(recipient, 0)") <
                registration.indexOf("callback = value"));
        assertTrue(registration.indexOf("callback = value") <
                registration.indexOf("unlinkDeathRecipient(previous, previousRecipient)"));

        String clear = source.substring(source.indexOf(
                        "private synchronized void clearCallback"),
                source.indexOf("private void terminateProcessOnce"));
        assertTrue(clear.contains("callbackDeathRecipient = null"));
        assertTrue(clear.contains("unlinkDeathRecipient(value, recipient)"));
    }
}
