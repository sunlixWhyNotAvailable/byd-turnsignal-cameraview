package com.byd.extend;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Continuous engine lane; four event sounds retain their independent sequential queue. */
final class AvasEngineRuntime implements AutoCloseable {
    private final Context context;
    private final Callable<AvasAudioPlayer> player;
    private final BooleanSupplier microphoneActive;
    private final Runnable changed;
    private final Consumer<JSONObject> events;
    private final AvasEngineTelemetry telemetry;
    private final AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
    private final ExecutorService render = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "avas-engine-pcm"));
    private volatile AvasConfig.Engine config = AvasConfig.Engine.defaults();
    private volatile Session current;
    private volatile CountDownLatch shutdownTail = new CountDownLatch(0);
    private volatile boolean closed;
    private String state = "stopped";
    private String error = "";
    private int lastLoggedPower = AvasEngineSessionPolicy.POWER_INVALID;
    private boolean loggedPower;

    AvasEngineRuntime(Context context, Callable<AvasAudioPlayer> player,
            BooleanSupplier microphoneActive, ScheduledExecutorService executor,
            Consumer<JSONObject> events, Runnable changed) {
        this.context = context;
        this.player = player;
        this.microphoneActive = microphoneActive;
        this.changed = changed;
        this.events = events;
        telemetry = new AvasEngineTelemetry(context, executor, events);
    }

    synchronized void configure(AvasConfig.Engine next) {
        if (closed) return;
        AvasConfig.Engine previous = config;
        config = next;
        telemetry.setEnabled(next.enabled && hasOutput(next));
        AvasEngineSessionPolicy.Action action = policy.configure(next.enabled, hasOutput(next));
        if (action != AvasEngineSessionPolicy.Action.NONE) {
            logPolicy("configure", policy.rawPower(), false, action);
        }
        if (!next.enabled || !hasOutput(next)) {
            if (current != null) current.cancelled = true;
            setState(current == null ? "stopped" : "stopping", "");
            return;
        }
        boolean reroute = !previous.packId.equals(next.packId)
                || previous.exteriorEnabled != next.exteriorEnabled
                || previous.interiorEnabled != next.interiorEnabled;
        if (action == AvasEngineSessionPolicy.Action.NONE && reroute && current != null
                && !current.tail && next.enabled && hasOutput(next)) {
            start(false);
        } else apply(action);
    }

    synchronized void onPower(int value, boolean baseline) {
        if (closed) return;
        AvasEngineSessionPolicy.Action action = policy.observePower(value, baseline);
        if (!loggedPower || value != lastLoggedPower || action != AvasEngineSessionPolicy.Action.NONE) {
            logPolicy("power", value, baseline, action);
            lastLoggedPower = value;
            loggedPower = true;
        }
        apply(action);
    }

    synchronized void startManual() {
        if (closed || current != null && !current.tail && !current.cancelled) return;
        AvasEngineSessionPolicy.Action action = policy.manualStart();
        logPolicy("manual_start", policy.rawPower(), false, action);
        apply(action);
    }

    synchronized void stopManual() {
        if (closed) return;
        AvasEngineSessionPolicy.Action action = policy.manualStop();
        logPolicy("manual_stop", policy.rawPower(), false, action);
        if (current != null) {
            current.cancelled = true;
            log("avas_engine_cancel", "source", "manual_stop", "pack", current.pack);
        }
        apply(action);
    }

    synchronized JSONObject status() {
        try { return new JSONObject().put("state", state).put("error", error)
                .put("packId", config.packId); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    boolean exteriorActive() {
        Session session = current;
        return session != null && !session.cancelled && session.exterior;
    }

    private void apply(AvasEngineSessionPolicy.Action action) {
        switch (action) {
            case START: start(true); break;
            case RESTORE: start(false); break;
            case STOP_WITH_TAIL:
                if (current != null && !current.cancelled) {
                    // Published synchronously before the Power/Lock sink enqueues its event.
                    shutdownTail = current.finished;
                    current.tail = true;
                    setState("stopping", "");
                }
                break;
            case STOP_NOW:
                if (current != null) current.cancelled = true;
                setState(current == null ? "stopped" : "stopping", "");
                break;
            default: break;
        }
    }

    private void logPolicy(String source, int rawPower, boolean baseline,
            AvasEngineSessionPolicy.Action action) {
        log("avas_engine_policy", "source", source, "power_raw", rawPower,
                "power_valid", policy.hasValidPower(), "last_valid_power", policy.lastValidPower(),
                "baseline", baseline, "action", action.name(),
                "desired_active", policy.desiredActive());
    }

    private void start(boolean ignition) {
        if (current != null) current.cancelled = true;
        Session next = new Session(config, ignition);
        current = next;
        setState("starting", "");
        render.execute(() -> run(next));
    }

    /** Wait only on the audio consumer, never the vehicle callback or Binder thread. */
    void awaitShutdown(BooleanSupplier cancelled) throws InterruptedException {
        CountDownLatch tail = shutdownTail;
        while (!closed && !cancelled.getAsBoolean() && !tail.await(50, TimeUnit.MILLISECONDS)) {}
    }

    private void run(Session session) {
        AvasAudioPlayer.EngineOutput exterior = null;
        AvasAudioPlayer.EngineOutput interior = null;
        String failure = "";
        boolean startCueStarted = false;
        boolean startCueEnded = false;
        boolean stopCueStarted = false;
        boolean stopCueEnded = false;
        long startExteriorSubmitted = 0;
        long startInteriorSubmitted = 0;
        long stopExteriorSubmitted = 0;
        long stopInteriorSubmitted = 0;
        try {
            if (session.cancelled || closed) return;
            Context owner = context.createPackageContext(BuildConfig.APPLICATION_ID, 0);
            AvasEnginePack pack = AvasEnginePack.load(owner.getAssets(), session.pack);
            AvasEngineSynth synth = new AvasEngineSynth(pack);
            AvasEngineModel model = new AvasEngineModel(pack.idleRpm, pack.maxRpm);
            AvasAudioPlayer output = player.call();
            BooleanSupplier cancelled = () -> session.cancelled || closed;
            if (session.exterior) {
                exterior = output.openEngineOutput(true, cancelled,
                        () -> config.exteriorVolume, session.exteriorVolume);
            }
            if (session.interior) interior = output.openEngineOutput(false, cancelled);
            if (cancelled.getAsBoolean()) return;
            synchronized (this) {
                if (current == session && !session.tail) setState("active", "");
            }
            float[] block = new float[480];
            short[] pcm = new short[480];
            float duck = 1f;
            float[] intro = session.ignition ? pack.start : null;
            int introAt = 0;
            int tailAt = 0;
            boolean stopping = false;
            long lastLog = 0;
            if (intro != null) {
                startCueStarted = true;
                logCue("start", "begin", intro.length, 0, 0, "ignition");
            }
            while (!cancelled.getAsBoolean()) {
                if (session.tail && !stopping) {
                    stopping = true;
                    stopCueStarted = true;
                    logCue("stop", "begin", pack.stop.length, 0, 0, "power_off");
                }
                int frames = block.length;
                int cue = 0;
                if (stopping) {
                    frames = Math.min(frames, pack.stop.length - tailAt);
                    if (frames <= 0) {
                        if (!cancelled.getAsBoolean() && !stopCueEnded) {
                            stopCueEnded = true;
                            logCue("stop", "end", pack.stop.length,
                                    stopExteriorSubmitted, stopInteriorSubmitted, "complete");
                        }
                        break;
                    }
                    System.arraycopy(pack.stop, tailAt, block, 0, frames);
                    tailAt += frames;
                    cue = 2;
                } else if (intro != null && introAt < intro.length) {
                    frames = Math.min(frames, intro.length - introAt);
                    System.arraycopy(intro, introAt, block, 0, frames);
                    introAt += frames;
                    cue = 1;
                } else {
                    long now = SystemClock.elapsedRealtime();
                    AvasEngineTelemetry.Snapshot snapshot = telemetry.updateSnapshot(model, now);
                    AvasEngineModel.State drive = snapshot.state;
                    synth.render(block, 0, frames, drive.rpm, drive.load);
                    if (now - lastLog >= 5000) {
                        AvasConfig.Engine settings = config;
                        log("avas_engine_motion", "pack", pack.id,
                                "rpm", drive.rpm, "load", drive.load,
                                "gear", drive.gear, "model_valid", drive.valid,
                                "speed_raw_bits", snapshot.speedRaw,
                                "speed_validity_raw", snapshot.speedValidityRaw,
                                "speed_valid", snapshot.speedValid,
                                "speed_kph", jsonFloat(snapshot.roadSpeedKph),
                                "speed_source", snapshot.speedSource,
                                "speed_validity_source", snapshot.speedValiditySource,
                                "selector_raw", snapshot.selectorRaw,
                                "selector_valid", snapshot.selectorValid,
                                "selector_source", snapshot.selectorSource,
                                "pedal_raw", snapshot.pedalRaw,
                                "pedal_validity_raw", snapshot.pedalValidityRaw,
                                "pedal_valid", snapshot.pedalValid,
                                "pedal_source", snapshot.pedalSource,
                                "pedal_validity_source", snapshot.pedalValiditySource,
                                "brake_raw", snapshot.brakeRaw,
                                "brake_validity_raw", snapshot.brakeValidityRaw,
                                "brake_valid", snapshot.brakeValid,
                                "brake_source", snapshot.brakeSource,
                                "brake_validity_source", snapshot.brakeValiditySource,
                                "front_motor_raw", snapshot.frontMotorRaw,
                                "front_motor_valid", snapshot.frontMotorValid,
                                "front_motor_source", snapshot.frontMotorSource,
                                "front_raw_per_kph", jsonFloat(snapshot.frontRawPerKph),
                                "front_calibration_samples", snapshot.frontCalibrationSamples,
                                "rear_motor_raw", snapshot.rearMotorRaw,
                                "rear_motor_valid", snapshot.rearMotorValid,
                                "rear_motor_source", snapshot.rearMotorSource,
                                "rear_raw_per_kph", jsonFloat(snapshot.rearRawPerKph),
                                "rear_calibration_samples", snapshot.rearCalibrationSamples,
                                "motion_source", snapshot.motionSource,
                                "motion_kph", jsonFloat(snapshot.motionKph),
                                "exterior_enabled", exterior != null,
                                "exterior_user_volume", settings.exteriorVolume,
                                "exterior_track_volume", exterior == null ? -1
                                        : exterior.trackVolumePercent(),
                                "exterior_loudness_enabled", exterior != null
                                        && exterior.loudnessEnabled(),
                                "exterior_loudness_gain_mb", exterior == null ? 0
                                        : exterior.loudnessGainMb(),
                                "interior_enabled", interior != null,
                                "interior_pcm_volume", settings.interiorVolume,
                                "interior_pcm_multiplier", settings.interiorVolume / 100.0f,
                                "microphone_duck", duck);
                        lastLog = now;
                    }
                }
                float target = microphoneActive.getAsBoolean() ? 0.25118864f : 1f;
                float step = (1f - 0.25118864f) / (target < duck ? 4800f : 14400f);
                for (int i = 0; i < frames; i++) {
                    duck += Math.max(-step, Math.min(step, target - duck));
                    block[i] *= duck;
                }
                AvasConfig.Engine settings = config;
                if (exterior != null) {
                    pcm(block, pcm, frames, 100);
                    int written = exterior.write(pcm, frames);
                    if (cue == 1) startExteriorSubmitted += written;
                    else if (cue == 2) stopExteriorSubmitted += written;
                }
                if (interior != null) {
                    pcm(block, pcm, frames, settings.interiorVolume);
                    int written = interior.write(pcm, frames);
                    if (cue == 1) startInteriorSubmitted += written;
                    else if (cue == 2) stopInteriorSubmitted += written;
                }
                if (cue == 1 && introAt >= intro.length && !cancelled.getAsBoolean()
                        && !startCueEnded) {
                    startCueEnded = true;
                    logCue("start", "end", intro.length,
                            startExteriorSubmitted, startInteriorSubmitted, "complete");
                } else if (cue == 2 && tailAt >= pack.stop.length
                        && !cancelled.getAsBoolean() && !stopCueEnded) {
                    stopCueEnded = true;
                    logCue("stop", "end", pack.stop.length,
                            stopExteriorSubmitted, stopInteriorSubmitted, "complete");
                }
            }
            if (!cancelled.getAsBoolean()) {
                if (exterior != null) exterior.drain();
                if (interior != null) interior.drain();
            }
        } catch (Exception problem) {
            if (!session.cancelled && !closed) {
                failure = problem.toString();
                log("avas_engine_error", "pack", session.pack, "phase", state,
                        "error", failure);
            }
        } finally {
            if (startCueStarted && !startCueEnded) {
                logCue("start", "cancel", -1, startExteriorSubmitted,
                        startInteriorSubmitted, "session_cancelled");
            }
            if (stopCueStarted && !stopCueEnded) {
                logCue("stop", "cancel", -1, stopExteriorSubmitted,
                        stopInteriorSubmitted, "session_cancelled");
            }
            if (session.cancelled || closed) {
                log("avas_engine_cancel", "source", closed ? "shutdown" : "session",
                        "pack", session.pack);
            }
            failure = closeOutput(interior, failure);
            failure = closeOutput(exterior, failure);
            synchronized (this) {
                if (current == session) {
                    current = null;
                    if (!config.enabled || !hasOutput(config)) failure = "";
                    if (!failure.isEmpty()) {
                        policy.playbackFailed();
                        logPolicy("playback_failed", policy.rawPower(), false,
                                AvasEngineSessionPolicy.Action.NONE);
                    }
                    setState(failure.isEmpty() ? "stopped" : "error", failure);
                }
            }
            // Even loading/routing/PCM failures must never strand the event lane.
            session.finished.countDown();
        }
    }

    private void logCue(String cue, String phase, int frames, long exteriorSubmitted,
            long interiorSubmitted, String reason) {
        log("avas_engine_cue", "cue", cue, "phase", phase, "frames", frames,
                "exterior_submitted_frames", exteriorSubmitted,
                "interior_submitted_frames", interiorSubmitted, "reason", reason);
    }

    private static Object jsonFloat(float value) {
        return Float.isFinite(value) ? value : JSONObject.NULL;
    }

    static void pcm(float[] input, short[] output, int frames, int volume) {
        float gain = Math.max(0, Math.min(100, volume)) / 100f;
        for (int i = 0; i < frames; i++) {
            float value = input[i] * gain;
            output[i] = Float.isFinite(value)
                    ? (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767f) : 0;
        }
    }

    private String closeOutput(AvasAudioPlayer.EngineOutput output, String failure) {
        if (output == null) return failure;
        try { output.close(); }
        catch (Exception problem) {
            log("avas_engine_cleanup_error", "error", problem.toString());
            if (failure.isEmpty()) return problem.toString();
        }
        return failure;
    }

    private void setState(String next, String failure) {
        if (state.equals(next) && error.equals(failure)) return;
        state = next;
        error = failure;
        log("avas_engine_state", "state", next, "error", failure, "packId", config.packId);
        changed.run();
    }

    private void log(String kind, Object... fields) {
        if (!DiagnosticLogPolicy.shouldProduce(kind)) return;
        try {
            JSONObject value = new JSONObject().put("kind", kind);
            for (int i = 0; i + 1 < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]);
            events.accept(value);
        } catch (Exception ignored) {}
    }

    private static boolean hasOutput(AvasConfig.Engine settings) {
        return settings.exteriorEnabled || settings.interiorEnabled;
    }

    @Override public void close() {
        synchronized (this) {
            closed = true;
            if (current != null) current.cancelled = true;
        }
        telemetry.close();
        render.shutdown();
        boolean interrupted = false;
        while (!render.isTerminated()) {
            try { render.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Session {
        final String pack;
        final boolean exterior;
        final boolean interior;
        final boolean ignition;
        final int exteriorVolume;
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean cancelled;
        volatile boolean tail;
        Session(AvasConfig.Engine settings, boolean ignition) {
            pack = settings.packId;
            exterior = settings.exteriorEnabled;
            interior = settings.interiorEnabled;
            this.ignition = ignition;
            exteriorVolume = settings.exteriorVolume;
        }
    }
}
