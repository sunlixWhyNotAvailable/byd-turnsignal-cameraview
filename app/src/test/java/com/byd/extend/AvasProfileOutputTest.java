package com.byd.extend;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class AvasProfileOutputTest {
    @org.junit.Rule public final org.junit.rules.TemporaryFolder files = new org.junit.rules.TemporaryFolder();

    @Test public void engineParkOptionSurvivesDiskRoundTripAndLegacyConfigDefaultsOn() throws Exception {
        AvasConfig config = AvasConfig.empty().withEngine(new AvasConfig.Engine(true,
                AvasEngineSettings.DEFAULT_PACK_ID, true, 30, false, 15, true, false, false));
        config = config.withProfile(config.profile("lock").withInternalSpeaker(true));
        java.io.File path = files.newFile("engine.json");
        Files.write(path.toPath(), config.toJson().getBytes(StandardCharsets.UTF_8));
        AvasConfig restored = AvasConfig.parse(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
        assertFalse(restored.engine.soundInPark);
        assertEquals(30, restored.engine.exteriorVolume);
        assertTrue(restored.profile("lock").internalSpeaker);

        JSONObject old = new JSONObject(restored.toJson());
        old.getJSONObject("engine").remove("soundInPark");
        assertTrue(AvasConfig.parse(old.toString()).engine.soundInPark);
        old.getJSONObject("engine").put("soundInPark", "false");
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(old.toString()));
    }

    @Test public void persistedRoutesAreIndependentAndSurviveProfileEdits() throws Exception {
        AvasConfig config = AvasConfig.empty();
        for (String id : AvasConfig.PROFILE_IDS) {
            assertFalse(config.profile(id).internalSpeaker);
            AvasConfig.Profile edited = config.profile(id).withInternalSpeaker(true)
                    .withSettings(true, true, 40, "")
                    .withSettings(false, true, 30, "", false)
                    .withSettings(false, false, 25, "", true, id.equals("power_off"))
                    .withAssets(Collections.emptyList(), "");
            assertTrue(edited.internalSpeaker);
            AvasConfig changed = config.withProfile(edited);
            java.io.File path = files.newFile(id + ".json");
            Files.write(path.toPath(), changed.toJson().getBytes(StandardCharsets.UTF_8));
            AvasConfig restored = AvasConfig.parse(new String(
                    Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
            assertTrue(restored.profile(id).internalSpeaker);
            assertEquals(25, restored.profile(id).volume);
            assertFalse(restored.profile(id).enabled);
            for (String other : AvasConfig.PROFILE_IDS) {
                if (!id.equals(other)) assertFalse(restored.profile(other).internalSpeaker);
            }
            assertFalse(restored.profile(id).withInternalSpeaker(false).internalSpeaker);
        }
    }

    @Test public void oldConfigurationsKeepExteriorOutput() throws Exception {
        for (int version = 1; version <= 4; version++) {
            JSONObject legacy = new JSONObject(AvasConfig.empty().toJson()).put("version", version);
            if (version < 3) legacy.remove("engine");
            for (int i = 0; i < 4; i++) {
                JSONObject profile = legacy.getJSONArray("profiles").getJSONObject(i);
                profile.remove("internalSpeaker");
                if (version < 4) profile.remove("onlyWhenLockingPoweredOff");
                if (version < 2) profile.remove("skipConcurrentLockUnlock");
            }
            AvasConfig restored = AvasConfig.parse(legacy.toString());
            for (AvasConfig.Profile profile : restored.profiles) assertFalse(profile.internalSpeaker);
        }
    }

    @Test public void currentSchemaRequiresBooleanRouteWithoutWeakeningAllowlist() throws Exception {
        JSONObject malformed = new JSONObject(AvasConfig.empty().toJson());
        JSONObject profile = malformed.getJSONArray("profiles").getJSONObject(0);
        profile.put("internalSpeaker", "true");
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(malformed.toString()));
        profile.remove("internalSpeaker");
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(malformed.toString()));
        profile.put("internalSpeaker", true).put("unknownRoute", true);
        assertThrows(IllegalArgumentException.class, () -> AvasConfig.parse(malformed.toString()));
    }

    @Test public void eventRouteChangesButFileListNoteRemainsNavigation() {
        for (AvasConfig.Profile profile : AvasConfig.empty().profiles) {
            assertEquals(AvasPlaybackPlan.Output.EXTERIOR, AvasPlaybackPlan.output(profile, false));
            assertEquals(AvasPlaybackPlan.Output.INTERIOR_MEDIA,
                    AvasPlaybackPlan.output(profile.withInternalSpeaker(true), false));
            assertEquals(AvasPlaybackPlan.Output.NAVIGATION, AvasPlaybackPlan.output(profile, true));
            assertEquals(AvasPlaybackPlan.Output.NAVIGATION,
                    AvasPlaybackPlan.output(profile.withInternalSpeaker(true), true));
        }
    }

    @Test public void cabinPcmGainMutesScalesAndClampsWholeSamples() {
        byte[] pcm = {(byte) 0xe8, 3, 0x18, (byte) 0xfc, (byte) 0xff, 0x7f};
        byte[] output = new byte[pcm.length];
        AvasWav.scalePcm16(pcm, 0, output, 0, pcm.length, 0);
        assertArrayEquals(new byte[pcm.length], output);
        AvasWav.scalePcm16(pcm, 0, output, 0, pcm.length, 50);
        assertArrayEquals(pcm, output);
        AvasWav.scalePcm16(pcm, 0, output, 0, pcm.length, 100);
        assertArrayEquals(new byte[]{(byte) 0xd0, 7, 0x30, (byte) 0xf8, (byte) 0xff, 0x7f}, output);
        assertEquals(246, AvasPlaybackPlan.tailPaddingFrames(10, 256));
        assertEquals(0, AvasPlaybackPlan.tailPaddingFrames(300, 256));
    }
}
