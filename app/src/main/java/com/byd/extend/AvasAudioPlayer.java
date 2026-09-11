package com.byd.extend;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Blocking helper-owned PCM player. The caller serializes play requests on its worker. */
final class AvasAudioPlayer implements AutoCloseable {
    private static final int NAV_STREAM = 15;
    private static final int REQUESTED_ROUTE_FLAGS = 0x20000;
    private static final int NAV_SILENCE_MILLIS = 420;
    private final AudioManager manager;
    private final AvasShellSettings settings;
    private final AvasExteriorRoute route;
    private final AvasNavigationRoute navigationRoute;
    private final Consumer<JSONObject> log;
    private final AtomicLong generation = new AtomicLong();
    private final Object trackLock = new Object();
    private AudioTrack activeTrack;

    AvasAudioPlayer(Context context, Consumer<JSONObject> log) throws Exception {
        this.log = log;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) throw new IllegalStateException("AudioManager unavailable");
        settings = new AvasShellSettings(context);
        route = new AvasExteriorRoute(context, log);
        navigationRoute = new AvasNavigationRoute(manager, log);
        restore(null);
    }

    void play(File wav, int volume, BooleanSupplier cancelled, IntSupplier currentVolume) throws Exception {
        AvasWav.Header header = AvasWav.read(wav);
        int initialVolume = clamp(volume);
        long ticket = generation.incrementAndGet();
        AudioFocusRequest focus = null;
        AudioTrack output = null;
        Exception playbackFailure = null;
        long framesWritten = 0;
        try {
            restore(null);
            if (cancelled(ticket, cancelled)) return;
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setLegacyStreamType(NAV_STREAM).setFlags(REQUESTED_ROUTE_FLAGS).build();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setLegacyStreamType(NAV_STREAM).build())
                    .setOnAudioFocusChangeListener(change -> event("avas_focus_change", "value", change),
                            new Handler(Looper.getMainLooper())).build();

            route.naviFocus(true);
            int granted = manager.requestAudioFocus(focus);
            event("avas_focus_request", "result", granted);
            settings.putInt(AvasShellSettings.SAVED_NAV, manager.getStreamVolume(NAV_STREAM));
            settings.putInt(AvasShellSettings.SAVED_MUTE, manager.isStreamMute(NAV_STREAM) ? 1 : 0);
            settings.putInt(AvasShellSettings.DIRTY, AvasShellSettings.EXTERIOR_DIRTY);
            route.mute(true);
            Thread.sleep(80); // Proven exterior NAV preflight, not a UI/startup delay.
            if (cancelled(ticket, cancelled)) return;
            route.prepare();
            manager.requestAudioFocus(focus);
            if (cancelled(ticket, cancelled)) return;

            int channelMask = header.channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minimum = AudioTrack.getMinBufferSize(
                    header.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Invalid AudioTrack buffer " + minimum);
            int bufferBytes = align(Math.max(minimum, header.frameSize * 256), header.frameSize);
            output = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(header.sampleRate)
                            .setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build();
            if (output.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack not initialized");
            }
            synchronized (trackLock) {
                if (ticket != generation.get()) return;
                activeTrack = output;
            }
            output.setVolume(1f);
            output.play();
            manager.setStreamVolume(NAV_STREAM, 1, 0);
            route.mute(false);
            if (manager.getStreamVolume(NAV_STREAM) != 1) {
                throw new IllegalStateException("Exterior NAV cap was not applied");
            }
            event("avas_play_begin", "file", wav.getName(), "volume", initialVolume,
                    "sampleRate", header.sampleRate, "channels", header.channels,
                    "requestedFlags", "0x20000", "javaFlags", attributes.getFlags(),
                    "attributes", attributes.toString());

            byte[] pcm = new byte[align(Math.max(bufferBytes, 4096), header.frameSize)];
            byte[] scaled = new byte[pcm.length];
            try (FileInputStream input = new FileInputStream(wav)) {
                skipFully(input, header.dataOffset);
                long remaining = header.dataBytes;
                while (remaining > 0 && !cancelled(ticket, cancelled)) {
                    int wanted = (int) Math.min(pcm.length, remaining);
                    readFully(input, pcm, wanted);
                    int written = write(output, pcm, scaled, wanted, header.frameSize, ticket,
                            cancelled, currentVolume, initialVolume, true);
                    framesWritten += written / header.frameSize;
                    remaining -= written;
                    if (written < wanted) break;
                }
            }
            drain(output, framesWritten, ticket, cancelled);
            event("avas_play_end", "interrupted", cancelled(ticket, cancelled),
                    "framesWritten", framesWritten,
                    "playbackHead", Integer.toUnsignedLong(output.getPlaybackHeadPosition()));
        } catch (Exception failure) {
            playbackFailure = failure;
            throw failure;
        } finally {
            synchronized (trackLock) {
                if (activeTrack == output) activeTrack = null;
            }
            release(output);
            try {
                restore(focus);
            } catch (Exception cleanupFailure) {
                if (playbackFailure != null) playbackFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Plays one file through the proven OEM in-cabin NAV route. */
    void playNavigation(File wav, int volume, BooleanSupplier cancelled,
            IntSupplier currentVolume) throws Exception {
        AvasWav.Header header = AvasWav.read(wav);
        int initialVolume = clamp(volume);
        long ticket = generation.incrementAndGet();
        AudioFocusRequest focus = null;
        AudioTrack output = null;
        Exception playbackFailure = null;
        long framesWritten = 0;
        try {
            restore(null);
            if (cancelled(ticket, cancelled)) return;
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setLegacyStreamType(NAV_STREAM).setFlags(REQUESTED_ROUTE_FLAGS).build();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change -> event("avas_focus_change", "value", change),
                            new Handler(Looper.getMainLooper())).build();
            int granted = manager.requestAudioFocus(focus);
            event("avas_focus_request", "result", granted, "route", "navigation");
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw new IllegalStateException("Navigation audio focus denied");
            }
            int previous = manager.getStreamVolume(NAV_STREAM);
            int maximum = manager.getStreamMaxVolume(NAV_STREAM);
            if (maximum <= 0) throw new IllegalStateException("No NAV stream on this firmware");
            settings.putInt(AvasShellSettings.SAVED_NAV, previous);
            settings.putInt(AvasShellSettings.DIRTY, AvasShellSettings.NAVIGATION_DIRTY);
            manager.setStreamVolume(NAV_STREAM, maximum, 0);
            navigationRoute.prepare();
            if (cancelled(ticket, cancelled)) return;

            int channelMask = header.channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minimum = AudioTrack.getMinBufferSize(
                    header.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Invalid AudioTrack buffer " + minimum);
            int bufferBytes = align(Math.max(minimum, header.frameSize * 256), header.frameSize);
            output = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(header.sampleRate)
                            .setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build();
            if (output.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack not initialized");
            }
            synchronized (trackLock) {
                if (ticket != generation.get()) return;
                activeTrack = output;
            }
            output.setVolume(1f);
            output.play();
            event("avas_play_begin", "file", wav.getName(), "volume", initialVolume,
                    "route", "navigation", "savedNav", previous, "navMax", maximum,
                    "sampleRate", header.sampleRate, "channels", header.channels,
                    "requestedFlags", "0x20000", "javaFlags", attributes.getFlags());

            long silenceWritten = writeSilence(output, bufferBytes, header, ticket, cancelled,
                    currentVolume, initialVolume);
            framesWritten += silenceWritten / header.frameSize;

            byte[] pcm = new byte[align(Math.max(bufferBytes, 4096), header.frameSize)];
            byte[] scaled = new byte[pcm.length];
            try (FileInputStream input = new FileInputStream(wav)) {
                skipFully(input, header.dataOffset);
                long remaining = header.dataBytes;
                while (remaining > 0 && !cancelled(ticket, cancelled)) {
                    int wanted = (int) Math.min(pcm.length, remaining);
                    readFully(input, pcm, wanted);
                    int written = write(output, pcm, scaled, wanted, header.frameSize, ticket,
                            cancelled, currentVolume, initialVolume, false);
                    framesWritten += written / header.frameSize;
                    remaining -= written;
                    if (written < wanted) break;
                }
            }
            drain(output, framesWritten, ticket, cancelled);
            event("avas_play_end", "route", "navigation",
                    "interrupted", cancelled(ticket, cancelled), "framesWritten", framesWritten,
                    "playbackHead", Integer.toUnsignedLong(output.getPlaybackHeadPosition()));
        } catch (Exception failure) {
            playbackFailure = failure;
            throw failure;
        } finally {
            synchronized (trackLock) {
                if (activeTrack == output) activeTrack = null;
            }
            release(output);
            try {
                restore(focus);
            } catch (Exception cleanupFailure) {
                if (playbackFailure != null) playbackFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    void stop() {
        generation.incrementAndGet();
        synchronized (trackLock) {
            if (activeTrack != null) {
                try {
                    activeTrack.pause();
                    activeTrack.flush();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private int write(AudioTrack output, byte[] pcm, byte[] scaled, int length, int frameSize,
            long ticket, BooleanSupplier externalCancellation, IntSupplier currentVolume,
            int initialVolume, boolean exterior) throws Exception {
        int offset = 0;
        long lastProgress = SystemClock.elapsedRealtime();
        while (offset < length && !cancelled(ticket, externalCancellation)) {
            int writable = length - offset;
            int volume = currentVolume == null ? initialVolume : clamp(currentVolume.getAsInt());
            if (exterior) AvasWav.scaleExteriorPcm16(pcm, offset, scaled, 0, writable, volume);
            else AvasWav.scalePcm16(pcm, offset, scaled, 0, writable, volume);
            int count = output.write(scaled, 0, writable, AudioTrack.WRITE_NON_BLOCKING);
            if (count < 0) {
                if (cancelled(ticket, externalCancellation)) return offset;
                throw new IllegalStateException("AudioTrack.write=" + count);
            }
            if (count % frameSize != 0) throw new IllegalStateException("AudioTrack split a PCM frame");
            if (count > 0) {
                offset += count;
                lastProgress = SystemClock.elapsedRealtime();
            } else {
                if (SystemClock.elapsedRealtime() - lastProgress > 3000) {
                    throw new IllegalStateException("AudioTrack write stalled");
                }
                Thread.sleep(10);
            }
        }
        return offset;
    }

    private long writeSilence(AudioTrack output, int bufferBytes, AvasWav.Header header,
            long ticket, BooleanSupplier externalCancellation, IntSupplier currentVolume,
            int initialVolume) throws Exception {
        long remaining = (long) header.sampleRate * header.frameSize * NAV_SILENCE_MILLIS / 1000;
        remaining -= remaining % header.frameSize;
        byte[] silence = new byte[align(Math.max(bufferBytes, 4096), header.frameSize)];
        byte[] scaled = new byte[silence.length];
        long written = 0;
        while (remaining > 0 && !cancelled(ticket, externalCancellation)) {
            int wanted = (int) Math.min(silence.length, remaining);
            int count = write(output, silence, scaled, wanted, header.frameSize, ticket,
                    externalCancellation, currentVolume, initialVolume, false);
            written += count;
            remaining -= count;
            if (count < wanted) break;
        }
        return written;
    }

    private void drain(AudioTrack output, long framesWritten, long ticket,
            BooleanSupplier externalCancellation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (!cancelled(ticket, externalCancellation)
                && Integer.toUnsignedLong(output.getPlaybackHeadPosition()) < framesWritten) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new IllegalStateException("AudioTrack drain timeout");
            }
            Thread.sleep(10);
        }
    }

    private void restore(AudioFocusRequest focus) throws Exception {
        Exception failure = null;
        boolean interrupted = Thread.interrupted();
        int dirty = 0;
        try {
            dirty = settings.getInt(AvasShellSettings.DIRTY, 0);
        } catch (Exception settingsFailure) {
            failure = settingsFailure;
        }
        if (dirty == AvasShellSettings.EXTERIOR_DIRTY) {
            try {
                route.release(focus);
            } catch (Exception routeFailure) {
                failure = routeFailure;
            }
        } else if (dirty == AvasShellSettings.NAVIGATION_DIRTY) {
            try {
                navigationRoute.release(focus);
            } catch (Exception routeFailure) {
                failure = routeFailure;
            }
        } else if (dirty != AvasShellSettings.CLEAN) {
            failure = new IllegalStateException("Unknown AVAS route marker " + dirty);
        } else if (focus != null) {
            try {
                manager.abandonAudioFocusRequest(focus);
            } catch (Exception focusFailure) {
                failure = combine(failure, focusFailure);
            }
        }
        interrupted |= Thread.interrupted();
        try {
            int savedNav = settings.getInt(AvasShellSettings.SAVED_NAV, -1);
            if (savedNav >= 0) {
                manager.setStreamVolume(NAV_STREAM, savedNav, 0);
                settings.putInt(AvasShellSettings.SAVED_NAV, -1);
                event("avas_nav_restored", "volume", savedNav,
                        "actual", manager.getStreamVolume(NAV_STREAM));
            }
        } catch (Exception navFailure) {
            failure = combine(failure, navFailure);
        }
        try {
            int savedMute = settings.getInt(AvasShellSettings.SAVED_MUTE, -1);
            if (savedMute >= 0) {
                route.mute(savedMute == 1);
                settings.putInt(AvasShellSettings.SAVED_MUTE, -1);
            }
        } catch (Exception muteFailure) {
            failure = combine(failure, muteFailure);
        }
        if (failure == null) {
            try {
                settings.putInt(AvasShellSettings.DIRTY, AvasShellSettings.CLEAN);
            } catch (Exception markerFailure) {
                failure = markerFailure;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }

    private static Exception combine(Exception first, Exception next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private boolean cancelled(long ticket, BooleanSupplier external) {
        return ticket != generation.get() || external != null && external.getAsBoolean();
    }

    private static int clamp(int volume) {
        return Math.max(0, Math.min(100, volume));
    }

    private static int align(int value, int frameSize) {
        return value - value % frameSize;
    }

    private static void skipFully(FileInputStream input, long bytes) throws Exception {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) throw new IllegalStateException("Unexpected end of WAV header");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void readFully(FileInputStream input, byte[] target, int length) throws Exception {
        int offset = 0;
        while (offset < length) {
            int count = input.read(target, offset, length - offset);
            if (count < 0) throw new IllegalStateException("Unexpected end of WAV PCM");
            if (count > 0) offset += count;
        }
    }

    private static void release(AudioTrack output) {
        if (output == null) return;
        try {
            output.pause();
            output.flush();
        } catch (Exception ignored) {
        }
        try {
            output.release();
        } catch (Exception ignored) {
        }
    }

    private void event(String kind, Object... fields) {
        if (log == null) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind);
            for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
            log.accept(event);
        } catch (Throwable ignored) {
        }
    }
}
