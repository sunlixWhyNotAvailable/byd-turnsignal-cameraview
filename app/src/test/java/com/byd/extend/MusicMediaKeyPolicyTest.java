package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.media.session.PlaybackState;

import org.junit.Test;

public final class MusicMediaKeyPolicyTest {
    @Test public void playerSelectionSurvivesNonPlayerWindowsAndChangesOnlyForCapableApp() {
        MusicMediaKeyPolicy.Selection selection = new MusicMediaKeyPolicy.Selection();
        selection.opened("com.example.radio", true);
        assertFalse(selection.needsSessionRefresh(true));
        assertTrue(selection.needsSessionRefresh(false));
        selection.opened("com.example.newplayer", false); // its session has not reached our cache yet
        assertTrue(selection.needsSessionRefresh(true)); // the OLD paused session cannot suppress refresh
        selection.opened("com.example.navigation", false);
        assertEquals("com.example.radio", selection.player);
        selection.opened("com.example.systemdialog", false);
        assertEquals("com.example.radio", selection.player);
        selection.opened("com.example.newplayer", true);
        assertEquals("com.example.newplayer", selection.player);
        assertTrue(selection.mayUseReceiver("com.example.newplayer", true, false));
        assertFalse(selection.mayUseReceiver("com.example.newplayer", false, false));
        assertFalse(selection.mayUseReceiver("com.example.newplayer", true, true));
        selection.opened("com.example.navigation", false);
        assertFalse(selection.mayUseReceiver("com.example.newplayer", true, false));
        selection.clear();
        assertEquals("", selection.player);
    }

    @Test public void transportCapabilityNotFocusQualifiesAndEachCommandMustBeSupported() {
        assertFalse(MusicMediaKeyPolicy.isPlayer(0));
        assertFalse(MusicMediaKeyPolicy.isPlayer(PlaybackState.ACTION_STOP));
        assertTrue(MusicMediaKeyPolicy.isPlayer(PlaybackState.ACTION_PLAY));
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_SKIP_TO_NEXT;
        assertTrue(MusicMediaKeyPolicy.supports(actions, 353));
        assertTrue(MusicMediaKeyPolicy.supports(actions, KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse(MusicMediaKeyPolicy.supports(actions, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertFalse(MusicMediaKeyPolicy.supports(actions, KeyEvent.KEYCODE_MEDIA_RECORD));
    }
    @Test
    public void routesOnlyEnabledUnassignedInitialMediaDowns() {
        assertTrue(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertTrue(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, MusicMediaKeyPolicy.OEM_WHEEL_KEY,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                false, true, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, false, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, true, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, true, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 1));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN, 0));
    }

    @Test
    public void wheelPressMapsToStandardPlayPauseAndShellPayloadIsBounded() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                MusicMediaKeyPolicy.standardKeyCode(MusicMediaKeyPolicy.OEM_WHEEL_KEY));
        assertTrue(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", MusicMediaKeyPolicy.OEM_WHEEL_KEY,
                KeyEvent.ACTION_DOWN, 0, 100, 100));
        assertTrue(MusicMediaKeyPolicy.isValidCommand(
                "", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_UP, 0, 100, 140));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "not a package", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_DOWN, 0, 100, 100));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN, 0, 100, 100));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_MULTIPLE, 0, 100, 100));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_DOWN, 0, 100, 100 + MusicMediaKeyPolicy.MAX_KEY_HOLD_MS + 1));
    }

    @Test
    public void consumedDownOwnsOnlyItsMatchingUp() {
        MusicMediaKeyPolicy.RoutedKeys keys = new MusicMediaKeyPolicy.RoutedKeys();
        keys.begin(MusicMediaKeyPolicy.OEM_WHEEL_KEY, 42);

        assertTrue(keys.owns(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 42));
        assertFalse(keys.finish(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 43));
        assertTrue(keys.owns(MusicMediaKeyPolicy.OEM_WHEEL_KEY, 42));
        assertTrue(keys.finish(MusicMediaKeyPolicy.OEM_WHEEL_KEY, 42));
        assertFalse(keys.owns(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 42));
    }
}
