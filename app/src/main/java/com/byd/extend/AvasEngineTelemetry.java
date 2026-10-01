package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Read-only fixed drivetrain inputs. Unchanged callback values remain valid. */
final class AvasEngineTelemetry implements AutoCloseable {
    static final int MISSING = Integer.MIN_VALUE;
    private static final int DEVICE_SPEED = 1013;
    private static final int DEVICE_GEARBOX = 1011;
    private static final int DEVICE_MOTOR = 1012;
    private static final int SPEED_VALIDITY_FID = 303038487;
    private static final int PEDAL_FID = 874512392;
    private static final int PEDAL_VALIDITY_FID = 874512408;
    private static final int BRAKE_FID = 874512400;
    private static final int BRAKE_VALIDITY_FID = 874512409;
    private static final int GEAR_FID = 555745336;

    private final Context context;
    private final ScheduledExecutorService executor;
    private final Consumer<JSONObject> events;
    private final int speedFid = speedFid();
    private final int[] motorFids = motorFids(speedFid == -1807745016);
    private final int[] fids = {speedFid, SPEED_VALIDITY_FID, PEDAL_FID,
            PEDAL_VALIDITY_FID, BRAKE_FID, BRAKE_VALIDITY_FID, GEAR_FID,
            motorFids[0], motorFids[1]};
    private final int[] values = new int[fids.length];
    private final boolean[] available = new boolean[fids.length];
    private final String[] sources = new String[fids.length];
    private final long[] revisions = new long[fids.length];
    private final long[] receivedAt = new long[fids.length];
    private final AvasEngineMotorMapping.Scale frontScale = new AvasEngineMotorMapping.Scale();
    private final AvasEngineMotorMapping.Scale rearScale = new AvasEngineMotorMapping.Scale();
    private volatile boolean closed;
    private volatile boolean enabled;
    private volatile long generation;
    private volatile boolean subscriptionFailed;
    private FixedBydTelemetryManager.Subscription subscription;
    private ScheduledFuture<?> next;
    private IBinder service;
    private long lastSubscribeAttempt = Long.MIN_VALUE;

    AvasEngineTelemetry(Context context, ScheduledExecutorService executor,
            Consumer<JSONObject> events) {
        this.context = context;
        this.executor = executor;
        this.events = events;
        Arrays.fill(values, MISSING);
        Arrays.fill(sources, "unavailable");
    }

    void setEnabled(boolean value) {
        if (closed) return;
        executor.execute(() -> {
            if (closed || enabled == value) return;
            enabled = value;
            generation++;
            cancel();
            synchronized (AvasEngineTelemetry.this) {
                invalidate();
                if (!value) {
                    frontScale.reset();
                    rearScale.reset();
                }
            }
            lastSubscribeAttempt = Long.MIN_VALUE;
            if (value) tick();
        });
    }

    private void tick() {
        if (!enabled || closed) return;
        if (subscriptionFailed) {
            if (subscription != null) subscription.close();
            subscription = null;
            subscriptionFailed = false;
            generation++;
        }
        long now = SystemClock.elapsedRealtime();
        if (subscription == null && (lastSubscribeAttempt == Long.MIN_VALUE
                || now - lastSubscribeAttempt >= 60_000)) subscribe();
        for (int i = 0; i < fids.length && enabled && !closed; i++) {
            long before;
            synchronized (this) { before = revisions[i]; }
            Integer raw = read(i);
            long readAt = SystemClock.elapsedRealtime();
            synchronized (this) {
                if (before == revisions[i]) {
                    values[i] = raw == null ? MISSING : raw;
                    available[i] = raw != null;
                    sources[i] = raw == null ? "unavailable" : "get";
                    receivedAt[i] = readAt;
                    revisions[i]++;
                }
            }
        }
        if (enabled && !closed) next = executor.schedule(this::tick,
                subscription == null ? 1000 : 60_000, TimeUnit.MILLISECONDS);
    }

