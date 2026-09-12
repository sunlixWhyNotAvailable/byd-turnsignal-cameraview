package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AvasNavigationAudioContractTest {
    @Test public void restartRecoversBeforeQueueAndTelemetryWithoutRequiringPlayback() throws Exception {
        String runtime = source("AvasRuntime.java");
        String start = runtime.substring(runtime.indexOf("synchronized void start()"),
                runtime.indexOf("synchronized void configure("));
        int recover = start.indexOf("player = new AvasAudioPlayer(context, this::emit)");
        assertTrue(recover >= 0);
        assertTrue(start.indexOf("playback.execute") > recover);
        assertTrue(start.indexOf("updatePolling()") > recover);
        assertTrue(start.indexOf("reportStatus()") > recover);
        assertTrue(start.contains("\"stage\", \"recovery\""));
    }

    @Test public void currentHelperDeathCancelsOnlyItsCapturedNoteSession() throws Exception {
        String controller = source("TurnSignalController.java");
        int start = controller.indexOf("private void helperDied(");
        String death = controller.substring(start, controller.indexOf("private ", start + 8));
        assertTrue(death.contains("diedAudition = desiredAvasAudition"));
        assertTrue(death.contains("desiredAvasAudition = \"\""));
        assertTrue(death.contains("\"audition_session_id\", diedAudition"));
        assertTrue(death.indexOf("if (stale)") < death.indexOf("emit(\"helper_death\""));
        String activity = source("CameraProbeActivity.java");
        int branch = activity.indexOf("} else if (\"helper_death\".equals(kind)");
        String clear = activity.substring(branch, activity.indexOf("telemetryReady = false;", branch));
        assertTrue(clear.contains("avasAudition.getSessionId().equals("));
        assertTrue(clear.contains("json.optString(\"audition_session_id\")"));
        assertTrue(clear.contains("avasAudition = new AvasAuditionUiState()"));
        assertTrue(clear.contains("productionUi.refreshAvasState()"));
    }

    @Test public void navigationRouteUsesOnlyTheFixedOemWrite() throws Exception {
        String route = source("AvasNavigationRoute.java");
        assertTrue(route.contains("DEVICE = 1000"));
        assertTrue(route.contains("POSITION_FID = 0xAA000282"));
        assertTrue(route.contains("write(1, \"prepare\")"));
        assertTrue(route.contains("write(0, \"release\")"));
        assertTrue(source("AvasNavigationRecovery.java").contains("status < 0"));
        assertTrue(route.contains("transact(6, data, reply, 0)"));
        assertFalse(route.contains("setLegacyStreamType"));
        assertFalse(route.contains("MediaPlayer"));
    }

    @Test public void navigationPlaybackKeepsReferenceFocusFlagsSilenceGainAndRestoration()
            throws Exception {
        String player = source("AvasAudioPlayer.java");
        String settings = source("AvasShellSettings.java");
        assertTrue(player.contains("NAV_STREAM = 15"));
        assertTrue(player.contains("REQUESTED_ROUTE_FLAGS = 0x20000"));
        assertTrue(player.contains("AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"));
        assertTrue(player.contains("NAV_SILENCE_MILLIS = 0"));
        assertTrue(player.contains("getStreamMaxVolume(NAV_STREAM)"));
        assertTrue(player.contains("setStreamVolume(NAV_STREAM, maximum, 0)"));
        assertTrue(player.contains("AvasWav.scalePcm16"));
        assertTrue(player.contains("currentVolume.getAsInt()"));
        assertTrue(player.contains("new AudioTrack.Builder()"));
        assertTrue(player.contains("navigationRoute.release(focus, dirty,"));
        assertTrue(player.contains("setStreamVolume(NAV_STREAM, savedNav, 0)"));
        assertTrue(settings.contains("EXTERIOR_DIRTY = 1"));
        assertTrue(settings.contains("NAVIGATION_DIRTY = 2"));
        assertFalse(player.contains("AudioManager.STREAM_MUSIC"));
        assertFalse(player.contains("MediaPlayer"));
    }

    @Test public void rejectedNavigationRestoresLocalStateWithoutIssuingRelease() throws Exception {
        String player = source("AvasAudioPlayer.java");
        String navigation = player.substring(player.indexOf("void playNavigation(File wav"),
                player.indexOf("void stop()"));
        assertTrue(navigation.contains("navigationRoute.prepare(dirty -> settings.putInt("));
        assertFalse(navigation.contains("AvasShellSettings.NAVIGATION_DIRTY"));
        String restore = player.substring(player.indexOf("int dirty = 0;"),
                player.indexOf("private static Exception combine("));
        assertTrue(restore.contains("AvasNavigationRecovery.requiresRelease(dirty)"));
        assertTrue(restore.contains("&& dirty != AvasShellSettings.NAVIGATION_REJECTED"));
        assertTrue(restore.contains("manager.abandonAudioFocusRequest(focus)"));
        assertTrue(restore.contains("setStreamVolume(NAV_STREAM, savedNav, 0)"));
        assertTrue(restore.indexOf("if (failure == null)") <
                restore.indexOf("settings.putInt(AvasShellSettings.DIRTY, AvasShellSettings.CLEAN)"));
        String route = source("AvasNavigationRoute.java");
        assertTrue(route.indexOf("AvasNavigationRecovery.release(") <
                route.indexOf("manager.abandonAudioFocusRequest(focus)"));
        assertTrue(route.contains("\"route_closed_confirmed\", false"));
    }

    @Test public void exteriorAndNavigationUseTheirOwnProductionGainPolicies() throws Exception {
        String player = source("AvasAudioPlayer.java");
        String exterior = player.substring(player.indexOf("void play(File wav"),
                player.indexOf("void playNavigation(File wav"));
        String navigation = player.substring(player.indexOf("void playNavigation(File wav"),
                player.indexOf("void stop()"));
        String writer = player.substring(player.indexOf("private int write("),
                player.indexOf("private long writeSilence("));
        String runtime = source("AvasRuntime.java");

        assertTrue(exterior.contains("currentVolume, initialVolume, exteriorGain"));
        assertTrue(exterior.contains("new ExteriorGain(output, currentVolume, initialVolume"));
        assertTrue(navigation.contains("currentVolume, initialVolume, null"));
        assertFalse(navigation.contains("ExteriorGain"));
        assertTrue(navigation.contains("output.setVolume(1f)"));
        assertTrue(writer.contains("currentVolume.getAsInt()"));
        assertFalse(writer.contains("scaleExteriorPcm16"));
        assertTrue(writer.contains("exteriorGain.update()"));
        assertTrue(writer.contains("output.write(pcm, offset, writable"));
        assertTrue(writer.contains("scalePcm16"));
        assertTrue(writer.contains("while (offset < length"));
        assertTrue(writer.contains("output.write(scaled, 0, writable"));
        assertTrue(writer.contains("offset += count"));
        assertTrue(runtime.contains("queue.enqueueExterior(profileId, true)"));
        assertTrue(runtime.contains("queue.enqueueExterior(profileId, false)"));
        assertTrue(runtime.contains("output.play(file, profile.volume"));
        assertTrue(runtime.contains("output.playNavigation(file, profile.volume"));
    }

    @Test public void exteriorPreparesChannel0NavQuietAndPrivateEffectBeforeStreamingWithoutNewSilence()
            throws Exception {
        String player = source("AvasAudioPlayer.java");
        String exterior = player.substring(player.indexOf("void play(File wav"),
                player.indexOf("void playNavigation(File wav"));
        int mute = exterior.indexOf("route.mute(true, diagnostics)");
        int focus = exterior.indexOf("manager.requestAudioFocus(focus)");
        int reassert = exterior.indexOf("\"pre_route_reassert\"");
        int route = exterior.indexOf("route.prepare(focus, diagnostics, dirty ->");
        int quiet = exterior.indexOf("route.mute(true, diagnostics)", route);
        int create = exterior.indexOf("new AudioTrack.Builder()");
        int gain = exterior.indexOf("exteriorGain.prepare()");
        int play = exterior.indexOf("output.play()");
        assertTrue(mute >= 0 && mute < focus && focus < reassert && reassert < route);
        assertTrue(route < quiet && quiet < create);
        assertFalse(exterior.contains("manager.setStreamVolume("));
        assertFalse(exterior.contains("route.mute(false"));
        assertTrue(create < gain && gain < play);
        assertTrue(exterior.contains("setTransferMode(AudioTrack.MODE_STREAM)"));
        assertFalse(exterior.contains("AudioTrack.MODE_STATIC"));
        assertTrue(exterior.contains("Thread.sleep(80)"));
        assertEquals(1, exterior.split("Thread\\.sleep\\(", -1).length - 1);
        assertTrue(exterior.contains("drain(output, framesWritten, ticket, cancelled, session, exteriorGain)"));
        assertTrue(exterior.indexOf("exteriorGain.close()") < exterior.indexOf("release(output, true)"));
        assertTrue(exterior.indexOf("release(output, true)") < exterior.indexOf("restore(focus, diagnostics)"));
    }

    @Test public void exteriorEffectIsPrivateOptionalAndUpdatedOnlyWhenVolumeChanges() throws Exception {
        String player = source("AvasAudioPlayer.java");
        String gain = player.substring(player.indexOf("private final class ExteriorGain"),
                player.indexOf("private final class SessionDiagnostics"));
        assertTrue(gain.contains("output.getAudioSessionId()"));
        assertTrue(gain.contains("sessionId <= 0"));
        assertTrue(gain.contains("new LoudnessEnhancer(sessionId)"));
        assertTrue(gain.contains("AvasPlaybackPlan.exteriorTargetGainMb(volume)"));
        assertTrue(gain.contains("loudness.setEnabled(true)"));
        assertTrue(gain.contains("result != AudioEffect.SUCCESS || !loudness.getEnabled()"));
        assertTrue(gain.contains("if (volume == appliedVolume) return"));
        assertTrue(gain.contains("AvasPlaybackPlan.exteriorPlayerVolume(volume)"));
        assertTrue(gain.contains("\"fallback\", \"unboosted_pcm\""));
        assertTrue(gain.contains("owned.release()"));
        assertTrue(gain.contains("loudness = null"));
        assertFalse(gain.contains("Thread.sleep"));
        assertFalse(gain.contains("settings.putInt"));
    }

    @Test public void exteriorRouteIsDirtyBeforeItsFirstSideEffect() throws Exception {
        String player = source("AvasAudioPlayer.java");
        String exterior = player.substring(player.indexOf("void play(File wav"),
                player.indexOf("void playNavigation(File wav"));
        int dirty = exterior.indexOf(
                "settings.putInt(AvasShellSettings.DIRTY, AvasShellSettings.EXTERIOR_CHANNEL0_UNACQUIRED)");
        assertTrue(dirty >= 0);
        assertTrue(dirty < exterior.indexOf("route.naviFocus(true, diagnostics)"));
        assertTrue(dirty < exterior.indexOf("manager.requestAudioFocus(focus)"));
        assertTrue(dirty < exterior.indexOf("route.mute(true, diagnostics)"));
        assertTrue(exterior.contains("route.prepare(focus, diagnostics, dirty -> settings.putInt("));
    }

    @Test public void exteriorSharedSetupAndCleanupAreNotGatedOnPrimaryAcceptance() throws Exception {
        String route = source("AvasExteriorRoute.java");
        String prepare = route.substring(route.indexOf("void prepare("),
                route.indexOf("static boolean acquirePrimary("));
        assertTrue(prepare.indexOf("acquirePrimary(") < prepare.indexOf("exteriorPath(true"));
        assertTrue(prepare.indexOf("exteriorPath(true") < prepare.indexOf("manager.requestAudioFocus"));
        assertTrue(prepare.indexOf("manager.requestAudioFocus") < prepare.indexOf("if (!ready)"));
        assertFalse(prepare.contains("if (primary"));
        String release = route.substring(route.indexOf("void release("),
                route.indexOf("static int releasePrimaryDevice("));
        assertTrue(release.contains("writePrimary(() -> tryWrite(primaryDevice"));
        assertFalse(release.contains("EXTERIOR_CHANNEL0_SHARED"));
        assertTrue(release.contains("if (dirty != AvasShellSettings.EXTERIOR_CHANNEL0_UNACQUIRED)"));
        assertTrue(release.contains("exteriorPath(false"));
        assertTrue(source("AvasAudioPlayer.java").contains(
                "|| dirty == AvasShellSettings.EXTERIOR_CHANNEL0_SHARED"));
    }

    @Test public void settingsProviderClosesOnlyAfterPlaybackWorkerTerminates() throws Exception {
        String runtime = source("AvasRuntime.java");
        String close = runtime.substring(runtime.indexOf("public void close()"),
                runtime.indexOf("private void playbackLoop()"));
        int joined = close.indexOf("playback.awaitTermination");
        int finalPlayer = close.indexOf("current = player;", joined);
        assertTrue(joined > close.indexOf("current.stop()"));
        // Covers the failed-start path where a dequeued request creates a late player.
        assertTrue(finalPlayer > joined);
        assertTrue(close.indexOf("player = null;", finalPlayer) > finalPlayer);
        assertTrue(close.indexOf("current.close()") > finalPlayer);

        String player = source("AvasAudioPlayer.java");
        String constructor = player.substring(player.indexOf("AvasAudioPlayer(Context"),
                player.indexOf("void play(File wav"));
        assertTrue(constructor.contains("settings.close()"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
