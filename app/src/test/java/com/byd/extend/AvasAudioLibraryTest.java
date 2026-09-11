package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AvasAudioLibraryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private TestSharedPreferences preferences;
    private File sourceRoot;
    private File preparedRoot;
    private AvasAudioLibrary library;

    @Before
    public void setUp() throws Exception {
        preferences = new TestSharedPreferences();
        sourceRoot = temporary.newFolder("source");
        preparedRoot = temporary.newFolder("prepared");
        library = new AvasAudioLibrary(preferences, sourceRoot, preparedRoot);
    }

    @Test
    public void freshLibrarySeedsFourSelectedBuiltinsAndIsIdempotent() {
        library.ensureBuiltins();
        String firstJson = library.loadConfig().toJson();
        int firstTransactions = preferences.transactions;

        for (String profileId : AvasConfig.PROFILE_IDS) {
            String assetId = AvasBuiltinSounds.assetId(profileId);
            AvasConfig.Profile profile = library.loadConfig().profile(profileId);
            assertEquals(assetId, profile.selectedAssetId);
            assertEquals(1, profile.assets.size());
            assertEquals("test", profile.assets.get(0).name);
            assertTrue(new File(new File(sourceRoot, profileId), assetId + ".source").isFile());
            assertTrue(library.preparedFile(assetId).isFile());
            assertEquals(Long.valueOf(1000), library.durationMillis(assetId));
        }

        library.ensureBuiltins();
        assertEquals(firstJson, library.loadConfig().toJson());
        assertEquals(firstTransactions, preferences.transactions);
    }

    @Test
    public void initialSelectionsPrecedeSettingsWhileWavCreationRemainsDeferred() {
        library.initializeBuiltinConfig();
        for (String id : AvasConfig.PROFILE_IDS) {
            assertEquals(AvasBuiltinSounds.assetId(id), library.loadConfig().profile(id).selectedAssetId);
            assertFalse(library.preparedFile(AvasBuiltinSounds.assetId(id)).exists());
        }
        AvasAudioLibrary other = new AvasAudioLibrary(preferences, sourceRoot, preparedRoot);
        other.updateProfileSettings("lock", true, null, 7, null);
        library.ensureBuiltins();
        assertTrue(library.loadConfig().profile("lock").enabled);
        assertEquals(7, library.loadConfig().profile("lock").volume);
        for (String id : AvasConfig.PROFILE_IDS) {
            assertEquals(AvasBuiltinSounds.assetId(id), library.loadConfig().profile(id).selectedAssetId);
            assertTrue(library.preparedFile(AvasBuiltinSounds.assetId(id)).isFile());
        }
    }

    @Test
    public void existingConfigPreservesSettingsImportsAndEmptySelection() {
        AvasConfig.Asset imported = new AvasConfig.Asset(
                "0123456789abcdef0123456789abcdef", "Imported.ogg");
        AvasConfig.Profile lock = AvasConfig.empty().profile("lock")
                .withAssets(List.of(imported), "")
                .withSettings(true, true, 84, "");
        library.saveConfig(AvasConfig.empty().withProfile(lock));

        library.ensureBuiltins();

        AvasConfig.Profile result = library.loadConfig().profile("lock");
        assertTrue(result.enabled);
        assertTrue(result.random);
        assertEquals(84, result.volume);
        assertEquals("", result.selectedAssetId);
        assertEquals(2, result.assets.size());
        assertEquals(imported.id, result.assets.get(1).id);
    }

    @Test
    public void builtinSamplesExactlyMatchProbeToneContract() {
        assertArrayEquals(expected(new int[]{880, 660}), AvasBuiltinSounds.samples("lock"));
        assertArrayEquals(expected(new int[]{660, 880}), AvasBuiltinSounds.samples("unlock"));
        assertArrayEquals(expected(new int[]{880, 660, 440}),
                AvasBuiltinSounds.samples("power_off"));
        assertArrayEquals(expected(new int[]{440, 660, 880}),
                AvasBuiltinSounds.samples("power_on"));
        assertThrows(IllegalArgumentException.class, () -> AvasBuiltinSounds.samples("test_2"));
    }

    @Test
    public void protectedAndMissingAssetsCannotBeRemoved() {
        library.ensureBuiltins();
        String builtin = AvasBuiltinSounds.assetId("lock");

        assertFalse(library.removeAsset("lock", builtin));
        assertFalse(library.removeAsset("lock", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertTrue(library.preparedFile(builtin).isFile());
        assertThrows(IllegalArgumentException.class,
                () -> library.removeAsset("../lock", builtin));
        assertThrows(IllegalArgumentException.class,
                () -> library.removeAsset("lock", "../asset"));
    }

    @Test
    public void removingSelectedImportFallsBackToBuiltinAndDeletesPrivateCopies() throws Exception {
        library.ensureBuiltins();
        AvasConfig.Asset imported = addImport("lock", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", true);

        assertTrue(library.removeAsset("lock", imported.id));

        AvasConfig.Profile result = library.loadConfig().profile("lock");
        assertEquals(AvasBuiltinSounds.assetId("lock"), result.selectedAssetId);
        assertFalse(contains(result.assets, imported.id));
        assertFalse(sourceFile("lock", imported.id).exists());
        assertFalse(library.preparedFile(imported.id).exists());
    }

    @Test
    public void removingNonselectedImportPreservesSelectionAndOtherFiles() throws Exception {
        library.ensureBuiltins();
        AvasConfig.Asset removed = addImport("unlock", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", false);
        AvasConfig.Asset retained = addImport("unlock", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", false);
        String builtin = AvasBuiltinSounds.assetId("unlock");

        assertTrue(library.removeAsset("unlock", removed.id));

        AvasConfig.Profile result = library.loadConfig().profile("unlock");
        assertEquals(builtin, result.selectedAssetId);
        assertFalse(contains(result.assets, removed.id));
        assertTrue(contains(result.assets, retained.id));
        assertTrue(sourceFile("unlock", retained.id).isFile());
        assertTrue(library.preparedFile(retained.id).isFile());
    }

    @Test
    public void importPublicationMergesAgainstLatestConfigWithoutResurrection() {
        library.ensureBuiltins();
        AvasConfig.Asset removed = new AvasConfig.Asset(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Removed.wav");
        AvasConfig.Profile staleProfile = library.loadConfig().profile("power_on");
        List<AvasConfig.Asset> staleAssets = new ArrayList<>(staleProfile.assets);
        staleAssets.add(removed);
        library.saveConfig(library.loadConfig().withProfile(
                staleProfile.withAssets(staleAssets, staleProfile.selectedAssetId)));
        assertTrue(library.removeAsset("power_on", removed.id));

        AvasConfig.Asset added = new AvasConfig.Asset(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Added.wav");
        try {
            Files.write(sourceFile("power_on", added.id).toPath(), new byte[]{1});
            Files.write(library.preparedFile(added.id).toPath(), new byte[]{2});
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        AvasConfig latest = library.loadConfig();
        AvasConfig.Profile changed = latest.profile("power_on")
                .withSettings(true, true, 91, latest.profile("power_on").selectedAssetId);
        library.saveConfig(latest.withProfile(changed));

        AvasConfig.Profile result = library.mergeImportedAssets("power_on",
                new AvasAudioLibrary.ImportResult(Collections.singletonList(added), 0))
                .profile("power_on");
        assertFalse(contains(result.assets, removed.id));
        assertTrue(contains(result.assets, added.id));
        assertTrue(result.enabled);
        assertTrue(result.random);
        assertEquals(91, result.volume);
    }

    @Test
    public void importPublicationSkipsAssetsWhosePrivateCopiesAreMissing() {
        library.ensureBuiltins();
        AvasConfig.Asset missing = new AvasConfig.Asset(
                "dddddddddddddddddddddddddddddddd", "Missing.wav");
        int transactions = preferences.transactions;

        AvasConfig.Profile result = library.mergeImportedAssets("lock",
                new AvasAudioLibrary.ImportResult(Collections.singletonList(missing), 0))
                .profile("lock");

        assertFalse(contains(result.assets, missing.id));
        assertEquals(transactions, preferences.transactions);
    }

    @Test
    public void profileSettingsUpdateUsesLatestValuesAndNullMeansUnchanged() {
        library.ensureBuiltins();
        String selected = AvasBuiltinSounds.assetId("power_off");

        AvasConfig.Profile first = library.updateProfileSettings(
                "power_off", true, null, 67, null).profile("power_off");
        AvasConfig.Profile second = library.updateProfileSettings(
                "power_off", null, true, null, selected).profile("power_off");

        assertTrue(first.enabled);
        assertFalse(first.random);
        assertEquals(67, first.volume);
        assertTrue(second.enabled);
        assertTrue(second.random);
        assertEquals(67, second.volume);
        assertEquals(selected, second.selectedAssetId);
        assertThrows(IllegalArgumentException.class,
                () -> library.updateProfileSettings("power_off", null, null, 101, null));
    }

    @Test
    public void durationReturnsUnavailableForMissingOrMalformedPreparedFile() throws Exception {
        String id = "cccccccccccccccccccccccccccccccc";
        assertNull(library.durationMillis(id));
        Files.write(library.preparedFile(id).toPath(), new byte[]{1, 2, 3});
        assertNull(library.durationMillis(id));
        assertThrows(IllegalArgumentException.class, () -> library.durationMillis("bad"));
    }

    private AvasConfig.Asset addImport(String profileId, String id, boolean selected) throws Exception {
        AvasConfig.Asset asset = new AvasConfig.Asset(id, id + ".wav");
        AvasConfig config = library.loadConfig();
        AvasConfig.Profile profile = config.profile(profileId);
        List<AvasConfig.Asset> assets = new ArrayList<>(profile.assets);
        assets.add(asset);
        String selection = selected ? id : profile.selectedAssetId;
        library.saveConfig(config.withProfile(profile.withAssets(assets, selection)));
        File source = sourceFile(profileId, id);
        assertTrue(source.getParentFile().isDirectory() || source.getParentFile().mkdirs());
        Files.write(source.toPath(), new byte[]{7});
        Files.write(library.preparedFile(id).toPath(), new byte[]{8});
        return asset;
    }

    private File sourceFile(String profileId, String assetId) {
        return new File(new File(sourceRoot, profileId), assetId + ".source");
    }

    private static boolean contains(List<AvasConfig.Asset> assets, String id) {
        for (AvasConfig.Asset asset : assets) if (id.equals(asset.id)) return true;
        return false;
    }

    private static short[] expected(int[] notes) {
        int frames = 48_000;
        int noteFrames = frames / notes.length;
        short[] pcm = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            double time = frame / 48_000.0;
            double frequency = notes[frame / noteFrames];
            double edge = Math.min(1, Math.min((frame % noteFrames) / 960.0,
                    ((noteFrames - 1) - frame % noteFrames) / 960.0));
            short sample = (short) (Math.sin(2 * Math.PI * frequency * time) * 16383 * edge);
            pcm[frame * 2] = pcm[frame * 2 + 1] = sample;
        }
        return pcm;
    }
}
