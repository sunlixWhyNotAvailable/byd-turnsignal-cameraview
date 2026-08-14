package com.byd.turnsignalguard.capture;

import android.media.AudioAttributes;
import android.os.IBinder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayDeque;
import java.util.Arrays;

public final class AdbCoreTest {
    private static final byte[] SHA1_DIGEST_INFO = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    @Test
    public void packetReadIgnoresChecksumAndMagicButKeepsFraming() throws Exception {
        byte[] payload = new byte[]{1, 2, 3};
        ByteBuffer wire = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        wire.putInt(AdbPacket.A_CNXN).putInt(7).putInt(8).putInt(payload.length)
                .putInt(0x12345678).putInt(0x10203040).put(payload);
        AdbPacket packet = AdbPacket.read(new ByteArrayInputStream(wire.array()));
        assertEquals(AdbPacket.A_CNXN, packet.command);
        assertEquals(7, packet.arg0);
        assertEquals(8, packet.arg1);
        assertArrayEquals(payload, packet.payload);
    }

    @Test
    public void packetReadRejectsOversizedPayload() {
        ByteBuffer wire = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        wire.putInt(AdbPacket.A_CNXN).putInt(0).putInt(0)
                .putInt(AdbPacket.MAX_PAYLOAD + 1).putInt(0).putInt(0);
        assertThrows(IOException.class,
                () -> AdbPacket.read(new ByteArrayInputStream(wire.array())));
    }

    @Test
    public void adbSignatureUsesRawSha1DigestInfo() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        byte[] token = new byte[20];
        Arrays.fill(token, (byte) 0x5a);
        byte[] expected = Arrays.copyOf(SHA1_DIGEST_INFO,
                SHA1_DIGEST_INFO.length + token.length);
        System.arraycopy(token, 0, expected, SHA1_DIGEST_INFO.length, token.length);

        Signature verifier = Signature.getInstance("NONEwithRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(expected);
        assertTrue(verifier.verify(LocalAdbClient.signToken(pair.getPrivate(), token)));
    }

