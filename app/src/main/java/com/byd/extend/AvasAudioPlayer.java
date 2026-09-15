package com.byd.extend;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
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
import java.util.function.IntConsumer;

/** Blocking helper-owned PCM player. The caller serializes play requests on its worker. */
final class AvasAudioPlayer implements AutoCloseable {
    private static final int NAV_STREAM = 15;
    private static final int REQUESTED_ROUTE_FLAGS = 0x20000;
    private final AudioManager manager;
    private final AvasShellSettings settings;
    private final AvasExteriorRoute route;
    private final AvasNavigationRoute navigationRoute;
    private final Consumer<JSONObject> log;
    private final AtomicLong generation = new AtomicLong();
    private final Object trackLock = new Object();
    private AudioTrack activeTrack;
    private AvasFocusMaintainer activeFocusMaintainer;

    AvasAudioPlayer(Context context, Consumer<JSONObject> log) throws Exception {
        this.log = log;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) throw new IllegalStateException("AudioManager unavailable");
        settings = new AvasShellSettings(context);
        route = new AvasExteriorRoute(context, log);
        navigationRoute = new AvasNavigationRoute(manager, log);
        try {
            restore(null);
        } catch (Exception failure) {
            try {
                settings.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void play(File wav, int volume, BooleanSupplier cancelled,
            AvasAudioDiagnostics.Context diagnostics, AvasPlaybackQueue.Kind kind,
            IntSupplier currentVolume) throws Exception {
        AvasWav.Header header = AvasWav.read(wav);
        long silenceFrames = AvasPlaybackPlan.silenceFrames(header.sampleRate,
                AvasPlaybackPlan.silenceMillis(kind));
        int silenceBytes = Math.toIntExact(silenceFrames * header.frameSize);
        int initialVolume = clamp(volume);
        long ticket = generation.incrementAndGet();
        AudioFocusRequest focus = null;
        AvasFocusMaintainer focusMaintainer = null;
        AudioTrack output = null;
        ExteriorGain exteriorGain = null;
        Exception playbackFailure = null;
        long framesWritten = 0;
        long fileFrames = 0;
        SessionDiagnostics session = new SessionDiagnostics(diagnostics);
        try {
            restore(null);
            if (cancelled(ticket, cancelled)) return;
            event(diagnostics, "avas_audio_preparation", "preparation_t_ms",
                    SystemClock.elapsedRealtime(), "silence_planned_frames", silenceFrames);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setLegacyStreamType(NAV_STREAM).setFlags(REQUESTED_ROUTE_FLAGS).build();
            IntConsumer focusCallback = AvasAudioDiagnostics.bind(diagnostics,
                    (captured, change) -> event(captured, "avas_focus_change", "value", change));
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setLegacyStreamType(NAV_STREAM).build())
                    .setOnAudioFocusChangeListener(focusCallback::accept,
                            new Handler(Looper.getMainLooper())).build();

            settings.putInt(AvasShellSettings.DIRTY, AvasShellSettings.EXTERIOR_UNACQUIRED);
            int savedVolume = manager.getStreamVolume(NAV_STREAM);
            int savedMute = manager.isStreamMute(NAV_STREAM) ? 1 : 0;
            settings.putInt(AvasShellSettings.SAVED_NAV, savedVolume);
            settings.putInt(AvasShellSettings.SAVED_MUTE, savedMute);
            event(diagnostics, "avas_mute_volume", "phase", "saved", "volume", savedVolume,
                    "muted", savedMute == 1);
            route.mute(true, diagnostics);
            route.naviFocus(true, diagnostics);
            int granted = manager.requestAudioFocus(focus);
            event(diagnostics, "avas_focus_request", "result", granted);
            Thread.sleep(80); // Proven exterior NAV preflight, not a UI/startup delay.
            if (cancelled(ticket, cancelled)) return;
            route.prepare(focus, diagnostics, dirty -> settings.putInt(AvasShellSettings.DIRTY, dirty));
            if (cancelled(ticket, cancelled)) return;

            int channelMask = header.channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int fileBytes = Math.toIntExact(header.dataBytes);
            int bufferBytes = Math.addExact(silenceBytes, fileBytes);
            output = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(header.sampleRate)
                            .setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(bufferBytes).build();
            if (output.getState() == AudioTrack.STATE_UNINITIALIZED) {
                throw new IllegalStateException("AudioTrack not initialized");
            }
            event(diagnostics, "avas_track_state", "phase", "created", "state",
                    safeTrackState(output), "play_state", safePlayState(output),
                    "buffer_bytes", bufferBytes, "transfer_mode", "static",
                    "session_id", output.getAudioSessionId());
            synchronized (trackLock) {
                if (ticket != generation.get()) return;
                activeTrack = output;
                AudioFocusRequest maintainedFocus = focus;
                focusMaintainer = new AvasFocusMaintainer(
                        () -> manager.requestAudioFocus(maintainedFocus),
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                        report -> focusReport(diagnostics, report));
                activeFocusMaintainer = focusMaintainer;
            }
            session.bind(output);
            // STATIC writes start at offset zero: preload silence + the entire file in one write.
            {
                byte[] pcm = AvasWav.readPcm(wav, header, silenceBytes);
                if (cancelled(ticket, cancelled)) return;
                int written = output.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                if (cancelled(ticket, cancelled)) return;
                if (written != pcm.length || output.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IllegalStateException("Static PCM preload=" + written
                            + "/" + pcm.length + " state=" + output.getState());
                }
                framesWritten = written / header.frameSize;
                fileFrames = fileBytes / header.frameSize;
                session.exteriorPcm(pcm, silenceBytes, fileBytes);
                session.staticPreload(output, silenceFrames, fileFrames);
            }
            event(diagnostics, "avas_track_state", "phase", "preloaded", "state",
                    safeTrackState(output), "transfer_mode", "static", "file_frames", fileFrames,
                    "silence_frames", silenceFrames, "total_frames", framesWritten);
            exteriorGain = new ExteriorGain(output, currentVolume, initialVolume, diagnostics);
            exteriorGain.prepare();
            long playCalledMs, playReturnedMs, capCalledMs, capReturnedMs,
                    unmuteCalledMs, unmuteReturnedMs;
            synchronized (trackLock) {
                if (cancelled(ticket, cancelled)) return;
                playCalledMs = SystemClock.elapsedRealtime();
                output.play();
                playReturnedMs = SystemClock.elapsedRealtime();
                focusMaintainer.start();
                capCalledMs = SystemClock.elapsedRealtime();
                manager.setStreamVolume(NAV_STREAM, 1, 0);
                capReturnedMs = SystemClock.elapsedRealtime();
                unmuteCalledMs = SystemClock.elapsedRealtime();
                route.mute(false, diagnostics);
                unmuteReturnedMs = SystemClock.elapsedRealtime();
                if (manager.getStreamVolume(NAV_STREAM) != 1) {
                    throw new IllegalStateException("Exterior NAV cap was not applied");
                }
            }
            // Emit after unmute; diagnostic I/O must not delay these critical calls.
            event(diagnostics, "avas_play_call_timing", "play_called_ms", playCalledMs,
                    "play_returned_ms", playReturnedMs, "nav_cap_called_ms", capCalledMs,
                    "nav_cap_returned_ms", capReturnedMs, "unmute_called_ms", unmuteCalledMs,
                    "unmute_returned_ms", unmuteReturnedMs);
            event(diagnostics, "avas_track_state", "phase", "played", "state",
                    safeTrackState(output), "play_state", safePlayState(output));
            event(diagnostics, "avas_mute_volume", "phase", "exterior_playback",
                    "saved_volume", savedVolume, "requested_volume", 1,
                    "actual_volume", safeStreamVolume(), "requested_muted", false,
                    "muted", safeStreamMute());
            event(diagnostics, "avas_play_begin", "file", wav.getName(), "volume", initialVolume,
                    "sampleRate", header.sampleRate, "channels", header.channels,
                    "requestedFlags", "0x20000", "javaFlags", attributes.getFlags(),
                    "attributes", attributes.toString());

            // Unlike STREAM's final buffered tail, STATIC still has the whole clip to play.
            long playbackMillis = (framesWritten * 1000 + header.sampleRate - 1) / header.sampleRate;
            drain(output, framesWritten, ticket, cancelled, session, exteriorGain, playbackMillis + 3000);
            event(diagnostics, "avas_play_end", "interrupted", cancelled(ticket, cancelled),
                    "framesWritten", framesWritten, "silenceFrames", silenceFrames,
                    "fileFrames", fileFrames,
                    "playbackHead", safePlaybackHead(output));
        } catch (Exception failure) {
            playbackFailure = failure;
            throw failure;
        } finally {
            stopFocusMaintainer(focusMaintainer);
            synchronized (trackLock) {
                if (activeTrack == output) activeTrack = null;
            }
            session.finalSample(output);
            session.unbind(output);
            if (exteriorGain != null) exteriorGain.close();
            release(output, true);
            try {
                restore(focus, diagnostics);
            } catch (Exception cleanupFailure) {
                if (playbackFailure != null) playbackFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Plays one file through the proven OEM in-cabin NAV route. */
    void playNavigation(File wav, int volume, BooleanSupplier cancelled,
            AvasAudioDiagnostics.Context diagnostics,
            IntSupplier currentVolume) throws Exception {
        AvasWav.Header header = AvasWav.read(wav);
        int initialVolume = clamp(volume);
        long ticket = generation.incrementAndGet();
        AudioFocusRequest focus = null;
        AudioTrack output = null;
        Exception playbackFailure = null;
        long framesWritten = 0;
        long fileFrames = 0;
        SessionDiagnostics session = new SessionDiagnostics(diagnostics);
        try {
            restore(null);
            if (cancelled(ticket, cancelled)) return;
            event(diagnostics, "avas_audio_preparation", "preparation_t_ms",
                    SystemClock.elapsedRealtime(), "route", "navigation",
                    "silence_planned_frames", 0);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setLegacyStreamType(NAV_STREAM).setFlags(REQUESTED_ROUTE_FLAGS).build();
            IntConsumer focusCallback = AvasAudioDiagnostics.bind(diagnostics,
                    (captured, change) -> event(captured, "avas_focus_change", "value", change));
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusCallback::accept,
                            new Handler(Looper.getMainLooper())).build();
            int granted = manager.requestAudioFocus(focus);
            event(diagnostics, "avas_focus_request", "result", granted, "route", "navigation");
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw new IllegalStateException("Navigation audio focus denied");
            }
            int previous = manager.getStreamVolume(NAV_STREAM);
            int maximum = manager.getStreamMaxVolume(NAV_STREAM);
            if (maximum <= 0) throw new IllegalStateException("No NAV stream on this firmware");
            settings.putInt(AvasShellSettings.SAVED_NAV, previous);
            manager.setStreamVolume(NAV_STREAM, maximum, 0);
            event(diagnostics, "avas_mute_volume", "phase", "navigation", "saved_volume",
                    previous, "requested_volume", maximum,
                    "actual_volume", safeStreamVolume());
            navigationRoute.prepare(dirty -> settings.putInt(AvasShellSettings.DIRTY, dirty));
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
            event(diagnostics, "avas_track_state", "phase", "created", "state",
                    safeTrackState(output), "play_state", safePlayState(output),
                    "buffer_bytes", bufferBytes);
            synchronized (trackLock) {
                if (ticket != generation.get()) return;
                activeTrack = output;
            }
            session.bind(output);
            output.setVolume(1f);
            output.play();
            event(diagnostics, "avas_track_state", "phase", "played", "state",
                    safeTrackState(output), "play_state", safePlayState(output));
            event(diagnostics, "avas_play_begin", "file", wav.getName(), "volume", initialVolume,
                    "route", "navigation", "savedNav", previous, "navMax", maximum,
                    "sampleRate", header.sampleRate, "channels", header.channels,
                    "requestedFlags", "0x20000", "javaFlags", attributes.getFlags());

            byte[] pcm = new byte[align(Math.max(bufferBytes, 4096), header.frameSize)];
            byte[] scaled = new byte[pcm.length];
            try (FileInputStream input = new FileInputStream(wav)) {
                skipFully(input, header.dataOffset);
                long remaining = header.dataBytes;
                while (remaining > 0 && !cancelled(ticket, cancelled)) {
                    int wanted = (int) Math.min(pcm.length, remaining);
                    readFully(input, pcm, wanted);
                    int written = write(output, pcm, scaled, wanted, header.frameSize, ticket,
                            cancelled, currentVolume, initialVolume, null, "wav", session);
                    long writtenFrames = written / header.frameSize;
                    framesWritten += writtenFrames;
                    fileFrames += writtenFrames;
                    remaining -= written;
                    if (written < wanted) break;
                }
            }
            drain(output, framesWritten, ticket, cancelled, session);
            event(diagnostics, "avas_play_end", "route", "navigation",
                    "interrupted", cancelled(ticket, cancelled), "framesWritten", framesWritten,
                    "silenceFrames", 0, "fileFrames", fileFrames,
                    "playbackHead", safePlaybackHead(output));
        } catch (Exception failure) {
            playbackFailure = failure;
            throw failure;
        } finally {
            synchronized (trackLock) {
                if (activeTrack == output) activeTrack = null;
            }
            session.finalSample(output);
            session.unbind(output);
            release(output);
            try {
                restore(focus, diagnostics);
            } catch (Exception cleanupFailure) {
                if (playbackFailure != null) playbackFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    void stop() {
        generation.incrementAndGet();
        AvasFocusMaintainer focusMaintainer;
        synchronized (trackLock) {
            focusMaintainer = activeFocusMaintainer;
            activeFocusMaintainer = null;
        }
        if (focusMaintainer != null) focusMaintainer.close();
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
        try {
            settings.close();
        } catch (Exception failure) {
            event("avas_settings_provider_release_error", "error", failure.toString());
        }
    }

    private int write(AudioTrack output, byte[] pcm, byte[] scaled, int length, int frameSize,
            long ticket, BooleanSupplier externalCancellation, IntSupplier currentVolume,
            int initialVolume, ExteriorGain exteriorGain, String phase, SessionDiagnostics diagnostics)
            throws Exception {
        int offset = 0;
        long lastProgress = SystemClock.elapsedRealtime();
        while (offset < length && !cancelled(ticket, externalCancellation)) {
            int writable = length - offset;
            int count;
            if (exteriorGain != null) {
                exteriorGain.update();
                // Keep original PCM peaks intact: amplification belongs to this track's effect.
                count = output.write(pcm, offset, writable, AudioTrack.WRITE_NON_BLOCKING);
            } else {
                int volume = currentVolume == null ? initialVolume : clamp(currentVolume.getAsInt());
                AvasWav.scalePcm16(pcm, offset, scaled, 0, writable, volume);
                count = output.write(scaled, 0, writable, AudioTrack.WRITE_NON_BLOCKING);
            }
            if (count < 0) {
                if (cancelled(ticket, externalCancellation)) return offset;
                throw new IllegalStateException("AudioTrack.write=" + count);
            }
            if (count % frameSize != 0) throw new IllegalStateException("AudioTrack split a PCM frame");
            if (count > 0) {
                if (exteriorGain != null) diagnostics.exteriorPcm(pcm, offset, count);
                diagnostics.positiveWrite(output, phase, count / frameSize);
                offset += count;
                lastProgress = SystemClock.elapsedRealtime();
            } else {
                diagnostics.initialSample(output);
                if (SystemClock.elapsedRealtime() - lastProgress > 3000) {
                    throw new IllegalStateException("AudioTrack write stalled");
                }
                Thread.sleep(10);
            }
        }
        return offset;
    }

    private void drain(AudioTrack output, long framesWritten, long ticket,
            BooleanSupplier externalCancellation, SessionDiagnostics diagnostics) throws Exception {
        drain(output, framesWritten, ticket, externalCancellation, diagnostics, null, 3000);
    }

    private void drain(AudioTrack output, long framesWritten, long ticket,
            BooleanSupplier externalCancellation, SessionDiagnostics diagnostics,
            ExteriorGain exteriorGain, long timeoutMillis) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (!cancelled(ticket, externalCancellation)
                && Integer.toUnsignedLong(output.getPlaybackHeadPosition()) < framesWritten) {
            if (exteriorGain != null) exteriorGain.update();
            // Sampling is performed by the same playback worker; no timer or idle polling exists.
            diagnostics.initialSample(output);
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new IllegalStateException("AudioTrack drain timeout");
            }
            Thread.sleep(10);
        }
    }

    private void restore(AudioFocusRequest focus) throws Exception {
        restore(focus, null);
    }

    private void stopFocusMaintainer(AvasFocusMaintainer owned) {
        if (owned == null) return;
        synchronized (trackLock) {
            if (activeFocusMaintainer == owned) activeFocusMaintainer = null;
        }
        owned.close();
    }

    private void focusReport(AvasAudioDiagnostics.Context diagnostics,
            AvasFocusMaintainer.Report report) {
        event(diagnostics, "avas_focus_maintenance", "phase", report.phase,
                "result", report.result, "successes", report.successes,
                "failures", report.failures, "error", report.error);
    }

    private void restore(AudioFocusRequest focus, AvasAudioDiagnostics.Context diagnostics)
            throws Exception {
        Exception failure = null;
        boolean interrupted = Thread.interrupted();
        int dirty = 0;
        try {
            dirty = settings.getInt(AvasShellSettings.DIRTY, 0);
        } catch (Exception settingsFailure) {
            failure = settingsFailure;
        }
        if (dirty == AvasShellSettings.EXTERIOR_DIRTY
                || dirty == AvasShellSettings.EXTERIOR_CHANNEL0_DIRTY
                || dirty == AvasShellSettings.EXTERIOR_DEVICE3_DIRTY
                || dirty == AvasShellSettings.EXTERIOR_UNACQUIRED
                || dirty == AvasShellSettings.EXTERIOR_SHARED) {
            try {
                route.release(focus, diagnostics, dirty);
            } catch (Exception routeFailure) {
                failure = routeFailure;
            }
        } else if (AvasNavigationRecovery.requiresRelease(dirty)) {
            try {
                navigationRoute.release(focus, dirty,
                        next -> settings.putInt(AvasShellSettings.DIRTY, next));
            } catch (Exception routeFailure) {
                failure = routeFailure;
            }
        } else if (dirty != AvasShellSettings.CLEAN
                && dirty != AvasShellSettings.NAVIGATION_REJECTED) {
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
                event(diagnostics, "avas_nav_restored", "volume", savedNav,
                        "actual", safeStreamVolume());
            }
        } catch (Exception navFailure) {
            failure = combine(failure, navFailure);
        }
        try {
            int savedMute = settings.getInt(AvasShellSettings.SAVED_MUTE, -1);
            if (savedMute >= 0) {
                route.mute(savedMute == 1, diagnostics);
                settings.putInt(AvasShellSettings.SAVED_MUTE, -1);
                event(diagnostics, "avas_mute_volume", "phase", "restored", "volume",
                        safeStreamVolume(), "muted", safeStreamMute());
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

    private Object safeStreamVolume() {
        try { return manager.getStreamVolume(NAV_STREAM); }
        catch (Throwable failure) { return "unavailable:" + failure; }
    }

    private Object safeStreamMute() {
        try { return manager.isStreamMute(NAV_STREAM); }
        catch (Throwable failure) { return "unavailable:" + failure; }
    }

    private static long safePlaybackHead(AudioTrack output) {
        AvasAudioDiagnostics.Snapshot snapshot = AvasAudioDiagnostics.safeProbe(() ->
                AvasAudioDiagnostics.Snapshot.ready(
                        Integer.toUnsignedLong(output.getPlaybackHeadPosition())));
        return snapshot.available ? snapshot.playbackHead : -1;
    }

    private static Object safeTrackState(AudioTrack output) {
        try { return output.getState(); }
        catch (Throwable failure) { return "unavailable:" + failure; }
    }

    private static Object safePlayState(AudioTrack output) {
        try { return output.getPlayState(); }
        catch (Throwable failure) { return "unavailable:" + failure; }
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
        release(output, false);
    }

    private static void release(AudioTrack output, boolean exterior) {
        if (output == null) return;
        try {
            if (exterior) {
                output.stop();
            } else {
                output.pause();
                output.flush();
            }
        } catch (Exception ignored) {
        }
        try {
            output.release();
        } catch (Exception ignored) {
        }
    }

    private void event(String kind, Object... fields) {
        event((AvasAudioDiagnostics.Context) null, kind, fields);
    }

    private void event(AvasAudioDiagnostics.Context context, String kind, Object... fields) {
        if (log == null) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind);
            if (context != null) {
                event.put("request", context.requestId).put("profile", context.profile)
                        .put("source", context.source).put("helper_pid", context.helperPid)
                        .put("accepted_t_ms", context.acceptedMs)
                        .put("enqueued_t_ms", context.enqueuedMs);
            }
            for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
            log.accept(event);
        } catch (Throwable ignored) {
        }
    }

    /** Worker-owned effect: unique to one exterior track, never attached to NAV audition. */
    private final class ExteriorGain implements AutoCloseable {
        private final AudioTrack output;
        private final IntSupplier currentVolume;
        private final int initialVolume;
        private final AvasAudioDiagnostics.Context diagnostics;
        private LoudnessEnhancer loudness;
        private int appliedVolume = -1;

        ExteriorGain(AudioTrack output, IntSupplier currentVolume, int initialVolume,
                AvasAudioDiagnostics.Context diagnostics) {
            this.output = output;
            this.currentVolume = currentVolume;
            this.initialVolume = initialVolume;
            this.diagnostics = diagnostics;
        }

        void prepare() {
            int volume = volume();
            // A failed optional effect must not prevent ordinary PCM playback or route cleanup.
            try {
                int sessionId = output.getAudioSessionId();
                if (sessionId <= 0) throw new IllegalStateException("No private audio session");
                loudness = new LoudnessEnhancer(sessionId);
                loudness.setTargetGain(AvasPlaybackPlan.exteriorTargetGainMb(volume));
                int result = loudness.setEnabled(true);
                if (result != AudioEffect.SUCCESS || !loudness.getEnabled()) {
                    throw new IllegalStateException("LoudnessEnhancer enable=" + result);
                }
                event(diagnostics, "avas_loudness_state", "phase", "enabled",
                        "session_id", sessionId, "target_gain_mb", loudness.getTargetGain());
            } catch (Exception failure) {
                unavailable(failure);
            }
            applyVolume(volume);
        }

        void update() {
            int volume = volume();
            if (volume == appliedVolume) return;
            if (loudness != null) {
                try {
                    loudness.setTargetGain(AvasPlaybackPlan.exteriorTargetGainMb(volume));
                } catch (Exception failure) {
                    unavailable(failure);
                }
            }
            applyVolume(volume);
        }

        private int volume() {
            return currentVolume == null ? initialVolume : clamp(currentVolume.getAsInt());
        }

        private void applyVolume(int volume) {
            float playerVolume = AvasPlaybackPlan.exteriorPlayerVolume(volume);
            int result = output.setVolume(playerVolume);
            if (result != AudioTrack.SUCCESS) {
                throw new IllegalStateException("Exterior player volume=" + result);
            }
            appliedVolume = volume;
            event(diagnostics, "avas_exterior_gain", "volume", volume,
                    "player_volume", playerVolume, "loudness_enabled", loudness != null,
                    "target_gain_mb", loudness == null ? 0
                            : AvasPlaybackPlan.exteriorTargetGainMb(volume), "pcm_multiplier", 1);
        }

        private void unavailable(Exception failure) {
            close();
            event(diagnostics, "avas_loudness_state", "phase", "unavailable",
                    "fallback", "unboosted_pcm", "error", failure.toString());
        }

        @Override public void close() {
            LoudnessEnhancer owned = loudness;
            loudness = null;
            if (owned == null) return;
            try {
                owned.release();
                event(diagnostics, "avas_loudness_state", "phase", "released");
            } catch (Exception failure) {
                event(diagnostics, "avas_loudness_state", "phase", "release_error",
                        "error", failure.toString());
            }
        }
    }

    private final class SessionDiagnostics {
        private final AvasAudioDiagnostics.Context context;
        private AvasAudioDiagnostics.SampleGate gate;
        private boolean firstPositiveWrite;
        private boolean firstWavWrite;
        private boolean firstProgress;
        private int initialUnderruns = -1;
        private String routedDevice = "";
        private boolean finalSampled;
        private long silenceSubmittedFrames;
        private long fileSubmittedFrames;
        private AvasAudioDiagnostics.PcmLevels exteriorLevels;
        private boolean pcmLevelsFailed;
        private final AudioRouting.OnRoutingChangedListener routingListener = routing -> {
            if (routing instanceof AudioTrack) routedDeviceChanged((AudioTrack) routing,
                    "callback", SystemClock.elapsedRealtime());
        };

        SessionDiagnostics(AvasAudioDiagnostics.Context context) { this.context = context; }

        void bind(AudioTrack output) {
            gate = new AvasAudioDiagnostics.SampleGate(SystemClock.elapsedRealtime());
            try {
                initialUnderruns = output.getUnderrunCount();
                event(context, "avas_underrun_baseline", "available", true,
                        "underruns", initialUnderruns);
            } catch (Throwable failure) {
                event(context, "avas_underrun_baseline", "available", false,
                        "error", String.valueOf(failure));
            }
            try {
                output.addOnRoutingChangedListener(routingListener,
                        new Handler(Looper.getMainLooper()));
            } catch (Throwable failure) {
                event(context, "avas_routing_listener", "available", false,
                        "error", String.valueOf(failure));
            }
        }

        void unbind(AudioTrack output) {
            if (output == null) return;
            try {
                output.removeOnRoutingChangedListener(routingListener);
            } catch (Throwable failure) {
                event(context, "avas_routing_listener", "phase", "remove", "available", false,
                        "error", String.valueOf(failure));
            }
        }

        void staticPreload(AudioTrack output, long leadingFrames, long fileFrames) {
            silenceSubmittedFrames = leadingFrames;
            positiveWrite(output, "wav", fileFrames);
        }

        void positiveWrite(AudioTrack output, String phase, long frames) {
            if ("silence".equals(phase)) silenceSubmittedFrames += frames;
            else if ("wav".equals(phase)) fileSubmittedFrames += frames;
            if (!firstPositiveWrite) {
                firstPositiveWrite = true;
                event(context, "avas_first_positive_write", "phase", phase, "frames", frames,
                        "write_t_ms", SystemClock.elapsedRealtime());
            }
            if ("wav".equals(phase) && !firstWavWrite) {
                firstWavWrite = true;
                event(context, "avas_first_wav_submission", "frames", frames,
                        "submission_t_ms", SystemClock.elapsedRealtime());
            }
            initialSample(output);
        }

        void initialSample(AudioTrack output) {
            long now = SystemClock.elapsedRealtime();
            if (gate == null) gate = new AvasAudioDiagnostics.SampleGate(now);
            if (!gate.initial(now)) return;
            sample(output, "initial", now);
        }

        void finalSample(AudioTrack output) {
            if (output == null || finalSampled) return;
            finalSampled = true;
            sample(output, "final", SystemClock.elapsedRealtime());
            if (exteriorLevels != null && !pcmLevelsFailed) {
                event(context, "avas_pcm_levels", "position", "before_native_gain",
                        "accepted_samples", exteriorLevels.samples, "peak", exteriorLevels.peak,
                        "rms", exteriorLevels.rms(), "post_effect_pcm", "not_observable",
                        "physical_output", "unknown");
            }
        }

        void exteriorPcm(byte[] pcm, int offset, int count) {
            if (pcmLevelsFailed) return;
            try {
                if (exteriorLevels == null) exteriorLevels = new AvasAudioDiagnostics.PcmLevels();
                exteriorLevels.record(pcm, offset, count);
            } catch (Exception failure) {
                pcmLevelsFailed = true;
                event(context, "avas_pcm_levels_unavailable", "error", failure.toString());
            }
        }

        private void sample(AudioTrack output, String phase, long now) {
            AvasAudioDiagnostics.Snapshot head = AvasAudioDiagnostics.safeProbe(() ->
                    AvasAudioDiagnostics.Snapshot.ready(
                            Integer.toUnsignedLong(output.getPlaybackHeadPosition())));
            boolean timestampAvailable = false;
            long timestampFrame = -1;
            long timestampNano = -1;
            String timestampError = "";
            try {
                AudioTimestamp timestamp = new AudioTimestamp();
                timestampAvailable = output.getTimestamp(timestamp);
                if (timestampAvailable) {
                    timestampFrame = timestamp.framePosition;
                    timestampNano = timestamp.nanoTime;
                }
            } catch (Throwable failure) {
                timestampError = String.valueOf(failure);
            }
            int underruns = -1;
            String underrunError = "";
            try {
                underruns = output.getUnderrunCount();
                if (initialUnderruns < 0) initialUnderruns = underruns;
            } catch (Throwable failure) {
                underrunError = String.valueOf(failure);
            }
            String device = routedDeviceChanged(output, phase, now);
            event(context, "avas_audio_sample", "phase", phase, "sample_t_ms", now,
                    "playback_head_available", head.available, "playback_head", head.playbackHead,
                    "playback_head_error", head.error, "timestamp_available", timestampAvailable,
                    "timestamp_frame", timestampFrame, "timestamp_nano", timestampNano,
                    "timestamp_error", timestampError, "routed_device", device,
                    "underruns", underruns, "underrun_error", underrunError,
                    "underrun_delta", underruns >= 0 && initialUnderruns >= 0
                            ? Math.max(0, underruns - initialUnderruns) : -1,
                    "silence_submitted_frames", silenceSubmittedFrames,
                    "file_submitted_frames", fileSubmittedFrames);
            if (!firstProgress && (head.available && head.playbackHead > 0
                    || timestampAvailable && timestampFrame > 0)) {
                firstProgress = true;
                if (gate != null) gate.progress();
                event(context, "avas_first_track_progress", "playback_head", head.playbackHead,
                        "timestamp_available", timestampAvailable, "timestamp_frame",
                        timestampFrame, "progress_t_ms", now);
            }
        }

        private String routedDeviceChanged(AudioTrack output, String phase, long now) {
            String currentDevice;
            try {
                AudioDeviceInfo device = output.getRoutedDevice();
                currentDevice = device == null ? "unavailable"
                        : device.getId() + ":" + device.getType() + ":" + device.getProductName();
            } catch (Throwable failure) {
                currentDevice = "error:" + failure;
            }
            if (!currentDevice.equals(routedDevice)) {
                event(context, "avas_routed_device_changed", "phase", phase, "from",
                        routedDevice.isEmpty() ? "unavailable" : routedDevice,
                        "to", currentDevice, "change_t_ms", now);
                routedDevice = currentDevice;
            }
            return currentDevice;
        }
    }
}
