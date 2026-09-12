package com.byd.extend;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Minimal PCM16 WAV reader shared by the shell AVAS player and JVM tests. */
final class AvasWav {
    static final class Header {
        final int sampleRate;
        final int channels;
        final int frameSize;
        final long dataOffset;
        final long dataBytes;

        Header(int sampleRate, int channels, long dataOffset, long dataBytes) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.frameSize = channels * 2;
            this.dataOffset = dataOffset;
            this.dataBytes = dataBytes;
        }
    }

    private AvasWav() {}

    static Header read(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("WAV file unavailable");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 12 || !"RIFF".equals(ascii(input, 4))) {
                throw new IOException("Not a RIFF WAV");
            }
            readU32(input);
            if (!"WAVE".equals(ascii(input, 4))) throw new IOException("Not a WAVE file");

            int format = -1;
            int channels = -1;
            int sampleRate = -1;
            int bits = -1;
            int blockAlign = -1;
            while (input.getFilePointer() + 8 <= input.length()) {
                String id = ascii(input, 4);
                long declared = readU32(input);
                long chunkStart = input.getFilePointer();
                long chunkEnd = chunkStart + declared;
                if (chunkEnd < chunkStart || chunkEnd > input.length()) {
                    throw new IOException("Truncated WAV chunk " + id);
                }
                if ("fmt ".equals(id)) {
                    if (declared < 16) throw new IOException("Invalid WAV fmt chunk");
                    format = readU16(input);
                    channels = readU16(input);
                    sampleRate = (int) readU32(input);
                    readU32(input); // byte rate
                    blockAlign = readU16(input);
                    bits = readU16(input);
                } else if ("data".equals(id)) {
                    if (format != 1 || (channels != 1 && channels != 2) || bits != 16
                            || sampleRate <= 0 || blockAlign != channels * 2) {
                        throw new IOException("WAV must be PCM16 mono or stereo");
                    }
                    long aligned = declared - declared % blockAlign;
                    if (aligned <= 0) throw new IOException("WAV contains no complete PCM frames");
                    return new Header(sampleRate, channels, chunkStart, aligned);
                }
                input.seek(chunkEnd + (declared & 1));
            }
        }
        throw new IOException("WAV data chunk missing");
    }

    static void scalePcm16(byte[] source, int sourceOffset, byte[] target,
            int targetOffset, int length, int volume) {
        if ((sourceOffset | targetOffset | length) < 0
                || sourceOffset + length > source.length || targetOffset + length > target.length
                || (length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 range must contain whole samples");
        }
        int gain = Math.max(0, Math.min(100, volume));
        for (int i = 0; i < length; i += 2) {
            int sample = (short) ((source[sourceOffset + i] & 0xff)
                    | (source[sourceOffset + i + 1] << 8));
            int scaled = sample * gain / 100;
            target[targetOffset + i] = (byte) scaled;
            target[targetOffset + i + 1] = (byte) (scaled >> 8);
        }
    }

    private static String ascii(RandomAccessFile input, int length) throws IOException {
        byte[] value = new byte[length];
        input.readFully(value);
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static int readU16(RandomAccessFile input) throws IOException {
        return input.readUnsignedByte() | (input.readUnsignedByte() << 8);
    }

    private static long readU32(RandomAccessFile input) throws IOException {
        return Integer.toUnsignedLong(input.readUnsignedByte()
                | (input.readUnsignedByte() << 8)
                | (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 24));
    }
}
