package com.byd.extend.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

/** Bounded HLS window with independently addressable, timed fMP4 fragments. */
public final class FragmentedVideoWindow extends OutputStream {
    private static final int MAX_BOX = 8 * 1024 * 1024;
    private static final int MAX_SEGMENTS = 4;
    private final byte[] header = new byte[8];
    private int headerBytes, remaining;
    private String boxType;
    private ByteArrayOutputStream box;
    private final ByteArrayOutputStream init = new ByteArrayOutputStream();
    private byte[] fragment;
    private final HlsFragment hls = new HlsFragment();
    private byte[] initialization;
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private long sequence;
    private boolean closed;
    private IOException writeFailure;
    private final Runnable onReady;
    private final String id = UUID.randomUUID().toString();
    private final String request;
    public FragmentedVideoWindow() { this(UUID.randomUUID().toString(), () -> {}); }
    public FragmentedVideoWindow(Runnable onReady) { this(UUID.randomUUID().toString(), onReady); }
    public FragmentedVideoWindow(String request, Runnable onReady) {
        this.request = UUID.fromString(request).toString();
        this.onReady = onReady;
    }
    public String getRequest() { return request; }
    public String getPlaylistPath() { return "/media/" + id + "/live.m3u8"; }
    public String resourcePath(String path) {
        String prefix = "/media/" + id;
        return path.startsWith(prefix + "/") ? path.substring(prefix.length()) : null;
    }
    public synchronized boolean isReady() { return initialization != null && !segments.isEmpty(); }

    private static final class Segment {
        final long id;
        final double duration;
        final byte[] bytes;
        Segment(long id, double duration, byte[] bytes) {
            this.id = id; this.duration = duration; this.bytes = bytes;
        }
    }

    @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }

    @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length)
            throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("Video window closed");
        if (writeFailure != null) throw new IOException("Video fragment write failed", writeFailure);
        try {
            while (length > 0) {
                if (headerBytes < 8) {
                    int count = Math.min(length, 8 - headerBytes);
                    System.arraycopy(bytes, offset, header, headerBytes, count);
                    headerBytes += count; offset += count; length -= count;
                    if (headerBytes < 8) continue;
                    long size = 0;
                    for (int i = 0; i < 4; i++) size = (size << 8) | (header[i] & 255);
                    if (size < 8 || size > MAX_BOX) throw new IOException("Unsupported MP4 box size");
                    boxType = new String(header, 4, 4, StandardCharsets.US_ASCII);
                    remaining = (int) size - 8;
                    box = new ByteArrayOutputStream((int) size);
                    box.write(header);
                }
                int count = Math.min(length, remaining);
                box.write(bytes, offset, count);
                offset += count; length -= count; remaining -= count;
                if (remaining == 0) {
                    completeBox(boxType, box.toByteArray());
                    headerBytes = 0; box = null;
                }
            }
        } catch (IOException failure) {
            writeFailure = failure;
            throw failure;
        }
    }

    private void completeBox(String type, byte[] bytes) throws IOException {
        switch (type) {
            case "ftyp":
            case "moov":
                if (initialization != null || init.size() + bytes.length > 1024 * 1024)
                    throw new IOException("Invalid video initialization");
                if ("ftyp".equals(type)) bytes = HlsFragment.compatibleBrands(bytes);
                else hls.initialize(bytes);
                init.write(bytes);
                if ("moov".equals(type)) initialization = init.toByteArray();
                break;
            case "moof":
                if (initialization == null || fragment != null)
                    throw new IOException("Invalid video fragment order");
                fragment = bytes;
                break;
            case "mdat":
                if (fragment == null) throw new IOException("Video payload without fragment");
                if (fragment.length + bytes.length > MAX_BOX)
                    throw new IOException("Video fragment exceeds memory budget");
                HlsFragment.Result normalized = hls.normalize(fragment, bytes.length - 8);
                ByteArrayOutputStream output = new ByteArrayOutputStream(normalized.moof.length + bytes.length);
                output.write(normalized.moof); output.write(bytes);
                segments.add(new Segment(sequence++, normalized.seconds, output.toByteArray()));
                while (segments.size() > MAX_SEGMENTS) segments.removeFirst();
                fragment = null;
                if (sequence == 1) onReady.run();
                break;
            case "free":
            case "mfra":
            case "sidx":
                break;
            default: throw new IOException("Unexpected MP4 box: " + type);
        }
    }

    public synchronized byte[] initialization() { return initialization; }
    public synchronized byte[] segment(long id) {
        for (Segment segment : segments) if (segment.id == id) return segment.bytes;
        return null;
    }

    public synchronized String playlist() {
        if (initialization == null || segments.isEmpty()) return null;
        double max = 2;
        for (Segment segment : segments) max = Math.max(max, segment.duration);
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:")
                .append((int) Math.ceil(max)).append("\n#EXT-X-MEDIA-SEQUENCE:")
                .append(segments.getFirst().id).append("\n#EXT-X-MAP:URI=\"init.mp4\"\n");
        for (Segment segment : segments) out.append(String.format(Locale.ROOT,
                "#EXTINF:%.6f,\nsegment/%d.m4s\n", segment.duration, segment.id));
        return out.toString();
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        // Media3 closes its stream in finally; retain the write failure it would otherwise mask.
        if (writeFailure != null) throw new IOException("Video fragment write failed", writeFailure);
        if (headerBytes != 0 || fragment != null) throw new IOException("Truncated video fragment: headerBytes="
                + headerBytes + " box=" + boxType + " remaining=" + remaining + " pendingMoof=" + (fragment != null));
    }

    public synchronized void clear() {
        segments.clear(); initialization = null; init.reset(); fragment = null;
        box = null; headerBytes = remaining = 0; closed = true;
    }
}
