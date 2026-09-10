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
    public void scalesSignedPcmExactlyIncludingTrueZero() {
        byte[] samples = {0, (byte) 0x80, (byte) 0xff, 0x7f, 0x34, 0x12, (byte) 0xcc, (byte) 0xed};
        byte[] half = new byte[samples.length];
        byte[] zero = new byte[samples.length];

        AvasWav.scalePcm16(samples, 0, half, 0, samples.length, 50);
        AvasWav.scalePcm16(samples, 0, zero, 0, samples.length, 0);

        assertArrayEquals(new byte[]{0, (byte) 0xc0, (byte) 0xff, 0x3f, 0x1a, 0x09, (byte) 0xe6, (byte) 0xf6}, half);
        assertArrayEquals(new byte[samples.length], zero);
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