    @Test
    public void androidPublicKeyEncodingHasExpectedShape() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
        byte[] encoded = AdbKeyFormatter.encodePublicKey(key);
        ByteBuffer values = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(524, encoded.length);
        assertEquals(64, values.getInt(0));
        assertEquals(key.getPublicExponent().intValue(), values.getInt(520));
        assertArrayEquals(collectorReferenceEncoding(key), encoded);
        assertTrue(AdbKeyFormatter.formatPublicKey(key).endsWith(" bydturnguard@dilink"));
    }

    @Test
    public void authPolicyAndLaunchStayNarrow() {
        assertEquals("127.0.0.1:5555", LocalAdbClient.endpointForTest());
        assertTrue(LocalAdbClient.shouldSendPublicKey(
                LocalAdbClient.PromptMode.AUTO_ONCE, false, false));
        assertFalse(LocalAdbClient.shouldSendPublicKey(
                LocalAdbClient.PromptMode.AUTO_ONCE, true, false));
        assertTrue(LocalAdbClient.shouldSendPublicKey(
                LocalAdbClient.PromptMode.AUTO_ONCE, true, true));
        assertTrue(LocalAdbClient.shouldSendPublicKey(
                LocalAdbClient.PromptMode.FORCE, true, false));
        assertFalse(LocalAdbClient.shouldSendPublicKey(
                LocalAdbClient.PromptMode.NEVER, false, true));
        assertEquals(77, BuildConfig.VERSION_CODE);
        assertEquals(6, TurnSignalShellProtocol.VERSION);
        assertTrue(TurnSignalShellProtocol.TX_CONFIGURE_MUSIC
                > TurnSignalShellProtocol.TX_SHUTDOWN);

        String command = TurnSignalController.launchCommand("/data/app/a'b/base.apk", 10058, 5);
        assertTrue(command.contains("pidof bydturnguard_helper"));
        assertTrue(command.contains("while [ -n \"$(pidof bydturnguard_helper"));
        assertTrue(command.contains("helper_stop_timeout; false"));
        assertTrue(command.contains("rm -f /data/local/tmp/bydturnguard_helper.lock"));
        assertTrue(command.indexOf("helper_stop_timeout; false")
                < command.indexOf("rm -f /data/local/tmp/bydturnguard_helper.lock"));
        assertTrue(command.contains("setsid app_process /system/bin"));
        assertTrue(command.contains("TurnSignalShellMain 10058"));
        assertTrue(command.contains(" 5 </dev/null"));
        assertTrue(command.contains("service list 2>/dev/null | grep -q byd_turn_signal_guard"));
        assertTrue(command.indexOf("setsid app_process") < command.indexOf("service list"));
        assertFalse(command.contains("bmmcamera"));
        assertTrue(TurnSignalShellProtocol.isCallerAllowed(10058, 10058));
        assertFalse(TurnSignalShellProtocol.isCallerAllowed(2000, 10058));
        assertTrue(TurnSignalShellProtocol.isPayloadAllowed(0));
        assertTrue(TurnSignalShellProtocol.isPayloadAllowed(3));
        assertFalse(TurnSignalShellProtocol.isPayloadAllowed(-1));
        assertFalse(TurnSignalShellProtocol.isPayloadAllowed(4));
    }

    @Test
    public void shellRecoveryIsWakeGatedAndCommandIsFixed() {
        assertFalse(TurnSignalShellMain.ShellBinder.shouldAttemptRecovery(
                false, false, true, false));
        assertFalse(TurnSignalShellMain.ShellBinder.shouldAttemptRecovery(
                true, true, true, false));
        assertFalse(TurnSignalShellMain.ShellBinder.shouldAttemptRecovery(
                true, false, false, false));
        assertFalse(TurnSignalShellMain.ShellBinder.shouldAttemptRecovery(
                true, false, true, true));
        assertTrue(TurnSignalShellMain.ShellBinder.shouldAttemptRecovery(
                true, false, true, false));
        assertArrayEquals(new String[]{
                        "am", "broadcast", "--user", "0", "--include-stopped-packages",
                        "--receiver-foreground", "--async",
                        "-a", GuardRecovery.ACTION_SHELL_RECOVERY,
                        "-n", CameraHelperMain.PACKAGE_NAME + "/.ShellRecoveryReceiver"
                },
                TurnSignalShellMain.ShellBinder.recoveryCommandForTest());
    }

    @Test
    public void updateDelayAgesBeforeActivityOpensAndRunsOnce() {
        UpdateAutoCheckRuntime.Scheduler scheduler =
                new UpdateAutoCheckRuntime.Scheduler(30_000L);
        scheduler.start(1_000L);
        assertEquals(20_000L, scheduler.remainingMs(11_000L));
        assertFalse(scheduler.consumeIfReady(30_999L));
        assertTrue(scheduler.consumeIfReady(31_000L));
        assertFalse(scheduler.consumeIfReady(61_000L));
        assertEquals(-1L, scheduler.remainingMs(61_000L));
    }

    @Test
    public void updateVersionComparisonUsesStableSemanticVersions() {
        assertTrue(AppUpdateManager.isNewerVersion("0.35.0", "0.34.0"));
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0", "0.99.9"));
        assertFalse(AppUpdateManager.isNewerVersion("0.34.0", "0.34.0"));
        assertFalse(AppUpdateManager.isNewerVersion("0.33.9", "0.34.0"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateManager.isNewerVersion("0.35-beta", "0.34.0"));
    }

    @Test
    public void updateUrlsStayOnTheConfiguredGitHubRepository() {
        String api = "https://api.github.com/repos/sunlixWhyNotAvailable/"
                + "byd-turnsignal-cameraview/releases/latest";
        String apk = "https://github.com/sunlixWhyNotAvailable/"
                + "byd-turnsignal-cameraview/releases/download/v0.35.0/"
                + "byd-turnsignal-camera-v0.35.0.apk";
        assertEquals(api, AppUpdateManager.requireTrustedReleaseApiUrl(api));
        assertEquals(apk, AppUpdateManager.requireTrustedApkDownloadUrl(apk));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateManager.requireTrustedReleaseApiUrl(
                        "https://example.com/releases/latest"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateManager.requireTrustedApkDownloadUrl(
                        "https://github.com/other/repo/releases/download/v1/app.apk"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateManager.requireTrustedApkDownloadUrl(
                        "http://github.com/sunlixWhyNotAvailable/"
                                + "byd-turnsignal-cameraview/releases/download/v1/app.apk"));
    }

    @Test
    public void authorizationModesReplaceAutoAndCoalesceForce() {
        TurnSignalController.AuthorizationGate gate =
                new TurnSignalController.AuthorizationGate();
        assertEquals(TurnSignalController.AuthorizationRequestAction.ACCEPTED,
                gate.request(LocalAdbClient.PromptMode.AUTO_ONCE));
        assertEquals(TurnSignalController.AuthorizationRequestAction.COALESCED,
                gate.request(LocalAdbClient.PromptMode.AUTO_ONCE));
        assertEquals(TurnSignalController.AuthorizationRequestAction.REPLACED_AUTO,
                gate.request(LocalAdbClient.PromptMode.FORCE));
        assertEquals(LocalAdbClient.PromptMode.FORCE, gate.mode());
        gate.finish(LocalAdbClient.PromptMode.AUTO_ONCE);
        assertEquals(LocalAdbClient.PromptMode.FORCE, gate.mode());
        assertEquals(TurnSignalController.AuthorizationRequestAction.COALESCED,
                gate.request(LocalAdbClient.PromptMode.FORCE));
        assertEquals("accepted",
                TurnSignalController.AuthorizationRequestAction.REPLACED_AUTO.wireName());
        assertEquals("coalesced",
                TurnSignalController.AuthorizationRequestAction.COALESCED.wireName());
        gate.finish(LocalAdbClient.PromptMode.FORCE);
        assertFalse(gate.active());

        assertEquals(LocalAdbClient.PromptMode.AUTO_ONCE,
                CameraHelperMain.adbAuthorizationMode(
                        CameraHelperMain.ADB_AUTH_MODE_AUTO_ONCE));
        assertEquals(LocalAdbClient.PromptMode.FORCE,
                CameraHelperMain.adbAuthorizationMode(CameraHelperMain.ADB_AUTH_MODE_FORCE));
        assertThrows(IllegalArgumentException.class,
                () -> CameraHelperMain.adbAuthorizationMode(2));

        assertTrue(TurnSignalController.canReuseHealthyHelperBeforeAuthorization(
                true, LocalAdbClient.PromptMode.AUTO_ONCE));
        assertFalse(TurnSignalController.canReuseHealthyHelperBeforeAuthorization(
                true, LocalAdbClient.PromptMode.FORCE));
        assertFalse(TurnSignalController.canReuseHealthyHelperBeforeAuthorization(
                false, LocalAdbClient.PromptMode.AUTO_ONCE));
        assertTrue(TurnSignalController.isSupersededByRequest(
                LocalAdbClient.PromptMode.NEVER, LocalAdbClient.PromptMode.FORCE));
        assertTrue(TurnSignalController.isSupersededByRequest(
                LocalAdbClient.PromptMode.NEVER, LocalAdbClient.PromptMode.AUTO_ONCE));
        assertFalse(TurnSignalController.isSupersededByRequest(
                LocalAdbClient.PromptMode.FORCE, LocalAdbClient.PromptMode.FORCE));
        assertFalse(TurnSignalController.isSupersededByRequest(
                LocalAdbClient.PromptMode.NEVER, null));
        assertTrue(TurnSignalController.isSupersededByRequest(
                LocalAdbClient.PromptMode.AUTO_ONCE, LocalAdbClient.PromptMode.FORCE));

        long cancellationToken = LocalAdbClient.cancellationToken();
        assertTrue(LocalAdbClient.isCancellationTokenCurrent(cancellationToken));
        LocalAdbClient.cancelPendingAuthorization();
        assertFalse(LocalAdbClient.isCancellationTokenCurrent(cancellationToken));
        assertTrue(LocalAdbClient.isCancellationTokenCurrent(
                LocalAdbClient.NO_CANCELLATION));

        assertFalse(TurnSignalController.shouldRecordLaunchFailure(
                LocalAdbClient.Result.superseded()));
        assertTrue(TurnSignalController.shouldRecordLaunchFailure(
                LocalAdbClient.Result.authorizationRequired(
                        "authorization_required", false, "key")));
        LocalAdbClient.Result authorizationRequired =
                LocalAdbClient.Result.authorizationRequired(
                        "authorization_required", false, "key");
        assertFalse(TurnSignalController.shouldRememberAutomaticAuthorizationBlock(
                LocalAdbClient.PromptMode.NEVER, authorizationRequired));
        assertTrue(TurnSignalController.shouldRememberAutomaticAuthorizationBlock(
                LocalAdbClient.PromptMode.AUTO_ONCE, authorizationRequired));
        assertTrue(TurnSignalController.shouldRememberAutomaticAuthorizationBlock(
                LocalAdbClient.PromptMode.FORCE, authorizationRequired));

        assertTrue(TurnSignalController.shouldBlockAutomaticAuthorization(
                LocalAdbClient.PromptMode.AUTO_ONCE, "2:key", "2:key"));
        assertFalse(TurnSignalController.shouldBlockAutomaticAuthorization(
                LocalAdbClient.PromptMode.FORCE, "2:key", "2:key"));
        assertTrue(TurnSignalController.shouldBlockAutomaticAuthorization(
                LocalAdbClient.PromptMode.NEVER, "2:key", "2:key"));
        assertFalse(TurnSignalController.shouldBlockAutomaticAuthorization(
                LocalAdbClient.PromptMode.AUTO_ONCE, "2:key", "2:new-key"));
    }

    @Test
    public void manualAuthorizationCanReplaceAutoButNotDuplicateForce() {
        assertTrue(CameraProbeActivity.shouldEnableManualAdbAuthorization(
                true, false, null));
        assertTrue(CameraProbeActivity.shouldEnableManualAdbAuthorization(
                true, true, LocalAdbClient.PromptMode.AUTO_ONCE));
        assertTrue(CameraProbeActivity.shouldEnableManualAdbAuthorization(
                true, true, LocalAdbClient.PromptMode.NEVER));
        assertFalse(CameraProbeActivity.shouldEnableManualAdbAuthorization(
                true, true, LocalAdbClient.PromptMode.FORCE));
        assertFalse(CameraProbeActivity.shouldEnableManualAdbAuthorization(
                false, false, null));
        assertEquals(LocalAdbClient.PromptMode.AUTO_ONCE,
                CameraProbeActivity.adbPromptMode("AUTO_ONCE"));
        assertEquals(LocalAdbClient.PromptMode.FORCE,
                CameraProbeActivity.adbPromptMode("FORCE"));
        assertEquals(null, CameraProbeActivity.adbPromptMode("NONE"));
    }

    @Test
    public void recoveryRequiresAutoStartWithoutUserShutdown() {
        assertTrue(GuardRecovery.shouldRecover(true, false));
        assertFalse(GuardRecovery.shouldRecover(false, false));
        assertFalse(GuardRecovery.shouldRecover(true, true));
        assertFalse(GuardRecovery.shouldRecover(false, true));
        assertTrue(GuardRecovery.stale(5_000, 0));
        assertTrue(GuardRecovery.stale(5_000, 100_000));
        assertFalse(GuardRecovery.stale(100_000, 50_000));
        assertTrue(GuardRecovery.stale(200_000, 50_000));
        assertTrue(CameraHelperService.shouldRetryCameraDiscovery(false, true));
        assertFalse(CameraHelperService.shouldRetryCameraDiscovery(true, true));
        assertFalse(CameraHelperService.shouldRetryCameraDiscovery(false, false));
        assertTrue(TurnSignalShellProtocol.TX_SHUTDOWN
                > TurnSignalShellProtocol.TX_ATTACH_CONTROLLER);
        assertTrue(CameraShellProtocol.TX_SHUTDOWN > CameraShellProtocol.TX_CLOSE);
    }

    @Test
    public void musicJournalKeepsOnlyLatestServiceEvents() {
        ArrayDeque<String> journal = new ArrayDeque<>();
        for (int i = 0; i < 25; i++) {
            CameraHelperMain.HelperBinder.appendBounded(journal, "event-" + i, 20);
        }
        assertEquals(20, journal.size());
        assertEquals("event-5", journal.getFirst());
        assertEquals("event-24", journal.getLast());
        assertTrue(CameraHelperMain.HelperBinder.isMusicJournalEvent(
                "music_playback_state"));
        assertTrue(CameraHelperMain.HelperBinder.isMusicJournalEvent(
                "music_metadata_publish"));
        assertTrue(CameraHelperMain.HelperBinder.isMusicJournalEvent(
                "music_metadata_error"));
        assertFalse(CameraHelperMain.HelperBinder.isMusicJournalEvent(
                "music_runtime_status"));
        assertFalse(CameraHelperMain.HelperBinder.isMusicJournalEvent(
                "music_journal_snapshot"));
    }

    @Test
    public void musicRuntimeContractStaysNarrowAndMediaOnly() {
        assertEquals("com.byd.mediacenter", MusicVisualizerRuntime.MEDIA_SELECTOR);
        assertEquals(3_000, MusicVisualizerRuntime.STOP_DEBOUNCE_MS);
        assertTrue(MusicVisualizerRuntime.isMusicAttributes(
                AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN));
        assertTrue(MusicVisualizerRuntime.isMusicAttributes(
                AudioAttributes.USAGE_UNKNOWN, AudioAttributes.CONTENT_TYPE_MUSIC));
        assertFalse(MusicVisualizerRuntime.isMusicAttributes(
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.CONTENT_TYPE_SONIFICATION));
        assertFalse(MusicVisualizerRuntime.isMediaPlayback(false,
                AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC));
        assertTrue(MusicVisualizerRuntime.shouldStartOutput(
                true, true, true, true, false));
        assertFalse(MusicVisualizerRuntime.shouldStartOutput(
                true, true, true, true, true));
        assertTrue(MusicVisualizerRuntime.shouldScheduleStop(true, false, false));
        assertFalse(MusicVisualizerRuntime.shouldScheduleStop(true, false, true));
        assertFalse(MusicVisualizerRuntime.shouldScheduleStop(true, true, false));
        assertEquals(3, MusicVisualizerRuntime.MAX_STOP_RETRIES);
        assertTrue(MusicVisualizerRuntime.shouldScheduleStopRetry(false, 0));
        assertFalse(MusicVisualizerRuntime.shouldScheduleStopRetry(false, 3));
        assertFalse(MusicVisualizerRuntime.shouldScheduleStopRetry(true, 0));
        assertTrue(MusicVisualizerRuntime.shouldCancelStopRetry(
                true, true, true, false));
        assertFalse(MusicVisualizerRuntime.shouldCancelStopRetry(
                true, true, true, true));
        assertFalse(MusicVisualizerRuntime.shouldCancelStopRetry(
                true, false, true, false));
        assertFalse(MusicVisualizerRuntime.hasMediaPlayback(null));
    }

    @Test
    public void musicMetadataBridgeUsesGenericSourceAndLeavesOemPlayersAlone() {
        assertEquals(26, MusicMetadataRuntime.SOURCE_THIRD_PARTY);
        assertEquals(750, MusicMetadataRuntime.PUBLISH_DEBOUNCE_MS);
        assertEquals(750, MusicMetadataRuntime.SESSION_REPAIR_DEBOUNCE_MS);
        assertEquals(750, MusicMetadataRuntime.publishDelayMs("sessions_callback"));
        assertEquals(750, MusicMetadataRuntime.publishDelayMs("metadata_callback"));
        assertTrue(MusicMetadataRuntime.isOemManagedPackage("com.byd.mediacenter"));
        assertTrue(MusicMetadataRuntime.isOemManagedPackage("com.android.bluetooth"));
        assertTrue(MusicMetadataRuntime.isOemManagedPackage("com.tencent.qqmusiccar"));
        assertFalse(MusicMetadataRuntime.isOemManagedPackage(
                "app.revanced.android.apps.youtube.music"));
        assertFalse(MusicMetadataRuntime.isOemManagedPackage("com.spotify.music"));
        assertFalse(MusicMetadataRuntime.isOemManagedPackage("ua.radioplayer.app"));
        assertFalse(MusicMetadataRuntime.shouldPublish(
                false, true, "same-track", "same-track"));
        assertTrue(MusicMetadataRuntime.shouldPublish(
                true, true, "same-track", "same-track"));
        assertTrue(MusicMetadataRuntime.shouldPublish(
                false, true, "old-track", "new-track"));
        assertFalse(MusicMetadataRuntime.shouldClaimSource(
                true, "com.spotify.music", "com.spotify.music"));
        assertTrue(MusicMetadataRuntime.shouldClaimSource(
                true, "com.spotify.music", "ua.radioplayer.app"));
        assertTrue(MusicMetadataRuntime.shouldRepairPublishedSource(
                "sessions_callback", true, "com.spotify.music", "com.spotify.music"));
        assertFalse(MusicMetadataRuntime.shouldRepairPublishedSource(
                "playback_callback", true, "com.spotify.music", "com.spotify.music"));
        assertTrue(MusicMetadataRuntime.shouldForceObserverRetry("configure"));
        assertFalse(MusicMetadataRuntime.shouldForceObserverRetry("wake"));
        assertTrue(MusicMetadataRuntime.shouldClaimSource(
                MusicMetadataRuntime.shouldForceObserverRetry("configure"),
                true, "com.spotify.music", "com.spotify.music"));
        boolean backgroundRefresh = MusicMetadataRuntime.coalesceForce(
                false, MusicMetadataRuntime.shouldForceObserverRetry("configure"));
        backgroundRefresh = MusicMetadataRuntime.coalesceForce(backgroundRefresh, false);
        assertTrue(MusicMetadataRuntime.shouldPublish(
                backgroundRefresh, true, "same-track", "same-track"));
        assertTrue(MusicMetadataRuntime.shouldClaimSource(
                backgroundRefresh, true, "com.spotify.music", "com.spotify.music"));
        backgroundRefresh = false;
        assertFalse(MusicMetadataRuntime.shouldPublish(
                backgroundRefresh, true, "same-track", "same-track"));
        assertTrue(MusicMetadataRuntime.shouldClaimSource(
                true, true, "com.spotify.music", "com.spotify.music"));
        assertEquals(2, CameraProbeActivity.CAMERA_PREVIEW_READY_FRAME_UPDATES);
        assertTrue(MusicMetadataRuntime.shouldRetainPublishedCard(true, false, false));
        assertTrue(MusicMetadataRuntime.shouldRetainPublishedCard(false, true, false));
        assertTrue(MusicMetadataRuntime.shouldRetainPublishedCard(false, false, true));
        assertFalse(MusicMetadataRuntime.shouldRetainPublishedCard(false, false, false));

        assertEquals(17, MusicMetadataRuntime.progressPercent(5_100, 30_000));
        assertEquals(0, MusicMetadataRuntime.progressPercent(5_100, 0));
        assertArrayEquals(new int[]{1, 1, 1, 2, 2, 2},
                MusicMetadataRuntime.timeline(3_661_000, 7_322_000));
        assertTrue(MusicMetadataRuntime.utf16Le("title").length <= 254);
        assertTrue(MusicMetadataRuntime.utf16Le("a".repeat(300)).length <= 254);
        assertEquals("AB", MusicMetadataRuntime.normalizeText("\uD835\uDC00\uFF22"));
        String emoji = new String(
                MusicMetadataRuntime.utf16Le("\uD83D\uDE80".repeat(100)),
                java.nio.charset.StandardCharsets.UTF_16LE);
        assertFalse(emoji.isEmpty());
        assertFalse(Character.isHighSurrogate(emoji.charAt(emoji.length() - 1)));
    }

    @Test
    public void foregroundAuthorizationWaitsForPermissionAndWindowFocus() {
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                true, false, true, true, false, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, false, false, true, false, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, false, true, false, false, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, false, true, true, true, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, false, true, true, false, true));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, true, true, true, false, false));
        assertTrue(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, false, true, true, false, false));
        assertEquals(600, CameraProbeActivity.ADB_AUTH_UI_SETTLE_MS);
        assertEquals(600, CameraProbeActivity.BACKGROUND_START_UI_SETTLE_MS);

        assertFalse(CameraProbeActivity.shouldOpenBackgroundStartSettings(
                true, true, true, true, false, false));
        assertFalse(CameraProbeActivity.shouldOpenBackgroundStartSettings(
                true, false, false, true, false, false));
        assertFalse(CameraProbeActivity.shouldOpenBackgroundStartSettings(
                false, false, true, true, false, false));
        assertFalse(CameraProbeActivity.shouldOpenBackgroundStartSettings(
                true, false, true, true, true, false));
        assertFalse(CameraProbeActivity.shouldOpenBackgroundStartSettings(
                true, false, true, true, false, true));
        assertTrue(CameraProbeActivity.shouldOpenBackgroundStartSettings(
                true, false, true, true, false, false));
    }

    @Test
    public void cameraRecoveryAndCalibrationWaitForRuntimeReadiness() {
        BlindSpotOverlayController.CameraRetryState retry =
                new BlindSpotOverlayController.CameraRetryState();
        assertTrue(retry.schedule("cold_open_failed"));
        assertFalse(retry.schedule("duplicate_failure"));
        assertTrue(retry.active());
        assertEquals("cold_open_failed", retry.consume());
        assertFalse(retry.active());
        assertTrue(retry.schedule("second_failure"));
        assertEquals("second_failure", retry.cancel());
        assertFalse(retry.active());

        assertEquals(null, BlindSpotOverlayController.cameraRetryBlockReason(
                false, true, false, true));
        assertEquals("overlay_disabled", BlindSpotOverlayController.cameraRetryBlockReason(
                false, false, false, true));
        assertEquals("overlay_hard_blocked", BlindSpotOverlayController.cameraRetryBlockReason(
                false, true, true, true));
        assertEquals("helper_unavailable", BlindSpotOverlayController.cameraRetryBlockReason(
                false, true, false, false));
        assertEquals("shutdown", BlindSpotOverlayController.cameraRetryBlockReason(
                true, true, false, true));
        assertEquals(BlindSpotOverlayController.PREPARATION_WAIT,
                BlindSpotOverlayController.preparationDecision(4, 3, 0));
        assertEquals(BlindSpotOverlayController.PREPARATION_OPEN,
                BlindSpotOverlayController.preparationDecision(4, 4, 0));
        assertEquals(BlindSpotOverlayController.PREPARATION_RETRY,
                BlindSpotOverlayController.preparationDecision(4, 1, 1));
        assertEquals(BlindSpotOverlayController.PREPARATION_RETRY,
                BlindSpotOverlayController.preparationDecision(0, 0, 0));
        assertTrue(BlindSpotOverlayController.shouldRebuildAfterCameraDiscovery(false));
        assertFalse(BlindSpotOverlayController.shouldRebuildAfterCameraDiscovery(true));

        assertTrue(CameraProbeActivity.shouldRetryCalibrationCopy(true, 0, 720));
        assertTrue(CameraProbeActivity.shouldRetryCalibrationCopy(true, 1280, 0));
        assertFalse(CameraProbeActivity.shouldRetryCalibrationCopy(true, 1280, 720));
        assertFalse(CameraProbeActivity.shouldRetryCalibrationCopy(false, 0, 0));
        assertTrue(CameraProbeActivity.isOverlayCameraEvent(
                "camera_error", CameraHelperMain.CAMERA_OWNER_OVERLAY));
        assertTrue(CameraProbeActivity.isOverlayCameraEvent(
                "camera_opened", CameraHelperMain.CAMERA_OWNER_OVERLAY));
        assertTrue(CameraProbeActivity.isOverlayCameraEvent(
                "camera_closed", CameraHelperMain.CAMERA_OWNER_REVERSE));
        assertFalse(CameraProbeActivity.isOverlayCameraEvent(
                "camera_error", CameraHelperMain.CAMERA_OWNER_ACTIVITY));
        assertFalse(CameraProbeActivity.isOverlayCameraEvent(
                "camera_discovery", CameraHelperMain.CAMERA_OWNER_OVERLAY));
        String coldReset = "activity_transition:3:activity_resume_cold_reset";
        assertTrue(CameraProbeActivity.isExpectedOverlayColdResetClose(
                coldReset, coldReset));
        assertFalse(CameraProbeActivity.isExpectedOverlayColdResetClose(
                coldReset, "rmPreviewSurface returned false"));
        assertFalse(CameraProbeActivity.isExpectedOverlayColdResetClose(
                "raw_source_failed", "raw_source_failed"));
        assertTrue(CameraProbeActivity.shouldRearmStockSurfaceRecovery(
                "camera_opened", "stock_avm_shell"));
        assertFalse(CameraProbeActivity.shouldRearmStockSurfaceRecovery(
                "camera_error", "stock_avm_shell"));
        assertFalse(CameraProbeActivity.shouldRearmStockSurfaceRecovery(
                "camera_opened", "direct_blind_spot"));
    }

    @Test
    public void authorizationFailureKeepsRejectedAndTimeoutDistinct() {
        LocalAdbClient.Result rejected = LocalAdbClient.Result.authorizationRequired(
                "authorization_rejected", true, "fingerprint");
        LocalAdbClient.Result timeout = LocalAdbClient.Result.authorizationRequired(
                "authorization_prompt_timeout", true, "fingerprint");
        LocalAdbClient.Result required = LocalAdbClient.Result.authorizationRequired(
                "authorization_required", false, "fingerprint");

        assertEquals("authorization_rejected", rejected.error);
        assertEquals("authorization_prompt_timeout", timeout.error);
        assertEquals("authorization_required", required.error);
        assertTrue(rejected.publicKeySent);
        assertTrue(timeout.publicKeySent);
        assertFalse(required.publicKeySent);
    }

    @Test
    public void guardSafetyMappingsStillPass() {
        TurnSignalGuardRuntime.selfCheckForTest();
        assertFalse(TurnSignalGuardRuntime.guardConfigurationChanged(
                true, 90.0f, 10.0f, 0, 30,
                true, 90.0f, 10.0f, 0, 30));
        assertTrue(TurnSignalGuardRuntime.guardConfigurationChanged(
                true, 90.0f, 10.0f, 0, 30,
                true, 90.0f, 10.0f, 250, 30));
        assertTrue(TurnSignalGuardRuntime.guardConfigurationChanged(
                true, 90.0f, 10.0f, 100, 30,
                true, 90.0f, 10.0f, 100, 20));
        assertFalse(TurnSignalGuardRuntime.shouldNeutralizePrematureOff(
                true, true, true, 1, false));
        assertTrue(TurnSignalGuardRuntime.shouldNeutralizePrematureOff(
                false, true, true, 1, false));
        assertFalse(TurnSignalGuardRuntime.shouldSuppressDirectionChange(2, 4, false));
        assertFalse(TurnSignalGuardRuntime.shouldSuppressDirectionChange(4, 4, true));
        assertTrue(TurnSignalGuardRuntime.shouldSuppressDirectionChange(2, 4, true));
        assertTrue(TurnSignalGuardRuntime.safeGuardDisableResetBlink(1));
        assertTrue(TurnSignalGuardRuntime.safeGuardDisableResetBlink(2));
        assertTrue(TurnSignalGuardRuntime.safeGuardDisableResetBlink(4));
        assertFalse(TurnSignalGuardRuntime.safeGuardDisableResetBlink(6));
        assertFalse(TurnSignalGuardRuntime.safeGuardDisableResetBlink(7));
        assertTrue(TurnSignalGuardRuntime.validMaxSpeed(0));
        assertTrue(TurnSignalGuardRuntime.validMaxSpeed(300));
        assertFalse(TurnSignalGuardRuntime.validMaxSpeed(301));
        assertTrue(TurnSignalGuardRuntime.speedAllowed(0.0f, 0));
        assertTrue(TurnSignalGuardRuntime.speedAllowed(30.0f, 30));
        assertFalse(TurnSignalGuardRuntime.speedAllowed(30.01f, 30));
        assertFalse(TurnSignalGuardRuntime.speedAllowed(Float.NaN, 30));
        assertFalse(TurnSignalGuardRuntime.pollFresh(1_000L, 0L));
        assertFalse(TurnSignalGuardRuntime.pollFresh(1_000L, 1_001L));
        assertTrue(TurnSignalGuardRuntime.pollFresh(1_000L, 750L));
        assertFalse(TurnSignalGuardRuntime.pollFresh(1_001L, 750L));
        assertEquals("payload_not_whitelisted",
                TurnSignalGuardRuntime.manualPrecheckReason(-1, false, false, 1_000L, 900L));
        assertEquals("command_already_pending",
                TurnSignalGuardRuntime.manualPrecheckReason(0, true, false, 1_000L, 900L));
        assertEquals("disable_guard_before_manual_command",
                TurnSignalGuardRuntime.manualPrecheckReason(0, false, true, 1_000L, 900L));
        assertEquals("stale_poll",
                TurnSignalGuardRuntime.manualPrecheckReason(0, false, false, 1_001L, 750L));
        assertEquals(null,
                TurnSignalGuardRuntime.manualPrecheckReason(0, false, false, 1_000L, 750L));
        assertEquals(null, TurnSignalGuardRuntime.manualTelemetryRejectionReason(
                0, true, 0, 1, 0, 6));
        assertEquals(null, TurnSignalGuardRuntime.manualTelemetryRejectionReason(
                2, true, 0, 1, 0, 1));
        assertEquals("requires_healthy_telemetry_P_and_safe_blink_state",
                TurnSignalGuardRuntime.manualTelemetryRejectionReason(
                        2, true, 0, 1, 0, 2));
        assertEquals("requires_healthy_telemetry_P_and_safe_blink_state",
                TurnSignalGuardRuntime.manualTelemetryRejectionReason(
                        0, false, 0, 1, 0, 1));
        assertEquals(871366669, TurnSignalGuardRuntime.turnSignalSetFidForTest());
        assertTrue(TurnSignalGuardRuntime.canRunSpeedLimitCleanup(
                true, true, true, 1, 1));
        assertFalse(TurnSignalGuardRuntime.canRunSpeedLimitCleanup(
                true, true, true, 1, 2));
        assertFalse(TurnSignalGuardRuntime.canRunSpeedLimitCleanup(
                true, true, true, 6, 1));
        assertFalse(TurnSignalGuardRuntime.canRunSpeedLimitCleanup(
                true, true, false, 1, 1));
        assertTrue(TurnSignalGuardRuntime.canResumeSpeedDeferredSession(
                4, true, true, true, 30.0f, 30, 4));
        assertFalse(TurnSignalGuardRuntime.canResumeSpeedDeferredSession(
                4, true, true, true, 31.0f, 30, 4));
        assertFalse(TurnSignalGuardRuntime.canResumeSpeedDeferredSession(
                4, false, true, true, 20.0f, 30, 4));
        assertFalse(TurnSignalGuardRuntime.canResumeSpeedDeferredSession(
                4, true, true, true, 20.0f, 30, 2));
        assertFalse(TurnSignalGuardRuntime.shouldCancelSpeedDeferredSession(4, false, 1));
        assertFalse(TurnSignalGuardRuntime.shouldCancelSpeedDeferredSession(4, false, 2));
        assertTrue(TurnSignalGuardRuntime.shouldCancelSpeedDeferredSession(4, false, 6));
        assertTrue(TurnSignalGuardRuntime.shouldCancelSpeedDeferredSession(4, true, 1));
        assertFalse(TurnSignalGuardRuntime.shouldCancelSpeedDeferredSession(4, true, 4));
        assertEquals("manual_reset",
                TurnSignalGuardRuntime.manualConfirmationOperation(0));
        assertEquals("direction_activation_not_observed",
                TurnSignalGuardRuntime.confirmationTimeoutClassification(2, 1));
        assertEquals("hazard_off_transition_not_observed",
                TurnSignalGuardRuntime.confirmationTimeoutClassification(0, 6));
        assertEquals("accepted_no_observable_transition",
                TurnSignalGuardRuntime.confirmationSuccessClassification(0, 1));
        assertEquals("activation_count",
                CameraHelperMain.lifetimeCounterKey("driver_activation"));
        assertEquals("correction_count",
                CameraHelperMain.lifetimeCounterKey("correction_confirmed"));
        assertEquals(null, CameraHelperMain.lifetimeCounterKey("telemetry_sample"));
        assertTrue(CameraHelperMain.isAllowedDirectCameraTag("pano_h"));
        assertTrue(CameraHelperMain.isAllowedDirectCameraTag("byd_apa"));
        assertFalse(CameraHelperMain.isAllowedDirectCameraTag("front"));
        assertFalse(CameraHelperMain.isAllowedDirectCameraTag(null));
        assertTrue(StockAvmPreview.isAllowedViewpoint(2018));
        assertTrue(StockAvmPreview.isAllowedViewpoint(2031));
        assertTrue(StockAvmPreview.isAllowedViewpoint(2033));
        assertFalse(StockAvmPreview.isAllowedViewpoint(2032));
        assertEquals("rear_left_2031", StockAvmPreview.viewName(2031));
        assertEquals("VIEW_2D_REAR_WHEELS", StockAvmPreview.layoutName(2018));
        assertEquals("VIEW_2D_REAR_WHEELS", StockAvmPreview.layoutName(2031));
        assertEquals("VIEW_2D_REAR_WHEELS", StockAvmPreview.layoutName(2033));
        assertEquals(51, StockAvmPreview.horizontalLayoutCount());
        java.util.Set<String> layouts = new java.util.HashSet<>();
        for (int i = 0; i < StockAvmPreview.horizontalLayoutCount(); i++) {
            int viewpoint = StockAvmPreview.horizontalViewpoint(i);
            assertTrue(StockAvmPreview.isAllowedViewpoint(viewpoint));
            assertTrue(layouts.add(StockAvmPreview.horizontalLayoutName(i)));
        }
        assertEquals("VIEW_2D_TOP", StockAvmPreview.horizontalLayoutName(0));
        assertFalse(StockAvmPreview.usesTopCameraTileCrop(
                StockAvmPreview.horizontalViewpoint(7)));
        assertTrue(StockAvmPreview.usesTopCameraTileCrop(
                StockAvmPreview.horizontalViewpoint(8)));
        assertTrue(StockAvmPreview.usesTopCameraTileCrop(
                StockAvmPreview.horizontalViewpoint(9)));
        assertEquals(StockAvmPreview.horizontalViewpoint(8),
                StockAvmPreview.VIEW_BLIND_SPOT_LEFT);
        assertEquals(StockAvmPreview.horizontalViewpoint(9),
                StockAvmPreview.VIEW_BLIND_SPOT_RIGHT);
        assertFalse(StockAvmPreview.usesTopCameraTileCrop(
                StockAvmPreview.horizontalViewpoint(10)));
        assertFalse(StockAvmPreview.usesTopCameraTileCrop(
                StockAvmPreview.VIEW_REAR_LEFT));
        assertEquals("VIEW_2D_RIGHT_CLAIRVOYANCE",
                StockAvmPreview.horizontalLayoutName(50));
        assertEquals("rear_left_clairvoyance_test", StockAvmPreview.viewName(
                StockAvmPreview.VIEW_REAR_LEFT_CLAIRVOYANCE));
        assertEquals(0.0f, StockAvmPreview.focusedTileStartX(
                StockAvmPreview.VIEW_REAR_LEFT_CLAIRVOYANCE), 0.0f);
        assertEquals(StockAvmPreview.NORMAL_CAMERA_TILE_START_X,
                StockAvmPreview.focusedTileStartX(
                        StockAvmPreview.VIEW_BLIND_SPOT_LEFT), 0.0f);
        assertEquals(0.0f, StockAvmPreview.focusedTileStartX(
                StockAvmPreview.horizontalViewpoint(0)), 0.0f);
        assertFalse(StockAvmPreview.isAllowedViewpoint(9_999));
        assertFalse(StockAvmPreview.isAllowedViewpoint(10_051));
        assertFalse(StockAvmPreview.isDisplayReadyStatus("Initialized"));
        assertTrue(StockAvmPreview.isDisplayReadyStatus("Configured"));
        assertTrue(StockAvmPreview.isDisplayReadyStatus("Started"));
        assertFalse(BlindSpotWarningRuntime.isValidRaw(-1));
        assertTrue(BlindSpotWarningRuntime.isValidRaw(0));
        assertTrue(BlindSpotWarningRuntime.isValidRaw(1));
        assertTrue(BlindSpotWarningRuntime.isValidRaw(2));
        assertFalse(BlindSpotWarningRuntime.isValidRaw(3));
        assertFalse(BlindSpotWarningRuntime.isActiveRaw(false, 2));
        assertFalse(BlindSpotWarningRuntime.isActiveRaw(true, 1));
        assertTrue(BlindSpotWarningRuntime.isActiveRaw(true, 2));
        assertEquals(CameraShellProtocol.WARNING_MODE_PULSE,
                BlindSpotOverlayController.normalizeWarningMode(false, 99));
        assertEquals(CameraShellProtocol.WARNING_MODE_CONSTANT,
                BlindSpotOverlayController.normalizeWarningMode(
                        true, CameraShellProtocol.WARNING_MODE_CONSTANT));
        assertEquals(CameraShellProtocol.WARNING_MODE_OFF,
                BlindSpotOverlayController.normalizeWarningMode(true, 99));
        assertEquals(CameraShellProtocol.WARNING_EDGE_NONE,
                BlindSpotOverlayController.warningEdge(
                        CameraShellProtocol.WARNING_MODE_OFF, true, 2,
                        true, 2, true, 2));
        assertEquals(CameraShellProtocol.WARNING_EDGE_NONE,
                BlindSpotOverlayController.warningEdge(
                        CameraShellProtocol.WARNING_MODE_PULSE, false, 2,
                        true, 2, true, 2));
        assertEquals(CameraShellProtocol.WARNING_EDGE_LEFT,
                BlindSpotOverlayController.warningEdge(
                        CameraShellProtocol.WARNING_MODE_PULSE, true, 2,
                        true, 2, true, 0));
        assertEquals(CameraShellProtocol.WARNING_EDGE_RIGHT,
                BlindSpotOverlayController.warningEdge(
                        CameraShellProtocol.WARNING_MODE_CONSTANT, true, 4,
                        true, 0, true, 2));
        assertEquals(CameraShellProtocol.WARNING_EDGE_NONE,
                BlindSpotOverlayController.warningEdge(
                        CameraShellProtocol.WARNING_MODE_PULSE, true, 2,
                        true, 0, true, 2));
        assertEquals(CameraShellProtocol.WARNING_EDGE_NONE,
                BlindSpotOverlayController.warningEdge(
                        CameraShellProtocol.WARNING_MODE_PULSE, true, 4,
                        true, 0, true, 1));
        assertFalse(ShellCameraOverlay.isFramePastStaleBuffer(1));
        assertTrue(ShellCameraOverlay.isFramePastStaleBuffer(2));
        assertTrue(CameraProbeActivity.isIntermediateCameraClose("preview_handoff"));
        assertTrue(CameraProbeActivity.isIntermediateCameraClose("replace_preview"));
        assertTrue(CameraProbeActivity.isIntermediateCameraClose("replace_with_stock_avm"));
        assertFalse(CameraProbeActivity.isIntermediateCameraClose(
                "replace_with_multi_preview"));
        assertFalse(CameraProbeActivity.isIntermediateCameraClose("user_close"));
        assertTrue(CameraProbeActivity.isInvalidStockSurfaceError(
                "stock_avm_shell", "get_camera_input_surface",
                "IllegalStateException: AVM input Surface is invalid"));
        assertFalse(CameraProbeActivity.isInvalidStockSurfaceError(
                "direct_avm", "open", "Surface is invalid"));
        assertEquals(0.0f, BlindSpotOverlayController.legacyPosition(6, false), 0.0f);
        assertEquals(1.0f, BlindSpotOverlayController.legacyPosition(6, true), 0.0f);
        assertEquals(0, BlindSpotOverlayController.DEFAULT_LEFT_POSITION);
        assertEquals(0.0f, BlindSpotOverlayController.legacyPosition(
                BlindSpotOverlayController.DEFAULT_LEFT_POSITION, false), 0.0f);
        assertEquals(0.0f, BlindSpotOverlayController.legacyPosition(
                BlindSpotOverlayController.DEFAULT_LEFT_POSITION, true), 0.0f);
        assertEquals(2, BlindSpotOverlayController.DEFAULT_RIGHT_POSITION);
        assertEquals(0.0f, BlindSpotOverlayController.legacyPosition(
                BlindSpotOverlayController.DEFAULT_RIGHT_POSITION, true), 0.0f);
        assertEquals(1.0f, BlindSpotOverlayController.legacyPosition(
                BlindSpotOverlayController.DEFAULT_RIGHT_POSITION, false), 0.0f);
        assertEquals(1.0f, BlindSpotOverlayController.legacyPosition(8, false), 0.0f);
        assertEquals(1.0f, BlindSpotOverlayController.legacyPosition(8, true), 0.0f);
        assertArrayEquals(new int[]{889, 500},
                BlindSpotOverlayController.fitAspect(1000, 1000, 500, 16.0f / 9.0f));
        assertArrayEquals(new int[]{500, 500},
                BlindSpotOverlayController.fitAspect(1000, 1000, 500, 1.0f));
        assertTrue(CameraHelperService.shouldResumeOverlay(false, false));
        assertFalse(CameraHelperService.shouldResumeOverlay(true, false));
        assertFalse(CameraHelperService.shouldResumeOverlay(false, true));
        assertTrue(CameraShellProtocol.isCallerAllowed(10058, 10058));
        assertFalse(CameraShellProtocol.isCallerAllowed(2000, 10058));
        String cameraLaunch = TurnSignalController.cameraLaunchCommand(
                "/data/app/a'b/base.apk", 10058, 5);
        assertTrue(cameraLaunch.contains("bydturnguard_camera"));
        assertTrue(cameraLaunch.contains("CameraShellMain 10058 5"));
        assertTrue(cameraLaunch.contains("setsid app_process /system/bin"));
        assertTrue(cameraLaunch.contains("camera_helper_stop_timeout"));
        assertFalse(cameraLaunch.contains("TurnSignalShellMain"));
    }

    @Test
    public void dewarpStatsExposeBoundedAggregateUnits() {
        CameraDewarpRenderer.Stats stats = new CameraDewarpRenderer.Stats(
                2_000_000_000L,
                12,
                9,
                10,
                20_000_000L,
                5_000_000L,
                9_000_000L,
                7_000_000L,
                10_000_000L,
                6_000_000L,
                30_000_000L,
                9_000_000L,
                12_000_000L,
                4,
                6,
                15_000_000L,
                4_000_000L,
                25_000_000L,
                8_000_000L,
                7_000_000L,
                11_000_000L,
                13_000_000L,
                2,
                3,
                99,
                7,
                4,
                CameraProfile.REAR_LEFT,
                1920,
                990,
                1280,
                660,
                new CameraDewarpRenderer.MappingRequest(
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT), 44L));

        assertEquals(2_000L, stats.intervalMs);
        assertEquals(12, stats.callbacks);
        assertEquals(9, stats.updateSamples);
        assertEquals(6.0d, stats.callbackFps, 0.001d);
        assertEquals(10, stats.completedSwaps);
        assertEquals(5.0d, stats.completedSwapFps, 0.001d);
        assertEquals(2.0d, stats.averageRenderMs, 0.001d);
        assertEquals(5.0d, stats.maxRenderMs, 0.001d);
        assertEquals(1.0d, stats.averageUpdateTexImageMs, 0.001d);
        assertEquals(7.0d, stats.maxUpdateTexImageMs, 0.001d);
        assertEquals(1.0d, stats.averagePreSwapMs, 0.001d);
        assertEquals(6.0d, stats.maxPreSwapMs, 0.001d);
        assertEquals(3.0d, stats.averageSwapWaitMs, 0.001d);
        assertEquals(9.0d, stats.maxSwapWaitMs, 0.001d);
        assertEquals(12.0d, stats.maxSwapMs, 0.001d);
        assertEquals(4, stats.rawMirrorSwaps);
        assertEquals(6, stats.correctedMirrorSwaps);
        assertEquals(7.0d, stats.maxCallbackGapMs, 0.001d);
        assertEquals(11.0d, stats.lastFrameAgeMs, 0.001d);
        assertEquals(13.0d, stats.maxFrameAgeMs, 0.001d);
        assertEquals(2, stats.processMaxConcurrentRenders);
        assertEquals(3, stats.processActiveRenderers);
        assertEquals(99, stats.rendererId);
        assertEquals(7, stats.requestId);
        assertEquals(4, stats.inputGeneration);
        assertEquals(CameraProfile.REAR_LEFT, stats.contextGeneration);
        assertEquals(1920, stats.bufferWidth);
        assertEquals(990, stats.bufferHeight);
        assertEquals(1280, stats.viewWidth);
        assertEquals(660, stats.viewHeight);
    }

    @Test
    public void dewarpStatsWindowRejectsEveryIdentityTransition() {
        CameraDewarpRenderer.MappingRequest first =
                new CameraDewarpRenderer.MappingRequest(
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT), 1L);
        CameraDewarpRenderer.MappingRequest second =
                new CameraDewarpRenderer.MappingRequest(
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT), 2L);
        assertTrue(CameraDewarpRenderer.sameStatsWindow(
                first, first, 7, 7, 4, 4, 1280, 1280, 660, 660));
        assertFalse(CameraDewarpRenderer.sameStatsWindow(
                first, second, 7, 7, 4, 4, 1280, 1280, 660, 660));
        assertFalse(CameraDewarpRenderer.sameStatsWindow(
                first, first, 7, 8, 4, 4, 1280, 1280, 660, 660));
        assertFalse(CameraDewarpRenderer.sameStatsWindow(
                first, first, 7, 7, 4, 5, 1280, 1280, 660, 660));
        assertFalse(CameraDewarpRenderer.sameStatsWindow(
                first, first, 7, 7, 4, 4, 1280, 1279, 660, 660));
        assertFalse(CameraDewarpRenderer.sameStatsWindow(
                first, first, 7, 7, 4, 4, 1280, 1280, 660, 659));
    }

    @Test
    public void staleCameraHelperDeathDoesNotMatchReplacementBinder() {
        IBinder first = proxyBinder();
        IBinder replacement = proxyBinder();
        assertTrue(TurnSignalController.matchesExpectedCameraHelper(first, null));
        assertTrue(TurnSignalController.matchesExpectedCameraHelper(first, first));
        assertFalse(TurnSignalController.matchesExpectedCameraHelper(replacement, first));
        assertTrue(TurnSignalController.matchesExpectedCameraHelper(
                first, 2, first, 2));
        assertFalse(TurnSignalController.matchesExpectedCameraHelper(
                first, 3, first, 2));
        assertFalse(TurnSignalController.matchesExpectedCameraHelper(
                replacement, 2, first, 2));
        assertTrue(TurnSignalController.isCurrentCameraShellEpoch(2, 2));
        assertFalse(TurnSignalController.isCurrentCameraShellEpoch(2, 1));
        assertFalse(TurnSignalController.isCurrentCameraShellEpoch(2, 0));
        assertTrue(CameraHelperService.shouldReopenLog(true, false));
        assertFalse(CameraHelperService.shouldReopenLog(true, true));
        assertFalse(CameraHelperService.shouldReopenLog(false, false));
    }

    @Test
    public void cameraOpenCallbacksMustMatchCurrentSession() {
        assertTrue(BlindSpotOverlayController.matchesCameraOpenEvent(true, 7, 7));
        assertFalse(BlindSpotOverlayController.matchesCameraOpenEvent(false, 7, 7));
        assertFalse(BlindSpotOverlayController.matchesCameraOpenEvent(true, 8, 7));
        assertFalse(BlindSpotOverlayController.matchesCameraOpenEvent(true, 0, 0));

        assertTrue(ReverseCameraController.matchesCameraOpenEvent(9, 9));
        assertFalse(ReverseCameraController.matchesCameraOpenEvent(10, 9));
        assertFalse(ReverseCameraController.matchesCameraOpenEvent(0, 0));

        assertTrue(CameraProbeActivity.shouldRecoverActivityCamera(true, false, false));
        assertTrue(CameraProbeActivity.shouldRecoverActivityCamera(false, true, false));
        assertTrue(CameraProbeActivity.shouldRecoverActivityCamera(false, false, true));
        assertFalse(CameraProbeActivity.shouldRecoverActivityCamera(false, false, false));
        assertFalse(CameraProbeActivity.shouldInvalidateActivityForCameraShellDeath(
                false, false, false, false));
        assertTrue(CameraProbeActivity.shouldInvalidateActivityForCameraShellDeath(
                true, false, false, false));
        assertTrue(CameraProbeActivity.shouldInvalidateActivityForCameraShellDeath(
                false, true, false, false));
        assertTrue(CameraProbeActivity.shouldInvalidateActivityForCameraShellDeath(
                false, false, true, false));
        assertTrue(CameraProbeActivity.shouldInvalidateActivityForCameraShellDeath(
                false, false, false, true));
        assertTrue(CameraProbeActivity.shouldResumeActivityCameraRecovery(true, true, true));
        assertFalse(CameraProbeActivity.shouldResumeActivityCameraRecovery(true, false, true));
        assertFalse(CameraProbeActivity.shouldResumeActivityCameraRecovery(true, true, false));
        assertTrue(CameraProbeActivity.isCurrentActivityCameraEvent(
                true, 12, 12, "helper"));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraEvent(
                true, 13, 12, "helper"));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraEvent(
                true, 12, 12, "camera_shell_helper"));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraEvent(
                false, 12, 12, "helper"));
        assertTrue(CameraHelperMain.HelperBinder.matchesPendingCameraRequest(21, 21));
        assertFalse(CameraHelperMain.HelperBinder.matchesPendingCameraRequest(22, 21));
        assertTrue(CameraHelperMain.HelperBinder.matchesCurrentStockRequest(
                true, 31, 0, 31));
        assertTrue(CameraHelperMain.HelperBinder.matchesCurrentStockRequest(
                true, 0, 32, 32));
        assertFalse(CameraHelperMain.HelperBinder.matchesCurrentStockRequest(
                true, 34, 33, 33));
        assertTrue(CameraHelperMain.HelperBinder.matchesCurrentStockRequest(
                true, 34, 33, 34));
        assertFalse(CameraHelperMain.HelperBinder.matchesCurrentStockRequest(
                true, 33, 0, 32));
        assertFalse(CameraHelperMain.HelperBinder.matchesCurrentStockRequest(
                false, 33, 33, 33));
        assertEquals(41, CameraHelperMain.HelperBinder.cameraRequestIdForClose(
                true, 41, 0));
        assertEquals(42, CameraHelperMain.HelperBinder.cameraRequestIdForClose(
                false, 41, 42));
        assertTrue(TurnSignalController.shouldQueueCameraRecovery(false, false));
        assertFalse(TurnSignalController.shouldQueueCameraRecovery(false, true));
        assertFalse(TurnSignalController.shouldQueueCameraRecovery(true, false));
    }

    @Test
    public void reverseCleanupRequiresExactSuccessfulCameraCloseResult() {
        assertTrue(CameraHelperMain.HelperBinder.isSuccessfulCameraClose(
                "camera_closed", ""));
        assertTrue(CameraHelperMain.HelperBinder.isSuccessfulCameraClose(
                "already_closed", ""));
        assertFalse(CameraHelperMain.HelperBinder.isSuccessfulCameraClose(
                "camera_closed", "close failed"));
        assertFalse(CameraHelperMain.HelperBinder.isSuccessfulCameraClose(
                "camera_close_ignored", ""));
        assertFalse(CameraHelperMain.HelperBinder.isSuccessfulCameraClose(
                "camera_closed", null));
        assertFalse(CameraHelperMain.HelperBinder.isSuccessfulCameraCloseResult(null));

        // A failed first close is retried for the same request; an idempotent
        // already_closed response then confirms cleanup.
        assertFalse(CameraHelperMain.HelperBinder.isSuccessfulCameraClose(
                "camera_closed", "vendor close failed"));
        assertTrue(CameraHelperMain.HelperBinder.isSuccessfulCameraClose(
                "already_closed", ""));
    }

    private static IBinder proxyBinder() {
        return (IBinder) Proxy.newProxyInstance(
                AdbCoreTest.class.getClassLoader(), new Class<?>[]{IBinder.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    @Test
    public void directCameraCropKeepsFourThreeAndSourceBounds() {
        DirectCameraCrop left = DirectCameraCrop.defaultFor(false);
        DirectCameraCrop right = DirectCameraCrop.defaultFor(true);
        assertEquals(0.0f, left.left, 0.0001f);
        assertEquals(0.35f, right.left, 0.0001f);
        assertEquals(DirectCameraCrop.OUTPUT_ASPECT,
                left.width * DirectCameraCrop.SOURCE_WIDTH
                        / (left.height * DirectCameraCrop.SOURCE_HEIGHT),
                0.0001f);
        assertEquals(0, left.rotationDegrees);
        assertEquals(CameraRotation.MODE_FIT, left.rotationMode);

        DirectCameraCrop moved = left.move(-2.0f, 2.0f);
        assertEquals(0.0f, moved.left, 0.0001f);
        assertEquals(1.0f, moved.bottom(), 0.0001f);
        DirectCameraCrop rotated = moved.withRotation(37).resize(
                DirectCameraCrop.EDGE_RIGHT, 0.1f, 0.0f);
        assertEquals(37, rotated.rotationDegrees);
        assertEquals(180, rotated.withRotation(999).rotationDegrees);

        DirectCameraCrop resized = right.resize(
                DirectCameraCrop.EDGE_LEFT | DirectCameraCrop.EDGE_TOP,
                -2.0f, -2.0f);
        assertTrue(resized.left >= 0.0f);
        assertTrue(resized.top >= 0.0f);
        assertTrue(resized.right() <= 1.0f);
        assertTrue(resized.bottom() <= 1.0f);
        assertEquals(DirectCameraCrop.OUTPUT_ASPECT,
                resized.width * DirectCameraCrop.SOURCE_WIDTH
                        / (resized.height * DirectCameraCrop.SOURCE_HEIGHT),
                0.0001f);

        DirectCameraCrop wide = left.withAspectMode(DirectCameraCrop.ASPECT_SIXTEEN_NINE);
        assertEquals(16.0f / 9.0f, wide.outputAspect(), 0.0001f);
        DirectCameraCrop square = left.withAspectMode(DirectCameraCrop.ASPECT_ONE_ONE);
        assertEquals(1.0f, square.outputAspect(), 0.0001f);
        DirectCameraCrop portrait = left.withRotation(90);
        assertEquals(4.0f / 3.0f, portrait.outputAspect(), 0.0001f);

        DirectCameraCrop aligned = left.withRotation(45)
                .withRotationMode(CameraRotation.MODE_ALIGNED);
        assertEquals(CameraRotation.MODE_ALIGNED, aligned.rotationMode);
        double radians = Math.toRadians(aligned.rotationDegrees);
        double extentX = Math.abs(Math.cos(radians)) * aligned.width / 2.0d
                + Math.abs(Math.sin(radians)) * aligned.height
                * DirectCameraCrop.SOURCE_HEIGHT / DirectCameraCrop.SOURCE_WIDTH / 2.0d;
        double extentY = Math.abs(Math.sin(radians)) * aligned.width
                * DirectCameraCrop.SOURCE_WIDTH / DirectCameraCrop.SOURCE_HEIGHT / 2.0d
                + Math.abs(Math.cos(radians)) * aligned.height / 2.0d;
        assertTrue(aligned.left + aligned.width / 2.0f - extentX >= -0.0001d);
        assertTrue(aligned.left + aligned.width / 2.0f + extentX <= 1.0001d);
        assertTrue(aligned.top + aligned.height / 2.0f - extentY >= -0.0001d);
        assertTrue(aligned.top + aligned.height / 2.0f + extentY <= 1.0001d);

        DirectCameraCrop free = wide.withAspectMode(DirectCameraCrop.ASPECT_FREE);
        float originalHeight = free.height;
        free = free.resize(DirectCameraCrop.EDGE_RIGHT, 0.1f, 0.0f);
        assertEquals(DirectCameraCrop.ASPECT_FREE, free.aspectMode);
        assertEquals(originalHeight, free.height, 0.0001f);
        assertTrue(free.right() <= 1.0f);
        free = free.resize(DirectCameraCrop.EDGE_BOTTOM, 0.0f, 2.0f);
        assertEquals(1.0f, free.bottom(), 0.0001f);
    }

    @Test
    public void productionPreviewRetriesOnlyForCurrentFrameWait() {
        assertTrue(CameraProbeActivity.shouldRetryProductionPreviewFrame(
                true, true, true, true));
        assertFalse(CameraProbeActivity.shouldRetryProductionPreviewFrame(
                false, true, true, true));
        assertFalse(CameraProbeActivity.shouldRetryProductionPreviewFrame(
                true, false, true, true));
        assertFalse(CameraProbeActivity.shouldRetryProductionPreviewFrame(
                true, true, false, true));
        assertFalse(CameraProbeActivity.shouldRetryProductionPreviewFrame(
                true, true, true, false));
    }

    @Test
    public void clusterDisplaySettingsAndGeometryStayDeterministic() {
        assertEquals(0, CameraDisplayTarget.clusterNameRank(
                "shared_fission_bg_XDJAScreenProjection_1"));
        assertEquals(1, CameraDisplayTarget.clusterNameRank(
                "shared_fission_bg_XDJAScreenProjection_0"));
        assertEquals(Integer.MAX_VALUE,
                CameraDisplayTarget.clusterNameRank("XDJAScreenProjection_aux"));
        assertEquals(Integer.MAX_VALUE, CameraDisplayTarget.clusterNameRank("Built-in Screen"));
        assertTrue(CameraDisplayTarget.isValid(CameraDisplayTarget.TABLET));
        assertTrue(CameraDisplayTarget.isValid(CameraDisplayTarget.CLUSTER));
        assertFalse(CameraDisplayTarget.isValid(4));

        assertEquals(36, BlindSpotOverlayController.migratedScale(false, 50, 36));
        assertEquals(50, BlindSpotOverlayController.migratedScale(true, 50, 36));
        assertEquals(20, BlindSpotOverlayController.migratedScale(false, 0, 2));
        assertEquals(60, BlindSpotOverlayController.migratedScale(true, 90, 36));
        assertEquals(30, BlindSpotOverlayController.DEFAULT_SCALE_PERCENT);
        assertEquals(10, BlindSpotOverlayController.DEFAULT_CORNER_RADIUS_DP);
        assertEquals(0.0f, BlindSpotOverlayController.defaultPosition(
                CameraProfile.of(CameraProfile.REAR_LEFT), true), 0.0f);
        assertEquals(1.0f, BlindSpotOverlayController.defaultPosition(
                CameraProfile.of(CameraProfile.FRONT_LEFT), true), 0.0f);

        assertArrayEquals(new int[]{16, 36, 691, 518},
                BlindSpotOverlayController.overlayGeometry(
                        1920, 1080, 36, 4.0f / 3.0f,
                        0.0f, 0.0f, 16, 36, 88));
        assertArrayEquals(new int[]{1213, 474, 691, 518},
                BlindSpotOverlayController.overlayGeometry(
                        1920, 1080, 36, 4.0f / 3.0f,
                        1.0f, 1.0f, 16, 36, 88));
        assertArrayEquals(new int[]{0, 0, 691, 518},
                BlindSpotOverlayController.overlayGeometry(
                        1920, 720, 36, 4.0f / 3.0f,
                        0.0f, 0.0f, 0, 0, 0));
        assertArrayEquals(new int[]{1229, 331, 691, 389},
                BlindSpotOverlayController.overlayGeometry(
                        1920, 720, 36, 16.0f / 9.0f,
                        1.0f, 1.0f, 0, 0, 0));
    }

    @Test
    public void awakeSessionAndFullscreenAttemptAreOneShot() {
        TurnSignalShellMain.ShellBinder.AwakeSessionState state =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.reconcile(
                        null, 12, true, 1_000);
        assertEquals(1, state.generation);
        assertTrue(state.interactive);
        assertFalse(state.update(true, true, 5_000));
        assertEquals(1, state.generation);
        assertFalse(state.update(false, false, 6_000));
        assertTrue(state.update(true, false, 7_000));
        assertEquals(2, state.generation);
        assertFalse(state.update(true, true, 18_001));
        assertEquals(2, state.generation);
        assertTrue(state.update(true, true, 30_000));
        assertEquals(3, state.generation);

        TurnSignalShellMain.ShellBinder.AwakeSessionState restored =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.reconcile(
                        TurnSignalShellMain.ShellBinder.AwakeSessionState.parse(state.encode()),
                        12, true, 31_000);
        assertEquals(3, restored.generation);
        TurnSignalShellMain.ShellBinder.AwakeSessionState rebooted =
                TurnSignalShellMain.ShellBinder.AwakeSessionState.reconcile(
                        restored, 13, true, 100);
        assertEquals(4, rebooted.generation);

        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, CameraDisplayTarget.CLUSTER, CameraDisplayTarget.TABLET,
                true, 4, 3));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, CameraDisplayTarget.CLUSTER, CameraDisplayTarget.TABLET,
                true, 4, 4));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                true, 4, 3));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, CameraDisplayTarget.CLUSTER, CameraDisplayTarget.TABLET,
                false, 4, 3));
        assertEquals(30011, ClusterFullscreenProtocol.protocolForTest());
        assertEquals(4, ClusterFullscreenProtocol.operationForTest());
        assertEquals("success", ClusterFullscreenController.outcome(""));
        assertEquals("failed", ClusterFullscreenController.outcome("bind rejected"));
        assertEquals("indeterminate", ClusterFullscreenController.outcome(
                "transaction outcome indeterminate after 3000ms"));
    }

    @Test
    public void cameraConfigRejectsUntrustedValues() {
        assertEquals(19, CameraShellProtocol.VERSION);
        assertTrue(CameraShellProtocol.TX_OVERLAY_PREPARE > CameraShellProtocol.TX_SHUTDOWN);
        assertTrue(CameraShellProtocol.TX_OVERLAY_CLOSE
                > CameraShellProtocol.TX_OVERLAY_SET_VISIBLE);
        assertTrue(CameraShellProtocol.TX_OVERLAY_SET_WARNING
                > CameraShellProtocol.TX_OVERLAY_CLOSE);
        assertTrue(CameraShellProtocol.TX_REVERSE_PREPARE
                > CameraShellProtocol.TX_OVERLAY_SET_WARNING);
        assertTrue(CameraShellProtocol.TX_REVERSE_CLOSE
                > CameraShellProtocol.TX_REVERSE_SET_VISIBLE);
        CameraShellProtocol.validateWarning(1, 1,
                CameraShellProtocol.WARNING_EDGE_NONE,
                CameraShellProtocol.WARNING_MODE_OFF);
        CameraShellProtocol.validateWarning(1, 1,
                CameraShellProtocol.WARNING_EDGE_LEFT,
                CameraShellProtocol.WARNING_MODE_CONSTANT);
        CameraShellProtocol.validateWarning(1, 1,
                CameraShellProtocol.WARNING_EDGE_RIGHT,
                CameraShellProtocol.WARNING_MODE_PULSE);
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.validateWarning(0, 1,
                        CameraShellProtocol.WARNING_EDGE_LEFT,
                        CameraShellProtocol.WARNING_MODE_PULSE));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.validateWarning(1, 0,
                        CameraShellProtocol.WARNING_EDGE_LEFT,
                        CameraShellProtocol.WARNING_MODE_PULSE));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.validateWarning(1, 1,
                        CameraShellProtocol.WARNING_EDGE_LEFT,
                        CameraShellProtocol.WARNING_MODE_OFF));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.validateWarning(1, 1,
                        CameraShellProtocol.WARNING_EDGE_NONE,
                        CameraShellProtocol.WARNING_MODE_PULSE));
        CameraShellProtocol.OverlaySpec overlay = new CameraShellProtocol.OverlaySpec(
                1, CameraDisplayTarget.TABLET, 640, 480, 16, 36,
                0.0f, 0.04f, 0.65f, 0.72f,
                DirectCameraCrop.ASPECT_FREE);
        overlay.validate(1920, 1080);
        assertThrows(IllegalArgumentException.class, () ->
                new CameraShellProtocol.OverlaySpec(
                        CameraProfile.REAR_LEFT, 1, CameraDisplayTarget.TABLET,
                        640, 480, 16, 36,
                        0.0f, 0.04f, 0.65f, 0.72f,
                        DirectCameraCrop.ASPECT_FREE, 181, 8)
                        .validate(1920, 1080));
        assertThrows(IllegalArgumentException.class, () ->
                new CameraShellProtocol.OverlaySpec(
                        CameraProfile.REAR_LEFT, 1, CameraDisplayTarget.TABLET,
                        640, 480, 16, 36,
                        0.0f, 0.04f, 0.65f, 0.72f,
                        DirectCameraCrop.ASPECT_FREE, 0, 99, 8,
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT))
                        .validate(1920, 1080));
        CameraDewarpConfig decoded = CameraShellProtocol.decodeDewarp(
                CameraDewarpConfig.LENS_RIGHT, 1, 115,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        assertEquals(CameraDewarpConfig.LENS_RIGHT, decoded.lens);
        assertTrue(decoded.enabled);
        assertEquals(115, decoded.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL, decoded.projection);
        int[] dewarpWire = CameraShellProtocol.encodeDewarp(decoded);
        assertArrayEquals(new int[]{CameraDewarpConfig.LENS_RIGHT, 1, 115,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL}, dewarpWire);
        CameraDewarpConfig roundTrip = CameraShellProtocol.decodeDewarp(dewarpWire);
        assertEquals(decoded.lens, roundTrip.lens);
        assertEquals(decoded.enabled, roundTrip.enabled);
        assertEquals(decoded.fovDegrees, roundTrip.fovDegrees);
        assertEquals(decoded.projection, roundTrip.projection);
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.decodeDewarp(new int[]{1, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.decodeDewarp((int[]) null));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.decodeDewarp(
                        CameraDewarpConfig.LENS_LEFT, 2, 100,
                        CameraDewarpConfig.PROJECTION_RECTILINEAR));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.decodeDewarp(
                        0, 0, 100, CameraDewarpConfig.PROJECTION_RECTILINEAR));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.decodeDewarp(
                        CameraDewarpConfig.LENS_LEFT, 0, 171,
                        CameraDewarpConfig.PROJECTION_RECTILINEAR));
        assertThrows(IllegalArgumentException.class,
                () -> CameraShellProtocol.decodeDewarp(
                        CameraDewarpConfig.LENS_LEFT, 0, 100, 9));
        assertThrows(IllegalArgumentException.class, () ->
                new CameraShellProtocol.OverlaySpec(
                        CameraProfile.REAR_RIGHT, 1, CameraDisplayTarget.TABLET,
                        640, 480, 16, 36,
                        0.0f, 0.04f, 0.65f, 0.72f,
                        DirectCameraCrop.ASPECT_FREE, 0, 8,
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT))
                        .validate(1920, 1080));
        assertThrows(IllegalArgumentException.class, () -> new CameraShellProtocol.OverlaySpec(
                0, CameraDisplayTarget.TABLET, 640, 480, 16, 36,
                0.0f, 0.04f, 0.65f, 0.72f,
                DirectCameraCrop.ASPECT_FREE).validate(1920, 1080));
        new CameraShellProtocol.ReverseOverlaySpec(
                1, ReverseCameraLayout.defaults(), 8).validate(1920, 990);
        assertThrows(IllegalArgumentException.class,
                () -> new CameraShellProtocol.ReverseOverlaySpec(
                        1, ReverseCameraLayout.defaults(), 8,
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT),
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR),
                        CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT))
                        .validate(1920, 990));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraShellProtocol.ReverseOverlaySpec(
                        1, ReverseCameraLayout.defaults(), 49).validate(1920, 990));
        assertThrows(IllegalArgumentException.class, () -> new CameraShellProtocol.OverlaySpec(
                1, CameraDisplayTarget.TABLET, 640, 480, 1500, 36,
                0.0f, 0.04f, 0.65f, 0.72f,
                DirectCameraCrop.ASPECT_FREE).validate(1920, 1080));
        assertThrows(IllegalArgumentException.class, () -> new CameraShellProtocol.OverlaySpec(
                1, CameraDisplayTarget.TABLET, 640, 480, 16, 36,
                0.6f, 0.04f, 0.5f, 0.72f,
                DirectCameraCrop.ASPECT_FREE).validate(1920, 1080));
        assertThrows(IllegalArgumentException.class, () -> new CameraShellProtocol.OverlaySpec(
                1, 9, 640, 480, 16, 36,
                0.0f, 0.04f, 0.65f, 0.72f,
                DirectCameraCrop.ASPECT_FREE).validate(1920, 1080));
        StockAvmPreview.Config config = new StockAvmPreview.Config(
                1, 250, 250, 1920, 1300, "ocean", "car", "sub");
        assertEquals(1, config.panoramaState);
        assertThrows(IllegalArgumentException.class, () -> new StockAvmPreview.Config(
                0, 250, 250, 1920, 1300, "ocean", "car", "sub"));
        assertThrows(IllegalArgumentException.class, () -> new StockAvmPreview.Config(
                1, 250, 250, 1920, 1300, "unknown", "car", "sub"));
        assertEquals(4, StockAvmPreview.resolution(1, 1300));
        assertEquals(3, StockAvmPreview.resolution(1, 1200));
        assertEquals(2, StockAvmPreview.resolution(4, 960));
        assertEquals("1300P", StockAvmPreview.resolutionDirectory(4));
        assertThrows(IllegalArgumentException.class,
                () -> StockAvmPreview.resolutionDirectory(5));
    }

    @Test
    public void cameraCacheDisablesPrivilegedSdkDebugPropertyWrite() throws Exception {
        Path config = Files.createTempFile("Vehicle_Configuration", ".json");
        Files.write(config, "{\"PROJECT_CONFIG\":{\"isDebug\": true}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(StockAvmPreview.disableSdkDebug(config.toFile()));
        assertFalse(StockAvmPreview.disableSdkDebug(config.toFile()));
        assertTrue(new String(Files.readAllBytes(config),
                java.nio.charset.StandardCharsets.UTF_8).contains("\"isDebug\": false"));
        config.toFile().deleteOnExit();
    }

    @Test
    public void cameraCacheAppliesOnlyStockRearSideDewarpBlocks() throws Exception {
        assertFalse(CameraProbeActivity.shouldUseStockDewarp(false, false));
        assertFalse(CameraProbeActivity.shouldUseStockDewarp(false, true));
        assertTrue(CameraProbeActivity.shouldUseStockDewarp(true, true));

        Path config = Files.createTempFile("SViewConfig_1300", ".json");
        String stock = "        , {#left back tire\n"
                + "        \"NAME\" : \"AREA_ID_2D_LEFT_BACK\",\n"
                + "        \"TYPE\" : \"SINGLE_CAM_TETHERED_REAR\",\n"
                + "        \"INPUT_CAM_IDX\" : 1,\n"
                + "        \"FOV\" : 100,\n"
                + "        }\n"
                + "        , {#right back tire\n"
                + "        \"NAME\" : \"AREA_ID_2D_RIGHT_BACK\",\n"
                + "        \"TYPE\" : \"SINGLE_CAM_TETHERED_REAR\",\n"
                + "        \"INPUT_CAM_IDX\" : 3,\n"
                + "        \"FOV\" : 100,\n"
                + "        }\n"
                + "        , {\n"
                + "        \"NAME\" : \"AREA_ID_RESERVED_VIEW1\",\n"
                + "        \"TYPE\" : \"INPUT_CAM_CROPPED\",\n"
                + "        \"DEWARP_STRENGTH\" : 1,\n"
                + "        \"INPUT_CAM_IDX\" : 1,\n"
                + "        }";
        Files.write(config, stock.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertFalse(StockAvmPreview.isRearSideDewarpEnabled(config.toFile()));
        assertFalse(StockAvmPreview.configureRearSideDewarp(config.toFile(), false));
        assertEquals(stock, new String(Files.readAllBytes(config),
                java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(StockAvmPreview.configureRearSideDewarp(config.toFile(), true));
        String expected = stock
                .replace("        \"TYPE\" : \"SINGLE_CAM_TETHERED_REAR\",\n",
                        "        \"TYPE\" : \"INPUT_CAM_CROPPED\",\n"
                                + "        \"DEWARP_STRENGTH\" : 1,\n");
        assertEquals(expected, new String(Files.readAllBytes(config),
                java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(StockAvmPreview.isRearSideDewarpEnabled(config.toFile()));
        assertFalse(StockAvmPreview.configureRearSideDewarp(config.toFile(), true));

        String stockTypeLine = "        \"TYPE\" : \"SINGLE_CAM_TETHERED_REAR\",\n";
        String dewarpTypeLine = "        \"TYPE\" : \"INPUT_CAM_CROPPED\",\n"
                + "        \"DEWARP_STRENGTH\" : 1,\n";
        int firstType = stock.indexOf(stockTypeLine);
        String partial = stock.substring(0, firstType) + dewarpTypeLine
                + stock.substring(firstType + stockTypeLine.length());
        Files.write(config, partial.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
                () -> StockAvmPreview.isRearSideDewarpEnabled(config.toFile()));

        Files.write(config, stock.replace("\"INPUT_CAM_IDX\" : 3",
                "\"INPUT_CAM_IDX\" : 2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
                () -> StockAvmPreview.configureRearSideDewarp(config.toFile(), true));
        Files.write(config, stock.replace(
                "        , {#right back tire\n"
                        + "        \"NAME\" : \"AREA_ID_2D_RIGHT_BACK\",\n"
                        + "        \"TYPE\" : \"SINGLE_CAM_TETHERED_REAR\",\n"
                        + "        \"INPUT_CAM_IDX\" : 3,\n"
                        + "        \"FOV\" : 100,\n"
                        + "        }\n", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
                () -> StockAvmPreview.configureRearSideDewarp(config.toFile(), true));
        Files.write(config, (stock + stock.substring(
                stock.indexOf("        , {#left back tire"),
                stock.indexOf("        , {#right back tire")))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
                () -> StockAvmPreview.configureRearSideDewarp(config.toFile(), true));
        config.toFile().deleteOnExit();

        Path cache = Files.createTempDirectory("stock-avm-cache");
        Path standard = cache.resolve("SViewConfig_1300.json");
        Path ceps = cache.resolve("CEPS_SViewConfig_1300.json");
        Path unrelated = cache.resolve("Vehicle_Configuration.json");
        Files.write(standard, stock.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(ceps, stock.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(unrelated, "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, StockAvmPreview.resetCachedViewConfigs(cache.toFile(), 1300));
        assertFalse(Files.exists(standard));
        assertFalse(Files.exists(ceps));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    public void cameraCacheBuildsRearClairvoyanceFromRearSideAreas() throws Exception {
        Path config = Files.createTempFile("Vehicle_Configuration", ".json");
        String stock = "\"VIEW_2D_LEFT_CLAIRVOYANCE\": "
                + "[8,[0,0.5,0.5,0,48],[40,-1,-1,0,8192]]\n"
                + "\"VIEW_2D_RIGHT_CLAIRVOYANCE\": "
                + "[8,[0,0.5,0.5,0,48],[41,-1,-1,0,8192]]\n"
                + "\"VIEW_2D_LEFT_CLAIRVOYANCE\": "
                + "[7,[0,0.5,0.5,0,48],[40,-1,-1,0,8192]]\n"
                + "\"VIEW_2D_RIGHT_CLAIRVOYANCE\": "
                + "[7,[0,0.5,0.5,0,48],[41,-1,-1,0,8192]]";
        Files.write(config, stock.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertTrue(StockAvmPreview.patchRearClairvoyance(config.toFile()));
        String patched = new String(Files.readAllBytes(config),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(patched.contains("[40,-1,-1,0,8192]"));
        assertTrue(patched.contains("[41,-1,-1,0,8192]"));
        assertTrue(patched.contains("\"VIEW_2D_REAR_LEFT\": "
                + "[15,[14,-1,-1,0,65535]]"));
        assertTrue(patched.contains("\"VIEW_2D_REAR_RIGHT\": "
                + "[0,[15,-1,-1,0,65535]]"));
        assertFalse(StockAvmPreview.patchRearClairvoyance(config.toFile()));

        String legacy = patched
                .replace("[15,[14,-1,-1,0,65535]]",
                        "[8,[0,0.5,0.5,0,48],[14,-1,-1,0,65535]]")
                .replace("[15,[15,-1,-1,0,65535]]",
                        "[8,[0,0.5,0.5,0,48],[15,-1,-1,0,65535]]")
                .replace("[0,[14,-1,-1,0,65535]]",
                        "[7,[0,0.5,0.5,0,48],[14,-1,-1,0,65535]]")
                .replace("[0,[15,-1,-1,0,65535]]",
                        "[7,[0,0.5,0.5,0,48],[15,-1,-1,0,65535]]");
        Files.write(config, legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(StockAvmPreview.patchRearClairvoyance(config.toFile()));
        assertFalse(StockAvmPreview.patchRearClairvoyance(config.toFile()));
        config.toFile().deleteOnExit();
    }

    @Test
    public void reverseGearAndLayoutStayFailSafe() {
        assertFalse(ReverseGearRuntime.isValidRaw(0));
        assertTrue(ReverseGearRuntime.isValidRaw(1));
        assertTrue(ReverseGearRuntime.isReverseRaw(true, 2));
        assertFalse(ReverseGearRuntime.isReverseRaw(false, 2));
        assertFalse(ReverseGearRuntime.isValidRaw(7));
        assertFalse(CameraHelperMain.HelperBinder.canReplaceCamera(
                true, CameraHelperMain.CAMERA_OWNER_REVERSE,
                CameraHelperMain.CAMERA_OWNER_ACTIVITY));
        assertFalse(CameraHelperMain.HelperBinder.canReplaceCamera(
                true, CameraHelperMain.CAMERA_OWNER_REVERSE,
                CameraHelperMain.CAMERA_OWNER_OVERLAY));
        assertTrue(CameraHelperMain.HelperBinder.canReplaceCamera(
                true, CameraHelperMain.CAMERA_OWNER_REVERSE,
                CameraHelperMain.CAMERA_OWNER_REVERSE));

        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        assertEquals(0.0f, layout.rear.destination.left, 0.0001f);
        assertEquals(0.5f, layout.rear.destination.height, 0.0001f);
        assertEquals(0.5f, layout.rearLeft.destination.top, 0.0001f);
        assertEquals(0.5f, layout.rearRight.destination.left, 0.0001f);
        ReverseCameraLayout.PixelRect fitted = ReverseCameraLayout.fitSourceCrop(
                layout.rear.sourceCrop, 1920, 540,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, 0);
        assertEquals(561, fitted.left);
        assertEquals(0, fitted.top);
        assertEquals(798, fitted.width);
        assertEquals(540, fitted.height);
        assertEquals(1920.0f / 1300.0f,
                (float) fitted.width / fitted.height, 0.002f);
        ReverseCameraLayout.PixelRect partial = ReverseCameraLayout.fitSourceCrop(
                ReverseCameraLayout.sourceCrop(0.2f, 0.1f, 0.4f, 0.7f),
                960, 540,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, 0);
        assertEquals(252, partial.left);
        assertEquals(0, partial.top);
        assertEquals(456, partial.width);
        assertEquals(540, partial.height);
        assertEquals(0.4f * 1920.0f / (0.7f * 1300.0f),
                (float) partial.width / partial.height, 0.002f);
        assertEquals(1, layout.rear.cameraIndex);
        assertEquals(2, layout.rearLeft.cameraIndex);
        assertEquals(3, layout.rearRight.cameraIndex);

        ReverseCameraLayout.Rect clamped = ReverseCameraLayout.destination(
                0.95f, 0.95f, 0.5f, 0.5f);
        assertEquals(0.5f, clamped.left, 0.0001f);
        assertEquals(0.5f, clamped.top, 0.0001f);
        assertThrows(IllegalArgumentException.class,
                () -> ReverseCameraLayout.sourceCrop(0, 0, 0, 1));

        layout = ReverseCameraLayout.bringToFront(
                layout, ReverseCameraLayout.REAR_CAMERA_INDEX);
        assertEquals(2, layout.rear.zOrder);
        assertEquals(0, layout.rearLeft.zOrder);
        assertEquals(1, layout.rearRight.zOrder);
        layout = ReverseCameraLayout.raise(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        assertEquals(2, layout.rear.zOrder);
        assertEquals(1, layout.rearLeft.zOrder);
        assertEquals(0, layout.rearRight.zOrder);
        layout = ReverseCameraLayout.lower(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        assertEquals(2, layout.rear.zOrder);
        assertEquals(0, layout.rearLeft.zOrder);
        assertEquals(1, layout.rearRight.zOrder);
    }

    @Test
    public void activityPreviewSurvivesReversePriorityThenReattaches() {
        CameraHelperMain.HelperBinder.ActivityPreviewState<String> preview =
                new CameraHelperMain.HelperBinder.ActivityPreviewState<>();
        preview.set("surface", 2, true);
        CameraHelperMain.HelperBinder.ActivityPreviewState.Snapshot<String> reverseEntry =
                preview.close(CameraHelperMain.HelperBinder.shouldPreserveActivityPreview(
                        preview.has(), CameraHelperMain.CAMERA_OWNER_REVERSE));
        assertEquals("surface", reverseEntry.value);
        assertTrue(reverseEntry.attached);
        assertFalse(reverseEntry.release);
        assertTrue(preview.has());
        assertFalse(preview.attached());
        CameraHelperMain.HelperBinder.ActivityPreviewState.Snapshot<String> reverseExit =
                preview.close(CameraHelperMain.HelperBinder.shouldPreserveActivityPreview(
                        preview.has(), CameraHelperMain.CAMERA_OWNER_REVERSE));
        assertFalse(reverseExit.attached);
        assertFalse(reverseExit.release);
        assertTrue(preview.has());
        preview.setAttached(true);
        assertTrue(preview.attached());
        CameraHelperMain.HelperBinder.ActivityPreviewState.Snapshot<String> activityClose =
                preview.close(CameraHelperMain.HelperBinder.shouldPreserveActivityPreview(
                        preview.has(), CameraHelperMain.CAMERA_OWNER_ACTIVITY));
        assertTrue(activityClose.attached);
        assertTrue(activityClose.release);
        assertFalse(preview.has());
        CameraHelperMain.HelperBinder.ActivityPreviewState.Snapshot<String> repeatedClose =
                preview.close(false);
        assertFalse(repeatedClose.release);
    }

    private static byte[] collectorReferenceEncoding(RSAPublicKey key) {
        int wordCount = 64;
        BigInteger radix = BigInteger.ONE.shiftLeft(32);
        BigInteger mask = radix.subtract(BigInteger.ONE);
        BigInteger modulus = key.getModulus();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(modulus);
        BigInteger n0inv = modulus.and(mask).modInverse(radix).negate().mod(radix);
        ByteBuffer output = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(wordCount);
        output.putInt(n0inv.intValue());
        for (int i = 0; i < wordCount; i++) {
            output.putInt(modulus.and(mask).intValue());
            modulus = modulus.shiftRight(32);
        }
        for (int i = 0; i < wordCount; i++) {
            output.putInt(rr.and(mask).intValue());
            rr = rr.shiftRight(32);
        }
        output.putInt(key.getPublicExponent().intValue());
        return output.array();
    }
}
