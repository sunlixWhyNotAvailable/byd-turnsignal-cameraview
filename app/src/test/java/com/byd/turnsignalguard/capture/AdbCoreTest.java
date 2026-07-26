package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
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
        assertEquals(34, BuildConfig.VERSION_CODE);
        assertEquals(3, TurnSignalShellProtocol.VERSION);

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
    public void foregroundAuthorizationWaitsForPermissionAndWindowFocus() {
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                true, true, true, false, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, false, true, false, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, true, false, false, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, true, true, true, false));
        assertFalse(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, true, true, false, true));
        assertTrue(CameraProbeActivity.shouldStartForegroundAdbAuthorization(
                false, true, true, false, false));
        assertEquals(600, CameraProbeActivity.ADB_AUTH_UI_SETTLE_MS);
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
                false, true, false, true, true, true));
        assertEquals("overlay_disabled", BlindSpotOverlayController.cameraRetryBlockReason(
                false, false, false, true, true, true));
        assertEquals("overlay_suspended", BlindSpotOverlayController.cameraRetryBlockReason(
                false, true, true, true, true, true));
        assertEquals("helper_unavailable", BlindSpotOverlayController.cameraRetryBlockReason(
                false, true, false, false, true, true));
        assertEquals("overlay_permission_missing",
                BlindSpotOverlayController.cameraRetryBlockReason(
                        false, true, false, true, false, true));
        assertEquals("surface_unavailable", BlindSpotOverlayController.cameraRetryBlockReason(
                false, true, false, true, true, false));
        assertEquals("shutdown", BlindSpotOverlayController.cameraRetryBlockReason(
                true, true, false, true, true, true));

        assertTrue(CameraProbeActivity.shouldRetryCalibrationCopy(true, 0, 720));
        assertTrue(CameraProbeActivity.shouldRetryCalibrationCopy(true, 1280, 0));
        assertFalse(CameraProbeActivity.shouldRetryCalibrationCopy(true, 1280, 720));
        assertFalse(CameraProbeActivity.shouldRetryCalibrationCopy(false, 0, 0));
        assertTrue(CameraProbeActivity.isOverlayCameraEvent(
                "camera_error", CameraHelperMain.CAMERA_OWNER_OVERLAY));
        assertTrue(CameraProbeActivity.isOverlayCameraEvent(
                "camera_opened", CameraHelperMain.CAMERA_OWNER_OVERLAY));
        assertFalse(CameraProbeActivity.isOverlayCameraEvent(
                "camera_error", CameraHelperMain.CAMERA_OWNER_ACTIVITY));
        assertFalse(CameraProbeActivity.isOverlayCameraEvent(
                "camera_discovery", CameraHelperMain.CAMERA_OWNER_OVERLAY));
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
        assertArrayEquals(new String[]{"pano_h", "pano_l", "apa", "byd_apa"},
                CameraHelperMain.directCameraTags());
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
        assertEquals(0, BlindSpotOverlayController.directionToShow(false, 2, 30.0f, 20));
        assertEquals(0, BlindSpotOverlayController.directionToShow(true, 2, 19.9f, 20));
        assertEquals(0, BlindSpotOverlayController.directionToShow(true, 6, 30.0f, 20));
        assertEquals(2, BlindSpotOverlayController.directionToShow(true, 2, 20.0f, 20));
        assertEquals(4, BlindSpotOverlayController.directionToShow(true, 4, 30.0f, 20));
        assertEquals(2, BlindSpotOverlayController.previewIndexForDirection(2));
        assertEquals(3, BlindSpotOverlayController.previewIndexForDirection(4));
        assertEquals(-1, BlindSpotOverlayController.previewIndexForDirection(6));
        assertTrue(CameraProbeActivity.isIntermediateCameraClose("preview_handoff"));
        assertTrue(CameraProbeActivity.isIntermediateCameraClose("replace_preview"));
        assertFalse(CameraProbeActivity.isIntermediateCameraClose("user_close"));
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
        assertTrue(BlindSpotOverlayController.isSettledViewpoint(10008, 10008, 10008));
        assertFalse(BlindSpotOverlayController.isSettledViewpoint(10008, 10009, 10008));
        assertFalse(BlindSpotOverlayController.isSettledViewpoint(-1, -1, -1));
        assertArrayEquals(new int[]{691, 518},
                BlindSpotOverlayController.fitFourThree(691, 917, 636));
        assertArrayEquals(new int[]{848, 636},
                BlindSpotOverlayController.fitFourThree(1152, 917, 636));
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
        assertFalse(cameraLaunch.contains("TurnSignalShellMain"));
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

        DirectCameraCrop moved = left.move(-2.0f, 2.0f);
        assertEquals(0.0f, moved.left, 0.0001f);
        assertEquals(1.0f, moved.bottom(), 0.0001f);

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
    public void cameraConfigRejectsUntrustedValues() {
        assertEquals(5, CameraShellProtocol.VERSION);
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
