package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AvasPlaybackPlanTest {
    @Test public void exteriorVolumeMapsToPlayerAndLoudnessEnhancer() {
        int[] volumes = {0, 25, 50, 60, 80, 100};
        float[] playerVolumes = {0f, 0.25f, 0.5f, 0.6f, 0.8f, 1f};
        int[] gainsMb = {0, 700, 1400, 1680, 2240, 2800};
        for (int i = 0; i < volumes.length; i++) {
            assertEquals(playerVolumes[i], AvasPlaybackPlan.exteriorPlayerVolume(volumes[i]), 0f);
            assertEquals(gainsMb[i], AvasPlaybackPlan.exteriorTargetGainMb(volumes[i]));
        }
        assertEquals(0f, AvasPlaybackPlan.exteriorPlayerVolume(-1), 0f);
        assertEquals(0, AvasPlaybackPlan.exteriorTargetGainMb(-1));
        assertEquals(1f, AvasPlaybackPlan.exteriorPlayerVolume(101), 0f);
        assertEquals(2800, AvasPlaybackPlan.exteriorTargetGainMb(101));
    }

    @Test public void automaticPowerOnAndNavigationSilenceAreDisabled() {
        for (int request = 0; request < 3; request++) {
            assertEquals(0, AvasPlaybackPlan.silenceMillis(
                    AvasPlaybackQueue.Kind.AUTOMATIC_EXTERIOR, "power_on"));
        }
        assertEquals(0, AvasPlaybackPlan.silenceMillis(
                AvasPlaybackQueue.Kind.MANUAL_EXTERIOR, "power_on"));
        assertEquals(0, AvasPlaybackPlan.silenceMillis(
                AvasPlaybackQueue.Kind.AUTOMATIC_EXTERIOR, "power_off"));
        assertEquals(0, AvasPlaybackPlan.silenceMillis(
                AvasPlaybackQueue.Kind.AUTOMATIC_EXTERIOR, "lock"));
        assertEquals(0, AvasPlaybackPlan.silenceMillis(
                AvasPlaybackQueue.Kind.AUDITION_NAV, "power_on"));
    }

    @Test public void monoAndStereoSilenceUsesWavRateAndWholeFrames() {
        assertEquals(24_000, AvasPlaybackPlan.silenceFrames(48_000, 500));
        assertEquals(48_000, AvasPlaybackPlan.silenceBytes(48_000, 2, 500));
        assertEquals(96_000, AvasPlaybackPlan.silenceBytes(48_000, 4, 500));
        assertEquals(18_522, AvasPlaybackPlan.silenceFrames(44_100, 420));
        assertEquals(74_088, AvasPlaybackPlan.silenceBytes(44_100, 4, 420));
    }

    @Test public void cancellationOrPartialZerosPreventFileSubmission() {
        assertFalse(AvasPlaybackPlan.maySubmitFile(24_000, 12_000, true));
        assertFalse(AvasPlaybackPlan.maySubmitFile(24_000, 12_000, false));
        assertFalse(AvasPlaybackPlan.maySubmitFile(24_000, 24_000, true));
        assertTrue(AvasPlaybackPlan.maySubmitFile(24_000, 24_000, false));
        assertTrue(AvasPlaybackPlan.maySubmitFile(0, 0, false));
    }

    @Test public void productionSilenceWriterStopsOnCancellationAndUsesZeroPcm() throws Exception {
        byte[] zeroPcm = AvasPlaybackPlan.zeroPcm(16);
        for (byte value : zeroPcm) assertEquals(0, value);

        boolean[] cancelled = {false};
        int[] calls = {0};
        long written = AvasPlaybackPlan.writeSilence(12, 4, () -> cancelled[0], frames -> {
            calls[0]++;
            cancelled[0] = true;
            return frames;
        });
        assertEquals(4, written);
        assertEquals(1, calls[0]);
        assertFalse(AvasPlaybackPlan.maySubmitFile(12, written, cancelled[0]));

        long partial = AvasPlaybackPlan.writeSilence(12, 4, () -> false, frames -> 2);
        assertEquals(2, partial);
        assertFalse(AvasPlaybackPlan.maySubmitFile(12, partial, false));
    }
}
