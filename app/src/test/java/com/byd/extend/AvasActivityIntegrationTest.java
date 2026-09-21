package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public final class AvasActivityIntegrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private AvasAudioLibrary libraryWithAsset(AvasConfig config, String profile,
            AvasConfig.Asset asset) throws Exception {
        File source = temporary.newFolder();
        File prepared = temporary.newFolder();
        AvasAudioLibrary library = new AvasAudioLibrary(new TestSharedPreferences(), source, prepared);
        library.saveConfig(config);
        File directory = new File(source, profile);
        assertTrue(directory.mkdir());
        AvasBuiltinSounds.writeWav(profile, new File(directory, asset.id + ".source"));
        AvasBuiltinSounds.writeWav(profile, library.preparedFile(asset.id));
        return library;
    }

    @Test
    public void importMergeUsesLatestSettingsAndSelectsFirstOnlyWhenEmpty() throws Exception {
        AvasConfig.Profile latestProfile = AvasConfig.empty().profile("lock")
                .withSettings(true, true, 83, "");
        AvasConfig latest = AvasConfig.empty().withProfile(latestProfile);
        AvasConfig.Asset added = new AvasConfig.Asset(
                "0123456789abcdef0123456789abcdef", "Lock.ogg");

        AvasConfig merged = libraryWithAsset(latest, "lock", added).mergeImportedAssets("lock",
                new AvasAudioLibrary.ImportResult(Collections.singletonList(added), 1));
        AvasConfig.Profile result = merged.profile("lock");

        assertTrue(result.enabled);
        assertTrue(result.random);
        assertEquals(83, result.volume);
        assertEquals(added.id, result.selectedAssetId);
        assertEquals(Collections.singletonList(added), result.assets);
    }

    @Test
    public void importMergeRetainsInterveningSelection() throws Exception {
        AvasConfig.Asset existing = new AvasConfig.Asset(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Existing.wav");
        AvasConfig.Asset added = new AvasConfig.Asset(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Added.wav");
        AvasConfig.Profile profile = AvasConfig.empty().profile("unlock")
                .withAssets(Collections.singletonList(existing), existing.id)
                .withSettings(false, true, 7, existing.id);
        AvasConfig latest = AvasConfig.empty().withProfile(profile);

        AvasConfig.Profile result = libraryWithAsset(latest, "unlock", added).mergeImportedAssets("unlock",
                new AvasAudioLibrary.ImportResult(Collections.singletonList(added), 0))
                .profile("unlock");

        assertEquals(existing.id, result.selectedAssetId);
        assertEquals(2, result.assets.size());
        assertEquals(7, result.volume);
    }

    @Test
    public void importMarkerKeepsPickerAndLiveDecoderButClearsOrphanedDecoder() {
        assertTrue(!CameraProbeActivity.shouldClearAvasImportMarker(
                true, "picker", "lock", null));
        assertTrue(!CameraProbeActivity.shouldClearAvasImportMarker(
                true, "decoding", "lock", "lock"));
        assertTrue(CameraProbeActivity.shouldClearAvasImportMarker(
                true, "decoding", "lock", null));
        assertTrue(CameraProbeActivity.shouldClearAvasImportMarker(
                false, "picker", "lock", null));
    }

    @Test
    public void powerSwitchUiUsesApprovedLocalesPlacementAndBackendPath() throws Exception {
        String ui = source("kotlin/com/byd/extend/ui/BydExtendApp.kt");
        String activity = source("java/com/byd/extend/CameraProbeActivity.java");
        int switchRow = ui.indexOf("SetSkipConcurrentLockUnlock");
        int selectedFile = ui.indexOf("Обраний аудіофайл", switchRow);

        assertTrue(ui.contains("profile.id == AvasProfileIds.POWER_OFF"));
        assertTrue(ui.contains("|| profile.id == AvasProfileIds.POWER_ON"));
        assertTrue(ui.contains("Пропускати одночасний звук\\nвідкриття/закриття"));
        assertTrue(ui.contains("Skip simultaneous lock/unlock sound"));
        assertTrue(ui.contains("跳过同时触发的解锁/锁车声音"));
        assertTrue(switchRow >= 0 && switchRow < selectedFile);
        assertTrue(ui.substring(ui.lastIndexOf("SwitchLine(", switchRow), switchRow)
                .contains("profile.skipConcurrentLockUnlock"));
        assertTrue(activity.contains("AvasActionKind.SetSkipConcurrentLockUnlock"));
        assertTrue(activity.contains("profile.skipConcurrentLockUnlock"));
        assertTrue(activity.contains("skipConcurrentLockUnlock);"));
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("app/src/main/" + relative);
        if (!Files.exists(path)) path = Path.of("src/main/" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
