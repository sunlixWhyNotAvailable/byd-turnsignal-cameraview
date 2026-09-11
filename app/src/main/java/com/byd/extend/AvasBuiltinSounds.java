package com.byd.extend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, app-owned test sounds for the four AVAS profiles. */
public final class AvasBuiltinSounds {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final String STORED_NAME = "test";
    private static final Map<String, String> ASSET_IDS;

    static {
        LinkedHashMap<String, String> ids = new LinkedHashMap<>();
        ids.put("lock", "b17d11b08fd35b36ad01bc963ee32001");
        ids.put("unlock", "b17d11b08fd35b36ad01bc963ee32002");
        ids.put("power_off", "b17d11b08fd35b36ad01bc963ee32003");
        ids.put("power_on", "b17d11b08fd35b36ad01bc963ee32004");
        ASSET_IDS = Collections.unmodifiableMap(ids);
    }

    private AvasBuiltinSounds() {}

    public static boolean isBuiltinAsset(String assetId) {
        return assetId != null && ASSET_IDS.containsValue(assetId);
    }

    public static String assetId(String profileId) {
        String assetId = ASSET_IDS.get(profileId);
        if (assetId == null) throw new IllegalArgumentException("unknown AVAS profile");
        return assetId;
    }

    static AvasConfig.Asset asset(String profileId) {
        return new AvasConfig.Asset(assetId(profileId), STORED_NAME);
    }

    public static short[] samples(String profileId) {
        int[] notes;
        switch (profileId) {
            case "lock": notes = new int[]{880, 660}; break;
            case "unlock": notes = new int[]{660, 880}; break;
            case "power_off": notes = new int[]{880, 660, 440}; break;
            case "power_on": notes = new int[]{440, 660, 880}; break;
            default: throw new IllegalArgumentException("unknown AVAS profile");
        }
        int frames = SAMPLE_RATE;
        int noteFrames = frames / notes.length;
        short[] pcm = new short[frames * CHANNELS];
        for (int frame = 0; frame < frames; frame++) {
            double time = frame / (double) SAMPLE_RATE;
            double frequency = notes[frame / noteFrames];
            double edge = Math.min(1, Math.min((frame % noteFrames) / 960.0,
                    ((noteFrames - 1) - frame % noteFrames) / 960.0));
            short sample = (short) (Math.sin(2 * Math.PI * frequency * time) * 16383 * edge);
            pcm[frame * 2] = pcm[frame * 2 + 1] = sample;
        }
        return pcm;
    }

    public static void writeWav(String profileId, File output) throws IOException {
        if (output == null) throw new IllegalArgumentException("output is null");
        short[] pcm = samples(profileId);
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(wav.capacity() - 8);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) CHANNELS);
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        wav.putInt(SAMPLE_RATE).putInt(byteRate)
                .putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8))
                .putShort((short) BITS_PER_SAMPLE);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length * 2);
        for (short sample : pcm) wav.putShort(sample);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(wav.array());
            stream.getFD().sync();
        }
    }
}
