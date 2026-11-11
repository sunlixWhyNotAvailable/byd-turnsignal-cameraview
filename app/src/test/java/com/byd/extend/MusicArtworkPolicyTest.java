package com.byd.extend;

import com.byd.extend.artwork.MusicArtworkPolicy;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public class MusicArtworkPolicyTest {
    private static final String PREFIX = "/storage/emulated/0/Download/.byd-extend-artwork/run/";
    private static final String OWN = PREFIX + "a".repeat(64) + ".png";

    @Test public void trackIdentityUsesSessionAndTrackNotDurationOrPlaybackState() {
        assertEquals(MusicArtworkPolicy.trackKey("session1", "song1", "", ""),
                MusicArtworkPolicy.trackKey("session1", "song1", "late title", "late artist"));
        assertNotEquals(MusicArtworkPolicy.trackKey("session1", "song1", "", ""),
                MusicArtworkPolicy.trackKey("session2", "song1", "", ""));
        assertNotEquals(MusicArtworkPolicy.trackKey("session1", null, "AB", "C"),
                MusicArtworkPolicy.trackKey("session1", null, "A", "BC"));
    }

    @Test public void changedTrackOrImageCannotPublishAfterPreparation() {
        assertTrue(MusicArtworkPolicy.mayPublish(1007, 1007, 26, "track", "track", OWN, OWN));
        assertFalse(MusicArtworkPolicy.mayPublish(1007, 1002, 26, "track", "track", OWN, OWN));
        assertFalse(MusicArtworkPolicy.mayPublish(1007, 1007, 3, "track", "track", OWN, OWN));
        assertFalse(MusicArtworkPolicy.mayPublish(1007, 1007, 26, "old", "new", OWN, OWN));
        assertFalse(MusicArtworkPolicy.mayPublish(1007, 1007, 26, "track", "track", OWN, "other"));
        assertFalse(MusicArtworkPolicy.mayPublish(1007, 1007, 26, "track", "track", null, null));
    }

    @Test public void releaseNeverOverwritesNewOemContent() {
        assertEquals(MusicArtworkPolicy.Restore.ORIGINAL, MusicArtworkPolicy.restore(7, 26, "a", 7, 26, OWN, PREFIX, "a"));
        assertEquals(MusicArtworkPolicy.Restore.DEFAULT, MusicArtworkPolicy.restore(7, 26, "a", 7, 26, OWN, PREFIX, "b"));
        assertEquals(MusicArtworkPolicy.Restore.DEFAULT, MusicArtworkPolicy.restore(7, 26, "a", 7, 26, OWN, PREFIX, null));
        assertEquals(MusicArtworkPolicy.Restore.LEAVE, MusicArtworkPolicy.restore(7, 26, "a", 7, 26, "oem.png", PREFIX, "a"));
        assertEquals(MusicArtworkPolicy.Restore.LEAVE, MusicArtworkPolicy.restore(7, 26, "a", 8, 26, OWN, PREFIX, "a"));
        assertFalse(MusicArtworkPolicy.owns(PREFIX, PREFIX + "../oem.png"));
        assertFalse(MusicArtworkPolicy.owns(PREFIX, OWN + "/extra"));
    }

    @Test public void badgeUsesMeasuredOemProportions() {
        assertEquals(44, MusicArtworkPolicy.badgeSize(176));
        assertEquals(10, MusicArtworkPolicy.badgeInset(176));
        assertEquals(120, MusicArtworkPolicy.badgeSize(480));
        assertEquals(27, MusicArtworkPolicy.badgeInset(480));
    }

    @Test public void packagedWorkerReadIsBoundedWithoutNewAndroidStreamApis() throws Exception {
        byte[] dex = new byte[12_000];
        dex[8192] = 42;
        assertArrayEquals(dex, MusicArtworkRuntime.readWorker(new ByteArrayInputStream(dex)));
        assertThrows(IOException.class, () -> MusicArtworkRuntime.readWorker(new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class, () -> MusicArtworkRuntime.readWorker(new ByteArrayInputStream(new byte[450_001])));
    }
}
