package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class AvasNavVolumePolicyTest {
    @Test public void partialJournalNeverLosesOriginalMute() throws Exception {
        for (boolean volumePersistedBeforeFailure : new boolean[]{false, true}) {
            int[] saved = {-1, -1};
            boolean[] muted = {true};
            List<String> calls = new ArrayList<>();
            assertThrows(IllegalStateException.class, () -> AvasNavVolumePolicy.journal(
                    new AvasNavVolumePolicy.Snapshot(7, true),
                    value -> { saved[1] = value; calls.add("save-mute"); },
                    value -> {
                        calls.add("save-volume");
                        if (volumePersistedBeforeFailure) saved[0] = value;
                        throw new IllegalStateException("journal acknowledgement failed");
                    }));
            assertEquals(Arrays.asList("save-mute", "save-volume"), calls);
            assertEquals(1, saved[1]);
            AvasNavVolumePolicy.restore(saved[0], saved[1],
                    value -> muted[0] = false, () -> saved[0] = -1,
                    () -> muted[0], value -> muted[0] = value, () -> saved[1] = -1);
            assertTrue(muted[0]);
            assertEquals(-1, saved[0]);
            assertEquals(-1, saved[1]);
        }
    }

    @Test public void mutedSnapshotUsesLastAudibleIndexWithoutReadingMaskedCurrent() throws Exception {
        AvasNavVolumePolicy.Snapshot snapshot = AvasNavVolumePolicy.capture(
                () -> true, () -> 15,
                () -> { throw new AssertionError("masked current volume must not be used"); });

        assertEquals(15, snapshot.volume);
        assertTrue(snapshot.muted);
    }

    @Test public void realZeroIsPreserved() throws Exception {
        AvasNavVolumePolicy.Snapshot snapshot = AvasNavVolumePolicy.capture(
                () -> false, () -> 0, () -> 9);

        assertEquals(0, snapshot.volume);
        assertFalse(snapshot.muted);
    }

    @Test public void unavailableLastAudibleFallsBackOnlyWhileUnmuted() throws Exception {
        AvasNavVolumePolicy.Snapshot snapshot = AvasNavVolumePolicy.capture(
                () -> false,
                () -> { throw new NoSuchMethodException("firmware API absent"); },
                () -> 7);
        assertEquals(7, snapshot.volume);

        assertThrows(IllegalStateException.class, () -> AvasNavVolumePolicy.capture(
                () -> true,
                () -> { throw new NoSuchMethodException("firmware API absent"); },
                () -> 0));
        assertThrows(IllegalStateException.class, () -> AvasNavVolumePolicy.capture(
                () -> { throw new IllegalStateException("mute unreadable"); },
                () -> 15, () -> 0));
    }

    @Test public void capAndReadbackPrecedePlay() throws Exception {
        List<String> calls = new ArrayList<>();

        assertTrue(AvasNavVolumePolicy.capAndPlay(
                value -> calls.add("cap:" + value),
                () -> { calls.add("readback"); return 1; },
                () -> { calls.add("cancel"); return false; },
                () -> calls.add("play")));

        assertEquals(Arrays.asList("cancel", "cap:1", "readback", "cancel", "play"), calls);
    }

    @Test public void cancellationAndFailedReadbackNeverPlay() throws Exception {
        List<String> calls = new ArrayList<>();
        assertFalse(AvasNavVolumePolicy.capAndPlay(
                value -> calls.add("cap"), () -> 1, () -> true,
                () -> calls.add("play")));
        assertEquals(Arrays.asList(), calls);

        AtomicInteger checks = new AtomicInteger();
        assertFalse(AvasNavVolumePolicy.capAndPlay(
                value -> calls.add("cap"),
                () -> { calls.add("readback"); return 1; },
                () -> checks.incrementAndGet() == 2,
                () -> calls.add("play")));
        assertEquals(Arrays.asList("cap", "readback"), calls);

        calls.clear();
        assertThrows(IllegalStateException.class, () -> AvasNavVolumePolicy.capAndPlay(
                value -> calls.add("cap"),
                () -> { calls.add("readback"); return 0; },
                () -> false, () -> calls.add("play")));
        assertEquals(Arrays.asList("cap", "readback"), calls);
    }

    @Test public void legacySavedStateRestoresZeroAndOriginalMuteThenClearsEachSuccess()
            throws Exception {
        List<String> calls = new ArrayList<>();
        AvasNavVolumePolicy.restoreVolume(0,
                value -> calls.add("volume:" + value), () -> calls.add("clear-volume"));
        AvasNavVolumePolicy.restoreMute(1, () -> false,
                value -> calls.add("mute:" + value), () -> calls.add("clear-mute"));

        assertEquals(Arrays.asList("volume:0", "clear-volume", "mute:true", "clear-mute"),
                calls);
    }

    @Test public void matchingMuteAvoidsWriteAndFailedRestoreKeepsSavedState() throws Exception {
        List<String> calls = new ArrayList<>();
        AvasNavVolumePolicy.restoreMute(0, () -> false,
                value -> calls.add("mute"), () -> calls.add("clear-mute"));
        assertEquals(Arrays.asList("clear-mute"), calls);

        assertThrows(IllegalStateException.class, () -> AvasNavVolumePolicy.restoreVolume(15,
                value -> { throw new IllegalStateException("write failed"); },
                () -> calls.add("clear-volume")));
        assertFalse(calls.contains("clear-volume"));
    }

    @Test public void failedVolumeRestoreKeepsMuteForRetryAfterImplicitUnmute() throws Exception {
        List<String> calls = new ArrayList<>();
        int[] saved = {15, 1};
        boolean[] muted = {true};

        assertThrows(IllegalStateException.class, () -> AvasNavVolumePolicy.restore(
                saved[0], saved[1],
                value -> { calls.add("volume-failed"); throw new IllegalStateException(); },
                () -> saved[0] = -1,
                () -> muted[0], value -> { muted[0] = value; calls.add("mute:" + value); },
                () -> saved[1] = -1));
        assertEquals(15, saved[0]);
        assertEquals(1, saved[1]);
        assertTrue(muted[0]);
        assertEquals(Arrays.asList("volume-failed"), calls);

        AvasNavVolumePolicy.restore(saved[0], saved[1], value -> {
                    calls.add("volume:" + value);
                    muted[0] = false; // OEM setStreamVolume side effect.
                }, () -> saved[0] = -1,
                () -> muted[0], value -> { muted[0] = value; calls.add("mute:" + value); },
                () -> saved[1] = -1);

        assertEquals(-1, saved[0]);
        assertEquals(-1, saved[1]);
        assertTrue(muted[0]);
        assertEquals(Arrays.asList("volume-failed", "volume:15", "mute:true"), calls);
    }
}
