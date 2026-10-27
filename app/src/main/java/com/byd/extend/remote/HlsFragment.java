package com.byd.extend.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Adapts our single-track fragmented MP4 muxer to independently fetched HLS fragments. */
final class HlsFragment {
    private long timescale, trackId, nextDecodeTime;
    private long defaultDuration, defaultSize;

    static byte[] compatibleBrands(byte[] ftyp) throws IOException {
        require(ftyp.length >= 16 && ftyp.length % 4 == 0, "Invalid ftyp");
        for (int i = 16; i < ftyp.length; i += 4)
            if ("iso6".equals(type(ftyp, i))) return ftyp;
        byte[] result = Arrays.copyOf(ftyp, ftyp.length + 4);
        ByteBuffer.wrap(result).putInt(result.length);
        System.arraycopy("iso6".getBytes(StandardCharsets.US_ASCII), 0, result, ftyp.length, 4);
        return result;
    }

    void initialize(byte[] moov) throws IOException {
        Box trak = one(moov, 8, moov.length, "trak");
        Box tkhd = one(moov, trak.start + 8, trak.end(), "tkhd");
        trackId = unsigned(moov, tkhd.start + (moov[tkhd.start + 8] == 1 ? 28 : 20), tkhd.end());
        Box mdia = one(moov, trak.start + 8, trak.end(), "mdia");
        Box mdhd = one(moov, mdia.start + 8, mdia.end(), "mdhd");
        timescale = unsigned(moov, mdhd.start + (moov[mdhd.start + 8] == 1 ? 28 : 20), mdhd.end());
        require(timescale > 0, "Invalid video timescale");
        Box mvex = one(moov, 8, moov.length, "mvex");
        Box trex = one(moov, mvex.start + 8, mvex.end(), "trex");
        require(unsigned(moov, trex.start + 12, trex.end()) == trackId, "Unexpected default track");
        defaultDuration = unsigned(moov, trex.start + 20, trex.end());
        defaultSize = unsigned(moov, trex.start + 24, trex.end());
        unsigned(moov, trex.start + 28, trex.end());
    }

    static final class Result {
        final byte[] moof;
        final double seconds;
        final int samples;
        Result(byte[] moof, double seconds, int samples) {
            this.moof = moof; this.seconds = seconds; this.samples = samples;
        }
    }