    private void subscribe() {
        lastSubscribeAttempt = SystemClock.elapsedRealtime();
        long session = generation;
        try {
            subscription = FixedBydTelemetryManager.get(context).subscribe(
                    new FixedBydTelemetryManager.Request[]{
                        new FixedBydTelemetryManager.Request(DEVICE_SPEED,
                                FixedBydTelemetryManager.ValueType.FLOAT, speedFid),
                        new FixedBydTelemetryManager.Request(DEVICE_SPEED,
                                SPEED_VALIDITY_FID, PEDAL_FID, PEDAL_VALIDITY_FID,
                                BRAKE_FID, BRAKE_VALIDITY_FID),
                        new FixedBydTelemetryManager.Request(DEVICE_GEARBOX, GEAR_FID),
                        new FixedBydTelemetryManager.Request(DEVICE_MOTOR,
                                motorFids[0], motorFids[1])},
                    new FixedBydTelemetryManager.Listener() {
                        @Override public void onValue(int device, int fid, int raw, long at) {
                            synchronized (AvasEngineTelemetry.this) {
                                if (session != generation || !enabled || closed) return;
                                for (int i = 0; i < fids.length; i++) {
                                    if (fids[i] == fid && device == deviceFor(i)) {
                                        values[i] = raw;
                                        available[i] = true;
                                        sources[i] = "callback";
                                        receivedAt[i] = at;
                                        revisions[i]++;
                                        break;
                                    }
                                }
                            }
                        }
                        @Override public void onError(String reason, long at) {
                            if (session != generation || closed) return;
                            executor.execute(() -> retryNow(
                                    session == generation && enabled && !closed, next, () -> {
                                        invalidate();
                                        subscriptionFailed = true;
                                        log("error", reason);
                                        tick();
                                    }));
                        }
                    });
            log("callback", "fixed_read_only_inputs speed_fid=" + speedFid
                    + " front_motor_fid=" + motorFids[0]
                    + " rear_motor_fid=" + motorFids[1]);
        } catch (Exception failure) {
            subscription = null;
            log("fallback_1s", failure.toString());
        }
    }

    AvasEngineModel.State update(AvasEngineModel model, long now) {
        return updateSnapshot(model, now).state;
    }

    synchronized Snapshot updateSnapshot(AvasEngineModel model, long now) {
        float roadSpeed = available[0] ? Float.intBitsToFloat(values[0]) : Float.NaN;
        boolean speedValid = available[0] && available[1] && values[1] == 1
                && Float.isFinite(roadSpeed) && roadSpeed >= 0 && roadSpeed <= 300;
        boolean pedalValid = available[2] && available[3]
                && percentValid(values[2], values[3]);
        boolean brakeValid = available[4] && available[5]
                && percentValid(values[4], values[5]);
        boolean selectorValid = available[6] && values[6] >= 1 && values[6] <= 6;
        boolean frontValid = available[7]
                && !AvasEngineMotorMapping.isKnownSdkError(values[7]);
        boolean rearValid = available[8]
                && !AvasEngineMotorMapping.isKnownSdkError(values[8]);

        frontScale.observe(roadSpeed, speedValid, receivedAt[0], revisions[0],
                values[7], frontValid, receivedAt[7], revisions[7]);
        rearScale.observe(roadSpeed, speedValid, receivedAt[0], revisions[0],
                values[8], rearValid, receivedAt[8], revisions[8]);
        AvasEngineMotorMapping.Motion motion = AvasEngineMotorMapping.select(
                roadSpeed, speedValid, values[7], frontValid, frontScale,
                values[8], rearValid, rearScale);
        AvasEngineModel.State state = model.update(now, motion.speedKph,
                Float.isFinite(motion.speedKph), values[2], pedalValid,
                values[4], brakeValid, values[6], selectorValid);
        return new Snapshot(state, values, available, sources, speedValid, selectorValid,
                pedalValid, brakeValid, frontValid, rearValid, roadSpeed,
                motion.speedKph, motion.source, frontScale.rawPerKph(),
                frontScale.sampleCount(), rearScale.rawPerKph(), rearScale.sampleCount());
    }

    static boolean percentValid(int value, int valid) {
        return valid == 1 && value >= 0 && value <= 100;
    }

    static void retryNow(boolean currentSession, Future<?> delayed, Runnable retry) {
        if (!currentSession) return;
        if (delayed != null) delayed.cancel(false);
        retry.run();
    }

    private synchronized void invalidate() {
        Arrays.fill(values, MISSING);
        Arrays.fill(available, false);
        Arrays.fill(sources, "unavailable");
        Arrays.fill(receivedAt, 0L);
        for (int i = 0; i < revisions.length; i++) revisions[i]++;
    }

