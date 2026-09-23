package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioAttributes;

import org.junit.Test;

public final class MusicVisualizerPlaybackTest {
    private static final int OEM_CONTENT_TYPE_NAVI = 6;

    @Test
    public void navigationAttributesAreExcludedBeforeMusicClassification() {
        assertFalse(MusicVisualizerRuntime.isMusicAttributes(
                AudioAttributes.USAGE_MEDIA, OEM_CONTENT_TYPE_NAVI));
        assertFalse(MusicVisualizerRuntime.isMusicAttributes(
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.CONTENT_TYPE_UNKNOWN));
        assertFalse(MusicVisualizerRuntime.isMusicAttributes(
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.CONTENT_TYPE_MUSIC));
    }

    @Test
    public void ordinaryMusicRemainsEligibleAndNavigationAloneCannotKeepOutputActive() {
        assertTrue(MusicVisualizerRuntime.isMediaPlayback(
                true, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN));
        assertTrue(MusicVisualizerRuntime.isMediaPlayback(
                true, AudioAttributes.USAGE_UNKNOWN, AudioAttributes.CONTENT_TYPE_MUSIC));
        assertFalse(MusicVisualizerRuntime.isMediaPlayback(
                false, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC));

        boolean avasActive = MusicVisualizerRuntime.isMediaPlayback(
                true, AudioAttributes.USAGE_MEDIA, OEM_CONTENT_TYPE_NAVI);
        assertFalse(avasActive);
        assertFalse(MusicVisualizerRuntime.shouldStartOutput(
                true, true, true, avasActive, false));
        assertTrue(MusicVisualizerRuntime.shouldScheduleStop(true, avasActive, false));
    }

    @Test
    public void mixedMusicAndAvasTracksKeepMusicEligible() {
        boolean musicTrackActive = MusicVisualizerRuntime.isMediaPlayback(
                true, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN);
        boolean avasTrackActive = MusicVisualizerRuntime.isMediaPlayback(
                true, AudioAttributes.USAGE_MEDIA, OEM_CONTENT_TYPE_NAVI);

        // Per-track classifier composition only; this does not test Android configuration dispatch.
        boolean anyMediaActive = musicTrackActive || avasTrackActive;
        assertTrue(musicTrackActive);
        assertFalse(avasTrackActive);
        assertTrue(MusicVisualizerRuntime.shouldStartOutput(
                true, true, true, anyMediaActive, false));
        assertFalse(MusicVisualizerRuntime.shouldScheduleStop(true, anyMediaActive, false));
        assertFalse(MusicVisualizerRuntime.shouldStartOutput(
                true, true, true, avasTrackActive, false));
        assertTrue(MusicVisualizerRuntime.shouldScheduleStop(true, avasTrackActive, false));
    }
}
