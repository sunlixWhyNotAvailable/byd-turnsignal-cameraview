package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.byd.extend.CameraRuntimeContinuityTest.source;

/** Guards the real app/shell boundary which arithmetic-only camera tests cannot cover. */
public class AppPlatformOwnershipTest {
    @Test public void androidRegistrationsAndClusterBindingBelongToTheApp() throws Exception {
        String client = source("CameraRuntimeClient"), host = source("CameraRuntimeHost");
        assertTrue(client.contains("this.context = context.getApplicationContext()"));
        assertTrue(client.contains("new ClusterFullscreenController(context, settings, handler"));
        assertTrue(client.contains("new OemCameraVisibilityRuntime(context, handler"));
        assertTrue(client.contains("context.registerReceiver(platformReceiver, filter, null, handler)"));
        assertTrue(client.contains("Intent.ACTION_SCREEN_ON"));
        assertTrue(client.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue(client.contains("Intent.ACTION_USER_PRESENT"));
        assertTrue(client.contains("android.intent.action.QUICKBOOT_POWERON"));
        assertTrue(client.contains("android.media.VOLUME_CHANGED_ACTION"));
        assertTrue(client.contains("android.media.STREAM_MUTE_CHANGED_ACTION"));
        assertFalse(host.contains("new ClusterFullscreenController"));
        assertFalse(host.contains("new OemCameraVisibilityRuntime"));
        assertFalse(source("TurnSignalShellMain").contains("registerReceiver("));
        assertFalse(source("AvasExteriorVolume").contains("registerReceiver("));
        assertTrue(source("CameraHelperService").contains("cameraRuntime.acceptPlatformEvent(line)"));
        assertTrue(client.contains("cluster.acceptEvent(line)"));
        assertTrue(client.contains("cluster.settingsChanged()"));
    }

    @Test public void reattachmentReplaysStateWithoutReplacingLiveSources() throws Exception {
        String client = source("CameraRuntimeClient");
        assertTrue(client.contains("handler.post(this::platformAttached)"));
        String attached = client.substring(client.indexOf("private synchronized void platformAttached()"),
                client.indexOf("synchronized void acceptPlatformEvent"));
        assertTrue(attached.contains("if (detached || shell == null) return"));
        assertTrue(attached.contains("if (cluster == null)"));
        assertTrue(attached.contains("if (!platformRegistered)"));
        assertTrue(attached.contains("platformCommand(CameraRuntimeHost.POWER, 0"));
        assertTrue(attached.contains("platformCommand(CameraRuntimeHost.NAV_VOLUME, 0"));
        assertTrue(attached.contains("else pano.reportStatus()"));
        assertFalse(attached.contains("shutdown("));
        assertFalse(attached.contains("new AvasAudioPlayer"));
        String detached = client.substring(client.indexOf("synchronized void detach()"),
                client.indexOf("private Bundle command("));
        assertTrue(detached.contains("pano.stopForTeardown()"));
        assertTrue(detached.contains("cluster.shutdown()"));
        assertTrue(detached.contains("context.unregisterReceiver(platformReceiver)"));
        assertFalse(detached.contains("CameraRuntimeHost.STOP"));
        assertTrue(client.contains("if (!detached && shell != null) command(operation"));
        assertTrue(client.contains("deliverEvent(event.toString())"));
        assertTrue(client.contains("handler.post(() -> { if (!detached) events.accept(line); })"));
    }

    @Test public void relayIsAuthenticatedAndKeepsAllCameraConsumers() throws Exception {
        String host = source("CameraRuntimeHost"), shell = source("TurnSignalShellMain");
        assertTrue(host.contains("owner == null || lifetime.client() != owner"));
        assertTrue(shell.contains("TurnSignalShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)"));
        String pano = host.substring(host.indexOf("case PANO:"), host.indexOf("case POWER:"));
        assertTrue(pano.contains("platformValue < -1 || platformValue > 1"));
        assertTrue(pano.contains("blind.oemVisibility("));
        assertTrue(pano.contains("reverse.oemVisibility("));
        assertTrue(pano.contains("mirror.oemVisibility("));
        assertTrue(shell.contains("}, this::platformSignal)"));
        assertTrue(shell.contains("avasRuntime.navigationVolumeChanged()"));
        assertTrue(shell.contains("powerStateChanged(value == 1"));
        assertTrue(shell.contains("new AvasShellSettings(shellContext(context))"));
        assertFalse(shell.contains("context.getContentResolver()"));
    }

    @Test public void volumeEdgesRefreshExistingGainsAndKeepFailSilentCurvePolicy() throws Exception {
        String player = source("AvasAudioPlayer"), volume = source("AvasExteriorVolume");
        assertTrue(source("AvasRuntime").contains("current.navigationVolumeChanged()"));
        assertTrue(player.contains("exteriorOutputs.toArray(new ExteriorGain[0])"));
        assertTrue(player.contains("for (ExteriorGain output : outputs) output.refreshNavigationVolume()"));
        assertTrue(player.contains("if (!disposed && navVolume != null) navVolume.refresh()"));
        assertTrue(volume.contains("track.addOnRoutingChangedListener("));
        assertTrue(volume.contains("track.removeOnRoutingChangedListener("));
        assertTrue(volume.contains("if (closed || !monitoring) return"));
        assertTrue(volume.contains("attenuation = 0"));
        assertTrue(volume.contains("compensation(referenceDb, actualDb)"));
        assertFalse(volume.contains("setStreamVolume"));
        assertTrue(player.contains("AvasPlaybackPlan.exteriorPlayerVolume(volume) * attenuation"));
    }

    @Test public void samePowerSnapshotAfterAppDeathKeepsSessionAndClusterAttempt() {
        TurnSignalShellMain.ShellBinder.AwakeSessionState power =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.reconcile(null, 1145, true, 1000);
        long session = power.generation;
        power.cleanupAttemptedGeneration = session;
        for (int attach = 0; attach < 3; attach++) {
            assertFalse(power.update(true, false, 2000 + attach));
            assertEquals(session, power.generation);
            assertEquals(session, power.cleanupAttemptedGeneration);
            assertFalse(ClusterFullscreenController.shouldAttempt(
                    true, true, power.generation, session, 1145, 1145, 4, 4));
        }
        assertFalse(power.update(false, false, 3000));
        assertTrue(power.update(true, false, 4000));
        assertFalse(power.update(true, true, 4001)); // paired quickboot is not a second wake
        assertEquals(session + 1, power.generation);
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, power.generation, session, 1145, 1145, 4, 4));
    }
}