    private Integer read(int index) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            service = AvasAutoService.resolve(service);
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(deviceFor(index));
            data.writeInt(fids[index]);
            if (!service.transact(index == 0 ? 7 : 5, data, reply, 0)
                    || reply.dataAvail() < 8) return null;
            int status = reply.readInt();
            int raw = reply.readInt();
            return status == 0 ? raw : null;
        } catch (Exception failure) {
            service = null;
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int deviceFor(int index) {
        if (index == 6) return DEVICE_GEARBOX;
        if (index >= 7) return DEVICE_MOTOR;
        return DEVICE_SPEED;
    }

    private static int speedFid() {
        try {
            int fid = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
                    .getField("SPEED_AUTO_SPEED").getInt(null);
            if (fid == -1807745016 || fid == -1176502256 || fid == 303038472) return fid;
        } catch (ReflectiveOperationException | LinkageError ignored) {}
        return -1807745016;
    }

    static int[] motorFids(boolean canFd) {
        int frontFallback = canFd ? 1141899272 : 1141901320;
        int rearFallback = canFd ? 621805576 : 621807624;
        return new int[]{engineMotorFid("ENGINE_FRONT_MOTOR_SPEED", frontFallback,
                        1141899272, 1141901320),
                engineMotorFid("ENGINE_REAR_MOTOR_SPEED", rearFallback,
                        621805576, 621807624)};
    }

    private static int engineMotorFid(String name, int fallback, int canFdFid, int otherFid) {
        try {
            int fid = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$Engine")
                    .getField(name).getInt(null);
            if (fid == canFdFid || fid == otherFid) return fid;
        } catch (ReflectiveOperationException | LinkageError ignored) {}
        return fallback;
    }

    private void log(String mode, String reason) {
        try {
            events.accept(new JSONObject().put("kind", "avas_engine_telemetry")
                    .put("mode", mode).put("reason", reason));
        } catch (Exception ignored) {}
    }

    private void cancel() {
        if (next != null) next.cancel(false);
        next = null;
        if (subscription != null) subscription.close();
        subscription = null;
    }

    @Override public void close() {
        closed = true;
        enabled = false;
        generation++;
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> { try { cancel(); } finally { done.countDown(); } });
        boolean interrupted = false;
        for (;;) {
            try { done.await(); break; }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static final class Snapshot {
        final AvasEngineModel.State state;
        final int speedRaw;
        final int speedValidityRaw;
        final int selectorRaw;
        final int pedalRaw;
        final int pedalValidityRaw;
        final int brakeRaw;
        final int brakeValidityRaw;
        final int frontMotorRaw;
        final int rearMotorRaw;
        final boolean speedValid;
        final boolean selectorValid;
        final boolean pedalValid;
        final boolean brakeValid;
        final boolean frontMotorValid;
        final boolean rearMotorValid;
        final String speedSource;
        final String speedValiditySource;
        final String selectorSource;
        final String pedalSource;
        final String pedalValiditySource;
        final String brakeSource;
        final String brakeValiditySource;
        final String frontMotorSource;
        final String rearMotorSource;
        final float roadSpeedKph;
        final float motionKph;
        final String motionSource;
        final float frontRawPerKph;
        final int frontCalibrationSamples;
        final float rearRawPerKph;
        final int rearCalibrationSamples;

        Snapshot(AvasEngineModel.State state, int[] values, boolean[] available,
                String[] sources, boolean speedValid, boolean selectorValid,
                boolean pedalValid, boolean brakeValid, boolean frontMotorValid,
                boolean rearMotorValid, float roadSpeedKph, float motionKph,
                String motionSource, float frontRawPerKph, int frontCalibrationSamples,
                float rearRawPerKph, int rearCalibrationSamples) {
            this.state = state;
            speedRaw = values[0];
            speedValidityRaw = values[1];
            pedalRaw = values[2];
            pedalValidityRaw = values[3];
            brakeRaw = values[4];
            brakeValidityRaw = values[5];
            selectorRaw = values[6];
            frontMotorRaw = values[7];
            rearMotorRaw = values[8];
            this.speedValid = speedValid;
            this.selectorValid = selectorValid;
            this.pedalValid = pedalValid;
            this.brakeValid = brakeValid;
            this.frontMotorValid = frontMotorValid;
            this.rearMotorValid = rearMotorValid;
            speedSource = source(available, sources, 0);
            speedValiditySource = source(available, sources, 1);
            pedalSource = source(available, sources, 2);
            pedalValiditySource = source(available, sources, 3);
            brakeSource = source(available, sources, 4);
            brakeValiditySource = source(available, sources, 5);
            selectorSource = source(available, sources, 6);
            frontMotorSource = source(available, sources, 7);
            rearMotorSource = source(available, sources, 8);
            this.roadSpeedKph = roadSpeedKph;
            this.motionKph = motionKph;
            this.motionSource = motionSource;
            this.frontRawPerKph = frontRawPerKph;
            this.frontCalibrationSamples = frontCalibrationSamples;
            this.rearRawPerKph = rearRawPerKph;
            this.rearCalibrationSamples = rearCalibrationSamples;
        }

        private static String source(boolean[] available, String[] sources, int index) {
            return available[index] ? sources[index] : "unavailable";
        }
    }
}
