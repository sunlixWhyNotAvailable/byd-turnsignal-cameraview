package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

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
        assertEquals(id + " idle calibration", -24.0, dbfs(pack.idle), 0.02);
        assertEquals(id + " start calibration", -24.0, dbfs(pack.start), 0.02);
        assertEquals(id + " stop calibration", -24.0, dbfs(pack.stop), 0.02);
        assertTrue(id + " idle has headroom", peak(pack.idle) <= 0.503f);
        assertTrue(id + " start has headroom", peak(pack.start) <= 0.503f);
        assertTrue(id + " stop has headroom", peak(pack.stop) <= 0.503f);
        double previousOn = Double.NaN;
        for (AvasEnginePack.Layer layer : pack.layers) {
            double onDb = dbfs(layer.on);
            double offDb = dbfs(layer.off);
            double fraction = (layer.rpm - pack.idleRpm)
                    / (double) (pack.maxRpm - pack.idleRpm);
            double reference = -24.0 + 4.0 * Math.sqrt(fraction);
            assertEquals(id + " " + layer.rpm + " on calibration",
                    reference + 3.0, onDb, 0.02);
            assertEquals(id + " " + layer.rpm + " off calibration",
                    reference - 3.0, offDb, 0.02);
            assertTrue(id + " " + layer.rpm + " pair has a 6dB load gap",
                    Math.abs((onDb - offDb) - 6.0) <= 0.03);
            assertTrue(id + " " + layer.rpm + " on layer has headroom",
                    peak(layer.on) <= 0.503f);
            assertTrue(id + " " + layer.rpm + " off layer has headroom",
                    peak(layer.off) <= 0.503f);
            if (Double.isFinite(previousOn)) {
                assertTrue(id + " adjacent on bands are smooth",
                        Math.abs(onDb - previousOn) < 2.0);
            }
            previousOn = onDb;
        }
        AvasEngineSynth synth = new AvasEngineSynth(pack);
        float[] block = new float[AvasEnginePack.SAMPLE_RATE];
        float[] rpms = {pack.idleRpm, (pack.idleRpm + pack.maxRpm) / 2.0f, pack.maxRpm};
        for (float rpm : rpms) {
            synth.reset();
            synth.render(block, 0, block.length, rpm, 0.5f);
            for (float sample : block) {
                assertTrue(id + " produced a non-finite sample", Float.isFinite(sample));
            }
            float renderedPeak = peak(block);
            assertTrue(id + " produced clipped output", renderedPeak <= 0.503f);
            assertTrue(id + " produced silent output", renderedPeak > 0.0001f);
        }
    }

    private static double dbfs(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) sum += sample * sample;
        return 20.0 * Math.log10(Math.sqrt(sum / samples.length));
    }

    private static float peak(float[] samples) {
        float peak = 0.0f;
        for (float sample : samples) peak = Math.max(peak, Math.abs(sample));
        return peak;
    }
}
