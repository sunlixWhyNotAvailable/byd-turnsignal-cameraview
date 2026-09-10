package com.byd.extend;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Native Android audio decoder used while importing AVAS assets. */
final class AvasPcmDecoder {
    private static final long CODEC_TIMEOUT_US = 10_000;

    private AvasPcmDecoder() {}

    static void decodeToPcm16Wav(File source, File output) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try (RandomAccessFile wav = new RandomAccessFile(output, "rw")) {
            extractor.setDataSource(source.getAbsolutePath());
            int track = audioTrack(extractor);
            MediaFormat inputFormat = extractor.getTrackFormat(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) {
                throw new IOException("unsupported audio file");
            }
            extractor.selectTrack(track);
            // WAV PCM already comes out of MediaExtractor decoded. There is no
            // audio/raw MediaCodec decoder on many DiLink/Android builds.
            if ("audio/raw".equals(mime)) {
                copyRawTrack(extractor, inputFormat, wav);
                return;
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            wav.setLength(0);
            wav.write(new byte[44]);

            boolean inputDone = false;
            boolean outputDone = false;
            int sampleRate = 0;
            int channels = 0;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            long pcmBytes = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("AVAS import cancelled");
                }
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inputIndex);
                        if (buffer == null) throw new IOException("decoder input unavailable");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    int newSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int newEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            : AudioFormat.ENCODING_PCM_16BIT;
                    validateOutput(newSampleRate, newChannels, newEncoding);
                    if (pcmBytes > 0 && (sampleRate != newSampleRate || channels != newChannels
                            || encoding != newEncoding)) {
                        throw new IOException("PCM format changed after decoding started");
                    }
                    sampleRate = newSampleRate;
                    channels = newChannels;
                    encoding = newEncoding;
                } else if (outputIndex >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (sampleRate == 0) {
                                MediaFormat format = codec.getOutputFormat(outputIndex);
                                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                                encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                        ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                        : AudioFormat.ENCODING_PCM_16BIT;
                                validateOutput(sampleRate, channels, encoding);
                            }
                            ByteBuffer buffer = codec.getOutputBuffer(outputIndex);
                            if (buffer == null) throw new IOException("decoder output unavailable");
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            pcmBytes += writePcm16(wav, buffer, encoding);
                            if (pcmBytes > 0xffff_ffffL - 36L) {
                                throw new IOException("decoded audio is too large");
                            }
                        }
                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                }
            }
            if (sampleRate == 0 || pcmBytes == 0) throw new IOException("audio contains no PCM data");
            writeWavHeader(wav, sampleRate, channels, pcmBytes);
            wav.getFD().sync();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }

    private static void copyRawTrack(MediaExtractor extractor, MediaFormat format,
            RandomAccessFile wav) throws IOException {
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        validateOutput(sampleRate, channels, encoding);
        int capacity = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? Math.max(65536, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 65536;
        ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        wav.setLength(0);
        wav.write(new byte[44]);
        long pcmBytes = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("AVAS import cancelled");
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                long size = extractor.getSampleSize();
                if (size > Integer.MAX_VALUE) throw new IOException("audio sample is too large");
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
            }
            buffer.clear();
            int count = extractor.readSampleData(buffer, 0);
            if (count < 0) break;
            buffer.position(0);
            buffer.limit(count);
            pcmBytes += writePcm16(wav, buffer, encoding);
            if (pcmBytes > 0xffff_ffffL - 36L) throw new IOException("decoded audio is too large");
            if (!extractor.advance()) break;
        }
        writeWavHeader(wav, sampleRate, channels, pcmBytes);
        wav.getFD().sync();
    }

    private static int audioTrack(MediaExtractor extractor) throws IOException {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return index;
        }
        throw new IOException("file has no audio track");
    }

    private static void validateOutput(int sampleRate, int channels, int encoding)
            throws IOException {
        if (sampleRate <= 0 || channels < 1 || channels > 2) {
            throw new IOException("unsupported PCM format");
        }
        if (encoding != AudioFormat.ENCODING_PCM_16BIT
                && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            throw new IOException("unsupported PCM encoding");
        }
    }

    static long writePcm16(RandomAccessFile output, ByteBuffer input, int encoding)
            throws IOException {
        if (encoding != AudioFormat.ENCODING_PCM_16BIT
                && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            throw new IOException("unsupported PCM encoding");
        }
        if ((input.remaining() & 1) != 0) throw new IOException("misaligned PCM16");
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            if ((input.remaining() & 3) != 0) throw new IOException("misaligned float PCM");
            input.order(ByteOrder.nativeOrder());
            byte[] bytes = new byte[Math.min(8192, Math.max(2, input.remaining() / 2)) & ~1];
            long written = 0;
            while (input.hasRemaining()) {
                int samples = Math.min(input.remaining() / 4, bytes.length / 2);
                for (int index = 0; index < samples; index++) {
                    float value = input.getFloat();
                    if (!Float.isFinite(value)) value = 0;
                    value = Math.max(-1.0f, Math.min(1.0f, value));
                    int pcm = value <= -1.0f ? -32768 : Math.round(value * 32767.0f);
                    bytes[index * 2] = (byte) pcm;
                    bytes[index * 2 + 1] = (byte) (pcm >>> 8);
                }
                output.write(bytes, 0, samples * 2);
                written += samples * 2L;
            }
            return written;
        }
        byte[] bytes = new byte[Math.min(8192, Math.max(2, input.remaining()))];
        long written = 0;
        while (input.hasRemaining()) {
            int count = Math.min(input.remaining(), bytes.length);
            input.get(bytes, 0, count);
            output.write(bytes, 0, count);
            written += count;
        }
        return written;
    }

    static void writeWavHeader(RandomAccessFile output, int sampleRate, int channels,
            long pcmBytes) throws IOException {
        validateOutput(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT);
        if (pcmBytes <= 0 || pcmBytes % (channels * 2L) != 0
                || pcmBytes > 0xffff_ffffL - 36L
                || (long) sampleRate * channels * 2 > 0xffff_ffffL) {
            throw new IOException("invalid PCM length");
        }
        output.seek(0);
        output.writeBytes("RIFF");
        writeLe32(output, pcmBytes + 36);
        output.writeBytes("WAVEfmt ");
        writeLe32(output, 16);
        writeLe16(output, 1);
        writeLe16(output, channels);
        writeLe32(output, sampleRate);
        writeLe32(output, (long) sampleRate * channels * 2);
        writeLe16(output, channels * 2);
        writeLe16(output, 16);
        output.writeBytes("data");
        writeLe32(output, pcmBytes);
    }

    private static void writeLe16(RandomAccessFile output, int value) throws IOException {
        output.write(value);
        output.write(value >>> 8);
    }

    private static void writeLe32(RandomAccessFile output, long value) throws IOException {
        output.write((int) value);
        output.write((int) (value >>> 8));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 24));
    }
}
