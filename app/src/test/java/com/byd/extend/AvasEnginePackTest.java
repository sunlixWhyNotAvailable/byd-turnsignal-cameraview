package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class AvasEnginePackTest {

    @Test
    public void everyBundledPackLoadsAndRendersIdleMidAndMaximumRpm() throws Exception {
        Path assets = Paths.get("src/main/assets/avas_engines");
        if (!Files.isDirectory(assets)) assets = Paths.get("app/src/main/assets/avas_engines");
        assertTrue("bundled engine assets are missing", Files.isDirectory(assets));

        for (String id : AvasEngineSettings.PACK_IDS) verifyPack(assets.resolve(id), id);
    }

    private static void verifyPack(Path directory, String id) throws Exception {
        String manifest = new String(Files.readAllBytes(directory.resolve("manifest.json")),
                StandardCharsets.UTF_8);
        AvasEnginePack pack = AvasEnginePack.fromManifest(id, manifest,
                name -> Files.readAllBytes(directory.resolve(name)));
        assertEquals(id + " idle calibration", -24.0, dbfs(pack.idle), 0.02);
        calibratedCue(id + " start", pack.start);
        calibratedCue(id + " stop", pack.stop);
        int[] expectedFrames;
        switch (id) {
            case "jaguar_v6": expectedFrames = new int[]{233350, 104585}; break;
            case "ferrari_v8": expectedFrames = new int[]{129544, 47738}; break;
            case "huracan_v10": expectedFrames = new int[]{206634, 174941}; break;
            case "maserati_v8": expectedFrames = new int[]{309450, 140275}; break;
            case "g500_v8": expectedFrames = new int[]{174025, 209075}; break;
            case "golf_gti_l4": expectedFrames = new int[]{123050, 98133}; break;
            case "porsche_gt3_h6": expectedFrames = new int[]{162000, 152400}; break;
            case "harley_vtwin": expectedFrames = new int[]{228452, 114511}; break;
            default: expectedFrames = new int[]{31200, 31200};
        }
        assertEquals(id + " full start recording", expectedFrames[0], pack.start.length);
        assertEquals(id + " full stop recording", expectedFrames[1], pack.stop.length);
        verifyStartTransition(pack);
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
            assertTrue(id + " " + layer.rpm + " exceeds calibration target",
                    onDb <= reference + 3.02 && offDb <= reference - 2.98);
            // Preserve original dynamics: lower both gains if either reaches the peak ceiling.
            assertTrue(id + " " + layer.rpm + " is uncalibrated",
                    Math.abs(onDb - reference - 3.0) <= 0.02
                            || Math.abs(20 * Math.log10(Math.max(peak(layer.on),
                                    peak(layer.off))) + 6.0) <= 0.02);
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

    private static void verifyStartTransition(AvasEnginePack pack) {
        AvasEngineSynth synth = new AvasEngineSynth(pack);
        AvasEngineSynth reference = new AvasEngineSynth(pack);
        float[] mixed = new float[480];
        float[] running = new float[480];
        int copied = 0;
        for (int at = 0; at < pack.start.length; at += mixed.length) {
            int count = Math.min(mixed.length, pack.start.length - at);
            synth.renderStart(mixed, count, at, pack.idleRpm, 0);
            if (pack.startBlendFull != 0) reference.render(running, 0, count, pack.idleRpm, 0);
            for (int i = 0; i < count; i++) {
                assertTrue(Float.isFinite(mixed[i]) && Math.abs(mixed[i]) <= 1f);
                if (pack.startBlendFull == 0 || at + i <= pack.startBlendFrom) {
                    assertEquals(pack.id + " unchanged cue prefix", pack.start[at + i], mixed[i], 0f);
                } else if (at + i >= pack.startBlendFull) {
                    assertEquals(pack.id + " full running overlap", pack.start[at + i] + running[i],
                            mixed[i], .000001f);
                }
            }
            copied += count;
        }
        assertEquals(pack.start.length, copied);
        if (pack.startBlendFull != 0) {
            synth.render(mixed, 0, mixed.length, pack.idleRpm, 0);
            reference.render(running, 0, running.length, pack.idleRpm, 0);
            org.junit.Assert.assertArrayEquals("running phase must continue", running, mixed, 0f);
        }
    }

    private static double dbfs(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) sum += sample * sample;
        return 20.0 * Math.log10(Math.sqrt(sum / samples.length));
    }

    private static void calibratedCue(String name, float[] samples) {
        double level = dbfs(samples);
        assertTrue(name + " exceeds target RMS", level <= -23.98);
        // A high-crest-factor original may hit the peak ceiling before the RMS target.
        assertTrue(name + " is uncalibrated", Math.abs(level + 24) <= 0.02
                || Math.abs(20 * Math.log10(peak(samples)) + 6) <= 0.02);
    }

    private static float peak(float[] samples) {
        float peak = 0.0f;
        for (float sample : samples) peak = Math.max(peak, Math.abs(sample));
        return peak;
    }
}
