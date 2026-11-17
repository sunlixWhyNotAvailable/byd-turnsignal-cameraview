package com.byd.extend;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MusicSettingsTest {
    @Test public void onlyExcludedPlayingSessionsSuppressTheCommonMix() {
        Set<String> excluded = Set.of("com.player.one", "com.player.two");
        assertTrue(MusicSettings.excludesPlaying(Set.of("com.player.one"), excluded));
        assertTrue(MusicSettings.excludesPlaying(excluded, excluded));
        assertFalse(MusicSettings.excludesPlaying(Set.of("com.player.one", "com.player.allowed"), excluded));
        assertFalse(MusicSettings.excludesPlaying(Set.of("com.player.one", ""), excluded));
        assertFalse(MusicSettings.excludesPlaying(Set.of(), excluded));
        assertFalse(MusicSettings.excludesPlaying(Set.of("com.player.one"), Set.of()));
    }

    @Test public void musicChoicesSurviveReopenAndOlderImportsAndRejectMalformedImports() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences settings = app.getSharedPreferences("music-test", Context.MODE_PRIVATE);
        assertFalse(settings.getBoolean(MusicSettings.ARTWORK, false));
        assertFalse(settings.getBoolean(MusicSettings.PLAYER_ICON, false));
        Set<String> packages = Set.of("com.player.one", "com.player.two");
        String xml = "<map><boolean name=\"music_show_artwork\" value=\"true\"/>"
                + "<boolean name=\"music_show_player_icon\" value=\"false\"/>"
                + "<string name=\"music_visualization_excluded_apps\">"
                + MusicSettings.encode(packages) + "</string></map>";
        CameraSettingsTransfer.applyLegacySettings(settings, CameraSettingsTransfer.parseLegacySettings(xml));
        SharedPreferences reopened = app.getSharedPreferences("music-test", Context.MODE_PRIVATE);
        assertTrue(reopened.getBoolean(MusicSettings.ARTWORK, false));
        assertFalse(reopened.getBoolean(MusicSettings.PLAYER_ICON, true));
        assertEquals(packages, MusicSettings.excluded(reopened));
        CameraSettingsTransfer.applyLegacySettings(reopened, CameraSettingsTransfer.parseLegacySettings("<map/>"));
        assertEquals(packages, MusicSettings.excluded(reopened));
        assertTrue(reopened.getBoolean(MusicSettings.ARTWORK, false));
        assertFalse(reopened.getBoolean(MusicSettings.PLAYER_ICON, true));
        for (String invalid : new String[]{"[1]", "[\"not a package\"]", "{}", "["}) {
            assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.parseLegacySettings(
                    "<map><string name=\"music_visualization_excluded_apps\">" + invalid + "</string></map>"));
            assertEquals(packages, MusicSettings.excluded(reopened));
        }
    }
}
