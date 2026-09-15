package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public final class AvasWavTest {
    @Test public void staticPreloadPreservesCompletePcmWithoutArtificialSilence() throws Exception {
        for (int rate : new int[]{44_100, 48_000}) {
            for (int channels : new int[]{1, 2}) {
                byte[] samples = pcm(12_345, -23_456, 1, -1);
                byte[] original = wav(rate, channels, samples, true);
                File file = Files.createTempFile("avas-static", ".wav").toFile();
                Files.write(file.toPath(), original);
                try {
                    AvasWav.Header header = AvasWav.read(file);
                    int leading = Math.toIntExact(AvasPlaybackPlan.silenceBytes(rate,
                            header.frameSize, AvasPlaybackPlan.EXTERIOR_SILENCE_MILLIS));
                    byte[] preloaded = AvasWav.readPcm(file, header, leading);
                    assertEquals(0, leading);
                    assertEquals(leading + samples.length, preloaded.length);
                    assertArrayEquals(new byte[leading], Arrays.copyOf(preloaded, leading));
                    assertArrayEquals(samples, Arrays.copyOfRange(preloaded, leading, preloaded.length));
                    assertArrayEquals(original, Files.readAllBytes(file.toPath()));
                    assertArrayEquals(samples, AvasWav.readPcm(file, header, 0));
                } finally {
                    Files.deleteIfExists(file.toPath());
                }
            }
        }
    }

    @Test public void staticPreloadRejectsTruncationInsteadOfPaddingMissingFileAudio() throws Exception {
        File file = Files.createTempFile("avas-static-truncated", ".wav").toFile();
        byte[] original = wav(44_100, 2, pcm(1, 2, 3, 4), false);
        Files.write(file.toPath(), original);
        try {
            AvasWav.Header header = AvasWav.read(file);
            assertThrows(IllegalArgumentException.class, () -> AvasWav.readPcm(file, header, 1));
            Files.write(file.toPath(), Arrays.copyOf(original, original.length - 1));
            assertThrows(java.io.EOFException.class, () -> AvasWav.readPcm(file, header, 88_200));
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void parsesPcm16StereoWithAnOddUnknownChunk() throws Exception {
        File wav = Files.createTempFile("avas", ".wav").toFile();
        Files.write(wav.toPath(), wav(22_050, 2, new byte[]{1, 0, 2, 0}, true));

        AvasWav.Header header = AvasWav.read(wav);

        assertEquals(22_050, header.sampleRate);
        assertEquals(2, header.channels);
        assertEquals(4, header.frameSize);
        assertEquals(4, header.dataBytes);
        assertEquals(54, header.dataOffset);
    }

    @Test
    public void rejectsUnsupportedPcmAndDropsIncompleteTailFrame() throws Exception {
        File invalid = Files.createTempFile("avas-invalid", ".wav").toFile();
        byte[] floatWav = wav(48_000, 2, new byte[]{0, 0, 0, 0}, false);
        floatWav[20] = 3;
        Files.write(invalid.toPath(), floatWav);
        assertThrows(Exception.class, () -> AvasWav.read(invalid));

        File tailed = Files.createTempFile("avas-tail", ".wav").toFile();
        Files.write(tailed.toPath(), wav(48_000, 2, new byte[]{1, 0, 2, 0, 99}, false));
        assertEquals(4, AvasWav.read(tailed).dataBytes);
    }

    @Test
    public void navigationScalingRemainsExactAtEverySupportedGain() {
        byte[] samples = pcm(Short.MIN_VALUE, -20_000, -1, 0, 1, 20_000, Short.MAX_VALUE);

        assertArrayEquals(pcm(0, 0, 0, 0, 0, 0, 0), navigation(samples, 0));
        assertArrayEquals(pcm(-8_192, -5_000, 0, 0, 0, 5_000, 8_191),
                navigation(samples, 25));
        assertArrayEquals(pcm(-16_384, -10_000, 0, 0, 0, 10_000, 16_383),
                navigation(samples, 50));
        assertArrayEquals(samples, navigation(samples, 100));
    }

    @Test
    public void navigationScalingClampsVolumeOutsideSupportedRange() {
        byte[] samples = pcm(Short.MIN_VALUE, -20_000, -1, 0, 1, 20_000, Short.MAX_VALUE);

        assertArrayEquals(navigation(samples, 0), navigation(samples, -1));
        assertArrayEquals(navigation(samples, 100), navigation(samples, 101));
    }

    @Test
    public void navigationScalingPreservesOffsetsAndRejectsInvalidRanges() {
        byte[] source = {99, 98, 0x34, 0x12, (byte) 0xcc, (byte) 0xed, 97, 96};
        byte[] original = source.clone();
        byte[] target = {11, 12, 13, 14, 15, 16, 17, 18};

        AvasWav.scalePcm16(source, 2, target, 2, 4, 50);

        assertArrayEquals(original, source);
        assertArrayEquals(new byte[]{11, 12, 0x1a, 0x09, (byte) 0xe6, (byte) 0xf6, 17, 18},
                target);
        assertThrows(IllegalArgumentException.class,
                () -> AvasWav.scalePcm16(source, 2, target, 2, 3, 50));
        assertThrows(IllegalArgumentException.class,
                () -> AvasWav.scalePcm16(source, 6, target, 2, 4, 50));
    }

    private static byte[] navigation(byte[] source, int volume) {
        byte[] target = new byte[source.length];
        AvasWav.scalePcm16(source, 0, target, 0, source.length, volume);
        return target;
    }

    private static byte[] pcm(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) samples[i];
            bytes[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        return bytes;
    }

    private static byte[] wav(int rate, int channels, byte[] pcm, boolean junk) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int extra = junk ? 10 : 0;
        ascii(out, "RIFF"); le32(out, 36 + extra + pcm.length); ascii(out, "WAVE");
        ascii(out, "fmt "); le32(out, 16); le16(out, 1); le16(out, channels);
        le32(out, rate); le32(out, rate * channels * 2); le16(out, channels * 2); le16(out, 16);
        if (junk) {
            ascii(out, "JUNK"); le32(out, 1); out.write(7); out.write(0);
        }
        ascii(out, "data"); le32(out, pcm.length); out.write(pcm);
        return bytes.toByteArray();
    }

    private static void ascii(DataOutputStream out, String value) throws Exception {
        out.writeBytes(value);
    }

    private static void le16(DataOutputStream out, int value) throws Exception {
        out.writeByte(value); out.writeByte(value >>> 8);
    }

    private static void le32(DataOutputStream out, int value) throws Exception {
        le16(out, value); le16(out, value >>> 16);
    }
}
