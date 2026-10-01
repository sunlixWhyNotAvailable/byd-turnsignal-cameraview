package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioAttributes;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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

    @Test
    public void sourceTagsExcludeEventMicAndDefaultOffEngineButKeepRealMusic() {
        assertTrue(MusicPlaybackSource.isEligibleTags(Collections.emptySet(), false));
        assertFalse(MusicPlaybackSource.isEligibleTags(
                Collections.singleton(MusicPlaybackSource.ENGINE), false));
        assertTrue(MusicPlaybackSource.isEligibleTags(
                Collections.singleton(MusicPlaybackSource.ENGINE), true));
        assertFalse(MusicPlaybackSource.isEligibleTags(
                Collections.singleton(MusicPlaybackSource.EVENT), true));
        assertFalse(MusicPlaybackSource.isEligibleTags(
                Collections.singleton(MusicPlaybackSource.MICROPHONE), true));

        Set<String> mixedTags = new HashSet<>();
        mixedTags.add(MusicPlaybackSource.ENGINE);
        boolean ordinaryMusic = MusicVisualizerRuntime.isMediaPlayback(true,
                AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN)
                && MusicPlaybackSource.isEligibleTags(Collections.emptySet(), false);
        boolean engineTrack = MusicVisualizerRuntime.isMediaPlayback(true,
                AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN)
                && MusicPlaybackSource.isEligibleTags(mixedTags, false);
        assertTrue(ordinaryMusic);
        assertFalse(engineTrack);
        assertTrue(ordinaryMusic || engineTrack);
    }
}
