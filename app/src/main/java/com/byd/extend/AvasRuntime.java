package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
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
    private final AvasPlaybackQueue queue;
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
    private final Set<String> pendingPrune = new HashSet<>();
    private volatile String activeAssetId = "";
    private String auditionProfileId = "";
    private String auditionAssetId = "";
    private volatile String auditionSessionId = "";

    AvasRuntime(Context context, int ownerUid, Consumer<JSONObject> eventSink) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (ownerUid <= 0) throw new IllegalArgumentException("ownerUid is invalid");
        this.context = context;
        this.ownerUid = ownerUid;
        this.eventSink = eventSink;
        queue = new AvasPlaybackQueue(SystemClock::elapsedRealtime, Process.myPid());
    }

    synchronized void start() {
        if (closed || started) return;
        started = true;
        config = loadConfig();
        // Recover a route left dirty by helper death even when no new sound is requested.
        try {
            player = new AvasAudioPlayer(context, this::emit);
        } catch (Exception failure) {
            // The dirty marker remains set; the existing next-play construction retries cleanup.
            event("avas_error", "stage", "recovery", "error", failure.toString());
        }
        playback.execute(this::playbackLoop);
        updatePolling();
        event("avas_runtime_started", "ownerUid", ownerUid);
        reportStatus();
    }

    synchronized void configure(AvasConfig next) {
        if (closed) return;
        if (next == null) throw new IllegalArgumentException("AVAS config is null");
        String previous = config.toJson();
        Set<String> deleted = assetIds(config);
        deleted.removeAll(assetIds(next));
        config = next;
        synchronized (policy) {
            policy.invalidateIneligible(skipEligible(next, "power_on"),
                    skipEligible(next, "power_off"));
        }
        if (!deleted.isEmpty()) {
            queue.removeAuditionsForAssets(deleted);
            pendingPrune.addAll(deleted);
            pruneDeletedAssets();
        }
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
        queue.stopAllAuditions();
        AvasConfig.Profile profile = config.profile(profileId);
        File selected = selectedReady(profile);
        if (selected == null) {
            event("avas_profile_skipped", "profile", profileId, "source", "manual",
                    "reason", "selected_asset_not_ready");
            reportStatus();
            return;
        }
        AvasPlaybackQueue.Request request = queue.enqueueExterior(profileId, true);
        if (request == null) {
            event("avas_profile_skipped", "profile", profileId, "source", "manual",
                    "reason", "already_queued_or_playing");
        } else {
            event(request, "avas_request_accepted", "accepted_t_ms", request.diagnostics.acceptedMs);
            event(request, "avas_queue_enqueued", "pending", queue.pendingCount());
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

    synchronized void startAudition(String profileId, String assetId, String sessionId) {
        if (closed) return;
        if (!validSessionId(sessionId)) {
            auditionError(profileId, assetId, sessionId, "invalid audition session id");
            reportStatus();
            return;
        }
        AvasConfig.Profile profile;
        try {
            profile = config.profile(profileId);
        } catch (Exception failure) {
            auditionError(profileId, assetId, sessionId, failure.toString());
            reportStatus();
            return;
        }
        if (!profileContains(profile, assetId)) {
            auditionError(profileId, assetId, sessionId, "asset is not in AVAS profile");
            reportStatus();
            return;
        }
        File file = assetFile(assetId);
        if (!file.isFile()) {
            auditionError(profileId, assetId, sessionId, "AVAS asset is not ready");
            reportStatus();
            return;
        }
        auditionProfileId = profileId;
        auditionAssetId = assetId;
        auditionSessionId = sessionId;
        AvasPlaybackQueue.Request request = queue.enqueueAudition(profileId, assetId, sessionId);
        if (request == null) {
            auditionError(profileId, assetId, sessionId, "exterior playback is busy");
        } else {
            event(request, "avas_request_accepted", "accepted_t_ms", request.diagnostics.acceptedMs);
            event(request, "avas_queue_enqueued", "asset", assetId, "session", sessionId,
                    "pending", queue.pendingCount());
        }
        reportStatus();
    }

    void stopAudition(String sessionId) {
        if (closed) return;
        if (!validSessionId(sessionId)) {
            auditionError("", "", sessionId, "invalid audition session id");
            return;
        }
        queue.stopAudition(sessionId);
        event("avas_audition_stopped", "session_id", sessionId);
        reportStatus();
    }

    void stopAllAuditions() {
        if (closed) return;
        queue.stopAllAuditions();
        event("avas_auditions_stopped");
        reportStatus();
    }

    /** Lock-free helper-death snapshot; callers stop only this captured session. */
    String auditionSessionId() { return auditionSessionId; }

    void reportStatus() {
        if (closed) return;
        try {
            JSONObject profiles = new JSONObject();
            for (String id : AvasConfig.PROFILE_IDS) profiles.put(id, queue.state(id));
            String profile;
            String asset;
            String session;
            synchronized (this) {
                profile = auditionProfileId;
                asset = auditionAssetId;
                session = auditionSessionId;
            }
            JSONObject audition = new JSONObject().put("profileId", profile)
                    .put("assetId", asset).put("sessionId", session)
                    .put("state", queue.auditionState(session));
            emit(new JSONObject().put("kind", "avas_status").put("profiles", profiles)
                    .put("audition", audition));
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
        // A dequeued request may have created the player after the pre-join stop snapshot.
        current = player;
        player = null;
        if (current != null) current.close();
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
            event(request, "avas_queue_dequeued", "pending", queue.pendingCount(),
                    "dequeued_t_ms", SystemClock.elapsedRealtime());
            try {
                play(request);
            } finally {
                queue.finish(request);
                event(request, "avas_request_cleanup", "cancelled", request.cancelled.get(),
                        "cleanup_t_ms", SystemClock.elapsedRealtime());
                reportStatus();
            }
        }
    }

    private void play(AvasPlaybackQueue.Request request) {
        AvasConfig.Profile profile = config.profile(request.profile);
        if (request.cancelled.get()) {
            event(request, "avas_play_cancel", "cancelled", true,
                    "finish_t_ms", SystemClock.elapsedRealtime(), "phase", "before_prepare");
            return;
        }
        File file = request.audition() ? assetFile(request.asset)
                : request.manual ? selectedReady(profile) : automaticReady(profile);
        if (file == null) {
            event(request, "avas_profile_skipped", "reason", "asset_not_ready");
            return;
        }
        setActiveAsset(assetId(file));
        if (!file.isFile()) {
            if (request.audition()) {
                auditionError(request.profile, request.asset, request.session,
                        "AVAS asset is not ready");
            }
            clearActiveAsset(assetId(file));
            return;
        }
        try {
            AvasAudioPlayer output = player;
            if (output == null) {
                output = new AvasAudioPlayer(context, this::emit);
                player = output;
            }
            event(request, "avas_play_start", "asset", assetId(file),
                    "gain", profile.volume);
            reportStatus();
            if (request.audition()) {
                output.playNavigation(file, profile.volume, request.cancelled::get,
                        request.diagnostics,
                        () -> config.profile(request.profile).volume);
            } else {
                output.play(file, profile.volume, request.cancelled::get,
                        request.diagnostics, request.kind,
                        () -> config.profile(request.profile).volume);
            }
            if (request.cancelled.get()) {
                event(request, "avas_play_cancel", "asset", assetId(file), "cancelled", true,
                        "finish_t_ms", SystemClock.elapsedRealtime());
            }
            event(request, "avas_play_finish", "asset", assetId(file),
                    "cancelled", request.cancelled.get(),
                    "finish_t_ms", SystemClock.elapsedRealtime());
        } catch (Exception failure) {
            event(request, "avas_play_error", "asset", assetId(file),
                    "error", failure.toString(), "error_t_ms", SystemClock.elapsedRealtime());
            if (request.audition()) {
                auditionError(request.profile, request.asset, request.session, failure.toString());
            } else if (request.manual) {
                event("avas_error", "stage", "manual_playback", "profile_id", request.profile,
                        "asset_id", assetId(file), "error", failure.toString());
            }
        } finally {
            clearActiveAsset(assetId(file));
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
        boolean onReady = skipEligible(config, "power_on");
        boolean offReady = skipEligible(config, "power_off");
        List<String> profiles;
        String suppressedProfile;
        String suppressionPowerProfile;
        long suppressionDeltaMs;
        synchronized (policy) {
            profiles = policy.sample(SystemClock.elapsedRealtime(), power, lock, onReady, offReady);
            suppressedProfile = policy.suppressedProfile();
            suppressionPowerProfile = policy.suppressionPowerProfile();
            suppressionDeltaMs = policy.suppressionDeltaMs();
        }
        for (String profile : profiles) enqueueAutomatic(profile);
        if (!suppressedProfile.isEmpty()) {
            event("avas_event_skipped", "profile", suppressedProfile,
                    "power_profile", suppressionPowerProfile,
                    "observed_delta_ms", suppressionDeltaMs,
                    "reason", "power_profile_concurrent_lock_unlock");
        }
    }

    private void enqueueAutomatic(String profileId) {
        if (!automaticEligible(profileId)) {
            event("avas_event_skipped", "profile", profileId, "reason", "disabled_or_not_ready");
            return;
        }
        AvasPlaybackQueue.Request request = queue.enqueueExterior(profileId, false);
        if (request != null) {
            event(request, "avas_event_accepted", "accepted_t_ms", request.diagnostics.acceptedMs);
            event(request, "avas_request_accepted", "accepted_t_ms", request.diagnostics.acceptedMs);
            event(request, "avas_queue_enqueued", "pending", queue.pendingCount());
            reportStatus();
        }
    }

    private boolean automaticEligible(String profileId) {
        AvasConfig.Profile profile = config.profile(profileId);
        return profile.enabled && automaticReady(profile) != null;
    }

    private boolean skipEligible(AvasConfig value, String profileId) {
        AvasConfig.Profile profile = value.profile(profileId);
        return profile.skipConcurrentLockUnlock && profile.enabled
                && automaticReady(profile) != null;
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
            if (AvasBuiltinSounds.isBuiltinAsset(asset.id)) continue;
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

    private static boolean profileContains(AvasConfig.Profile profile, String assetId) {
        if (!validAssetId(assetId)) return false;
        for (AvasConfig.Asset asset : profile.assets) if (asset.id.equals(assetId)) return true;
        return false;
    }

    private static boolean validSessionId(String sessionId) {
        return sessionId != null && sessionId.matches("[0-9a-f]{32}");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static Set<String> assetIds(AvasConfig value) {
        Set<String> ids = new HashSet<>();
        for (AvasConfig.Profile profile : value.profiles) {
            for (AvasConfig.Asset asset : profile.assets) ids.add(asset.id);
        }
        return ids;
    }

    private synchronized void setActiveAsset(String assetId) { activeAssetId = assetId; }

    private synchronized void clearActiveAsset(String assetId) {
        if (activeAssetId.equals(assetId)) activeAssetId = "";
        pruneDeletedAssets();
    }

    private void pruneDeletedAssets() {
        pendingPrune.removeIf(assetId -> {
            if (assetId.equals(activeAssetId)) return false;
            try {
                return Files.deleteIfExists(assetFile(assetId).toPath())
                        || !assetFile(assetId).exists();
            } catch (Exception failure) {
                event("avas_asset_error", "asset", assetId, "operation", "prune",
                        "error", failure.toString());
                return false;
            }
        });
    }

    private void auditionError(String profileId, String assetId, String sessionId, String error) {
        event("avas_error", "stage", "audition", "profile_id", safe(profileId),
                "asset_id", safe(assetId), "session_id", safe(sessionId), "error", error);
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

    private void event(AvasPlaybackQueue.Request request, String kind, Object... fields) {
        if (request == null) {
            event(kind, fields);
            return;
        }
        AvasAudioDiagnostics.Context context = request.diagnostics;
        Object[] correlated = new Object[fields.length + 12];
        Object[] identity = {"request", context.requestId, "profile", context.profile,
                "source", context.source, "helper_pid", context.helperPid,
                "accepted_t_ms", context.acceptedMs, "enqueued_t_ms", context.enqueuedMs};
        System.arraycopy(identity, 0, correlated, 0, identity.length);
        System.arraycopy(fields, 0, correlated, identity.length, fields.length);
        event(kind, correlated);
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
