package com.byd.extend;

import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Compensates only our track against the real NAV curve; never changes shared stream volume. */
final class AvasExteriorVolume implements AutoCloseable {
    private static final int NAV_STREAM = 15;
    private final AudioManager manager;
    private final AudioTrack track;
    private final Runnable changed;
    private final Consumer<String> diagnostic;
    private final Method devices;
    private final Method curve;
    private volatile float attenuation;
    private volatile boolean closed;
    private boolean monitoring;
    private String lastError = "";
    private final AudioRouting.OnRoutingChangedListener routing = ignored -> refresh();

    AvasExteriorVolume(AudioManager manager, AudioTrack track,
            Runnable changed, Consumer<String> diagnostic) throws Exception {
        this.manager = manager;
        this.track = track;
        this.changed = changed;
        this.diagnostic = diagnostic;
        Class<?> audioSystem = Class.forName("android.media.AudioSystem");
        devices = audioSystem.getMethod("getDevicesForStream", int.class);
        curve = audioSystem.getMethod("getStreamVolumeDB", int.class, int.class, int.class);
    }

    void start() {
        try {
            track.addOnRoutingChangedListener(routing, new Handler(Looper.getMainLooper()));
            monitoring = true;
            refresh();
        } catch (Exception failure) {
            attenuation = 0;
            report(failure.toString());
            changed.run();
        }
    }

    float attenuation() { return attenuation; }

    // Volume/mute broadcasts arrive through the real app; routing callbacks are native to the track.
    void refresh() {
        if (closed || !monitoring) return;
        try {
            int index = manager.getStreamVolume(NAV_STREAM);
            if (index == 0 || manager.isStreamMute(NAV_STREAM)) {
                attenuation = 0;
            } else {
                int device = ((Number) devices.invoke(null, NAV_STREAM)).intValue();
                if (device == 0 || Integer.bitCount(device) != 1) {
                    throw new IllegalStateException("Ambiguous NAV output device: " + device);
                }
                float referenceDb = ((Number) curve.invoke(null, NAV_STREAM, 1, device)).floatValue();
                float actualDb = ((Number) curve.invoke(null, NAV_STREAM, index, device)).floatValue();
                attenuation = compensation(referenceDb, actualDb);
                if (!Float.isFinite(attenuation)) throw new IllegalStateException("NAV curve unavailable");
            }
            report("");
        } catch (Exception failure) {
            attenuation = 0;
            report(failure.toString());
        }
        changed.run();
    }

    static float compensation(float referenceDb, float actualDb) {
        if (!Float.isFinite(referenceDb) || !Float.isFinite(actualDb)) return Float.NaN;
        return (float) Math.min(1.0, Math.pow(10.0, (referenceDb - actualDb) / 20.0));
    }

    private void report(String error) {
        if (error.equals(lastError)) return;
        lastError = error;
        diagnostic.accept(error);
    }

    @Override public void close() {
        closed = true;
        try { track.removeOnRoutingChangedListener(routing); } catch (RuntimeException ignored) { }
    }
}
