package com.byd.extend.remote;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.FragmentedMp4Muxer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real muxer and real encoded frames; Android runtime supplies Format's platform references only. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class Media3HlsPlaybackTest {
    @Test public void closingWithOneFrameInTheFinalFragmentRetainsEveryFrame() throws Exception {
        Path dir = Files.createTempDirectory("media3-hls-short-");
        try {
            byte[] fixture;
            try (InputStream input = getClass().getResourceAsStream("/com/byd/extend/remote/fragmented_test.mp4")) {
                assertNotNull(input);
                fixture = input.readAllBytes();
            }
            Path input = dir.resolve("input.mp4");
            Files.write(input, fixture);
            JSONArray packets = new JSONObject(command(dir, "ffprobe", "-v", "error", "-select_streams", "v",
                    "-show_entries", "packet=pts_time,pos,size,flags", "-of", "json", input.toString()))
                    .getJSONArray("packets");
            for (int frameCount : new int[]{1, 31}) {
                FragmentedVideoWindow window = new FragmentedVideoWindow();
                FragmentedMp4Muxer muxer = new FragmentedMp4Muxer.Builder(window).setFragmentDurationMs(2000).build();
                int track = muxer.addTrack(new Format.Builder().setSampleMimeType("video/avc")
                        .setWidth(96).setHeight(64).setInitializationData(configuration(fixture)).build());
                long lastPts = -1, previousPts = -1;
                for (int i = 0; i < frameCount; i++) {
                    JSONObject packet = packets.getJSONObject(i % packets.length());
                    ByteBuffer encoded = ByteBuffer.wrap(fixture, packet.getInt("pos"), packet.getInt("size")).slice();
                    ByteArrayOutputStream annexB = new ByteArrayOutputStream();
                    while (encoded.hasRemaining()) {
                        byte[] nal = new byte[encoded.getInt()];
                        encoded.get(nal);
                        annexB.write(new byte[]{0, 0, 0, 1}); annexB.write(nal);
                    }
                    byte[] sample = annexB.toByteArray();
                    previousPts = lastPts;
                    lastPts = (i / packets.length()) * 2_000_000L
                            + Math.round(packet.getDouble("pts_time") * 1_000_000);
                    muxer.writeSampleData(track, ByteBuffer.wrap(sample), new BufferInfo(lastPts, sample.length,
                            packet.getString("flags").contains("K") ? C.BUFFER_FLAG_KEY_FRAME : 0));
                }
                CameraHlsEncoder.Companion.finishMuxer(muxer, track, lastPts, previousPts);
                assertTrue(window.isReady());
                ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
                rebuilt.write(window.initialization());
                for (int id = 0; window.segment(id) != null; id++) rebuilt.write(window.segment(id));
                Path output = dir.resolve("single.mp4");
                Files.write(output, rebuilt.toByteArray());
                command(dir, "ffmpeg", "-v", "error", "-xerror", "-i", output.toString(), "-f", "null", "-");
                JSONObject stream = new JSONObject(command(dir, "ffprobe", "-v", "error", "-count_frames",
                        "-show_entries", "stream=nb_read_frames,duration", "-of", "json", output.toString()))
                        .getJSONArray("streams").getJSONObject(0);
                assertEquals(frameCount, stream.getInt("nb_read_frames"));
                assertTrue(stream.getDouble("duration") > 0);
            }
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
            }
        }
    }

    @Test public void media3FragmentsDecodeIndependentlyAndJoinAtTheirActualTimestamps() throws Exception {
        Path dir = Files.createTempDirectory("media3-hls-");
        try {
            byte[] fixture;
            try (InputStream input = getClass().getResourceAsStream("/com/byd/extend/remote/fragmented_test.mp4")) {
                assertNotNull(input);
                fixture = input.readAllBytes();
            }
            Path input = dir.resolve("input.mp4");
            Files.write(input, fixture);
            JSONArray packets = new JSONObject(command(dir, "ffprobe", "-v", "error", "-select_streams", "v",
                    "-show_entries", "packet=pts_time,pos,size,flags", "-of", "json", input.toString())).getJSONArray("packets");
            FragmentedVideoWindow window = new FragmentedVideoWindow();
            try (FragmentedMp4Muxer muxer = new FragmentedMp4Muxer.Builder(window).setFragmentDurationMs(2000).build()) {
                int track = muxer.addTrack(new Format.Builder().setSampleMimeType("video/avc")
                        .setWidth(96).setHeight(64).setInitializationData(configuration(fixture)).build());
                for (int cycle = 0; cycle < 5; cycle++) for (int i = 0; i < packets.length(); i++) {
                    JSONObject packet = packets.getJSONObject(i);
                    int offset = packet.getInt("pos"), size = packet.getInt("size");
                    ByteBuffer encoded = ByteBuffer.wrap(fixture, offset, size).slice();
                    ByteArrayOutputStream annexB = new ByteArrayOutputStream();
                    while (encoded.hasRemaining()) {
                        int nalSize = encoded.getInt();
                        byte[] nal = new byte[nalSize]; encoded.get(nal);
                        annexB.write(new byte[] {0, 0, 0, 1}); annexB.write(nal);
                    }
                    byte[] sample = annexB.toByteArray();
                    long pts = cycle * 2_000_000L + Math.round(packet.getDouble("pts_time") * 1_000_000);
                    muxer.writeSampleData(track, ByteBuffer.wrap(sample), new BufferInfo(pts, sample.length,
                            packet.getString("flags").contains("K") ? C.BUFFER_FLAG_KEY_FRAME : 0));
                }
            }
            Files.write(dir.resolve("init.mp4"), window.initialization());
            String playlist = window.playlist();
            assertNotNull(playlist);
            int fragments = 0, totalFrames = 0;
            long previousDecodeTime = -1;
            for (int id = 0; window.segment(id) != null; id++) {
                byte[] segment = window.segment(id);
                int header = findBox(segment, "tfhd"), timestamp = findBox(segment, "tfdt");
                int flags = ByteBuffer.wrap(segment).getInt(header + 8);
                assertEquals(0, flags & 1);
                assertNotEquals(0, flags & 0x020000);
                long decodeTime = ByteBuffer.wrap(segment).getLong(timestamp + 12);
                assertTrue(decodeTime > previousDecodeTime);
                previousDecodeTime = decodeTime;
                ByteArrayOutputStream independent = new ByteArrayOutputStream();
                independent.write(window.initialization()); independent.write(segment);
                Path media = dir.resolve("independent.mp4");
                Files.write(media, independent.toByteArray());
                command(dir, "ffmpeg", "-v", "error", "-xerror", "-i", media.toString(), "-f", "null", "-");
                JSONObject stream = new JSONObject(command(dir, "ffprobe", "-v", "error", "-count_frames",
                        "-show_entries", "stream=nb_read_frames", "-of", "json", media.toString()))
                        .getJSONArray("streams").getJSONObject(0);
                int frames = stream.getInt("nb_read_frames");
                assertTrue(frames > 0); totalFrames += frames;
                Path segments = dir.resolve("segment"); Files.createDirectories(segments);
                Files.write(segments.resolve(id + ".m4s"), segment);
                fragments++;
            }
            assertEquals(4, fragments);
            assertEquals(100, totalFrames);
            assertTrue("Actual 3-second fragments must not be advertised as 2 seconds", playlist.contains("#EXTINF:3.000000,"));
            Files.write(dir.resolve("live.m3u8"), (playlist + "#EXT-X-ENDLIST\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            command(dir, "ffmpeg", "-v", "error", "-xerror", "-allowed_extensions", "ALL",
                    "-i", dir.resolve("live.m3u8").toString(), "-f", "null", "-");
            String joined = playlist.replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:1")
                    .replaceFirst("#EXTINF:[^\\n]+\\nsegment/0\\.m4s\\n", "");
            Files.write(dir.resolve("join.m3u8"), (joined + "#EXT-X-ENDLIST\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            command(dir, "ffmpeg", "-v", "error", "-xerror", "-allowed_extensions", "ALL",
                    "-i", dir.resolve("join.m3u8").toString(), "-f", "null", "-");
            JSONArray joinedStreams = new JSONObject(command(dir, "ffprobe", "-v", "error", "-count_frames",
                    "-allowed_extensions", "ALL", "-show_entries", "stream=nb_read_frames", "-of", "json",
                    dir.resolve("join.m3u8").toString())).getJSONArray("streams");
            assertEquals(70, joinedStreams.getJSONObject(0).getInt("nb_read_frames"));
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
            }
        }
    }

    private static List<byte[]> configuration(byte[] mp4) throws Exception {
        int start = findBox(mp4, "avcC");
        ByteBuffer data = ByteBuffer.wrap(mp4); data.position(start + 13);
        List<byte[]> csd = new ArrayList<>();
        int spsCount = data.get() & 31;
        for (int group = 0; group < 2; group++) {
            int count = group == 0 ? spsCount : data.get() & 255;
            for (int i = 0; i < count; i++) {
                int length = data.getShort() & 65535;
                byte[] nal = new byte[length + 4]; nal[3] = 1; data.get(nal, 4, length); csd.add(nal);
            }
        }
        return csd;
    }

    private static int findBox(byte[] data, String name) {
        byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 4; i <= data.length - 4; i++)
            if (Arrays.equals(type, Arrays.copyOfRange(data, i, i + 4))) return i - 4;
        throw new AssertionError("Missing MP4 box " + name);
    }

    private static String command(Path dir, String... args) throws Exception {
        Path output = dir.resolve("process.log");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Media tool timed out"); }
        String text = new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(text, 0, process.exitValue());
        return text;
    }
}
