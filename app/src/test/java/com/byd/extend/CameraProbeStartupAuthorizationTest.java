package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Bounded checks for startup permission ordering and transfer distinctions. */
public final class CameraProbeStartupAuthorizationTest {
    @Test
    public void grantedOverlayPermissionSkipsStartupRequest() {
        CameraProbeActivity.StartupOverlayPermissionFlow flow =
                new CameraProbeActivity.StartupOverlayPermissionFlow();

        assertFalse(flow.beginStartupOverlayPermissionRequest(true, false));
        assertFalse(flow.overlayPermissionAttempted());
        assertTrue(flow.canPresentStartupDialogs(true));
    }

    @Test
    public void foregroundAuthorizationMustFinishBeforeOverlayAndFailureDoesNotRetryAdb() {
        CameraProbeActivity.StartupOverlayPermissionFlow flow =
                new CameraProbeActivity.StartupOverlayPermissionFlow();

        assertTrue(flow.beginForegroundAuthorizationAttempt(true));
        assertFalse(flow.beginStartupOverlayPermissionRequest(false, false));
        flow.finishForegroundAuthorizationAttempt();

        assertFalse(flow.beginForegroundAuthorizationAttempt(true));
        assertTrue(flow.beginStartupOverlayPermissionRequest(false, false));
        assertTrue(flow.overlaySettingsInFlight());
    }

    @Test
    public void callbackRegistrationFailureReleasesStartupOverlayStep() {
        CameraProbeActivity.StartupOverlayPermissionFlow flow =
                new CameraProbeActivity.StartupOverlayPermissionFlow();

        flow.skipForegroundAuthorizationAttempt();

        assertFalse(flow.beginForegroundAuthorizationAttempt(true));
        assertTrue(flow.beginStartupOverlayPermissionRequest(false, false));
    }

    @Test
    public void anotherModalDefersOverlayWithoutConsumingStartupAttempt() {
        CameraProbeActivity.StartupOverlayPermissionFlow flow =
                new CameraProbeActivity.StartupOverlayPermissionFlow();

        assertFalse(flow.beginStartupOverlayPermissionRequest(false, true));
        assertFalse(flow.overlayPermissionAttempted());
        assertTrue(flow.beginStartupOverlayPermissionRequest(false, false));
    }

    @Test
    public void overlayAttemptSurvivesRecreationAndReturnDoesNotRepeat() {
        CameraProbeActivity.StartupOverlayPermissionFlow original =
                new CameraProbeActivity.StartupOverlayPermissionFlow();
        assertTrue(original.beginStartupOverlayPermissionRequest(false, false));

        CameraProbeActivity.StartupOverlayPermissionFlow recreated =
                new CameraProbeActivity.StartupOverlayPermissionFlow();
        recreated.restore(original.foregroundAuthorizationStarted(),
                original.foregroundAuthorizationFinished(),
                original.overlayPermissionAttempted(), original.overlaySettingsInFlight(), false);

        assertFalse(recreated.beginStartupOverlayPermissionRequest(false, false));
        assertFalse(recreated.canPresentStartupDialogs(false));
        assertTrue(recreated.finishOverlaySettings());
        assertTrue(recreated.canPresentStartupDialogs(false));
        assertFalse(recreated.beginStartupOverlayPermissionRequest(false, false));
    }

    @Test
    public void recreationReconcilesOnlyUnfinishedLocalAuthorization() {
        CameraProbeActivity.StartupOverlayPermissionFlow flow =
                new CameraProbeActivity.StartupOverlayPermissionFlow();
        flow.restore(true, false, false, false, true);
        assertTrue(flow.beginForegroundAuthorizationAttempt(true));
        assertFalse(flow.beginStartupOverlayPermissionRequest(false, false));
        flow.finishForegroundAuthorizationAttempt();
        assertTrue(flow.beginStartupOverlayPermissionRequest(false, false));

        flow.restore(true, true, true, true, true);
        assertFalse(flow.beginForegroundAuthorizationAttempt(true));
        assertTrue(flow.overlaySettingsInFlight());
        assertTrue(flow.finishOverlaySettings());
        assertFalse(flow.beginStartupOverlayPermissionRequest(false, false));

        flow.restore(true, false, false, false, false);
        assertFalse(flow.beginForegroundAuthorizationAttempt(true));
        assertTrue(flow.foregroundAuthorizationInFlight());
    }

    @Test
    public void overlayDeclineOrLaunchFailureReleasesImportAndUpdateStages() {
        CameraProbeActivity.StartupOverlayPermissionFlow declined =
                new CameraProbeActivity.StartupOverlayPermissionFlow();
        assertTrue(declined.beginStartupOverlayPermissionRequest(false, false));
        assertFalse(declined.canPresentStartupDialogs(false));
        assertTrue(declined.finishOverlaySettings());
        assertTrue(declined.canPresentStartupDialogs(false));
        assertTrue(declined.beginManualOverlayPermissionRequest(false, false));
        assertTrue(declined.finishOverlaySettings());

        CameraProbeActivity.StartupOverlayPermissionFlow failedLaunch =
                new CameraProbeActivity.StartupOverlayPermissionFlow();
        assertTrue(failedLaunch.beginStartupOverlayPermissionRequest(false, false));
        assertFalse(failedLaunch.canPresentStartupDialogs(false));
        assertTrue(failedLaunch.finishOverlaySettings());
        assertTrue(failedLaunch.canPresentStartupDialogs(false));
    }

    @Test
    public void onlyFullImportOrFirstImportedUpgradeSchedulesWeatherPermission() {
        assertTrue(CameraProbeActivity.shouldRequestStartupWeatherPermission(
                true, false, true, false, true));
        assertTrue(CameraProbeActivity.shouldRequestStartupWeatherPermission(
                true, false, false, true, false));
        assertFalse(CameraProbeActivity.shouldRequestStartupWeatherPermission(
                true, false, false, true, true));
        assertFalse(CameraProbeActivity.shouldRequestStartupWeatherPermission(
                false, false, true, true, false));
        assertFalse(CameraProbeActivity.shouldRequestStartupWeatherPermission(
                true, true, true, true, false));
    }

    @Test
    public void denialDisablesWeatherWithoutRefreshLoop() {
        assertTrue(CameraProbeActivity.shouldEnableWeatherAfterPermission(
                true, true));
        assertFalse(CameraProbeActivity.shouldEnableWeatherAfterPermission(
                false, true));
        assertFalse(CameraProbeActivity.shouldEnableWeatherAfterPermission(
                true, false));
        assertTrue(CameraProbeActivity.shouldRefreshWeatherAfterPermission(
                true, true, true));
        assertFalse(CameraProbeActivity.shouldRefreshWeatherAfterPermission(
                false, true, true));
        assertFalse(CameraProbeActivity.shouldRefreshWeatherAfterPermission(
                true, true, false));
    }

    @Test
    public void compatibilityProgressTextCarriesPhaseAndCounters() {
        CompatibilityBundleExporter.Progress progress =
                new CompatibilityBundleExporter.Progress(
                        CompatibilityBundleExporter.Progress.Phase.REMOTE,
                        "/system/app/AutoVideo.apk", 2, 4, 1024L, 8192L);
        String text = CameraProbeActivity.compatibilityExportProgressText(progress);
        assertTrue(text.contains("2/4"));
        assertTrue(text.contains("AutoVideo.apk"));
        assertTrue(text.contains("1 KiB"));
        assertTrue(text.contains("8 KiB"));
    }
}
