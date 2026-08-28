package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Bounded checks for startup permission ordering and transfer distinctions. */
public final class CameraProbeStartupAuthorizationTest {
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
