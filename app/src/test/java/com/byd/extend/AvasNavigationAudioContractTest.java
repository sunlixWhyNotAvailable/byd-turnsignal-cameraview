package com.byd.extend;

import static org.junit.Assert.assertFalse;
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

        assertTrue(exterior.contains("currentVolume, initialVolume, true"));
        assertFalse(exterior.contains("currentVolume, initialVolume, false"));
        assertTrue(navigation.contains("currentVolume, initialVolume, false"));
        assertFalse(navigation.contains("currentVolume, initialVolume, true"));
        assertTrue(writer.contains("currentVolume.getAsInt()"));
        assertTrue(writer.contains("scaleExteriorPcm16"));
        assertTrue(writer.contains("scalePcm16"));
        assertTrue(writer.contains("while (offset < length"));
        assertTrue(writer.contains("output.write(scaled, 0, writable"));
        assertTrue(writer.contains("offset += count"));
        assertTrue(runtime.contains("queue.enqueueExterior(profileId, true)"));
        assertTrue(runtime.contains("queue.enqueueExterior(profileId, false)"));
        assertTrue(runtime.contains("output.play(file, profile.volume"));
        assertTrue(runtime.contains("output.playNavigation(file, profile.volume"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
