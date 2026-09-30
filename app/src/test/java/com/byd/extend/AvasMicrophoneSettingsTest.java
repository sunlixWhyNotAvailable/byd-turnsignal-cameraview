package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class AvasMicrophoneSettingsTest {
    @Test public void processingDefaultsDoNotEnableSpeechOrRewriteExistingSettings() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putInt(AvasMicrophoneSettings.VOLUME, 73).apply();
        int writes = preferences.transactions;
        assertTrue(AvasMicrophoneSettings.noiseSuppression(preferences));
        assertTrue(AvasMicrophoneSettings.echoCancellation(preferences));
        assertFalse(AvasMicrophoneSettings.enabled(preferences));
        assertEquals(73, AvasMicrophoneSettings.volume(preferences));
        assertFalse(preferences.contains(AvasMicrophoneSettings.NOISE_SUPPRESSION));
        assertFalse(preferences.contains(AvasMicrophoneSettings.ECHO_CANCELLATION));
        assertEquals(writes, preferences.transactions);
    }

    @Test public void independentProcessingChoicesSurviveDisablingAndReenablingSpeech() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putBoolean(AvasMicrophoneSettings.NOISE_SUPPRESSION, false)
                .putBoolean(AvasMicrophoneSettings.ECHO_CANCELLATION, true).apply();
        for (boolean speechEnabled : new boolean[]{true, false, true}) {
            preferences.edit().putBoolean(AvasMicrophoneSettings.ENABLED, speechEnabled).apply();
            assertFalse(AvasMicrophoneSettings.noiseSuppression(preferences));
            assertTrue(AvasMicrophoneSettings.echoCancellation(preferences));
        }
        preferences.edit().putBoolean(AvasMicrophoneSettings.NOISE_SUPPRESSION, true)
                .putBoolean(AvasMicrophoneSettings.ECHO_CANCELLATION, false).apply();
        assertTrue(AvasMicrophoneSettings.noiseSuppression(preferences));
        assertFalse(AvasMicrophoneSettings.echoCancellation(preferences));
    }
}
