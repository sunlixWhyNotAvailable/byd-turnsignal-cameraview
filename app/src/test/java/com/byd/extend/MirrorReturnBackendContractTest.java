package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source wiring complements the executed settings, policy and typed-UI tests. */
public final class MirrorReturnBackendContractTest {
    @Test
    public void returnOptionWritesOnlyItsPreferenceWithoutRestartingCamera() throws Exception {
        String branch = backendBranch("SetReturnOnAppOpen", "HideUntilOpen");
        assertTrue(branch.contains("RearviewMirrorSettings.PREF_RETURN_ON_APP_OPEN, restore"));
        assertTrue(branch.contains("RearviewMirrorSettings.returnOnAppOpen(preferences)"));
        assertTrue(branch.contains("productionUi.reload();"));
        assertTrue(branch.contains("return;"));
        assertFalse(branch.contains("mirrorSettingsChanged"));
        assertFalse(branch.contains("notifyProductionProfileChanged"));
        assertFalse(branch.contains("model.save"));
        assertFalse(branch.contains("setHidden"));
    }

    @Test
    public void explicitHideUsesNonButtonOriginWithoutSavingCameraSettings() throws Exception {
        String branch = backendBranch("HideUntilOpen", "SetSuppressWhilePanorama");
        assertTrue(branch.contains("RearviewMirrorSettings.setHidden(preferences, true);"));
        assertTrue(branch.contains("CameraHelperService.mirrorSettingsChanged(this);"));
        assertTrue(branch.contains("refreshProductionMirrorState();"));
        assertTrue(branch.contains("return;"));
        assertFalse(branch.contains("model.save"));
    }

    private static String backendBranch(String kind, String nextKind) throws Exception {
        Path path = Path.of("src/main/java/com/byd/extend/CameraProbeActivity.java");
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int start = source.indexOf("if (action.getKind() == MirrorBackendActionKind." + kind + ")");
        int end = source.indexOf("if (action.getKind() == MirrorBackendActionKind." + nextKind + ")", start);
        assertTrue("missing backend branch: " + kind, start >= 0 && end > start);
        return source.substring(start, end);
    }
}
