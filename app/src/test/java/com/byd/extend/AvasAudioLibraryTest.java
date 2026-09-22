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
                .withSettings(true, true, 84, "", false);
        library.saveConfig(AvasConfig.empty().withProfile(lock));

        library.ensureBuiltins();

        AvasConfig.Profile result = library.loadConfig().profile("lock");
        assertTrue(result.enabled);
        assertTrue(result.random);
        assertEquals(84, result.volume);
        assertEquals("", result.selectedAssetId);
        assertEquals(2, result.assets.size());
        assertEquals(imported.id, result.assets.get(1).id);
        assertFalse(result.skipConcurrentLockUnlock);
    }

    @Test
    public void builtinsHavePlayableStereoPcmAndTheExpectedToneOrder() throws Exception {
        java.util.Map<String, int[]> frequencies = java.util.Map.of(
                "lock", new int[]{880, 660}, "unlock", new int[]{660, 880},
                "power_off", new int[]{880, 660, 440}, "power_on", new int[]{440, 660, 880});
        for (java.util.Map.Entry<String, int[]> entry : frequencies.entrySet()) {
            short[] pcm = AvasBuiltinSounds.samples(entry.getKey());
            assertEquals(48_000 * 2, pcm.length); // one second, two channels
            int peak = 0;
            for (int frame = 0; frame < 48_000; frame++) {
                assertEquals(pcm[frame * 2], pcm[frame * 2 + 1]);
                peak = Math.max(peak, Math.abs((int) pcm[frame * 2]));
            }
            assertTrue("non-silent PCM without full-scale clipping", peak > 0 && peak < Short.MAX_VALUE);
            int[] tones = entry.getValue();
            for (int note = 0; note < tones.length; note++) {
                int noteStart = note * (48_000 / tones.length);
                assertEquals("fade at note boundary", 0, pcm[noteStart * 2]);
                // Count measured positive zero crossings in 100 ms, away from fades.
                // This oracle does not reproduce the sine/envelope generator.
                int start = noteStart + 2400;
                int crossings = 0;
                for (int frame = start + 1; frame <= start + 4800; frame++) {
                    if (pcm[(frame - 1) * 2] <= 0 && pcm[frame * 2] > 0) crossings++;
                }
                assertEquals(entry.getKey() + " note " + note,
                        tones[note] / 10.0, crossings, 1.0);
            }
            assertEquals(0, pcm[pcm.length - 1]);
            File wav = temporary.newFile(entry.getKey() + ".wav");
            AvasBuiltinSounds.writeWav(entry.getKey(), wav);
            AvasWav.Header header = AvasWav.read(wav);
            assertEquals(48_000, header.sampleRate);
            assertEquals(2, header.channels);
            byte[] data = AvasWav.readPcm(wav, header, 0);
            assertEquals(pcm.length * 2, data.length);
            java.nio.ShortBuffer encoded = java.nio.ByteBuffer.wrap(data)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            short[] decoded = new short[pcm.length];
            encoded.get(decoded);
            assertArrayEquals(pcm, decoded);
        }
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
                .withSettings(true, true, 91, latest.profile("power_on").selectedAssetId, false);
        library.saveConfig(latest.withProfile(changed));

        AvasConfig.Profile result = library.mergeImportedAssets("power_on",
                new AvasAudioLibrary.ImportResult(Collections.singletonList(added), 0))
                .profile("power_on");
        assertFalse(contains(result.assets, removed.id));
        assertTrue(contains(result.assets, added.id));
        assertTrue(result.enabled);
        assertTrue(result.random);
        assertEquals(91, result.volume);
        assertFalse(result.skipConcurrentLockUnlock);
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
        AvasConfig.Profile third = library.updateProfileSettings(
                "power_off", null, null, null, null, false).profile("power_off");

        assertTrue(first.enabled);
        assertFalse(first.random);
        assertEquals(67, first.volume);
        assertTrue(second.enabled);
        assertTrue(second.random);
        assertEquals(67, second.volume);
        assertEquals(selected, second.selectedAssetId);
        assertFalse(third.skipConcurrentLockUnlock);
        assertTrue(third.enabled);
        assertTrue(third.random);
        assertEquals(67, third.volume);
        assertThrows(IllegalArgumentException.class,
                () -> library.updateProfileSettings("power_off", null, null, 101, null));
    }

    @Test
    public void deleteAndAssetCopyPreserveSkipFlag() throws Exception {
        library.ensureBuiltins();
        library.updateProfileSettings("power_off", null, null, null, null, false);
        AvasConfig.Asset imported = addImport(
                "power_off", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", true);

        assertTrue(library.removeAsset("power_off", imported.id));
        assertFalse(library.loadConfig().profile("power_off").skipConcurrentLockUnlock);
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

}
