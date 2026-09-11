package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;

public final class AvasWavTest {
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
    public void exteriorScalingDoublesGainAndSaturatesWithoutWrap() {
        byte[] samples = pcm(Short.MIN_VALUE, -20_000, -1, 0, 1, 20_000, Short.MAX_VALUE);

        assertArrayEquals(pcm(0, 0, 0, 0, 0, 0, 0), exterior(samples, 0));
        assertArrayEquals(pcm(-16_384, -10_000, 0, 0, 0, 10_000, 16_383),
                exterior(samples, 25));
        assertArrayEquals(samples, exterior(samples, 50));
        assertArrayEquals(pcm(Short.MIN_VALUE, Short.MIN_VALUE, -2, 0, 2,
                Short.MAX_VALUE, Short.MAX_VALUE), exterior(samples, 100));
        assertArrayEquals(exterior(samples, 0), exterior(samples, -1));
        assertArrayEquals(exterior(samples, 100), exterior(samples, 101));
    }

    @Test
    public void exteriorScalingPreservesSourceOffsetsChannelsAndSurroundingBytes() {
        byte[] source = {99, 98, 0x34, 0x12, (byte) 0xcc, (byte) 0xed, 97, 96};
        byte[] original = source.clone();
        byte[] target = {11, 12, 13, 14, 15, 16, 17, 18};

        AvasWav.scaleExteriorPcm16(source, 2, target, 2, 4, 50);

        assertArrayEquals(original, source);
        assertArrayEquals(new byte[]{11, 12, 0x34, 0x12, (byte) 0xcc, (byte) 0xed, 17, 18},
                target);
    }

    private static byte[] exterior(byte[] source, int volume) {
        byte[] target = new byte[source.length];
        AvasWav.scaleExteriorPcm16(source, 0, target, 0, source.length, volume);
        return target;
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
