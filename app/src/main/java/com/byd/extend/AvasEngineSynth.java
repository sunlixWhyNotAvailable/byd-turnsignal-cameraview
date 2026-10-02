package com.byd.extend;

import java.util.Arrays;

/** Allocation-free 48 kHz mono loop mixer. Confine each instance to its audio thread. */
public final class AvasEngineSynth {
    // Per-sample smoothing: 25 ms RPM, 45 ms load attack, and 90 ms overrun release.
    private static final double RPM_ALPHA = 1.0 - Math.exp(-1.0 / (AvasEnginePack.SAMPLE_RATE * 0.025));
    private static final double LOAD_ATTACK_ALPHA =
            1.0 - Math.exp(-1.0 / (AvasEnginePack.SAMPLE_RATE * 0.045));
    private static final double LOAD_RELEASE_ALPHA =
            1.0 - Math.exp(-1.0 / (AvasEnginePack.SAMPLE_RATE * 0.090));

    private final AvasEnginePack pack;
    private final double[] onPosition;
    private final double[] offPosition;
    private final boolean syntheticIdleBand;
    private double idlePosition;
    private double currentRpm;
    private double currentLoad;

    public AvasEngineSynth(AvasEnginePack pack) {
        if (pack == null) throw new IllegalArgumentException("engine pack is required");
        this.pack = pack;
        onPosition = new double[pack.layers.length];
        offPosition = new double[pack.layers.length];
        syntheticIdleBand = pack.layers[0].rpm > pack.idleRpm;
        currentRpm = pack.idleRpm;
    }

    /** Replaces {@code frames} samples in {@code output}; the buffer must be 48 kHz mono. */
    public void render(float[] output, int offset, int frames, float rpm, float load) {
        if (output == null || offset < 0 || frames < 0 || offset > output.length - frames) {
            throw new IllegalArgumentException("invalid engine output range");
        }
        double targetRpm = Float.isFinite(rpm)
                ? Math.max(pack.idleRpm, Math.min(pack.maxRpm, rpm)) : pack.idleRpm;
        double targetLoad = Float.isFinite(load) ? Math.max(0.0, Math.min(1.0, load)) : 0.0;
        int bandCount = pack.layers.length + (syntheticIdleBand ? 1 : 0);
        for (int frame = 0; frame < frames; frame++) {
            currentRpm += (targetRpm - currentRpm) * RPM_ALPHA;
            double loadAlpha = targetLoad > currentLoad ? LOAD_ATTACK_ALPHA : LOAD_RELEASE_ALPHA;
            currentLoad += (targetLoad - currentLoad) * loadAlpha;

            int lower = 0;
            while (lower + 1 < bandCount && currentRpm > bandRpm(lower + 1)) lower++;
            int upper = Math.min(lower + 1, bandCount - 1);
            double lowerRpm = bandRpm(lower);
            double upperRpm = bandRpm(upper);
            double upperWeight = upper == lower || upperRpm <= lowerRpm
                    ? 0.0 : (currentRpm - lowerRpm) / (upperRpm - lowerRpm);
            upperWeight = Math.max(0.0, Math.min(1.0, upperWeight));

            double lowerSample = bandSample(lower, currentLoad);
            double mixed = upper == lower ? lowerSample
                    : lowerSample * (1.0 - upperWeight)
                            + bandSample(upper, currentLoad) * upperWeight;
            output[offset + frame] = limit(mixed);
            advance(currentRpm);
        }
    }

    /** Preserve the whole cue while bringing in the same phase-continuous running mixer. */
    void renderStart(float[] output, int frames, int cueFrame, float rpm, float load) {
        if (pack.startBlendFull == 0) {
            System.arraycopy(pack.start, cueFrame, output, 0, frames);
            return;
        }
        render(output, 0, frames, rpm, load);
        for (int index = 0; index < frames; index++) {
            float weight = Math.max(0f, Math.min(1f, (cueFrame + index - pack.startBlendFrom)
                    / (float) (pack.startBlendFull - pack.startBlendFrom)));
            weight = weight * weight * (3f - 2f * weight);
            output[index] = limit(pack.start[cueFrame + index] + output[index] * weight);
        }
    }

    public void reset() {
        Arrays.fill(onPosition, 0.0);
        Arrays.fill(offPosition, 0.0);
        idlePosition = 0.0;
        currentRpm = pack.idleRpm;
        currentLoad = 0.0;
    }

    private double bandRpm(int band) {
        if (syntheticIdleBand && band == 0) return pack.idleRpm;
        return pack.layers[band - (syntheticIdleBand ? 1 : 0)].rpm;
    }

    private double bandSample(int band, double load) {
        if (syntheticIdleBand && band == 0) {
            return sampleAt(pack.idle, idlePosition);
        }
        int layerIndex = band - (syntheticIdleBand ? 1 : 0);
        AvasEnginePack.Layer layer = pack.layers[layerIndex];
        double off = sampleAt(layer.off, offPosition[layerIndex]);
        double on = sampleAt(layer.on, onPosition[layerIndex]);
        return off * (1.0 - load) + on * load;
    }

    private double sampleAt(float[] samples, double position) {
        int first = (int) position;
        int second = first + 1 == samples.length ? 0 : first + 1;
        double fraction = position - first;
        return samples[first] + (samples[second] - samples[first]) * fraction;
    }

    private void advance(double rpm) {
        if (syntheticIdleBand) {
            idlePosition = wrap(idlePosition + loopStep(rpm, pack.idleRpm), pack.idle.length);
        }
        for (int index = 0; index < pack.layers.length; index++) {
            double step = loopStep(rpm, pack.layers[index].rpm);
            onPosition[index] = wrap(onPosition[index] + step, pack.layers[index].on.length);
            offPosition[index] = wrap(offPosition[index] + step, pack.layers[index].off.length);
        }
    }

    private static double loopStep(double rpm, int recordedRpm) {
        return Math.max(0.5, Math.min(2.0, rpm / recordedRpm));
    }

    private static double wrap(double position, int length) {
        return position >= length ? position % length : position;
    }

    private static float limit(double sample) {
        if (!Double.isFinite(sample)) return 0.0f;
        return (float) Math.max(-1.0, Math.min(1.0, sample));
    }
}
