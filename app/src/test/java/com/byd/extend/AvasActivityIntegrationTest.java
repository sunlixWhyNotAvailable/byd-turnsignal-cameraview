package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
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
}
