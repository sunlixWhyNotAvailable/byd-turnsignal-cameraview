package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assume;
import org.junit.Test;

public final class ReverseSteeringModelConfigTest {
    @Test public void shellSnapshotValidatesBoundsAndIdentityWithoutOemLookup() {
        ReverseSteeringModelConfig snapshot = new ReverseSteeringModelConfig(-543.163f, 543.163f,
                "286/ocean/uked");
        org.junit.Assert.assertTrue(snapshot.sameModel(
                new ReverseSteeringModelConfig(-543.163f, 543.163f, "286/ocean/uked")));
        org.junit.Assert.assertFalse(snapshot.sameModel(null));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new ReverseSteeringModelConfig(Float.NaN, 543f, "model"));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new ReverseSteeringModelConfig(-543f, 543f, ""));
    }

    @Test public void readsOnlyExplicitActiveModelBoundsInMillidegrees() throws Exception {
        ReverseSteeringModelConfig config = ReverseSteeringModelConfig.fromJson(
                "series/body/trim",
                "{\"PROJECT_CONFIG\":{\"AngleMin\":-543163,\"AngleMax\":543163}} ");

        assertEquals(-543.163f, config.minimumDegrees, .0001f);
        assertEquals(543.163f, config.maximumDegrees, .0001f);
        assertEquals("series/body/trim", config.model);
    }

    @Test public void missingOrInvalidBoundsAreUnavailableInsteadOfDefaulted() throws Exception {
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMax\":543163}}");
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMin\":-10,\"AngleMax\":10.5}}");
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMin\":0,\"AngleMax\":10}}");
        assertInvalid("{\"PROJECT_CONFIG\":{\"AngleMin\":-10,\"AngleMax\":0}}");
    }

    @Test public void normalizesOemCommentsAndMissingCommas() throws Exception {
        ReverseSteeringModelConfig config = ReverseSteeringModelConfig.fromJson(
                "ukea/ukea", "{\n\"PROJECT_CONFIG\":{\n# maximum\n"
                        + "\"AngleMax\":543163\n# minimum\n"
                        + "\"AngleMin\":-543163\n}\n}");

        assertEquals(-543.163f, config.minimumDegrees, .0001f);
        assertEquals(543.163f, config.maximumDegrees, .0001f);
    }

    @Test public void readsTheCheckedInUkeaAssetWhenResearchCheckoutIsPresent()
            throws Exception {
        Path asset = checkedInUkeaAsset();
        Assume.assumeTrue("UKEA research asset is outside this checkout", asset != null);
        String source = new String(Files.readAllBytes(asset), StandardCharsets.UTF_8);

        ReverseSteeringModelConfig config = ReverseSteeringModelConfig.fromJson(
                "ukea/ukea", source);

        assertEquals(-543.163f, config.minimumDegrees, .0001f);
        assertEquals(543.163f, config.maximumDegrees, .0001f);
    }

    private static void assertInvalid(String json) throws Exception {
        try {
            ReverseSteeringModelConfig.fromJson("body/trim", json);
            fail("expected missing or invalid explicit steering bounds");
        } catch (IllegalArgumentException expected) {
            // Missing, fractional, or non-signed model bounds must stay unavailable.
        }
    }

    private static Path checkedInUkeaAsset() {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; directory != null && depth < 8; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve("research/AutoVideo-jadx/resources/assets/"
                    + "resource_ts/ukea/CarModel/ukea/Vehicle_Configuration.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
}