    Result normalize(byte[] moof, int payloadBytes) throws IOException {
        require(timescale > 0, "Video init missing");
        Box traf = one(moof, 8, moof.length, "traf");
        Box tfhd = one(moof, traf.start + 8, traf.end(), "tfhd");
        Box trun = one(moof, traf.start + 8, traf.end(), "trun");
        int tfhdFlags = (int) unsigned(moof, tfhd.start + 8, tfhd.end());
        require(unsigned(moof, tfhd.start + 12, tfhd.end()) == trackId, "Unexpected fragment track");
        int cursor = tfhd.start + 16 + ((tfhdFlags & 1) != 0 ? 8 : 0);
        if ((tfhdFlags & 2) != 0) cursor += 4;
        long sampleDuration = defaultDuration, sampleSize = defaultSize;
        if ((tfhdFlags & 8) != 0) { sampleDuration = unsigned(moof, cursor, tfhd.end()); cursor += 4; }
        if ((tfhdFlags & 16) != 0) { sampleSize = unsigned(moof, cursor, tfhd.end()); cursor += 4; }
        if ((tfhdFlags & 32) != 0) { unsigned(moof, cursor, tfhd.end()); cursor += 4; }
        require(cursor == tfhd.end(), "Unsupported tfhd fields");

        int flags = (int) unsigned(moof, trun.start + 8, trun.end());
        long count = unsigned(moof, trun.start + 12, trun.end());
        require(count > 0 && count <= 100_000, "Invalid video sample count");
        cursor = trun.start + 16 + ((flags & 1) != 0 ? 4 : 0);
        if ((flags & 4) != 0) { unsigned(moof, cursor, trun.end()); cursor += 4; }
        long duration = 0, size = 0;
        for (int i = 0; i < count; i++) {
            long ticks = sampleDuration, bytes = sampleSize;
            if ((flags & 0x100) != 0) { ticks = unsigned(moof, cursor, trun.end()); cursor += 4; }
            if ((flags & 0x200) != 0) { bytes = unsigned(moof, cursor, trun.end()); cursor += 4; }
            if ((flags & 0x400) != 0) { unsigned(moof, cursor, trun.end()); cursor += 4; }
            if ((flags & 0x800) != 0) { unsigned(moof, cursor, trun.end()); cursor += 4; }
            require(ticks > 0 && bytes > 0, "Empty video sample");
            duration += ticks; size += bytes;
        }
        require(cursor == trun.end() && size == payloadBytes, "Fragment payload mismatch");

        // Media3 1.8 has absolute offsets and no tfdt. Retain existing timestamps for other muxers.
        long decodeTime = nextDecodeTime;
        for (Box child : boxes(moof, traf.start + 8, traf.end())) if (child.type.equals("tfdt")) {
            if (moof[child.start + 8] == 1) {
                require(child.size == 20, "Invalid tfdt");
                decodeTime = ByteBuffer.wrap(moof).getLong(child.start + 12);
            } else decodeTime = unsigned(moof, child.start + 12, child.end());
            require(decodeTime >= nextDecodeTime, "Video decode time regressed");
        }
        byte[] oldHeader = Arrays.copyOfRange(moof, tfhd.start + 8, tfhd.end());
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(ByteBuffer.allocate(4).putInt((tfhdFlags & ~1) | 0x020000).array());
        header.write(oldHeader, 4, 4);
        int remainingStart = (tfhdFlags & 1) != 0 ? 16 : 8;
        header.write(oldHeader, remainingStart, oldHeader.length - remainingStart);
        byte[] newHeader = box("tfhd", header.toByteArray());
        byte[] timestamp = box("tfdt", ByteBuffer.allocate(12).putInt(0x01000000).putLong(decodeTime).array());

        ByteArrayOutputStream run = new ByteArrayOutputStream();
        run.write(ByteBuffer.allocate(12).putInt(flags | 1).putInt((int) count).putInt(0).array());
        int oldFields = trun.start + 16 + ((flags & 1) != 0 ? 4 : 0);
        run.write(moof, oldFields, trun.end() - oldFields);
        byte[] newRun = box("trun", run.toByteArray());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(newHeader); body.write(timestamp); body.write(newRun);
        for (Box child : boxes(moof, traf.start + 8, traf.end()))
            if (!child.type.equals("tfhd") && !child.type.equals("tfdt") && !child.type.equals("trun"))
                body.write(moof, child.start, child.size);
        byte[] newTraf = box("traf", body.toByteArray());
        int moofSize = moof.length - traf.size + newTraf.length;
        // The only track's samples start immediately after the following mdat header.
        ByteBuffer.wrap(newTraf).putInt(8 + newHeader.length + timestamp.length + 16, moofSize + 8);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (Box child : boxes(moof, 8, moof.length)) {
            if (child.type.equals("traf")) result.write(newTraf);
            else result.write(moof, child.start, child.size);
        }
        nextDecodeTime = Math.addExact(decodeTime, duration);
        return new Result(box("moof", result.toByteArray()), (double) duration / timescale, (int) count);
    }

    private static final class Box {
        final int start, size;
        final String type;
        Box(int start, int size, String type) { this.start = start; this.size = size; this.type = type; }
        int end() { return start + size; }
    }
    private static List<Box> boxes(byte[] data, int start, int end) throws IOException {
        List<Box> result = new ArrayList<>();
        while (start < end) {
            long size = unsigned(data, start, end);
            require(size >= 8 && size <= end - start, "Invalid child box size");
            result.add(new Box(start, (int) size, type(data, start + 4)));
            start += (int) size;
        }
        return result;
    }
    private static Box one(byte[] data, int start, int end, String type) throws IOException {
        Box found = null;
        for (Box child : boxes(data, start, end)) if (child.type.equals(type)) {
            require(found == null, "Only one " + type + " is supported"); found = child;
        }
        require(found != null, "Missing " + type);
        return found;
    }
    private static long unsigned(byte[] data, int offset, int end) throws IOException {
        require(offset >= 0 && offset <= end - 4 && end <= data.length, "Truncated MP4 field");
        return Integer.toUnsignedLong(ByteBuffer.wrap(data).getInt(offset));
    }
    private static String type(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }
    private static byte[] box(String type, byte[] body) {
        return ByteBuffer.allocate(body.length + 8).putInt(body.length + 8)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(body).array();
    }
    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
