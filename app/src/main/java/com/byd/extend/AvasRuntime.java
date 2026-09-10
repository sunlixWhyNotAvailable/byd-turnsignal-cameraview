package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Isolated AVAS state, telemetry and one-player FIFO owned by the shell helper. */
final class AvasRuntime implements AutoCloseable {
    private static final File CACHE = new File("/data/local/tmp/bydextend_avas");
    private static final File CONFIG = new File(CACHE, "config.json");
    private static final long POLL_MS = 250;
    private static final int POWER_DEVICE = 1001;
    private static final int POWER_FID = 315621418;
    private static final int LOCK_DEVICE = 1032;
    private static final int LOCK_FID = 1081081864;

    private final Context context;
    private final int ownerUid;
    private final Consumer<JSONObject> eventSink;
    private final AvasPlaybackQueue queue = new AvasPlaybackQueue();
    private final AvasEventPolicy policy = new AvasEventPolicy();
    private final VehicleReader vehicle = new VehicleReader();
    private final ExecutorService playback = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "avas-playback"));
    private final ScheduledExecutorService telemetry = Executors.newSingleThreadScheduledExecutor(r ->
            new Thread(r, "avas-telemetry"));
    private volatile AvasConfig config = AvasConfig.empty();
    private volatile AvasAudioPlayer player;
    private ScheduledFuture<?> pollTask;
    private boolean started;
    private volatile boolean closed;
    private boolean configOwned;
    private volatile int telemetryState = -1;

    AvasRuntime(Context context, int ownerUid, Consumer<JSONObject> eventSink) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (ownerUid <= 0) throw new IllegalArgumentException("ownerUid is invalid");
        this.context = context;
        this.ownerUid = ownerUid;
        this.eventSink = eventSink;
    }

    synchronized void start() {
        if (closed || started) return;
        started = true;
        config = loadConfig();
        playback.execute(this::playbackLoop);
        updatePolling();
        event("avas_runtime_started", "ownerUid", ownerUid);
        reportStatus();
    }

    synchronized void configure(AvasConfig next) {
        if (closed) return;
        if (next == null) throw new IllegalArgumentException("AVAS config is null");
        String previous = config.toJson();
        config = next;
        if (!previous.equals(next.toJson()) || !configOwned) {
            configOwned = persistConfig(next);
            Set<String> enabled = new HashSet<>();
            for (AvasConfig.Profile profile : next.profiles) {
                if (profile.enabled) enabled.add(profile.id);
            }
            queue.retainAutomaticProfiles(enabled);
            event("avas_configured", "automaticProfiles", enabled.size());
        }
        updatePolling();
        reportStatus();
    }

    void installAsset(String assetId, ParcelFileDescriptor descriptor) throws IOException {
        if (!validAssetId(assetId)) {
            closeDescriptor(descriptor);
            throw new IOException("invalid AVAS asset id");
        }
        if (descriptor == null) throw new IOException("AVAS asset descriptor unavailable");
        synchronized (this) {
            if (closed) {
                closeDescriptor(descriptor);
                throw new IOException("AVAS runtime is closed");
            }
        }
        File temporary = null;
        File ready = assetFile(assetId);
        try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            ensureCache();
            temporary = new File(CACHE, "." + assetId + "." + UUID.randomUUID() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            AvasWav.read(temporary);
            moveAtomic(temporary, ready);
            event("avas_asset_ready", "asset", assetId, "bytes", ready.length());
            reportStatus();
        } catch (IOException failure) {
            event("avas_asset_error", "asset", assetId, "error", failure.toString());
            throw failure;
        } catch (RuntimeException failure) {
            IOException wrapped = new IOException("AVAS asset publication failed", failure);
            event("avas_asset_error", "asset", assetId, "error", wrapped.toString());
            throw wrapped;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary.toPath()); } catch (Exception ignored) {}
            }
        }
    }

    void startManual(String profileId) {
        if (closed) return;
        AvasConfig.Profile profile = config.profile(profileId);
        File selected = selectedReady(profile);
        if (selected == null) {
            event("avas_profile_skipped", "profile", profileId, "source", "manual",
                    "reason", "selected_asset_not_ready");
            reportStatus();
            return;
        }
        AvasPlaybackQueue.Request request = queue.enqueue(profileId, true);
        if (request == null) {
            event("avas_profile_skipped", "profile", profileId, "source", "manual",
                    "reason", "already_queued_or_playing");
        } else {
            event("avas_queue_enqueued", "request", request.id, "profile", profileId,
                    "source", "manual", "pending", queue.pendingCount());
        }
        reportStatus();
    }

    void stopManual(String profileId) {
        if (closed) return;
        config.profile(profileId);
        queue.stopManual(profileId);
        event("avas_manual_stopped", "profile", profileId, "pending", queue.pendingCount());
        reportStatus();
    }

    void reportStatus() {
        if (closed) return;
        try {
            JSONObject profiles = new JSONObject();
            for (String id : AvasConfig.PROFILE_IDS) profiles.put(id, queue.state(id));
            emit(new JSONObject().put("kind", "avas_status").put("profiles", profiles));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (pollTask != null) pollTask.cancel(true);
            pollTask = null;
            queue.close();
        }
        telemetry.shutdownNow();
        AvasAudioPlayer current = player;
        if (current != null) current.stop();
        playback.shutdown();
        boolean interrupted = false;
        while (true) {
            try {
                if (playback.awaitTermination(1, TimeUnit.SECONDS)) break;
            } catch (InterruptedException ignored) {
                interrupted = true;
                if (current != null) current.stop();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        event("avas_runtime_closed");
    }

    private void playbackLoop() {
        while (true) {
            AvasPlaybackQueue.Request request;
            try {
                request = queue.take();
            } catch (InterruptedException ignored) {
                if (closed) return;
                continue;
            }
            if (request == null) return;
            try {
                play(request);
            } finally {
                queue.finish(request);
                reportStatus();
            }
        }
    }

    private void play(AvasPlaybackQueue.Request request) {
        AvasConfig.Profile profile = config.profile(request.profile);
        File file = request.manual ? selectedReady(profile) : automaticReady(profile);
        if (file == null) {
            event("avas_profile_skipped", "request", request.id, "profile", request.profile,
                    "source", request.manual ? "manual" : "automatic", "reason", "asset_not_ready");
            return;
        }
        try {
            AvasAudioPlayer output = player;
            if (output == null) {
                output = new AvasAudioPlayer(context, this::emit);
                player = output;
            }
            event("avas_play_start", "request", request.id, "profile", request.profile,
                    "source", request.manual ? "manual" : "automatic", "asset", assetId(file),
                    "gain", profile.volume);
            reportStatus();
            output.play(file, profile.volume, request.cancelled::get,
                    () -> config.profile(request.profile).volume);
            event("avas_play_finish", "request", request.id, "profile", request.profile,
                    "asset", assetId(file), "cancelled", request.cancelled.get());
        } catch (Exception failure) {
            event("avas_play_error", "request", request.id, "profile", request.profile,
                    "asset", assetId(file), "error", failure.toString());
            if (request.manual) {
                event("avas_error", "stage", "manual_playback", "profile_id", request.profile,
                        "asset_id", assetId(file), "error", failure.toString());
            }
        }
    }

    private synchronized void updatePolling() {
        if (!started || closed) return;
        boolean enabled = false;
        for (AvasConfig.Profile profile : config.profiles) enabled |= profile.enabled;
        if (enabled && pollTask == null) {
            synchronized (policy) { policy.reset(); }
            telemetryState = -1;
            pollTask = telemetry.scheduleWithFixedDelay(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
        } else if (!enabled && pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
            synchronized (policy) { policy.reset(); }
            telemetryState = -1;
        }
    }

    private void poll() {
        int power = vehicle.read(POWER_DEVICE, POWER_FID);
        int lock = vehicle.read(LOCK_DEVICE, LOCK_FID);
        boolean healthy = AvasEventPolicy.normalizedPower(power) >= 0 && (lock == 1 || lock == 2);
        if (!healthy) {
            synchronized (policy) {
                policy.sample(SystemClock.elapsedRealtime(), power, lock, false);
            }
            if (telemetryState != 0) event("avas_telemetry_gap", "power", power, "lock", lock);
            telemetryState = 0;
            return;
        }
        if (telemetryState != 1) event("avas_telemetry_ready", "power", power, "lock", lock);
        telemetryState = 1;
        boolean offReady = automaticEligible("power_off");
        List<String> profiles;
        boolean unlockSuppressed;
        synchronized (policy) {
            profiles = policy.sample(SystemClock.elapsedRealtime(), power, lock, offReady);
            unlockSuppressed = policy.wasUnlockSuppressed();
        }
        for (String profile : profiles) enqueueAutomatic(profile);
        if (unlockSuppressed) {
            event("avas_event_skipped", "profile", "unlock", "reason", "power_off_priority");
        }
    }

    private void enqueueAutomatic(String profileId) {
        if (!automaticEligible(profileId)) {
            event("avas_event_skipped", "profile", profileId, "reason", "disabled_or_not_ready");
            return;
        }
        AvasPlaybackQueue.Request request = queue.enqueue(profileId, false);
        if (request != null) {
            event("avas_event_accepted", "profile", profileId, "request", request.id);
            event("avas_queue_enqueued", "request", request.id, "profile", profileId,
                    "source", "automatic", "pending", queue.pendingCount());
            reportStatus();
        }
    }

    private boolean automaticEligible(String profileId) {
        AvasConfig.Profile profile = config.profile(profileId);
        return profile.enabled && automaticReady(profile) != null;
    }

    private File selectedReady(AvasConfig.Profile profile) {
        if (profile.selectedAssetId.isEmpty()) return null;
        File file = assetFile(profile.selectedAssetId);
        return file.isFile() ? file : null;
    }

    private File automaticReady(AvasConfig.Profile profile) {
        if (!profile.random) return selectedReady(profile);
        List<File> ready = new ArrayList<>();
        for (AvasConfig.Asset asset : profile.assets) {
            File file = assetFile(asset.id);
            if (file.isFile()) ready.add(file);
        }
        if (ready.isEmpty()) return null;
        return ready.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(ready.size()));
    }

    private AvasConfig loadConfig() {
        if (!CONFIG.isFile()) return AvasConfig.empty();
        try {
            JSONObject root = new JSONObject(new String(
                    Files.readAllBytes(CONFIG.toPath()), StandardCharsets.UTF_8));
            int storedUid = root.getInt("ownerUid");
            if (storedUid != ownerUid) {
                event("avas_config_rejected", "storedOwnerUid", storedUid, "ownerUid", ownerUid);
                return AvasConfig.empty();
            }
            AvasConfig loaded = AvasConfig.parse(root.getJSONObject("config").toString());
            configOwned = true;
            return loaded;
        } catch (Exception failure) {
            event("avas_config_error", "operation", "load", "error", failure.toString());
            return AvasConfig.empty();
        }
    }

    private boolean persistConfig(AvasConfig value) {
        File temporary = null;
        try {
            ensureCache();
            temporary = new File(CACHE, ".config." + UUID.randomUUID() + ".tmp");
            JSONObject root = new JSONObject().put("ownerUid", ownerUid)
                    .put("config", new JSONObject(value.toJson()));
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            moveAtomic(temporary, CONFIG);
            return true;
        } catch (Exception failure) {
            event("avas_config_error", "operation", "save", "error", failure.toString());
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) {}
            }
        }
    }

    private static void ensureCache() throws IOException {
        if (!CACHE.isDirectory() && !CACHE.mkdirs()) throw new IOException("AVAS cache unavailable");
        try {
            Os.chmod(CACHE.getAbsolutePath(), 0700);
        } catch (ErrnoException failure) {
            throw new IOException("AVAS cache permissions unavailable", failure);
        }
    }

    private static void moveAtomic(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("atomic AVAS publication unavailable", failure);
        }
    }

    private static boolean validAssetId(String assetId) {
        return assetId != null && assetId.matches("[0-9a-f]{32}");
    }

    private static File assetFile(String assetId) {
        if (!validAssetId(assetId)) throw new IllegalArgumentException("invalid AVAS asset id");
        return new File(CACHE, assetId + ".wav");
    }

    private static String assetId(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - 4);
    }

    private void event(String kind, Object... fields) {
        try {
            JSONObject event = new JSONObject().put("kind", kind);
            for (int index = 0; index + 1 < fields.length; index += 2) {
                event.put(String.valueOf(fields[index]), fields[index + 1]);
            }
            emit(event);
        } catch (Exception ignored) {
        }
    }

    private void emit(JSONObject event) {
        if (eventSink == null) return;
        try {
            if (!event.has("source")) event.put("source", "shell_helper");
            if (!event.has("wall_time")) event.put("wall_time", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
            if (!event.has("t_ms")) event.put("t_ms", SystemClock.elapsedRealtime());
            eventSink.accept(event);
        } catch (Throwable ignored) {}
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) {}
    }

    private static final class VehicleReader {
        private IBinder service;

        int read(int device, int fid) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                IBinder autoservice = service();
                data.writeInterfaceToken(autoservice.getInterfaceDescriptor());
                data.writeInt(device);
                data.writeInt(fid);
                if (!autoservice.transact(5, data, reply, 0) || reply.dataAvail() < 8) return -1;
                int status = reply.readInt();
                int value = reply.readInt();
                return status == 0 ? value : -1;
            } catch (Exception ignored) {
                return -1;
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        private synchronized IBinder service() throws Exception {
            if (service == null || !service.isBinderAlive()) {
                service = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "autoservice");
            }
            if (service == null) throw new IllegalStateException("autoservice unavailable");
            return service;
        }
    }
}
