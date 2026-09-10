package com.byd.extend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class AvasPcmDecoderTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void writesStandardLittleEndianPcm16WavHeader() throws Exception {
        File file = temporary.newFile("tone.wav");
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.write(new byte[48]);
            AvasPcmDecoder.writeWavHeader(output, 44_100, 2, 4);
        }

        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(40, le32(bytes, 4));
        assertEquals("WAVEfmt ", new String(bytes, 8, 8, StandardCharsets.US_ASCII));
        assertEquals(1, le16(bytes, 20));
        assertEquals(2, le16(bytes, 22));
        assertEquals(44_100, le32(bytes, 24));
        assertEquals(176_400, le32(bytes, 28));
        assertEquals(4, le16(bytes, 32));
        assertEquals(16, le16(bytes, 34));
        assertEquals("data", new String(bytes, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(4, le32(bytes, 40));
        assertArrayEquals(new byte[4], java.util.Arrays.copyOfRange(bytes, 44, 48));
    }

    @Test
    public void rejectsInvalidWavShape() throws Exception {
        File file = temporary.newFile("bad.wav");
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            assertThrows(java.io.IOException.class,
                    () -> AvasPcmDecoder.writeWavHeader(output, 44_100, 3, 4));
            assertThrows(java.io.IOException.class,
                    () -> AvasPcmDecoder.writeWavHeader(output, 44_100, 1, 3));
        }
    }

    @Test
    public void convertsBoundedAndNonFiniteFloatPcmToLittleEndianPcm16() throws Exception {
        File file = temporary.newFile("float.pcm");
        ByteBuffer floats = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        floats.putFloat(-1.5f).putFloat(0.0f).putFloat(1.5f).putFloat(Float.NaN).flip();
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            assertEquals(8, AvasPcmDecoder.writePcm16(output, floats,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT));
        }
        assertArrayEquals(new byte[]{0, (byte) 0x80, 0, 0, (byte) 0xff, 0x7f, 0, 0},
                java.nio.file.Files.readAllBytes(file.toPath()));
    }

    @Test
    public void rawPcm16PassesThroughWithBufferWindowPreserved() throws Exception {
        File file = temporary.newFile("raw.pcm");
        ByteBuffer raw = ByteBuffer.wrap(new byte[]{9, 9, 1, 0, 0, (byte) 0x80, 9});
        raw.position(2);
        raw.limit(6);
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            assertEquals(4, AvasPcmDecoder.writePcm16(output, raw,
                    android.media.AudioFormat.ENCODING_PCM_16BIT));
        }
        assertArrayEquals(new byte[]{1, 0, 0, (byte) 0x80},
                java.nio.file.Files.readAllBytes(file.toPath()));
    }

    @Test
    public void rejectsOddOrUnknownPcmInsteadOfPublishingMisinterpretedAudio() throws Exception {
        try (RandomAccessFile output = new RandomAccessFile(temporary.newFile("invalid.pcm"), "rw")) {
            assertThrows(java.io.IOException.class, () -> AvasPcmDecoder.writePcm16(output,
                    ByteBuffer.wrap(new byte[]{1}), android.media.AudioFormat.ENCODING_PCM_16BIT));
            assertThrows(java.io.IOException.class, () -> AvasPcmDecoder.writePcm16(output,
                    ByteBuffer.wrap(new byte[]{1, 2}), android.media.AudioFormat.ENCODING_PCM_8BIT));
        }
    }

    private static int le16(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
    }

    private static int le32(byte[] value, int offset) {
        return le16(value, offset) | (le16(value, offset + 2) << 16);
    }
}
