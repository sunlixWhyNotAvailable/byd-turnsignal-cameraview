package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class MusicMetadataPublishTest {
    private static final int SOURCE_FID = 101;
    private static final int MUSIC_STATE_FID = 102;
    private static final int RADIO_STATE_FID = 103;
    private static final int TITLE_FID = 104;
    private static final int ARTIST_FID = 105;
    private static final int PROGRESS_FID = 106;
    private static final int[] TIME_FIDS = {107, 108, 109, 110, 111, 112};

    @Test
    public void emptyTitleOrArtistUsesSpaceAndPublishesRemainingFields() throws Exception {
        assertEmptyFieldPublishes(
                new MusicMetadataRuntime.Snapshot("player", "", "Співак", 90_000, 15_000, true),
                TITLE_FID, "Співак");
        assertEmptyFieldPublishes(
                new MusicMetadataRuntime.Snapshot("player", "歌曲", "", 90_000, 15_000, true),
                ARTIST_FID, "歌曲");
    }

    @Test
    public void nonemptyEnglishUkrainianAndChineseTextKeepsItsOriginalOemBytes()
            throws Exception {
        String title = "Morning Light | Ранкове світло | 晨光";
        String artist = "The Band | Гурт | 乐队";
        MusicMetadataRuntime.Snapshot snapshot = new MusicMetadataRuntime.Snapshot(
                "player", title, artist, 90_000, 15_000, true);
        RecordingAutoManager manager = new RecordingAutoManager();

        writer(manager, new ArrayList<>()).publish(snapshot, false);

        assertArrayEquals(MusicMetadataRuntime.utf16Le(snapshot.title), buffer(manager, TITLE_FID));
        assertArrayEquals(MusicMetadataRuntime.utf16Le(snapshot.artist), buffer(manager, ARTIST_FID));
        assertFalse(hasZeroLengthBuffer(manager));
    }

    @Test
    public void cleanupStillWritesSpaceForBothMetadataFields() throws Exception {
        RecordingAutoManager manager = new RecordingAutoManager();

        writer(manager, new ArrayList<>()).cleanup();

        byte[] space = " ".getBytes(StandardCharsets.UTF_16LE);
        assertArrayEquals(space, buffer(manager, TITLE_FID));
        assertArrayEquals(space, buffer(manager, ARTIST_FID));
    }

    @Test
    public void nonzeroOemStatusForMetadataStillFailsPublish() throws Exception {
        RecordingAutoManager manager = new RecordingAutoManager();
        manager.failedBufferFid = ARTIST_FID;
        List<WriteEvent> events = new ArrayList<>();

        try {
            writer(manager, events).publish(
                    new MusicMetadataRuntime.Snapshot("player", "Title", "", 90_000, 15_000, true),
                    false);
            fail("Expected the OEM status to fail the publish");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("artist framework status -7"));
        }

        assertEquals(2, manager.writes.size());
        assertEquals("music_metadata_fid_write", events.get(events.size() - 1).name);
        assertEquals(-7, events.get(events.size() - 1).frameworkStatus);
        assertEquals("buffer", manager.writes.get(1).operation);
        assertEquals(ARTIST_FID, manager.writes.get(1).fids[0]);
    }

    private static void assertEmptyFieldPublishes(
            MusicMetadataRuntime.Snapshot snapshot, int emptyFid, String otherValue)
            throws Exception {
        RecordingAutoManager manager = new RecordingAutoManager();
        writer(manager, new ArrayList<>()).publish(snapshot, false);

        assertEquals(5, manager.writes.size());
        assertFalse(hasZeroLengthBuffer(manager));
        assertArrayEquals(" ".getBytes(StandardCharsets.UTF_16LE), buffer(manager, emptyFid));
        int otherFid = emptyFid == TITLE_FID ? ARTIST_FID : TITLE_FID;
        assertArrayEquals(MusicMetadataRuntime.utf16Le(otherValue), buffer(manager, otherFid));
        assertEquals(MusicMetadataRuntime.DEVICE_INSTRUMENT, manager.writes.get(0).device);
        assertEquals(MusicMetadataRuntime.DEVICE_AUDIO, manager.writes.get(1).device);
        assertEquals("int_array", manager.writes.get(2).operation);
        assertEquals(MusicMetadataRuntime.DEVICE_AUDIO, manager.writes.get(2).device);
        assertArrayEquals(TIME_FIDS, manager.writes.get(2).fids);
        assertArrayEquals(snapshot.timeline, manager.writes.get(2).values);
        assertEquals(MusicMetadataRuntime.DEVICE_INSTRUMENT, manager.writes.get(3).device);
        assertEquals(PROGRESS_FID, manager.writes.get(3).fids[0]);
        assertEquals(snapshot.progress, manager.writes.get(3).values[0]);
        assertEquals(MusicMetadataRuntime.DEVICE_INSTRUMENT, manager.writes.get(4).device);
        assertEquals(MUSIC_STATE_FID, manager.writes.get(4).fids[0]);
        assertEquals(1, manager.writes.get(4).values[0]);
    }

    private static MusicMetadataRuntime.BydMediaWriter writer(
            RecordingAutoManager manager, List<WriteEvent> events) throws Exception {
        return new MusicMetadataRuntime.BydMediaWriter(
                manager,
                (name, values) -> events.add(new WriteEvent(name, values)),
                SOURCE_FID, MUSIC_STATE_FID, RADIO_STATE_FID,
                TITLE_FID, ARTIST_FID, PROGRESS_FID, TIME_FIDS.clone());
    }

    private static byte[] buffer(RecordingAutoManager manager, int fid) {
        for (Write write : manager.writes) {
            if (write.operation.equals("buffer") && write.fids[0] == fid) return write.buffer;
        }
        fail("No buffer write for FID " + fid);
        return new byte[0];
    }

    private static boolean hasZeroLengthBuffer(RecordingAutoManager manager) {
        for (Write write : manager.writes) {
            if (write.operation.equals("buffer") && write.buffer.length == 0) return true;
        }
        return false;
    }

    public static final class RecordingAutoManager {
        final List<Write> writes = new ArrayList<>();
        int failedBufferFid = -1;

        public int setInt(int device, int fid, int value) {
            writes.add(new Write("int", device, new int[]{fid}, new int[]{value}, null));
            return 0;
        }

        public int setBuffer(int device, int fid, byte[] value) {
            writes.add(new Write("buffer", device, new int[]{fid}, null, value.clone()));
            if (value.length == 0) return -2147482648;
            return fid == failedBufferFid ? -7 : 0;
        }

        public int setIntArray(int device, int[] fids, int[] values) {
            writes.add(new Write("int_array", device, fids.clone(), values.clone(), null));
            return 0;
        }
    }

    private static final class Write {
        final String operation;
        final int device;
        final int[] fids;
        final int[] values;
        final byte[] buffer;

        Write(String operation, int device, int[] fids, int[] values, byte[] buffer) {
            this.operation = operation;
            this.device = device;
            this.fids = fids;
            this.values = values;
            this.buffer = buffer;
        }
    }

    private static final class WriteEvent {
        final String name;
        final int frameworkStatus;

        WriteEvent(String name, Object[] values) {
            this.name = name;
            int status = 0;
            for (int i = 0; i + 1 < values.length; i += 2) {
                if ("framework_status".equals(values[i])) status = (Integer) values[i + 1];
            }
            frameworkStatus = status;
        }
    }
}
