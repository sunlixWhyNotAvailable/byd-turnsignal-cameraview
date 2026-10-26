package com.byd.extend.remote;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class FragmentedVideoWindowTest {
    private static final String FIXTURE = "/com/byd/extend/remote/fragmented_test.mp4";

    @Test public void rejectedBoxCauseSurvivesStreamClose() throws Exception {
        FragmentedVideoWindow window = new FragmentedVideoWindow();
        byte[] invalid = {0, 0, 0, 7, 'm', 'd', 'a', 't'};
        IOException write = assertThrows(IOException.class, () -> window.write(invalid));
        assertEquals("Unsupported MP4 box size", write.getMessage());
        IOException close = assertThrows(IOException.class, window::close);
        assertSame(write, close.getCause());
        assertFalse(window.isReady());
        assertNull(window.segment(0));
    }

    @Test public void newViewCannotServePreviousGenerationResourcesOrInheritReadiness() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(FIXTURE)) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        FragmentedVideoWindow oldView = new FragmentedVideoWindow();
        oldView.write(bytes);
        assertTrue(oldView.isReady());
        FragmentedVideoWindow nextView = new FragmentedVideoWindow(java.util.UUID.randomUUID().toString(), () -> {});
        String oldBase = oldView.getPlaylistPath().replace("live.m3u8", "");
        String nextBase = nextView.getPlaylistPath().replace("live.m3u8", "");
        for (String resource : new String[]{"live.m3u8", "init.mp4", "segment/0.m4s"}) {
            assertNull(nextView.resourcePath(oldBase + resource));
            assertNull(oldView.resourcePath(nextBase + resource));
            assertEquals("/" + resource, nextView.resourcePath(nextBase + resource));
        }
        assertFalse(nextView.isReady());
        assertNull(nextView.playlist());
        assertNull(nextView.segment(0));
        nextView.write(bytes);
        assertTrue(nextView.isReady());
        oldView.clear();
        assertTrue(nextView.isReady());
        assertNotNull(nextView.segment(0));
    }

    @Test public void streamIdOwnsTheStableMediaResourcePath() {
        String streamId = java.util.UUID.randomUUID().toString();
        FragmentedVideoWindow window = new FragmentedVideoWindow(streamId, () -> {});

        assertEquals(streamId, window.getStreamId());
        assertEquals("/media/" + streamId + "/live.m3u8", window.getPlaylistPath());
        assertEquals("/live.m3u8", window.resourcePath(window.getPlaylistPath()));
        assertNull(window.resourcePath("/media/" + java.util.UUID.randomUUID() + "/live.m3u8"));
    }

    @Test public void readinessWaitsForTheWholeFirstFragmentAndSignalsOnlyOnce() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(FIXTURE)) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        FragmentedVideoWindow window = new FragmentedVideoWindow(notifications::incrementAndGet);
        int offset = 0;
        while (!"mdat".equals(boxType(bytes, offset))) {
            int length = boxSize(bytes, offset);
            window.write(bytes, offset, length);
            offset += length;
            assertFalse(window.isReady());
            assertNull(window.playlist());
        }
        int payload = boxSize(bytes, offset);
        window.write(bytes, offset, payload - 1);
        assertFalse(window.isReady());
        assertEquals(0, notifications.get());
        window.write(bytes, offset + payload - 1, 1);
        assertTrue(window.isReady());
        assertNotNull(window.initialization());
        assertNotNull(window.segment(0));
        assertEquals(1, notifications.get());
        offset += payload;
        window.write(bytes, offset, bytes.length - offset);
        window.close();
        assertEquals(1, notifications.get());
        window.clear();
        assertFalse(window.isReady());
    }

    @Test public void splitsFfmpegFragmentedMp4AcrossArbitraryOutputWrites() throws Exception {
        WindowResult result = splitFixture();

        assertEquals("ftyp", boxType(result.initialization, 0));
        int moovOffset = boxSize(result.initialization, 0);
        assertEquals("moov", boxType(result.initialization, moovOffset));
        assertEquals(result.initialization.length,
                moovOffset + boxSize(result.initialization, moovOffset));
        assertEquals(4, result.segmentCount);
        assertTrue(result.playlist.startsWith("#EXTM3U\n#EXT-X-VERSION:7\n"));
        assertTrue(result.playlist.contains("#EXT-X-MEDIA-SEQUENCE:0\n"));
        assertTrue(result.playlist.contains("#EXT-X-MAP:URI=\"init.mp4\"\n"));

        for (int id = 0; id < result.segmentCount; id++) {
            byte[] segment = result.window.segment(id);
            assertEquals("moof", boxType(segment, 0));
            int mdatOffset = boxSize(segment, 0);
            assertEquals("mdat", boxType(segment, mdatOffset));
            assertEquals(segment.length, mdatOffset + boxSize(segment, mdatOffset));
            assertTrue(result.playlist.contains("segment/" + id + ".m4s\n"));
        }
        assertNull(result.window.segment(result.segmentCount));
        assertTrue(result.reconstructed.length > result.initialization.length);
    }

    @Test public void reconstructedInitializationAndSegmentsDecodeWithFfmpegWhenAvailable()
            throws Exception {
        WindowResult result = splitFixture();
        Path media = Files.createTempFile("fragmented-window-", ".mp4");
        Path log = Files.createTempFile("fragmented-window-ffmpeg-", ".log");
        try {
            Files.write(media, result.reconstructed);
            String executable = System.getenv("FFMPEG_BIN");
            if (executable == null || executable.trim().isEmpty()) executable = "ffmpeg";
            Process process;
            try {
                process = new ProcessBuilder(executable, "-hide_banner", "-v", "error",
                        "-i", media.toString(), "-f", "null", "-")
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            } catch (IOException unavailable) {
                Assume.assumeNoException("ffmpeg is unavailable; production splitter assertions still run",
                        unavailable);
                return;
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                fail("ffmpeg timed out while decoding reconstructed H.264 fragments");
            }
            String diagnostics = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            assertEquals("ffmpeg could not decode the reconstructed video: " + diagnostics,
                    0, process.exitValue());
        } finally {
            Files.deleteIfExists(log);
            Files.deleteIfExists(media);
        }
    }

    private static WindowResult splitFixture() throws Exception {
        byte[] encoded;
        try (InputStream input = FragmentedVideoWindowTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull("synthetic FFmpeg H.264 fixture missing", input);
            encoded = input.readAllBytes();
        }

        FragmentedVideoWindow window = new FragmentedVideoWindow();
        int[] chunks = {1, 7, 2, 19, 4093, 3, 257, 11};
        int offset = 0;
        int chunk = 0;
        while (offset < encoded.length) {
            int length = Math.min(chunks[chunk++ % chunks.length], encoded.length - offset);
            window.write(encoded, offset, length);
            offset += length;
        }
        window.close();

        byte[] initialization = window.initialization();
        assertNotNull("MP4 initialization was not assembled", initialization);
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        rebuilt.write(initialization);
        int segments = 0;
        byte[] segment;
        while ((segment = window.segment(segments)) != null) {
            rebuilt.write(segment);
            segments++;
        }
        assertTrue("fixture must contain several complete video fragments", segments > 1);
        return new WindowResult(window, initialization, window.playlist(), segments,
                rebuilt.toByteArray());
    }

    private static String boxType(byte[] bytes, int offset) {
        assertNotNull(bytes);
        assertTrue(offset >= 0 && offset + 8 <= bytes.length);
        return new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
    }

    private static int boxSize(byte[] bytes, int offset) {
        assertNotNull(bytes);
        assertTrue(offset >= 0 && offset + 8 <= bytes.length);
        int size = ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
        assertTrue(size >= 8 && offset + size <= bytes.length);
        return size;
    }

    private static final class WindowResult {
        final FragmentedVideoWindow window;
        final byte[] initialization;
        final String playlist;
        final int segmentCount;
        final byte[] reconstructed;

        WindowResult(FragmentedVideoWindow window, byte[] initialization, String playlist,
                int segmentCount, byte[] reconstructed) {
            this.window = window;
            this.initialization = initialization;
            this.playlist = playlist;
            this.segmentCount = segmentCount;
            this.reconstructed = reconstructed;
        }
    }
}
