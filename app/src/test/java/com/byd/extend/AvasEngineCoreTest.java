package com.byd.extend;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class AvasEngineCoreTest {
    @Test
    public void loadsCanonicalPcm16Mono48kPackAssets() throws Exception {
        Map<String, byte[]> assets = fixtureWavs();
        AvasEnginePack pack = AvasEnginePack.fromManifest("ferrari_v8", manifest(),
                name -> assets.get(name));

        assertEquals("Test V8", pack.name);
        assertEquals(1_000, pack.idleRpm);
        assertEquals(3_000, pack.maxRpm);
        assertEquals(48_000, pack.sampleRate);
        assertEquals(0.25f, pack.idle[0], 0.0001f);
        assertEquals(0.4f, pack.layers[0].on[0], 0.0001f);
        assertEquals(-0.2f, pack.layers[1].off[0], 0.0001f);
        assertEquals(7, assets.size());
    }

    @Test
    public void appliesFixedManifestGainsToDecodedSamples() throws Exception {
        String calibrated = manifest()
                .replace("\"idle\":\"idle.wav\"", "\"idle\":\"idle.wav\",\"idleGainDb\":6.020599913")
                .replace("\"start\":\"start.wav\"", "\"start\":\"start.wav\",\"startGainDb\":-6.020599913")
                .replace("\"on\":\"low-on.wav\"", "\"on\":\"low-on.wav\",\"onGainDb\":6.020599913")
                .replace("\"off\":\"high-off.wav\"", "\"off\":\"high-off.wav\",\"offGainDb\":6.020599913");
        AvasEnginePack pack = AvasEnginePack.fromManifest("ferrari_v8", calibrated,
                fixtureWavs()::get);

        assertEquals(0.5f, pack.idle[0], 0.0001f);
        assertEquals(0.25f, pack.start[0], 0.0001f);
        assertEquals(0.8f, pack.layers[0].on[0], 0.0001f);
        assertEquals(-0.4f, pack.layers[1].off[0], 0.0001f);
    }

    @Test
    public void rejectsUnknownPackIdsUnsafePathsAndWrongWavRate() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> AvasEnginePack.fromManifest("arbitrary", manifest(), name -> new byte[0]));
        assertThrows(IOException.class, () -> AvasEnginePack.fromManifest("ferrari_v8",
                manifest().replace("idle.wav", "../other.wav"), name -> new byte[0]));
        assertThrows(IOException.class, () -> AvasEnginePack.fromManifest("ferrari_v8",
                manifest().replace("\"idle\":\"idle.wav\"",
                        "\"idle\":\"idle.wav\",\"idleGainDb\":25"), fixtureWavs()::get));

        Map<String, byte[]> assets = fixtureWavs();
        assets.put("idle.wav", wav(44_100, (short) 1));
        assertThrows(IOException.class, () -> AvasEnginePack.fromManifest(
                "ferrari_v8", manifest(), assets::get));
    }

    @Test
    public void parkAndNeutralRevFromPedalWithoutSpeedAndStateIsReused() {
        AvasEngineModel model = new AvasEngineModel(800, 6_000);
        AvasEngineModel.State state = model.update(0, Float.NaN, false,
                100, true, 0, true, 1, true);
        assertEquals(800.0f, state.rpm, 0.01f);
        assertEquals(0.0f, state.load, 0.001f);
        assertEquals(0, state.gear);
        assertTrue(state.valid);

        AvasEngineModel.State next = model.update(1_000, Float.NaN, false,
                50, true, 0, true, 3, true);
        assertSame(state, next);
        assertTrue(next.rpm > 800.0f);
        assertEquals(0, next.gear);
    }

    @Test
    public void fullPedalParkRevReachesNinetyFivePercentAroundNineHundredMs() {
        AvasEngineModel model = new AvasEngineModel(800, 6_000);
        AvasEngineModel.State state = model.update(0, Float.NaN, false,
                100, true, 0, true, 1, true);
        assertEquals(800.0f, state.rpm, 0.01f);
        assertEquals(0.0f, state.load, 0.001f);

        state = model.update(20, Float.NaN, false, 100, true,
                0, true, 1, true);
        assertEquals(1_135.4f, state.rpm, 1.0f);
        assertEquals(0.221f, state.load, 0.002f);
        for (long time = 40; time <= 900; time += 20) {
            state = model.update(time, Float.NaN, false, 100, true,
                    0, true, 1, true);
        }
        assertTrue(state.rpm >= 5_740.0f);
        assertEquals(1.0f, state.load, 0.001f);

        state = model.update(920, Float.NaN, false, 100, true,
                100, true, 1, true);
        assertEquals(0.867f, state.load, 0.003f);
    }

    @Test
    public void speedDrivesRpmAndVirtualShiftsMoveOneGearAtATime() {
        AvasEngineModel moving = new AvasEngineModel(800, 6_000);
        AvasEngineModel.State state = moving.update(1_000, 30, true,
                0, true, 0, true, 4, true);
        assertEquals(800.0f, state.rpm, 0.01f);
        assertEquals(0.0f, state.load, 0.001f);
        assertEquals(1, state.gear);
        moving.update(1_100, 30, true, 0, true, 0, true, 4, true);
        assertTrue(state.rpm > 3_000.0f);

        moving.update(1_400, 250, true, 0, true, 0, true, 4, true);
        assertEquals(2, state.gear);
        moving.update(1_800, 250, true, 0, true, 0, true, 4, true);
        assertEquals(3, state.gear);
        moving.update(2_200, 0, true, 0, true, 0, true, 4, true);
        assertEquals(2, state.gear);

        AvasEngineModel reverse = new AvasEngineModel(800, 6_000);
        AvasEngineModel.State reverseState = reverse.update(0, 40, true,
                0, true, 0, true, 2, true);
        assertEquals(800.0f, reverseState.rpm, 0.01f);
        assertEquals(1, reverseState.gear);
        reverse.update(100, 40, true, 0, true, 0, true, 2, true);
        assertTrue(reverseState.rpm > 2_000.0f);
        assertTrue(reverseState.rpm < 3_120.0f);
    }

    @Test
    public void invalidSpeedFailsToIdleButInvalidPedalPreservesMotionAndBrakeRemovesLoad() {
        AvasEngineModel model = new AvasEngineModel(800, 6_000);
        AvasEngineModel.State state = model.update(0, 45, true,
                100, true, Float.NaN, false, 4, true);
        assertTrue(state.valid);
        assertEquals(0.0f, state.load, 0.001f);
        assertEquals(800.0f, state.rpm, 0.01f);

        state = model.update(20, Float.NaN, true, 100, true,
                0, true, 4, true);
        assertFalse(state.valid);
        assertEquals(800.0f, state.rpm, 0.001f);
        assertEquals(0.0f, state.load, 0.001f);
        assertEquals(0, state.gear);

        state = model.update(40, 45, true, 100, true,
                0, true, 4, true);
        assertTrue(state.valid);
        assertEquals(800.0f, state.rpm, 0.01f);
        assertEquals(0.0f, state.load, 0.001f);

        state = model.update(60, 45, true, 100, true,
                0, true, 4, true);
        assertTrue(state.rpm > 800.0f);
        assertTrue(state.load > 0.0f);
        float poweredLoad = state.load;
        state = model.update(80, 45, true, 100, true,
                100, true, 4, true);
        assertTrue(state.load < poweredLoad);
        assertTrue(state.load > 0.0f);
        float movingRpm = state.rpm;
        state = model.update(100, 45, true, Float.NaN, false,
                0, true, 4, true);
        assertTrue(state.rpm > movingRpm);
        float coastingRpm = state.rpm;
        state = model.update(120, Float.NaN, false, Float.NaN, false,
                0, true, 1, true);
        assertTrue(state.valid);
        assertTrue(state.rpm < coastingRpm);
        assertEquals(0, state.gear);
        for (long time = 140; time <= 3_060; time += 20) {
            state = model.update(time, Float.NaN, false, Float.NaN, false,
                    0, true, 1, true);
        }
        assertEquals(800.0f, state.rpm, 0.1f);
    }

    @Test
    public void releasedPedalCoastsAtMovingRpmWhileLoadReleases() {
        AvasEngineModel model = new AvasEngineModel(800, 6_000);
        AvasEngineModel.State state = model.update(0, 30, true,
                100, true, 0, true, 4, true);
        int gear = state.gear;
        assertEquals(800.0f, state.rpm, 0.01f);

        state = model.update(100, 30, true, 100, true, 0, true, 4, true);
        float poweredRpm = state.rpm;
        float poweredLoad = state.load;
        assertTrue(poweredRpm > 3_000.0f);
        assertTrue(poweredLoad > 0.7f);

        state = model.update(120, 30, true, 0, true, 0, true, 4, true);
        assertEquals(gear, state.gear);
        assertTrue(state.rpm > poweredRpm);
        assertEquals(0.619f, state.load, 0.003f);

        for (long time = 140; time <= 500; time += 20) {
            state = model.update(time, 30, true, 0, true, 0, true, 4, true);
        }
        assertTrue(state.rpm > 4_000.0f);
        assertTrue(state.load < 0.1f);
        assertEquals(gear, state.gear);
    }

    @Test
    public void pedalIncreaseCannotLowerRpmAtFixedSpeedAndGear() {
        AvasEngineModel model = new AvasEngineModel(800, 6_000);
        AvasEngineModel.State state = model.update(0, 20, true,
                0, true, 0, true, 4, true);
        int gear = state.gear;
        float lowPedalRpm = state.rpm;

        model.update(1_000, 20, true, 100, true, 0, true, 4, true);

        assertEquals(gear, state.gear);
        assertTrue(state.rpm >= lowPedalRpm);

        // Shift intent still responds to pedal; it does not enter the RPM divisor.
        state = model.update(1_400, 34, true, 100, true, 0, true, 4, true);
        assertEquals(1, state.gear);
        state = model.update(1_800, 34, true, 0, true, 0, true, 4, true);
        assertEquals(2, state.gear);
    }

    @Test
    public void mixerCrossfadesBandsAndLoadWithoutClipping() throws Exception {
        AvasEnginePack pack = AvasEnginePack.fromManifest("ferrari_v8", manifest(),
                fixtureWavs()::get);
        AvasEngineSynth synth = new AvasEngineSynth(pack);
        float[] output = new float[48_002];
        output[0] = 7.0f;
        output[output.length - 1] = 7.0f;

        synth.render(output, 1, 48_000, 1_500, 0.0f);
        assertEquals(-0.05f, output[48_000], 0.002f);
        synth.render(output, 1, 1, 1_500, 1.0f);
        assertTrue(Math.abs(output[1] - output[48_000]) < 0.01f);
        assertEquals(7.0f, output[0], 0.0f);
        assertEquals(7.0f, output[output.length - 1], 0.0f);
        boolean finite = true;
        float peak = 0.0f;
        for (int index = 1; index <= 48_000; index++) {
            finite &= Float.isFinite(output[index]);
            peak = Math.max(peak, Math.abs(output[index]));
        }
        assertTrue(finite);
        assertTrue(peak <= 1.0f);
        synth.reset();
    }

    private static String manifest() {
        return "{\"id\":\"ferrari_v8\",\"name\":\"Test V8\","
                + "\"idleRpm\":1000,\"maxRpm\":3000,\"sampleRate\":48000,"
                + "\"idle\":\"idle.wav\",\"start\":\"start.wav\",\"stop\":\"stop.wav\","
                + "\"layers\":[{\"rpm\":1000,\"on\":\"low-on.wav\","
                + "\"off\":\"low-off.wav\"},{\"rpm\":2000,"
                + "\"on\":\"high-on.wav\",\"off\":\"high-off.wav\"}]}";
    }

    private static Map<String, byte[]> fixtureWavs() throws Exception {
        Map<String, byte[]> assets = new HashMap<>();
        assets.put("idle.wav", wav(48_000, (short) 8_192));
        assets.put("start.wav", wav(48_000, (short) 16_384));
        assets.put("stop.wav", wav(48_000, (short) -16_384));
        assets.put("low-on.wav", wav(48_000, (short) 13_107));
        assets.put("low-off.wav", wav(48_000, (short) 3_277));
        assets.put("high-on.wav", wav(48_000, (short) 26_214));
        assets.put("high-off.wav", wav(48_000, (short) -6_554));
        return assets;
    }

    private static byte[] wav(int sampleRate, short sample) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        ascii(out, "RIFF"); le32(out, 60); ascii(out, "WAVEfmt ");
        le32(out, 16); le16(out, 1); le16(out, 1); le32(out, sampleRate);
        le32(out, sampleRate * 2); le16(out, 2); le16(out, 16);
        ascii(out, "data"); le32(out, 24);
        for (int index = 0; index < 12; index++) le16(out, sample);
        return bytes.toByteArray();
    }

    private static void ascii(DataOutputStream out, String value) throws Exception {
        out.writeBytes(value);
    }

    private static void le16(DataOutputStream out, int value) throws Exception {
        out.writeByte(value);
        out.writeByte(value >>> 8);
    }

    private static void le32(DataOutputStream out, int value) throws Exception {
        le16(out, value);
        le16(out, value >>> 16);
    }
}
