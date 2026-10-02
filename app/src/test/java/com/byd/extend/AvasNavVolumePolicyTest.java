package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AvasNavVolumePolicyTest {
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
