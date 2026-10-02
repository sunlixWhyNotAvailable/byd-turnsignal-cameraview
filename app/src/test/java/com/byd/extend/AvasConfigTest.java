package com.byd.extend;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class AvasConfigTest {
    @Test public void navigationPriorityDefaultsOnForOldConfigAndPreservesExplicitOff() throws Exception {
        AvasConfig disabled = AvasConfig.empty().withEngine(new AvasConfig.Engine(true,
                "harley_vtwin", true, 45, true, 32, false));
        assertFalse(AvasConfig.parse(disabled.toJson()).engine.navigationPriority);
        JSONObject legacy = new JSONObject(disabled.toJson());
        legacy.getJSONObject("engine").remove("navigationPriority");
        AvasConfig migrated = AvasConfig.parse(legacy.toString());
        assertTrue(migrated.engine.navigationPriority);
        assertEquals(45, migrated.engine.exteriorVolume);
        assertEquals(32, migrated.engine.interiorVolume);
        assertEquals("harley_vtwin", migrated.engine.packId);
        legacy.getJSONObject("engine").put("navigationPriority", "false");
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(legacy.toString()));
    }

    @Test
    public void emptyHasFixedSafeDefaults() {
        AvasConfig config = AvasConfig.empty();

        assertEquals(AvasConfig.PROFILE_IDS, ids(config.profiles));
        for (AvasConfig.Profile profile : config.profiles) {
            assertFalse(profile.enabled);
            assertFalse(profile.random);
            assertEquals(15, profile.volume);
            assertTrue(profile.skipConcurrentLockUnlock);
            assertEquals("", profile.selectedAssetId);
            assertTrue(profile.assets.isEmpty());
        }
        assertFalse(config.engine.enabled);
        assertEquals("ferrari_v8", config.engine.packId);
        assertTrue(config.engine.exteriorEnabled);
        assertEquals(15, config.engine.exteriorVolume);
        assertFalse(config.engine.interiorEnabled);
        assertEquals(15, config.engine.interiorVolume);
    }

    @Test
    public void versionThreeRoundTripsEngineAndIndependentSkipFlags() {
        AvasConfig config = AvasConfig.empty()
                .withProfile(AvasConfig.empty().profile("power_on")
                        .withSettings(true, false, 31, "", false))
                .withProfile(AvasConfig.empty().profile("power_off")
                        .withSettings(false, true, 62, "", true))
                .withEngine(new AvasConfig.Engine(true, "jaguar_v6", false, 88, true, 41));

        AvasConfig parsed = AvasConfig.parse(config.toJson());

        assertTrue(parsed.toJson().contains("\"version\":3"));
        assertFalse(parsed.profile("power_on").skipConcurrentLockUnlock);
        assertTrue(parsed.profile("power_off").skipConcurrentLockUnlock);
        assertTrue(parsed.profile("power_on").enabled);
        assertTrue(parsed.profile("power_off").random);
        assertTrue(parsed.engine.enabled);
        assertEquals("jaguar_v6", parsed.engine.packId);
        assertFalse(parsed.engine.exteriorEnabled);
        assertEquals(88, parsed.engine.exteriorVolume);
        assertTrue(parsed.engine.interiorEnabled);
        assertEquals(41, parsed.engine.interiorVolume);
        assertEquals(config.engine.packId,
                parsed.withProfile(parsed.profile("lock").withSettings(true, false, 15, ""))
                        .engine.packId);
    }

    @Test
    public void versionOneMigratesToDefaultOnWithoutLosingProfilesOrAssets() throws Exception {
        AvasConfig.Asset asset = new AvasConfig.Asset(
                "0123456789abcdef0123456789abcdef", "Legacy.wav");
        AvasConfig.Profile legacy = AvasConfig.empty().profile("power_on")
                .withAssets(List.of(asset), asset.id)
                .withSettings(true, true, 88, asset.id, false);
        String versionOne = legacyJson(AvasConfig.empty().withProfile(legacy), 1);

        AvasConfig migrated = AvasConfig.parse(versionOne);

        AvasConfig.Profile result = migrated.profile("power_on");
        assertTrue(result.skipConcurrentLockUnlock);
        assertTrue(result.enabled);
        assertTrue(result.random);
        assertEquals(88, result.volume);
        assertEquals(asset.id, result.selectedAssetId);
        assertEquals("Legacy.wav", result.assets.get(0).name);
        assertFalse(migrated.engine.enabled);
        assertEquals("ferrari_v8", migrated.engine.packId);
        assertTrue(migrated.engine.exteriorEnabled);
    }

    @Test
    public void versionTwoMigratesAllProfilesAndKeepsEngineOffDefaults() throws Exception {
        AvasConfig config = AvasConfig.empty().withProfile(AvasConfig.empty()
                .profile("power_on").withSettings(true, true, 38, "", false));

        AvasConfig migrated = AvasConfig.parse(legacyJson(config, 2));

        assertTrue(migrated.profile("power_on").enabled);
        assertTrue(migrated.profile("power_on").random);
        assertFalse(migrated.profile("power_on").skipConcurrentLockUnlock);
        assertFalse(migrated.engine.enabled);
        assertEquals("ferrari_v8", migrated.engine.packId);
        assertTrue(migrated.engine.exteriorEnabled);
        assertEquals(15, migrated.engine.exteriorVolume);
        assertFalse(migrated.engine.interiorEnabled);
        assertEquals(15, migrated.engine.interiorVolume);
    }

    @Test
    public void versionThreeRequiresStrictValuesAndExactAllowlist() {
        String valid = AvasConfig.empty().toJson();
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(
                valid.replace("\"skipConcurrentLockUnlock\":true",
                        "\"skipConcurrentLockUnlock\":\"true\"")));
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(
                valid.replaceFirst(",\"skipConcurrentLockUnlock\":true", "")));
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(
                valid.replaceFirst("\"skipConcurrentLockUnlock\":true",
                        "\"skipConcurrentLockUnlock\":true,\"extra\":false")));
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(
                valid.replace("\"packId\":\"ferrari_v8\"", "\"packId\":\"unknown\"")));
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(
                valid.replace("\"exteriorVolume\":15", "\"exteriorVolume\":101")));
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(
                valid.replace("\"interiorEnabled\":false", "\"interiorEnabled\":\"false\"")));
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(
                valid.replace("\"interiorVolume\":15", "\"interiorVolume\":15,\"extra\":0")));
    }

    @Test
    public void withProfilePreservesEngineAndRejectsInvalidEngineValues() {
        AvasConfig.Engine engine = new AvasConfig.Engine(true, "german_l4", true, 0, false, 100);
        AvasConfig config = AvasConfig.empty().withEngine(engine);

        assertSame(engine, config.withProfile(config.profile("lock")
                .withSettings(true, false, 22, "")).engine);
        assertThrows(IllegalArgumentException.class,
                () -> new AvasConfig.Engine(false, "../pack", true, 15, false, 15));
        assertThrows(IllegalArgumentException.class,
                () -> new AvasConfig.Engine(false, "ferrari_v8", true, 101, false, 15));
        assertThrows(IllegalArgumentException.class,
                () -> new AvasConfig.Engine(false, "ferrari_v8", true, 15, false, -1));
    }

    @Test
    public void immutableConfigRoundTripsUnicodeAssets() {
        AvasConfig.Asset asset = new AvasConfig.Asset(
                "0123456789abcdef0123456789abcdef", "Закриття 車.wav");
        AvasConfig.Profile lock = AvasConfig.empty().profile("lock")
                .withAssets(List.of(asset), asset.id)
                .withSettings(true, true, 73, asset.id);
        AvasConfig config = AvasConfig.empty().withProfile(lock);

        AvasConfig parsed = AvasConfig.parse(config.toJson());

        assertEquals(config.toJson(), parsed.toJson());
        assertEquals("Закриття 車.wav", parsed.profile("lock").assets.get(0).name);
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.profiles.add(lock));
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.profile("lock").assets.add(asset));
    }

    @Test
    public void rejectsUnsafeOrInconsistentTransferInput() {
        String valid = AvasConfig.empty().toJson();
        assertThrows(IllegalArgumentException.class,
                () -> AvasConfig.parse(valid.replace("\"volume\":15", "\"volume\":101")));
        assertThrows(IllegalArgumentException.class,
                () -> AvasConfig.parse(valid.replace("\"enabled\":false", "\"enabled\":\"false\"")));
        assertThrows(IllegalArgumentException.class,
                () -> AvasConfig.parse(valid.replaceFirst("\"lock\"", "\"../lock\"")));
        assertThrows(IllegalArgumentException.class,
                () -> new AvasConfig.Asset("../asset", "name"));

        AvasConfig.Asset asset = new AvasConfig.Asset(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "one");
        List<AvasConfig.Profile> profiles = new ArrayList<>(AvasConfig.empty().profiles);
        profiles.set(0, profiles.get(0).withAssets(List.of(asset), asset.id));
        profiles.set(1, profiles.get(1).withAssets(List.of(asset), asset.id));
        assertThrows(IllegalArgumentException.class, () -> new AvasConfig(profiles));
        assertThrows(IllegalArgumentException.class,
                () -> AvasConfig.empty().profile("lock").withSettings(false, false, 15, asset.id));
    }

    @Test
    public void duplicateDisplayNamesReceiveStableSuffixes() {
        HashSet<String> names = new HashSet<>(List.of("tone.wav", "tone (2).wav"));
        assertEquals("tone (3).wav", AvasAudioLibrary.uniqueName("tone.wav", names));
        assertEquals("звук.wav", AvasAudioLibrary.uniqueName(" звук.wav ", names));
        String longName = "x." + "a".repeat(254);
        String duplicate = AvasAudioLibrary.uniqueName(longName, Set.of(longName));
        assertEquals(256, duplicate.length());
        assertTrue(duplicate.contains(" (2)"));
    }

    private static List<String> ids(List<AvasConfig.Profile> profiles) {
        List<String> ids = new ArrayList<>();
        for (AvasConfig.Profile profile : profiles) ids.add(profile.id);
        return ids;
    }

    private static String legacyJson(AvasConfig config, int version) throws Exception {
        JSONObject legacy = new JSONObject(config.toJson());
        legacy.remove("engine");
        if (version == 1) {
            JSONArray profiles = legacy.getJSONArray("profiles");
            for (int index = 0; index < profiles.length(); index++) {
                profiles.getJSONObject(index).remove("skipConcurrentLockUnlock");
            }
        }
        legacy.put("version", version);
        return legacy.toString();
    }
}
