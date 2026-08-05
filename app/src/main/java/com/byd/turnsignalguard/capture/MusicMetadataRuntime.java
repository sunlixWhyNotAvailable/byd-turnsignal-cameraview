package com.byd.turnsignalguard.capture;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

final class MusicMetadataRuntime {
    static final long PUBLISH_DEBOUNCE_MS = 750;
    static final long SESSION_REPAIR_DEBOUNCE_MS = PUBLISH_DEBOUNCE_MS;
    static final int SOURCE_THIRD_PARTY = 26;
    static final int DEVICE_AUDIO = 1002;
    static final int DEVICE_INSTRUMENT = 1007;
    private static final int MUSIC_PLAYING = 1;
    private static final int MUSIC_PAUSED = 2;
    private static final int RADIO_OFF = 2;
    private static final int MAX_TEXT_BYTES = 254;
    private static final Set<String> OEM_MANAGED_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.server.telecom",
            "com.android.bluetooth",
            "com.byd.mediacenter",
            "com.byd.videoplay.youku",
            "com.byd.videoplay.youku.fse",
            "com.byd.videoplay",
            "com.byd.videoplay.fse",
            "com.byd.videoplay.hd",
            "com.tencent.qqmusiccar",
            "com.tencent.qqmusictv",
            "com.tencent.qqmusic",
            "com.netease.cloudmusic",
            "com.netease.cloudmusic.iot",
            "com.netease.cloudmusic.tv",
            "com.ximalaya.ting.android.car.byd",
            "com.ximalaya.ting.android",
            "bubei.tingshu.hd",
            "com.kugou.android.auto",
            "com.kugou.android",
            "cn.kuwo.kwmusiccar",
            "cn.kuwo.player",
            "cn.wenyu.bodian",
            "app.podcast.cosmos",
            "cmgyunting.vehicleplayer.cnr",
            "com.byd.synclink",
            "com.huawei.dmsdpdevice"));

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final AudioManager audioManager;
    private final ActivityManager activityManager;
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "byd-music-metadata");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<MediaSession.Token, SessionBinding> sessions = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener =
            controllers -> runOnHandler(() -> replaceSessions(controllers, "sessions_callback"));
    private final Runnable publishRunnable = this::publishSelectedSession;

    private MediaSessionManager sessionManager;
    private Method currentFocusPackage;
    private BydMediaWriter writer;
    private boolean enabled;
    private boolean awake;
    private boolean observersStarted;
    private boolean publishPending;
    private boolean forcePending;
    private boolean published;
    private boolean writeInFlight;
    private boolean terminalStop;
    private int lifecycleGeneration;
    private String pendingReason = "";
    private String lastFocusPackage = "";
    private String lastPublishedPackage = "";
    private String lastFingerprint = "";
    private String error = "";
    private WriteRequest queuedWrite;

    MusicMetadataRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.context = context;
        this.handler = handler;
        this.eventSink = eventSink;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    void configure(boolean value) {
        runOnHandler(() -> {
            enabled = value;
            if (enabled && awake) startObservers("configure");
            else if (!enabled) stopObservers("disabled", awake);
            emit("music_metadata_config", "enabled", enabled, "awake", awake,
                    "observer_active", observersStarted, "error", error);
        });
    }

    void powerStateChanged(boolean interactive) {
        runOnHandler(() -> {
            if (awake == interactive) return;
            awake = interactive;
            if (awake && enabled) startObservers("wake");
            else if (!awake) stopObservers("sleep", false);
        });
    }

    void audioPlaybackChanged(String reason) {
        runOnHandler(() -> {
            if (!observersStarted) return;
            boolean force = false;
            try {
                force = !currentAudioFocusPackage().equals(lastFocusPackage);
            } catch (Throwable ignored) {
            }
            schedulePublish(reason, force);
        });
    }

    void reportStatus() {
        runOnHandler(() -> emit("music_metadata_status",
                "enabled", enabled, "awake", awake,
                "observer_active", observersStarted,
                "session_count", sessions.size(),
                "focus_package", lastFocusPackage,
                "published", published,
                "write_in_flight", writeInFlight,
                "error", error));
    }

    void stop() {
        runOnHandler(() -> {
            terminalStop = true;
            enabled = false;
            stopObservers("shutdown", awake);
            if (!writeInFlight && queuedWrite == null) writerExecutor.shutdown();
        });
    }

    private void startObservers(String reason) {
        if (!enabled || !awake) return;
        if (observersStarted) {
            schedulePublish(reason + "_retry", shouldForceObserverRetry(reason));
            return;
        }
        try {
            ensureMediaFrameworkInitialized();
            sessionManager = (MediaSessionManager) context.getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
            if (sessionManager == null) {
                throw new IllegalStateException("MediaSessionManager unavailable");
            }
            if (audioManager == null) throw new IllegalStateException("AudioManager unavailable");
            currentFocusPackage = audioManager.getClass().getMethod(
                    "getCurrentAudioFocusPackage");
            sessionManager.addOnActiveSessionsChangedListener(sessionListener, null, handler);
            observersStarted = true;
            replaceSessions(sessionManager.getActiveSessions(null), reason);
            clearError();
            schedulePublish(reason, true);
        } catch (Throwable failure) {
            stopObservers(reason + "_failed", false);
            setError("observer_start: " + summary(failure), reason);
        }
    }

    private void stopObservers(String reason, boolean clearCard) {
        lifecycleGeneration++;
        handler.removeCallbacks(publishRunnable);
        publishPending = false;
        forcePending = false;
        pendingReason = "";
        if (observersStarted && sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionListener);
            } catch (Throwable failure) {
                setError("observer_stop: " + summary(failure), reason);
            }
        }
        observersStarted = false;
        for (SessionBinding binding : new ArrayList<>(sessions.values())) {
            binding.unregister();
        }
        sessions.clear();
        sessionManager = null;
        currentFocusPackage = null;
        queuedWrite = null;
        lastFocusPackage = "";
        lastPublishedPackage = "";
        lastFingerprint = "";
        if (clearCard && (published || writeInFlight)) {
            enqueueWrite(WriteRequest.cleanup(lifecycleGeneration, reason));
        } else {
            published = false;
        }
    }

    private void replaceSessions(List<MediaController> controllers, String reason) {
        if (!observersStarted) return;
        Map<MediaSession.Token, MediaController> next = new HashMap<>();
        if (controllers != null) {
            for (MediaController controller : controllers) {
                if (controller == null || controller.getSessionToken() == null) continue;
                next.put(controller.getSessionToken(), controller);
                if (!sessions.containsKey(controller.getSessionToken())) {
                    SessionBinding binding = new SessionBinding(controller);
                    sessions.put(controller.getSessionToken(), binding);
                    binding.register();
                }
            }
        }
        for (MediaSession.Token token : new ArrayList<>(sessions.keySet())) {
            if (next.containsKey(token)) continue;
            SessionBinding removed = sessions.remove(token);
            if (removed != null) removed.unregister();
        }
        schedulePublish(reason, false);
    }

    private void schedulePublish(String reason, boolean force) {
        if (!enabled || !awake || !observersStarted) return;
        forcePending = coalesceForce(forcePending, force);
        String nextReason = reason == null ? "unknown" : reason;
        if ("sessions_callback".equals(nextReason)
                || !"sessions_callback".equals(pendingReason)) {
            pendingReason = nextReason;
        }
        publishPending = true;
        handler.removeCallbacks(publishRunnable);
        handler.postDelayed(publishRunnable, publishDelayMs(pendingReason));
    }

    private void publishSelectedSession() {
        if (!publishPending || !enabled || !awake || !observersStarted) return;
        publishPending = false;
        boolean force = forcePending;
        forcePending = false;
        String reason = pendingReason;
        pendingReason = "";

        String focusPackage;
        try {
            focusPackage = currentAudioFocusPackage();
        } catch (Throwable failure) {
            setError("audio_focus: " + summary(failure), reason);
            return;
        }
        if (!focusPackage.equals(lastFocusPackage)) {
            emit("music_metadata_focus", "from", lastFocusPackage,
                    "to", focusPackage, "source_event", reason);
            lastFocusPackage = focusPackage;
            force = true;
        }
        if (isOemManagedPackage(focusPackage)) {
            relinquishToOem(reason);
            return;
        }
        String selectionPackage = focusPackage;
        if (selectionPackage.isEmpty() && isPackageProcessAlive(lastPublishedPackage)) {
            selectionPackage = lastPublishedPackage;
        }
        SessionBinding selected = selectSession(sessions.values(), selectionPackage);
        if (selected == null) {
            if (shouldRetainPublishedCard(false,
                    isPackageProcessAlive(focusPackage),
                    isPackageProcessAlive(lastPublishedPackage))) return;
            cleanupOwnedCard(reason + "_no_session");
            return;
        }
        Snapshot snapshot;
        try {
            snapshot = selected.snapshot();
        } catch (Throwable failure) {
            setError("session_snapshot: " + summary(failure), reason);
            return;
        }
        if (snapshot == null || snapshot.title.isEmpty() && snapshot.artist.isEmpty()) {
            if (shouldRetainPublishedCard(true, true,
                    isPackageProcessAlive(lastPublishedPackage))) return;
            cleanupOwnedCard(reason + "_empty_metadata");
            return;
        }
        boolean claimSource = shouldClaimSource(
                force, published, lastPublishedPackage, snapshot.packageName);
        boolean repair = shouldRepairPublishedSource(
                reason, published, lastPublishedPackage, snapshot.packageName);
        claimSource |= repair;
        if (!shouldPublish(force || repair, published,
                lastFingerprint, snapshot.fingerprint)) return;
        enqueueWrite(WriteRequest.publish(
                lifecycleGeneration, reason, snapshot, claimSource));
    }

    private void relinquishToOem(String reason) {
        boolean owned = published || writeInFlight || queuedWrite != null;
        if (owned) lifecycleGeneration++;
        queuedWrite = null;
        published = false;
        lastPublishedPackage = "";
        lastFingerprint = "";
        if (owned) {
            emit("music_metadata_relinquished", "focus_package", lastFocusPackage,
                    "source_event", reason);
        }
    }

    private void cleanupOwnedCard(String reason) {
        if (!published && !writeInFlight
                && (queuedWrite == null || queuedWrite.cleanup)) return;
        enqueueWrite(WriteRequest.cleanup(lifecycleGeneration, reason));
    }

    private void enqueueWrite(WriteRequest request) {
        queuedWrite = request;
        startNextWrite();
    }

    private void startNextWrite() {
        if (writeInFlight || queuedWrite == null) return;
        WriteRequest request = queuedWrite;
        queuedWrite = null;
        writeInFlight = true;
        writerExecutor.execute(() -> {
            String failure = "";
            try {
                if (writer == null) writer = new BydMediaWriter(context, eventSink);
                if (request.cleanup) writer.cleanup();
                else writer.publish(request.snapshot, request.claimSource);
            } catch (Throwable error) {
                failure = summary(error);
            }
            String result = failure;
            handler.post(() -> finishWrite(request, result));
        });
    }

    private void finishWrite(WriteRequest request, String failure) {
        writeInFlight = false;
        boolean current = request.generation == lifecycleGeneration;
        if (failure.isEmpty()) {
            if (current) {
                published = !request.cleanup;
                lastPublishedPackage = request.cleanup ? "" : request.snapshot.packageName;
                lastFingerprint = request.cleanup ? "" : request.snapshot.fingerprint;
                clearError();
            }
            if (request.cleanup) {
                emit("music_metadata_cleanup", "ok", true,
                        "source_event", request.reason, "stale", !current);
            } else {
                emit("music_metadata_publish", "ok", true,
                        "package", request.snapshot.packageName,
                        "title_present", !request.snapshot.title.isEmpty(),
                        "artist_present", !request.snapshot.artist.isEmpty(),
                        "title_bytes", request.snapshot.titleBytes,
                        "artist_bytes", request.snapshot.artistBytes,
                        "title_code_points", request.snapshot.titleCodePoints,
                        "artist_code_points", request.snapshot.artistCodePoints,
                        "normalization_changed",
                        request.snapshot.titleNormalizationChanged
                                || request.snapshot.artistNormalizationChanged,
                        "duration_ms", request.snapshot.durationMs,
                        "position_ms", request.snapshot.positionMs,
                        "progress", request.snapshot.progress,
                        "playing", request.snapshot.playing,
                        "source_event", request.reason,
                        "source_claimed", request.claimSource,
                        "stale", !current);
            }
        } else {
            setError("metadata_write: " + failure, request.reason);
        }
        if (queuedWrite != null) startNextWrite();
        else if (terminalStop) writerExecutor.shutdown();
    }

    private String currentAudioFocusPackage() throws Exception {
        if (audioManager == null || currentFocusPackage == null) {
            throw new IllegalStateException("audio focus API unavailable");
        }
        Object value = currentFocusPackage.invoke(audioManager);
        return value == null ? "" : value.toString();
    }

    private boolean isPackageProcessAlive(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (activityManager == null) return true;
        try {
            List<ActivityManager.RunningAppProcessInfo> processes =
                    activityManager.getRunningAppProcesses();
            if (processes == null) return true;
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process == null) continue;
                if (packageName.equals(process.processName)
                        || process.processName != null
                        && process.processName.startsWith(packageName + ":")) return true;
                if (process.pkgList == null) continue;
                for (String value : process.pkgList) {
                    if (packageName.equals(value)) return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void setError(String value, String reason) {
        error = value;
        emit("music_metadata_error", "error", value, "source_event", reason,
                "focus_package", lastFocusPackage, "published", published);
    }

    private void clearError() {
        error = "";
    }

    private void runOnHandler(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    static boolean isOemManagedPackage(String packageName) {
        return packageName != null && OEM_MANAGED_PACKAGES.contains(packageName);
    }

    static boolean shouldPublish(
            boolean force, boolean published, String previousFingerprint,
            String nextFingerprint) {
        return force || !published || !nextFingerprint.equals(previousFingerprint);
    }

    static boolean shouldClaimSource(
            boolean published, String previousPackage, String nextPackage) {
        return !published || nextPackage == null || !nextPackage.equals(previousPackage);
    }

    static boolean shouldClaimSource(
            boolean force, boolean published, String previousPackage, String nextPackage) {
        return force || shouldClaimSource(published, previousPackage, nextPackage);
    }

    static boolean shouldRepairPublishedSource(
            String reason, boolean published, String previousPackage, String nextPackage) {
        return published && "sessions_callback".equals(reason)
                && nextPackage != null && nextPackage.equals(previousPackage);
    }

    static boolean shouldForceObserverRetry(String reason) {
        return "configure".equals(reason);
    }

    static boolean coalesceForce(boolean pending, boolean requested) {
        return pending || requested;
    }

    static long publishDelayMs(String reason) {
        return "sessions_callback".equals(reason)
                ? SESSION_REPAIR_DEBOUNCE_MS : PUBLISH_DEBOUNCE_MS;
    }

    static boolean shouldRetainPublishedCard(
            boolean hasLiveSession, boolean focusedProcessAlive,
            boolean publishedProcessAlive) {
        return hasLiveSession || focusedProcessAlive || publishedProcessAlive;
    }

    static int progressPercent(long positionMs, long durationMs) {
        if (durationMs <= 0L) return 0;
        long position = Math.max(0L, Math.min(positionMs, durationMs));
        return (int) Math.max(0L, Math.min(100L,
                Math.round(position * 100.0d / durationMs)));
    }

    static int[] timeline(long positionMs, long durationMs) {
        int[] current = hms(positionMs);
        int[] total = hms(durationMs);
        return new int[]{current[0], current[1], current[2],
                total[0], total[1], total[2]};
    }

    static long estimatePosition(PlaybackState state, long durationMs, long nowMs) {
        if (state == null) return 0L;
        long value = Math.max(0L, state.getPosition());
        if (state.getState() == PlaybackState.STATE_PLAYING
                && state.getLastPositionUpdateTime() > 0L
                && nowMs > state.getLastPositionUpdateTime()
                && state.getPlaybackSpeed() != 0f) {
            value += Math.round((nowMs - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        return durationMs > 0L ? Math.min(value, durationMs) : value;
    }

    static byte[] utf16Le(String value) {
        String safe = normalizeText(value);
        while (safe.getBytes(StandardCharsets.UTF_16LE).length > MAX_TEXT_BYTES) {
            safe = safe.substring(0, safe.offsetByCodePoints(safe.length(), -1));
        }
        return safe.getBytes(StandardCharsets.UTF_16LE);
    }

    static String normalizeText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
    }

    private static int[] hms(long valueMs) {
        long seconds = Math.max(0L, valueMs / 1_000L);
        int hours = (int) Math.min(Integer.MAX_VALUE, seconds / 3_600L);
        return new int[]{hours, (int) (seconds / 60L % 60L), (int) (seconds % 60L)};
    }

    private static SessionBinding selectSession(
            Iterable<SessionBinding> candidates, String focusPackage) {
        if (focusPackage == null || focusPackage.isEmpty()) return null;
        SessionBinding best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SessionBinding binding : candidates) {
            if (!focusPackage.equals(binding.packageName)) continue;
            int score = binding.score();
            if (score > bestScore) {
                best = binding;
                bestScore = score;
            }
        }
        return best;
    }

    private static String firstText(MediaMetadata metadata, String... keys) {
        if (metadata == null) return "";
        for (String key : keys) {
            CharSequence value = metadata.getText(key);
            if (value != null && !value.toString().trim().isEmpty()) {
                return value.toString().trim();
            }
        }
        return "";
    }

    private static synchronized void ensureMediaFrameworkInitialized() throws Exception {
        Class<?> managerType = Class.forName("android.media.MediaServiceManager");
        Object manager = managerType.getConstructor().newInstance();
        for (String name : new String[]{
                "android.media.MediaFrameworkPlatformInitializer",
                "android.media.MediaFrameworkInitializer"}) {
            Class<?> initializer = Class.forName(name);
            Method getManager = initializer.getMethod("getMediaServiceManager");
            if (getManager.invoke(null) == null) {
                initializer.getMethod("setMediaServiceManager", managerType)
                        .invoke(null, manager);
            }
        }
    }

    private final class SessionBinding extends MediaController.Callback {
        final MediaController controller;
        final MediaSession.Token token;
        final String packageName;

        SessionBinding(MediaController controller) {
            this.controller = controller;
            token = controller.getSessionToken();
            packageName = controller.getPackageName() == null
                    ? "" : controller.getPackageName();
        }

        void register() {
            controller.registerCallback(this, handler);
        }

        void unregister() {
            try {
                controller.unregisterCallback(this);
            } catch (Throwable ignored) {
            }
        }

        int score() {
            PlaybackState state = controller.getPlaybackState();
            int value = controller.getMetadata() == null ? 0 : 1;
            if (state == null) return value;
            if (state.getState() == PlaybackState.STATE_PLAYING) return value + 4;
            if (state.getState() == PlaybackState.STATE_BUFFERING
                    || state.getState() == PlaybackState.STATE_CONNECTING) return value + 3;
            if (state.getState() == PlaybackState.STATE_PAUSED) return value + 2;
            return value;
        }

        Snapshot snapshot() {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            MediaDescription description = metadata == null ? null : metadata.getDescription();
            String title = firstText(metadata,
                    MediaMetadata.METADATA_KEY_TITLE,
                    MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title.isEmpty() && description != null && description.getTitle() != null) {
                title = description.getTitle().toString().trim();
            }
            String artist = firstText(metadata,
                    MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (artist.isEmpty() && description != null && description.getSubtitle() != null) {
                artist = description.getSubtitle().toString().trim();
            }
            long duration = metadata == null ? 0L
                    : Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            long position = estimatePosition(state, duration, SystemClock.elapsedRealtime());
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            return new Snapshot(packageName, title, artist, duration, position, playing);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            schedulePublish("metadata_callback", false);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            schedulePublish("playback_callback", false);
        }

        @Override
        public void onSessionDestroyed() {
            SessionBinding removed = sessions.remove(token);
            if (removed != null) removed.unregister();
            schedulePublish("session_destroyed", false);
        }
    }

    static final class Snapshot {
        final String packageName;
        final String title;
        final String artist;
        final long durationMs;
        final long positionMs;
        final boolean playing;
        final int progress;
        final int[] timeline;
        final String fingerprint;
        final boolean titleNormalizationChanged;
        final boolean artistNormalizationChanged;
        final int titleCodePoints;
        final int artistCodePoints;
        final int titleBytes;
        final int artistBytes;

        Snapshot(
                String packageName, String title, String artist,
                long durationMs, long positionMs, boolean playing) {
            this.packageName = packageName == null ? "" : packageName;
            String rawTitle = title == null ? "" : title;
            String rawArtist = artist == null ? "" : artist;
            this.title = normalizeText(rawTitle);
            this.artist = normalizeText(rawArtist);
            titleNormalizationChanged = !this.title.equals(rawTitle);
            artistNormalizationChanged = !this.artist.equals(rawArtist);
            titleCodePoints = this.title.codePointCount(0, this.title.length());
            artistCodePoints = this.artist.codePointCount(0, this.artist.length());
            titleBytes = utf16Le(this.title).length;
            artistBytes = utf16Le(this.artist).length;
            this.durationMs = Math.max(0L, durationMs);
            this.positionMs = Math.max(0L, positionMs);
            this.playing = playing;
            progress = progressPercent(this.positionMs, this.durationMs);
            timeline = timeline(this.positionMs, this.durationMs);
            fingerprint = this.packageName + '\u0000' + this.title + '\u0000' + this.artist
                    + '\u0000' + this.durationMs + '\u0000' + this.positionMs / 1_000L
                    + '\u0000' + this.playing;
        }
    }

    private static final class WriteRequest {
        final int generation;
        final String reason;
        final Snapshot snapshot;
        final boolean cleanup;
        final boolean claimSource;

        private WriteRequest(
                int generation, String reason, Snapshot snapshot,
                boolean cleanup, boolean claimSource) {
            this.generation = generation;
            this.reason = reason;
            this.snapshot = snapshot;
            this.cleanup = cleanup;
            this.claimSource = claimSource;
        }

        static WriteRequest publish(
                int generation, String reason, Snapshot snapshot, boolean claimSource) {
            return new WriteRequest(generation, reason, snapshot, false, claimSource);
        }

        static WriteRequest cleanup(int generation, String reason) {
            return new WriteRequest(generation, reason, null, true, false);
        }
    }

    private static final class BydMediaWriter {
        private final BiConsumer<String, Object[]> eventSink;
        private final Object autoManager;
        private final Method setInt;
        private final Method setBuffer;
        private final Method setIntArray;
        private final int sourceFid;
        private final int musicStateFid;
        private final int radioStateFid;
        private final int titleFid;
        private final int singerFid;
        private final int progressFid;
        private final int[] timeFids;

        @SuppressLint("WrongConstant")
        BydMediaWriter(Context context, BiConsumer<String, Object[]> eventSink) throws Exception {
            this.eventSink = eventSink;
            autoManager = context.getSystemService("auto");
            if (autoManager == null) throw new IllegalStateException("BYDAutoManager unavailable");
            setInt = autoManager.getClass().getMethod(
                    "setInt", int.class, int.class, int.class);
            setBuffer = autoManager.getClass().getMethod(
                    "setBuffer", int.class, int.class, byte[].class);
            setIntArray = autoManager.getClass().getMethod(
                    "setIntArray", int.class, int[].class, int[].class);
            sourceFid = feature("INSTRUMENT_MUSIC_SOURCE_SET");
            musicStateFid = feature("INSTRUMENT_MUSIC_STATE_SET");
            radioStateFid = feature("INSTRUMENT_RADIO_STATE_SET");
            titleFid = feature("INSTRUMENT_MUSIC_INFO_SET");
            singerFid = feature("AUDIO_ARMREST_SCREEN_SINGER_NAME_SET");
            progressFid = feature("INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET");
            timeFids = new int[]{
                    feature("AUDIO_PLAY_TIME_HOUR_SET"),
                    feature("AUDIO_PLAY_TIME_MINUTE_SET"),
                    feature("AUDIO_PLAY_TIME_SECOND_SET"),
                    feature("AUDIO_TOTAL_TIME_HOUR_SET"),
                    feature("AUDIO_TOTAL_TIME_MINUTE_SET"),
                    feature("AUDIO_TOTAL_TIME_SECOND_SET")};
        }

        void publish(Snapshot snapshot, boolean claimSource) throws Exception {
            if (claimSource) {
                writeInt(DEVICE_INSTRUMENT, sourceFid, SOURCE_THIRD_PARTY, "source");
                writeInt(DEVICE_INSTRUMENT, radioStateFid, RADIO_OFF, "radio_state");
            }
            writeBytes(DEVICE_INSTRUMENT, titleFid, utf16Le(snapshot.title), "title");
            writeBytes(DEVICE_AUDIO, singerFid, utf16Le(snapshot.artist), "artist");
            writeIntArray(DEVICE_AUDIO, timeFids, snapshot.timeline, "timeline");
            writeInt(DEVICE_INSTRUMENT, progressFid, snapshot.progress, "progress");
            writeInt(DEVICE_INSTRUMENT, musicStateFid,
                    snapshot.playing ? MUSIC_PLAYING : MUSIC_PAUSED, "music_state");
        }

        void cleanup() throws Exception {
            String failure = firstFailure(
                    safeInt(DEVICE_INSTRUMENT, sourceFid,
                            SOURCE_THIRD_PARTY, "cleanup_source"),
                    safeInt(DEVICE_INSTRUMENT, musicStateFid,
                            MUSIC_PAUSED, "cleanup_music_state"),
                    safeInt(DEVICE_INSTRUMENT, radioStateFid,
                            RADIO_OFF, "cleanup_radio_state"),
                    safeBytes(DEVICE_INSTRUMENT, titleFid,
                            utf16Le(" "), "cleanup_title"),
                    safeBytes(DEVICE_AUDIO, singerFid,
                            utf16Le(" "), "cleanup_artist"),
                    safeIntArray(DEVICE_AUDIO, timeFids,
                            new int[]{0, 0, 0, 0, 0, 0}, "cleanup_timeline"),
                    safeInt(DEVICE_INSTRUMENT, progressFid, 0, "cleanup_progress"));
            if (!failure.isEmpty()) {
                throw new IllegalStateException("cleanup incomplete: " + failure);
            }
        }

        private String safeInt(int device, int fid, int value, String field) {
            try {
                writeInt(device, fid, value, field);
                return "";
            } catch (Throwable failure) {
                return emitFailure(field, failure);
            }
        }

        private String safeBytes(int device, int fid, byte[] value, String field) {
            try {
                writeBytes(device, fid, value, field);
                return "";
            } catch (Throwable failure) {
                return emitFailure(field, failure);
            }
        }

        private String safeIntArray(
                int device, int[] fids, int[] values, String field) {
            try {
                writeIntArray(device, fids, values, field);
                return "";
            } catch (Throwable failure) {
                return emitFailure(field, failure);
            }
        }

        private void writeInt(int device, int fid, int value, String field) throws Exception {
            invoke(setInt, new Object[]{device, fid, value}, device, new int[]{fid},
                    field, "int", value);
        }

        private void writeBytes(int device, int fid, byte[] value, String field)
                throws Exception {
            invoke(setBuffer, new Object[]{device, fid, value}, device, new int[]{fid},
                    field, "bytes", value.length);
        }

        private void writeIntArray(
                int device, int[] fids, int[] values, String field) throws Exception {
            invoke(setIntArray, new Object[]{device, fids, values}, device, fids,
                    field, "int_array", Arrays.toString(values));
        }

        private void invoke(
                Method method, Object[] arguments, int device, int[] fids,
                String field, String payloadType, Object payload) throws Exception {
            Object result = method.invoke(autoManager, arguments);
            if (!(result instanceof Integer)) {
                throw new IllegalStateException("unexpected set result for " + field);
            }
            int status = (Integer) result;
            eventSink.accept("music_metadata_fid_write", new Object[]{
                    "field", field, "device", device,
                    "fids", Arrays.toString(fids),
                    "payload_type", payloadType, "payload", payload,
                    "framework_status", status});
            if (status != 0) {
                throw new IllegalStateException(field + " framework status " + status);
            }
        }

        private String emitFailure(String field, Throwable failure) {
            String value = summary(failure);
            eventSink.accept("music_metadata_cleanup_error", new Object[]{
                    "field", field, "error", value});
            return field + ": " + value;
        }

        private static String firstFailure(String... values) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) return value;
            }
            return "";
        }

        private static int feature(String name) throws Exception {
            Class<?> type = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            Field field = type.getField(name);
            return field.getInt(null);
        }
    }

    private static String summary(Throwable failure) {
        Throwable root = failure;
        while (root instanceof InvocationTargetException && root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
