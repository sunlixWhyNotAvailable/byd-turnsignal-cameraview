package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateHintRuntimeDownloadTest {
    @Test
    public void activeDownloadReopensInsteadOfStartingADuplicate() {
        AppUpdateManager.UpdateInfo offered = update("1.4.1");
        UpdateHintRuntime.DownloadSnapshot hidden = new UpdateHintRuntime.DownloadSnapshot(
                offered, UpdateHintRuntime.DownloadPhase.DOWNLOADING,
                42, null, null, false, false);

        assertTrue(UpdateHintRuntime.shouldReuseDownload(hidden, offered));
        assertFalse(hidden.dialogVisible);
        assertTrue(hidden.withDialogVisible(true).dialogVisible);
        assertEquals(UpdateHintRuntime.DownloadPhase.DOWNLOADING,
                hidden.withDialogVisible(true).phase);
    }

    @Test
    public void readyInstallCanBeReopenedButANewerOfferCanReplaceIt() {
        UpdateHintRuntime.DownloadSnapshot ready = new UpdateHintRuntime.DownloadSnapshot(
                update("1.4.1"), UpdateHintRuntime.DownloadPhase.READY,
                100, null, null, false, false);

        assertTrue(UpdateHintRuntime.shouldReuseDownload(ready, update("1.4.1")));
        assertFalse(UpdateHintRuntime.shouldReuseDownload(ready, update("1.4.2")));
    }

    private static AppUpdateManager.UpdateInfo update(String version) {
        return new AppUpdateManager.UpdateInfo(version,
                "https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/"
                        + "releases/download/v" + version + "/byd-extend-v" + version + ".apk",
                "notes");
    }
}
