package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AvasNavigationMonitorTest {
    @Test public void ownTracksAreExcludedByIdentityNotBySharedShellUid() {
        int own = 912301;
        for (String source : new String[]{MusicPlaybackSource.ENGINE,
                MusicPlaybackSource.MICROPHONE, MusicPlaybackSource.EVENT}) {
            MusicPlaybackSource.register(own, source);
            assertTrue(MusicPlaybackSource.isOwnedPlayer(own, null));
            assertFalse(MusicPlaybackSource.isOwnedPlayer(own + 1, java.util.Collections.emptySet()));
            MusicPlaybackSource.released(own);
            assertTrue(MusicPlaybackSource.isOwnedPlayer(own, null));
            assertTrue(MusicPlaybackSource.isOwnedPlayer(-1, java.util.Collections.singleton(source)));
        }
    }

    @Test public void navigationIsNotDeterminedByStreamInequality() {
        assertTrue(AvasNavigationMonitor.isNavigation(12, 6, 0x20800, 3)); // captured Waze
        assertTrue(AvasNavigationMonitor.isNavigation(12, 1, 0, 3));
        assertTrue(AvasNavigationMonitor.isNavigation(0, 0, 0, 15));
        assertTrue(AvasNavigationMonitor.isNavigation(0, 6, 0, 3));
        assertFalse(AvasNavigationMonitor.isNavigation(1, 2, 0, 3)); // ordinary music
        assertFalse(AvasNavigationMonitor.isNavigation(4, 4, 0, 4)); // alarm
        assertFalse(AvasNavigationMonitor.isNavigation(1, 2, 0x800, 3));
        assertTrue(AvasNavigationMonitor.isNavigation(1, 2, 0x4000, 3));
        assertTrue(AvasNavigationMonitor.isNavigation(1, 2, 0x8000, 3));
    }
}
