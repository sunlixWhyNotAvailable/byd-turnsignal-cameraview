package com.byd.extend;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Owned exclusively by the capture thread and released before its AudioRecord. */
final class AvasMicrophoneEffects implements AutoCloseable {
    private static final String TAG = "BydAvasMicrophone";

    private static final class Support {
        static final boolean NS = available(NoiseSuppressor::isAvailable, "NS");
        static final boolean AEC = available(AcousticEchoCanceler::isAvailable, "AEC");
    }

    static boolean supportsNoiseSuppression() { return Support.NS; }
    static boolean supportsEchoCancellation() { return Support.AEC; }

    private static boolean available(BooleanSupplier query, String name) {
        try { return query.getAsBoolean(); }
        catch (RuntimeException failure) {
            Log.w(TAG, name + " availability check failed", failure);
            return false;
        }
    }

    private final Effect noise;
    private final Effect echo;

    AvasMicrophoneEffects(int sessionId, Consumer<Integer> onFailure) {
        noise = new Effect(sessionId, Support.NS, R.string.avas_mic_noise_suppression,
                NoiseSuppressor::create, onFailure);
        echo = new Effect(sessionId, Support.AEC, R.string.avas_mic_echo_cancellation,
                AcousticEchoCanceler::create, onFailure);
    }

    void apply(boolean noiseSuppression, boolean echoCancellation) {
        noise.apply(noiseSuppression);
        echo.apply(echoCancellation);
    }

    @Override public void close() {
        noise.close();
        echo.close();
    }

    private static final class Effect {
        final int sessionId;
        final boolean supported;
        final int label;
        final IntFunction<? extends AudioEffect> create;
        final Consumer<Integer> onFailure;
        Boolean requested;
        AudioEffect effect;

        Effect(int sessionId, boolean supported, int label,
                IntFunction<? extends AudioEffect> create, Consumer<Integer> onFailure) {
            this.sessionId = sessionId;
            this.supported = supported;
            this.label = label;
            this.create = create;
            this.onFailure = onFailure;
        }

        void apply(boolean enabled) {
            if (!supported || (requested != null && requested == enabled)) return;
            requested = enabled; // No retries on every audio block after a native failure.
            try {
                if (effect == null) effect = create.apply(sessionId);
                if (effect == null) throw new IllegalStateException("effect_create_returned_null");
                // Explicit OFF also disables effects inserted by the platform for this source.
                int result = effect.setEnabled(enabled);
                if (result != AudioEffect.SUCCESS || effect.getEnabled() != enabled)
                    throw new IllegalStateException("effect_enable_failed: " + result);
                Log.i(TAG, "Microphone effect type=" + effect.getDescriptor().type
                        + " session=" + sessionId + " enabled=" + enabled);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Microphone effect label=" + label + " session=" + sessionId
                        + " requested=" + enabled + " failed", failure);
                close();
                onFailure.accept(label);
            }
        }

        void close() {
            AudioEffect previous = effect;
            effect = null;
            if (previous != null) {
                try { previous.release(); }
                catch (RuntimeException failure) { Log.w(TAG, "Effect release failed", failure); }
            }
        }
    }
}
