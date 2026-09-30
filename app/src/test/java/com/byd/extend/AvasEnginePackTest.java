package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public final class AvasEnginePackTest {
    private static final String[] PACK_IDS = {
            "ferrari_v8", "jaguar_v6", "huracan_v10", "german_l4"
    };

    @Test
    public void everyBundledPackLoadsAndRendersIdleMidAndMaximumRpm() throws Exception {
        Path assets = Paths.get("src/main/assets/avas_engines");
        if (!Files.isDirectory(assets)) assets = Paths.get("app/src/main/assets/avas_engines");
        assertTrue("bundled engine assets are missing", Files.isDirectory(assets));

        for (String id : PACK_IDS) verifyPack(assets.resolve(id), id);
    }

    private static void verifyPack(Path directory, String id) throws Exception {
        String manifest = new String(Files.readAllBytes(directory.resolve("manifest.json")),
                StandardCharsets.UTF_8);
        AvasEnginePack pack = AvasEnginePack.fromManifest(id, manifest,
                name -> Files.readAllBytes(directory.resolve(name)));
        AvasEngineSynth synth = new AvasEngineSynth(pack);
        float[] block = new float[AvasEnginePack.SAMPLE_RATE];
        float[] rpms = {pack.idleRpm, (pack.idleRpm + pack.maxRpm) / 2.0f, pack.maxRpm};
        for (float rpm : rpms) {
            synth.reset();
            synth.render(block, 0, block.length, rpm, 0.5f);
            float peak = 0.0f;
            for (float sample : block) {
                assertTrue(id + " produced a non-finite sample", Float.isFinite(sample));
                peak = Math.max(peak, Math.abs(sample));
            }
            assertTrue(id + " produced clipped output", peak <= 1.0f);
            assertTrue(id + " produced silent output", peak > 0.0001f);
        }
    }
}
