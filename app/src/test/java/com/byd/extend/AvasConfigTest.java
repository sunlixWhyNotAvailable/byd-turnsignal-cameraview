package com.byd.extend;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class AvasConfigTest {
    @Test
    public void emptyHasFixedSafeDefaults() {
        AvasConfig config = AvasConfig.empty();

        assertEquals(AvasConfig.PROFILE_IDS, ids(config.profiles));
        for (AvasConfig.Profile profile : config.profiles) {
            assertFalse(profile.enabled);
            assertFalse(profile.random);
            assertEquals(15, profile.volume);
            assertEquals("", profile.selectedAssetId);
            assertTrue(profile.assets.isEmpty());
        }
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
}
