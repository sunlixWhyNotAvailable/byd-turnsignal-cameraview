package com.byd.extend;

import com.byd.extend.remote.CameraViewingDiagnostics;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** App facade for the shell-owned native detector and fixed camera/screen controls. */
public final class MotionDetectorClient implements AutoCloseable, MotionMediaLibrary.Access {
    interface Listener {
        default void onPrepared(IBinder workerBinder) {}
        /** The worker applied or activated this config; this is distinct from native preparation. */
        default void onConfigured() {}
        /** True when the worker kept the current shared camera/viewing resources intact. */
        default void onConfigured(boolean retainViewing) { onConfigured(); }
        default void onConfigured(boolean retainViewing, String storageError) {
            if (storageError == null) onConfigured(retainViewing);
            else onError("storage_configure", storageError, SystemClock.elapsedRealtime());
        }
        default void onStatus(String state, String detail, long elapsedMs) {}
        default void onDetection(int level, long elapsedMs, int rawResult, int nativeState,
                long frameNumber, long frameTimestampNs, long warningUntilMs) {}
        default void onError(String stage, String detail, long elapsedMs) {}
        default void onScreenState(String state, String detail, long elapsedMs) {}
        default void onChargeState(CameraChargePolicy.State state) {}
    }

    static final class Status {
        final String state;
        final boolean prepared;
        final boolean eligible;
        final String lastError;
        final long elapsedMs;
        final String sessionId;
        final String configJson;
        final boolean warning;
        final boolean warningWindowDrawn;
        final CameraChargePolicy.State chargeState;

        Status(String state, boolean prepared, boolean eligible, String lastError, long elapsedMs,
                String sessionId, String configJson, boolean warning, boolean warningWindowDrawn,
                CameraChargePolicy.State chargeState) {
            this.state = state;
            this.prepared = prepared;
            this.eligible = eligible;
            this.lastError = lastError;
            this.elapsedMs = elapsedMs;
            this.sessionId = sessionId;
            this.configJson = configJson;
            this.warning = warning;
            this.warningWindowDrawn = warningWindowDrawn;
            this.chargeState = chargeState;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "motion-detector-client"));
    private final Object lock = new Object();
    private Callback callback = new Callback(0);
    private volatile String sessionId;
    private volatile String configJson;
    private volatile Listener listener;
    private volatile IBinder worker;
    private volatile boolean closed;
    private String storageConfigError;
    private static final Object STORAGE_APPLY_LOCK = new Object();
    private int requestGeneration;
    private int configureCompletionGeneration;
    private String configureCompletionToken;
    private String configureCompletionJson;
    private boolean awaitingConfigureCompletion;
    private boolean configureFailed;
    private boolean legacyIndexImported;
    private boolean legacyIndexAttempted;

    public MotionDetectorClient(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public int refresh() {
        ensureLegacyIndexImported();
        IBinder target = ensureMediaWorker();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!target.transact(MotionDetectorProtocol.TX_REFRESH_MEDIA, data, reply, 0))
                throw new IllegalStateException("Motion media refresh unavailable");
            reply.readException();
            int refreshed = reply.readInt();
            if (reply.dataAvail() != 0 || refreshed < 0)
                throw new IllegalStateException("Invalid Motion media refresh reply");
            return refreshed;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media refresh failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    @Override public String query(MotionMediaLibrary.Query query, MotionConfig config, String pageToken) {
        ensureLegacyIndexImported();
        IBinder target = ensureMediaWorker();
        MotionMediaLibrary.Query actual = query == null ? MotionMediaLibrary.Query.recent() : query;
        MotionConfig actualConfig = config == null ? MotionConfig.read(context) : config;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeInt(actual.startInclusiveMs == null ? 0 : 1);
            if (actual.startInclusiveMs != null) data.writeLong(actual.startInclusiveMs);
            data.writeInt(actual.endExclusiveMs == null ? 0 : 1);
            if (actual.endExclusiveMs != null) data.writeLong(actual.endExclusiveMs);
            data.writeString(actual.volumeId);
            data.writeInt(actual.sort == MotionMediaLibrary.Sort.NEWEST_FIRST ? 0 : 1);
            data.writeString(actualConfig.toJson());
            data.writeString(pageToken);
            if (!target.transact(MotionDetectorProtocol.TX_QUERY_MEDIA, data, reply, 0))
                throw new IllegalStateException("Motion media query unavailable");
            reply.readException();
            String encoded = reply.readString();
            if (encoded == null || encoded.length() > 256 * 1024 || reply.dataAvail() != 0)
                throw new IllegalStateException("Invalid Motion media query reply");
            return encoded;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media query failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    @Override public String read(String volumeId, long mediaStoreId) {
        ensureLegacyIndexImported();
        return mediaRecordTransaction(ensureMediaWorker(), MotionDetectorProtocol.TX_READ_MEDIA,
                volumeId, mediaStoreId);
    }

    @Override public boolean delete(String volumeId, long mediaStoreId) {
        ensureLegacyIndexImported();
        IBinder target = ensureMediaWorker();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(volumeId);
            data.writeLong(mediaStoreId);
            if (!target.transact(MotionDetectorProtocol.TX_DELETE_MEDIA, data, reply, 0))
                throw new IllegalStateException("Motion media delete unavailable");
            reply.readException();
            int deleted = reply.readInt();
            if ((deleted != 0 && deleted != 1) || reply.dataAvail() != 0)
                throw new IllegalStateException("Invalid Motion media delete reply");
            return deleted == 1;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media delete failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    /** Passive guard for lifecycle polling; it never starts, replaces, or configures a worker. */
    @Override public boolean isWorkerAvailable() {
        return existingMediaWorker() != null;
    }

    /** Opens an existing prepared cover only; tile requests never launch or import a worker. */
    @Override public ParcelFileDescriptor openThumbnail(String volumeId, long mediaStoreId,
            String expectedKey) {
        if (volumeId == null || volumeId.isEmpty() || mediaStoreId <= 0
                || !MotionThumbnailKey.isValid(expectedKey)) return null;
        IBinder target = existingMediaWorker();
        if (target == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        ParcelFileDescriptor result = null;
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(volumeId);
            data.writeLong(mediaStoreId);
            data.writeString(expectedKey);
            if (!target.transact(MotionDetectorProtocol.TX_OPEN_THUMBNAIL, data, reply, 0)) return null;
            reply.readException();
            result = reply.readTypedObject(ParcelFileDescriptor.CREATOR);
            if (reply.dataAvail() != 0) {
                if (result != null) try { result.close(); } catch (IOException ignored) { }
                return null;
            }
            return result;
        } catch (Throwable unavailable) {
            if (result != null) try { result.close(); } catch (IOException ignored) { }
            return null;
        } finally { data.recycle(); reply.recycle(); }
    }

    @Override public boolean importLegacyIndex(ParcelFileDescriptor source) throws IOException {
        if (source == null) throw new IOException("Legacy Motion index descriptor is missing");
        IBinder target;
        try { target = ensureMediaWorker(); }
        catch (RuntimeException error) { throw new IOException("Motion worker is unavailable", error); }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeTypedObject(source, 0);
            if (!target.transact(MotionDetectorProtocol.TX_IMPORT_MEDIA_INDEX, data, reply, 0))
                throw new IOException("Motion legacy-index import unavailable");
            reply.readException();
            int complete = reply.readInt();
            if ((complete != 0 && complete != 1) || reply.dataAvail() != 0)
                throw new IOException("Motion legacy-index import refused");
            return complete == 1;
        } catch (IOException error) { throw error; }
        catch (Throwable error) { throw new IOException("Motion legacy-index import failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    private String mediaRecordTransaction(IBinder target, int operation, String volumeId, long mediaStoreId) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(volumeId);
            data.writeLong(mediaStoreId);
            if (!target.transact(operation, data, reply, 0))
                throw new IllegalStateException("Motion media record operation unavailable");
            reply.readException();
            String encoded = reply.readString();
            if (encoded == null || encoded.length() > 64 * 1024 || reply.dataAvail() != 0)
                throw new IllegalStateException("Invalid Motion media record reply");
            return encoded;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Motion media record operation failed", error); }
        finally { data.recycle(); reply.recycle(); }
    }

    private void ensureLegacyIndexImported() {
        synchronized (lock) {
            if (legacyIndexImported || legacyIndexAttempted) return;
            legacyIndexAttempted = true;
        }
        File legacy = new File(new File(context.getFilesDir(), "motion"), "media-index.json");
        if (!legacy.isFile()) return;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(legacy,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            boolean complete = importLegacyIndex(descriptor);
            synchronized (lock) { legacyIndexImported = complete; }
        } catch (IOException error) {
            throw new IllegalStateException("Could not import the existing Motion media index", error);
        }
    }

    private static void configureStorage(IBinder target, String config) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(config);
            if (!target.transact(MotionDetectorProtocol.TX_CONFIGURE_STORAGE, data, reply, 0))
                throw new IllegalStateException("Motion storage configuration unavailable");
            reply.readException();
            if (reply.dataAvail() != 0) throw new IllegalStateException("Unexpected storage reply");
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Called on the service runtime thread; never starts a worker just to stop it. */
    static void suspendOemRetention(Context context) throws Exception {
        synchronized (STORAGE_APPLY_LOCK) { suspendStorageWorker(context); }
    }

    /** Notification only: never launches or reconfigures cameras or storage. */
    void recheckOemRetention(int reason) {
        try {
            // This handle was authenticated when configuration/attachment completed.
            IBinder candidate = worker;
            if (closed || candidate == null || !candidate.isBinderAlive()) return;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                data.writeInt(reason);
                if (!candidate.transact(MotionDetectorProtocol.TX_RECHECK_STORAGE, data, reply, 0))
                    throw new IllegalStateException("Motion storage notification unavailable");
                reply.readException();
                if (reply.dataAvail() != 0) throw new IllegalStateException("Unexpected storage notification reply");
            } finally { data.recycle(); reply.recycle(); }
        } catch (Exception failure) {
            throw new IllegalStateException("Motion storage notification failed", failure);
        }
    }

    private static void suspendStorageWorker(Context context) throws Exception {
        IBinder candidate = resolveWorker();
        if (candidate == null || !candidate.isBinderAlive()) return;
        WorkerIdentity identity = readIdentity(candidate);
        if (identity == null) throw new IllegalStateException("Cannot identify Motion worker during shutdown");
        if (identity.version < 18) return; // Older workers do not own independent retention.
        if (identity.version != MotionDetectorProtocol.VERSION
                || !currentApkIdentity(context).equals(identity.apk))
            throw new IllegalStateException("Unexpected Motion worker identity during shutdown");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!candidate.transact(MotionDetectorProtocol.TX_SUSPEND_STORAGE, data, reply, 0))
                throw new IllegalStateException("Motion storage shutdown unavailable");
            reply.readException();
            if (reply.dataAvail() != 0) throw new IllegalStateException("Unexpected storage shutdown reply");
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Opens the worker for fixed media operations; never configures monitoring. */
    private IBinder ensureMediaWorker() { return ensureWorker(true); }

    private IBinder existingMediaWorker() {
        if (closed) return null;
        try {
            IBinder candidate = resolveWorker();
            if (!healthy(candidate)) return null;
            worker = candidate;
            return candidate;
        } catch (Throwable unavailable) { return null; }
    }

    IBinder ensureCheckWorker() { return ensureWorker(false); }

    private IBinder ensureWorker(boolean replaceObsolete) {
        try {
            IBinder candidate = resolveWorker();
            WorkerIdentity identity = readIdentity(candidate);
            if (matchesCurrentApk(identity)) {
                worker = candidate;
                return candidate;
            }
            if (candidate != null && candidate.isBinderAlive()) {
                if (!replaceObsolete)
                    throw new IllegalStateException("obsolete Motion worker is left running; "
                            + "check-up cannot interrupt it; reopen the application to complete normal worker recovery");
                if (identity == null)
                    throw new IllegalStateException("cannot identify existing Motion worker");
                retireObsoleteWorker(requestGeneration, candidate, identity);
            }
            candidate = launchWorker(replaceObsolete ? "media" : "check");
            worker = candidate;
            return candidate;
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) {
            throw new IllegalStateException("Motion worker unavailable: " + summary(error), error);
        }
    }

    /** Persist settings in the shell worker, then attach this process as an optional UI observer. */
    void configure(String nextConfigJson, Listener nextListener) {
        if (nextConfigJson == null || nextConfigJson.length() > 8192)
            throw new IllegalArgumentException("valid Motion config is required");
        if (nextListener == null) throw new IllegalArgumentException("listener required");
        final int generation;
        final String token;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("motion client is closed");
            if (nextConfigJson.equals(configJson) && awaitingConfigureCompletion && !configureFailed) {
                listener = nextListener;
                return;
            }
            configJson = nextConfigJson;
            listener = nextListener;
            sessionId = null;
            generation = ++requestGeneration;
            if (callback != null) callback.deactivate();
            callback = new Callback(generation);
            token = UUID.randomUUID().toString().replace("-", "");
            configureCompletionGeneration = generation;
            configureCompletionToken = token;
            configureCompletionJson = nextConfigJson;
            awaitingConfigureCompletion = true;
            configureFailed = false;
            storageConfigError = null;
        }
        try { commands.execute(() -> configureOnWorker(generation, nextConfigJson, token)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() may win between publishing the session and queuing this task.
        }
    }

    private void configureOnWorker(int generation, String requestedConfig, String requestToken) {
        String id = "";
        boolean configuredAcknowledged = false;
        boolean retainViewing = false;
        boolean workerReplyValidated = false;
        Callback requestCallback = null;
        try {
            if (!isCurrentRequest(generation)) return;
            IBinder candidate = resolveWorker();
            WorkerIdentity identity = readIdentity(candidate);
            if (!matchesCurrentApk(identity)) {
                retireObsoleteWorker(generation, candidate, identity);
                if (!isCurrentRequest(generation)) return;
                candidate = launchWorker("configure:" + requestToken);
                identity = readIdentity(candidate);
            }
            if (!isCurrentRequest(generation)) return;
            if (!matchesCurrentApk(identity))
                throw new IllegalStateException("motion shell worker readiness/protocol mismatch");
            synchronized (lock) {
                if (closed || generation != requestGeneration || callback == null
                        || callback.generation != generation) return;
                requestCallback = callback;
                requestCallback.bind(candidate, identity.pid);
                worker = candidate;
            }
            try {
                synchronized (STORAGE_APPLY_LOCK) {
                    if (!isCurrentRequest(generation) || GuardRecovery.isUserShutdownActive(context)) return;
                    configureStorage(candidate, requestedConfig);
                }
            } catch (Exception failure) {
                CameraViewingDiagnostics.failure("oem_retention", "configure request=" + requestToken, failure);
                synchronized (lock) {
                    if (generation == requestGeneration) storageConfigError = summary(failure)
                            + " (request " + requestToken + ")";
                }
            }
            // Storage failure must not prevent a camera OFF command or its acknowledgement.
            if (!isCurrentRequest(generation) || GuardRecovery.isUserShutdownActive(context)) return;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                data.writeInt(MotionDetectorProtocol.VERSION);
                data.writeInt(BuildConfig.VERSION_CODE);
                data.writeString(requestedConfig);
                data.writeString(requestToken);
                data.writeStrongBinder(requestCallback);
                if (!candidate.transact(MotionDetectorProtocol.TX_CONFIGURE, data, reply, 0))
                    throw new IllegalStateException("motion worker rejected config transaction");
                reply.readException();
                if (reply.readInt() != 1) throw new IllegalStateException("motion worker refused configuration");
                id = reply.readString();
                IBinder returned = reply.readStrongBinder();
                int applyKind = reply.readInt();
                if (applyKind != MotionDetectorProtocol.CONFIG_PENDING
                        && applyKind != MotionDetectorProtocol.CONFIG_APPLIED
                        && applyKind != MotionDetectorProtocol.CONFIG_MODE_ONLY
                        && applyKind != MotionDetectorProtocol.CONFIG_RETAINED)
                    throw new IllegalStateException("invalid Motion configure result");
                configuredAcknowledged = applyKind != MotionDetectorProtocol.CONFIG_PENDING;
                retainViewing = applyKind == MotionDetectorProtocol.CONFIG_MODE_ONLY
                        || applyKind == MotionDetectorProtocol.CONFIG_RETAINED;
                if (returned == null || !returned.isBinderAlive())
                    throw new IllegalStateException("motion worker Binder unavailable");
                WorkerIdentity returnedIdentity = readIdentity(returned);
                if (!matchesCurrentApk(returnedIdentity) || returnedIdentity.pid != identity.pid)
                    throw new SecurityException("Motion configure reply came from a changed worker");
                worker = candidate;
                if (reply.dataAvail() != 0)
                    throw new IllegalStateException("Motion configure reply has trailing data");
            } finally {
                data.recycle();
                reply.recycle();
            }
            if (!isCurrentRequest(generation)) return;
            if (id == null || id.isEmpty()) {
                synchronized (lock) { sessionId = null; }
                boolean enabled = MotionConfig.parse(requestedConfig).anyEnabled();
                if (retainViewing) throw new IllegalStateException("retained Motion result has no active session");
                if (enabled && configuredAcknowledged) {
                    configuredAcknowledged = false;
                    throw new IllegalStateException("worker reported an applied config without a session");
                }
                workerReplyValidated = true;
                reportRequestStatus(generation, !configuredAcknowledged ? "configured_pending" : "configured_disabled",
                        !configuredAcknowledged ? "latest Motion settings are persisted; waiting for resource cleanup"
                                : "monitoring disabled in shell; media-only worker remains available",
                        SystemClock.elapsedRealtime());
                if (!enabled && configuredAcknowledged) reportRequestConfigured(generation, requestToken, false);
                return;
            }
            if (!MotionDetectorProtocol.validSession(id))
                throw new IllegalStateException("motion worker returned an invalid session ID");
            workerReplyValidated = true;
            synchronized (lock) { sessionId = id; }
            if (configuredAcknowledged && workerReplyValidated)
                reportRequestConfigured(generation, requestToken, retainViewing);
        } catch (Throwable error) {
            if (requestCallback != null && !isCurrentCallback(requestCallback))
                requestCallback.deactivate();
            if (configuredAcknowledged && workerReplyValidated)
                reportRequestConfigured(generation, requestToken, retainViewing);
            else clearConfigureRequest(generation, requestToken);
            reportRequestError(generation, id, "configure", summary(error));
        }
    }

    static boolean validConfiguredSession(String id, String json, int retained) {
        if (id == null || json == null || json.length() > 8192 || retained < 0 || retained > 1)
            return false;
        try {
            boolean enabled = MotionConfig.parse(json).anyEnabled();
            return enabled ? MotionDetectorProtocol.validSession(id) : id.isEmpty() && retained == 0;
        } catch (Exception invalid) { return false; }
    }

    private void attachConfiguredSession(int generation, String requestToken, String id,
            String requestedConfig, int workerPid, boolean retainViewing) {
        if (!isPendingConfigure(generation, requestToken, requestedConfig)) return;
        boolean confirmedWorker = false;
        try {
            IBinder candidate = resolveWorker();
            WorkerIdentity identity = readIdentity(candidate);
            if (!matchesCurrentApk(identity) || identity.pid != workerPid)
                throw new IllegalStateException("configured Motion worker is unavailable or changed");
            worker = candidate;
            synchronized (lock) {
                if (!matchesPendingConfigureLocked(generation, requestToken, requestedConfig)) return;
                if (callback == null || callback.generation != generation
                        || !callback.matchesWorker(workerPid))
                    throw new SecurityException("configured Motion callback worker changed");
                sessionId = id.isEmpty() ? null : id;
            }
            confirmedWorker = true;
            reportRequestConfigured(generation, requestToken, retainViewing);
        } catch (Throwable error) {
            if (confirmedWorker) reportRequestConfigured(generation, requestToken, retainViewing);
            else clearConfigureRequest(generation, requestToken);
            reportRequestError(generation, id, "configure_attach", summary(error));
        }
    }

    private boolean isPendingConfigure(int generation, String requestToken, String requestedConfig) {
        synchronized (lock) {
            return matchesPendingConfigureLocked(generation, requestToken, requestedConfig);
        }
    }

    private boolean matchesPendingConfigureLocked(int generation, String requestToken,
            String requestedConfig) {
        return !closed && awaitingConfigureCompletion && generation == requestGeneration
                && generation == configureCompletionGeneration
                && requestToken.equals(configureCompletionToken)
                && requestedConfig.equals(configureCompletionJson);
    }

    private void queueConfiguredSession(String requestToken, String id, String requestedConfig,
            int workerPid, boolean retainViewing) {
        final int generation;
        synchronized (lock) {
            if (closed || !awaitingConfigureCompletion
                    || !requestToken.equals(configureCompletionToken)
                    || !requestedConfig.equals(configureCompletionJson)) return;
            generation = configureCompletionGeneration;
        }
        try {
            commands.execute(() -> attachConfiguredSession(generation, requestToken, id,
                    requestedConfig, workerPid, retainViewing));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() may win before the confirmed-session attachment can be queued.
        }
    }

    private void reportRequestConfigured(int generation, String requestToken, boolean retainViewing) {
        final Listener target;
        final String storageError;
        synchronized (lock) {
            if (closed || !awaitingConfigureCompletion || generation != requestGeneration
                    || generation != configureCompletionGeneration
                    || !requestToken.equals(configureCompletionToken)) return;
            awaitingConfigureCompletion = false;
            configureCompletionToken = null;
            configureCompletionJson = null;
            target = listener;
            storageError = storageConfigError;
        }
        if (target != null) main.post(() -> {
            if (isCurrentRequest(generation)) target.onConfigured(retainViewing, storageError);
        });
    }

    private void clearConfigureRequest(int generation, String requestToken) {
        synchronized (lock) {
            if (!awaitingConfigureCompletion || generation != requestGeneration
                    || generation != configureCompletionGeneration
                    || !requestToken.equals(configureCompletionToken)) return;
            awaitingConfigureCompletion = false;
            configureCompletionToken = null;
            configureCompletionJson = null;
        }
    }

    private boolean isCurrentCallback(Callback expected) {
        synchronized (lock) {
            return !closed && callback == expected && requestGeneration == expected.generation
                    && expected.isBoundAndAlive();
        }
    }

    void status(String id, Consumer<Status> result) {
        if (!isCurrent(id)) return;
        try {
            commands.execute(() -> {
                try {
                    IBinder target = requireWorker(id);
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                        if (!target.transact(MotionDetectorProtocol.TX_STATUS, data, reply, 0))
                            throw new IllegalStateException("motion status transaction unavailable");
                        reply.readException();
                        Status status = readStatusReply(reply);
                        if (result != null) main.post(() -> result.accept(status));
                    } finally { data.recycle(); reply.recycle(); }
                } catch (Throwable error) { reportError(id, "status", summary(error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing won before this read-only status request could be queued.
        }
    }

    static Status readStatus(Context context) throws Exception {
        Context app = context.getApplicationContext();
        IBinder candidate = resolveWorker();
        WorkerIdentity identity = readIdentity(candidate);
        if (candidate == null || identity == null || identity.version != MotionDetectorProtocol.VERSION
                || identity.build != BuildConfig.VERSION_CODE
                || !currentApkIdentity(app).equals(identity.apk))
            throw new IllegalStateException("current Motion worker is unavailable or obsolete");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!candidate.transact(MotionDetectorProtocol.TX_STATUS, data, reply, 0))
                throw new IllegalStateException("Motion status unavailable");
            reply.readException();
            return readStatusReply(reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    static CameraChargePolicy.State readChargeState(Context context) throws Exception {
        return readChargeState(context, false);
    }

    static CameraChargePolicy.State readChargeState(Context context, boolean forceFresh)
            throws Exception {
        Context application = context.getApplicationContext();
        Context app = application == null ? context : application;
        IBinder candidate = resolveWorker();
        WorkerIdentity identity = readIdentity(candidate);
        if (candidate == null || identity == null || identity.version != MotionDetectorProtocol.VERSION
                || identity.build != BuildConfig.VERSION_CODE
                || !currentApkIdentity(app).equals(identity.apk))
            throw new IllegalStateException("current Motion worker is unavailable or obsolete");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            int transaction = forceFresh ? MotionDetectorProtocol.TX_READ_CHARGE
                    : MotionDetectorProtocol.TX_STATUS;
            if (!candidate.transact(transaction, data, reply, 0))
                throw new IllegalStateException("shared camera charge status unavailable");
            reply.readException();
            CameraChargePolicy.State state;
            if (forceFresh) state = MotionDetectorProtocol.readChargeState(reply);
            else state = readStatusReply(reply).chargeState;
            if (reply.dataAvail() != 0)
                throw new IllegalStateException("shared camera charge reply has trailing data");
            CameraChargeTelemetry.publish(state);
            return state;
        } finally { data.recycle(); reply.recycle(); }
    }

    static boolean prepareRemoteView(Context context, IBinder owner, String id) throws Exception {
        if (owner == null || !MotionDetectorProtocol.validSession(id))
            throw new IllegalArgumentException("remote view owner and 32-character session are required");
        IBinder target = verifiedWorker(context, CameraViewingShellProtocol.currentApkIdentity(context));
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(owner);
            if (!target.transact(MotionDetectorProtocol.TX_VIEW_PREPARE, data, reply, 0))
                throw new IllegalStateException("remote camera preparation unavailable");
            reply.readException();
            int ready = reply.readInt();
            if ((ready != 0 && ready != 1) || reply.dataAvail() != 0)
                throw new IllegalStateException("invalid remote camera preparation reply");
            return ready == 1;
        } finally { data.recycle(); reply.recycle(); }
    }

    static void releaseRemoteView(Context context, IBinder owner, String id) throws Exception {
        if (owner == null || !MotionDetectorProtocol.validSession(id))
            throw new IllegalArgumentException("remote view owner and 32-character session are required");
        IBinder target = verifiedWorker(context, CameraViewingShellProtocol.currentApkIdentity(context));
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(owner);
            if (!target.transact(MotionDetectorProtocol.TX_VIEW_RELEASE, data, reply, 0))
                throw new IllegalStateException("remote camera release unavailable");
            reply.readException();
            if (reply.readInt() != 1 || reply.dataAvail() != 0)
                throw new IllegalStateException("invalid remote camera release reply");
        } finally { data.recycle(); reply.recycle(); }
    }

    private static IBinder verifiedWorker(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("context required");
        Context application = context.getApplicationContext();
        Context app = application == null ? context : application;
        return verifiedWorker(context, currentApkIdentity(app));
    }

    static IBinder verifiedWorker(Context context, String expectedApkIdentity) throws Exception {
        if (context == null || expectedApkIdentity == null || expectedApkIdentity.isEmpty())
            throw new IllegalArgumentException("context and expected APK identity are required");
        IBinder candidate = resolveWorker();
        WorkerIdentity identity = readIdentity(candidate);
        if (candidate == null || identity == null || identity.version != MotionDetectorProtocol.VERSION
                || identity.build != BuildConfig.VERSION_CODE
                || !expectedApkIdentity.equals(identity.apk))
            throw new IllegalStateException("current Motion worker is unavailable or obsolete");
        return candidate;
    }

    static int verifiedWorkerPid(IBinder candidate, String expectedApkIdentity) throws Exception {
        WorkerIdentity identity = readIdentity(candidate);
        if (identity == null || identity.version != MotionDetectorProtocol.VERSION
                || identity.build != BuildConfig.VERSION_CODE || identity.pid <= 0
                || !expectedApkIdentity.equals(identity.apk))
            throw new IllegalStateException("current Motion worker identity is invalid");
        return identity.pid;
    }

    /** Notify only an already-running, APK-verified worker that the camera host is attached. */
    static void notifyCameraHostReady(Context context) {
        if (context == null || android.os.Process.myUid() == 2000) return;
        try {
            Context application = context.getApplicationContext();
            Context app = application == null ? context : application;
            int ownerUid = app.getPackageManager().getApplicationInfo(
                    CameraViewingShellProtocol.OWNER_PACKAGE, 0).uid;
            if (ownerUid != android.os.Process.myUid())
                throw new SecurityException("Extend package UID changed before camera-ready notification");
            IBinder candidate = resolveWorker();
            if (candidate == null || !candidate.isBinderAlive()) return;
            WorkerIdentity identity = readIdentity(candidate);
            if (identity == null || identity.version != MotionDetectorProtocol.VERSION
                    || identity.build != BuildConfig.VERSION_CODE
                    || !currentApkIdentity(app).equals(identity.apk))
                throw new SecurityException("camera-ready target is not the current Motion worker");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
                if (!candidate.transact(MotionDetectorProtocol.TX_CAMERA_HOST_READY, data, reply, 0))
                    throw new IllegalStateException("Motion worker rejected camera-host readiness event");
                reply.readException();
                if (reply.dataAvail() != 0)
                    throw new IllegalStateException("Motion camera-host readiness reply has trailing data");
            } finally { data.recycle(); reply.recycle(); }
        } catch (Throwable failure) {
            com.byd.extend.remote.CameraViewingDiagnostics.failure(
                    "camera_host_ready_notify", "motion_worker", failure);
        }
    }

    public static boolean isLiveWarningSession(Context context, String id) {
        if (!MotionDetectorProtocol.validSession(id)) return false;
        try {
            Status status = readStatus(context);
            return id.equals(status.sessionId) && status.warning && !status.lastError.equals("host_shutdown");
        } catch (Throwable ignored) { return false; }
    }

    private static Status readStatusReply(Parcel reply) {
        String state = reply.readString();
        boolean prepared = reply.readInt() == 1;
        boolean eligible = reply.readInt() == 1;
        String error = reply.readString();
        long elapsed = reply.readLong();
        String id = reply.readString();
        String config = reply.readString();
        boolean warning = reply.readInt() == 1;
        boolean drawn = reply.readInt() == 1;
        CameraChargePolicy.State chargeState = MotionDetectorProtocol.readChargeState(reply);
        if (reply.dataAvail() != 0) throw new IllegalStateException("Motion status reply has trailing data");
        return new Status(state, prepared, eligible, error, elapsed, id, config, warning, drawn,
                chargeState);
    }

    private IBinder requireWorker(String id) throws Exception {
        if (!isCurrent(id)) throw new IllegalStateException("motion session expired");
        IBinder target = worker;
        if (!healthy(target)) throw new IllegalStateException("motion shell worker disconnected");
        return target;
    }

    private IBinder launchWorker(String operation) throws IOException {
        String reference = "motion-launch-" + UUID.randomUUID();
        java.util.function.BiConsumer<String, Object[]> diagnostics = (event, fields) -> {
            Object[] safe = AdbRecoveryDiagnostics.transportFields(event, fields);
            Object[] correlated = java.util.Arrays.copyOf(safe, safe.length + 4);
            correlated[safe.length] = "request";
            correlated[safe.length + 1] = reference;
            correlated[safe.length + 2] = "launch_operation";
            correlated[safe.length + 3] = operation;
            AvasRecoveryJournal.event(context, "motion_worker_launch_transport", correlated);
        };
        try {
            LocalAdbClient.Result launched = LocalAdbClient.executeAuthorized(
                    context, launchCommand(context), diagnostics);
            IOException launchFailure = launched.ok ? null : new IOException(
                    "ADB launch: " + AdbRecoveryDiagnostics.safeText(launched.error)
                            + "; exit_code=" + launched.exitCode
                            + "; transport_unavailable=" + launched.transportUnavailable
                            + "; command_read_timeout=" + launched.commandReadTimeout
                            + "; output=" + AdbRecoveryDiagnostics.safeText(launched.output));
            if (launchFailure != null && !launched.commandReadTimeout) throw launchFailure;
            try {
                IBinder ready = awaitWorker(6_000L);
                AvasRecoveryJournal.event(context, "motion_worker_launch_ready",
                        "request", reference, "operation", operation,
                        "command_read_timeout", launched.commandReadTimeout);
                return ready;
            } catch (Exception readinessFailure) {
                if (launchFailure == null) throw readinessFailure;
                launchFailure.addSuppressed(readinessFailure);
                throw launchFailure;
            }
        } catch (Exception failure) {
            AdbRecoveryDiagnostics.failure(
                    (event, fields) -> AvasRecoveryJournal.event(context, event, fields),
                    "motion_worker_launch_failed", operation, failure,
                    "request", reference, "diagnostic_ref", reference);
            throw new IOException("Motion worker launch failed (diagnostic ref " + reference + "): "
                    + AdbRecoveryDiagnostics.safeText(summary(failure)), failure);
        }
    }

    private IBinder awaitWorker(long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            IBinder candidate = resolveWorker();
            if (healthy(candidate)) return candidate;
            SystemClock.sleep(100L);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IllegalStateException("motion worker did not become ready before timeout");
    }

    private boolean healthy(IBinder candidate) {
        return matchesCurrentApk(readIdentity(candidate));
    }

    private boolean matchesCurrentApk(WorkerIdentity identity) {
        return identity != null && identity.version == MotionDetectorProtocol.VERSION
                && identity.build == BuildConfig.VERSION_CODE
                && currentApkIdentity(context).equals(identity.apk);
    }

    private static WorkerIdentity readIdentity(IBinder candidate) {
        if (candidate == null || !candidate.isBinderAlive()) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            if (!candidate.transact(MotionDetectorProtocol.TX_PING, data, reply, 0)) return null;
            reply.readException();
            int version = reply.readInt();
            int build = reply.readInt();
            int uid = reply.readInt();
            int pid = reply.readInt();
            String identity = reply.readString();
            if (uid != 2000 || pid <= 0 || identity == null || identity.isEmpty()
                    || reply.dataAvail() != 0) return null;
            return new WorkerIdentity(version, build, pid, identity);
        } catch (Throwable ignored) {
            return null;
        } finally { data.recycle(); reply.recycle(); }
    }

    private void retireObsoleteWorker(int generation, IBinder candidate, WorkerIdentity old) throws Exception {
        if (candidate == null || !candidate.isBinderAlive()) return;
        if (old == null) {
            throw new IllegalStateException("cannot identify existing Motion worker for replacement");
        }
        // The first released worker has no shutdown transaction; use the existing authorized shell.
        reportRequestStatus(generation, "worker_replacing", "pid=" + old.pid + " protocol=" + old.version
                + " build=" + old.build + " old_apk=" + old.apk
                + " new_apk=" + currentApkIdentity(context), SystemClock.elapsedRealtime());
        LocalAdbClient.Result stopped = LocalAdbClient.executeAuthorized(context,
                stopObsoleteWorkerCommand(old.pid), (kind, fields) -> { });
        if (!stopped.ok) throw new IllegalStateException("obsolete Motion worker stop failed: "
                + stopped.error + "; " + stopped.output);
        reportRequestStatus(generation, "obsolete_worker_stopped", "old_pid=" + old.pid,
                SystemClock.elapsedRealtime());
    }

    static String stopObsoleteWorkerCommand(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("positive worker PID required");
        String proc = "/proc/" + pid;
        return "if [ ! -d " + proc + " ]; then exit 0; fi; "
                + "if [ \"$(tr '\\000' '\\n' <" + proc + "/cmdline | head -n 1)\" != '"
                + MotionDetectorProtocol.PROCESS_NAME + "' ] || ! grep -Eq "
                + "'^Uid:[[:space:]]+2000[[:space:]]+2000[[:space:]]+2000[[:space:]]+2000$' "
                + proc + "/status; then echo motion_worker_identity_changed; exit 1; fi; "
                + "kill -TERM " + pid + " || exit 1; "
                + "wait_count=0; while [ -d " + proc + " ] && [ \"$wait_count\" -lt 30 ]; "
                + "do sleep 0.1; wait_count=$((wait_count + 1)); done; "
                + "if [ -d " + proc + " ]; then echo motion_worker_stop_timeout; exit 1; fi";
    }

    private static final class WorkerIdentity {
        final int version, build, pid;
        final String apk;
        WorkerIdentity(int version, int build, int pid, String apk) {
            this.version = version;
            this.build = build;
            this.pid = pid;
            this.apk = apk;
        }
    }

    private static IBinder resolveWorker() throws Exception {
        return service(MotionDetectorProtocol.SERVICE_NAME);
    }

    private static IBinder service(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private boolean isCurrent(String id) {
        return !closed && id != null && id.equals(sessionId);
    }

    private boolean isCurrentRequest(int generation) {
        synchronized (lock) { return !closed && generation == requestGeneration; }
    }

    private void reportRequestStatus(int generation, String state, String detail, long elapsedMs) {
        Listener target = listener;
        if (!isCurrentRequest(generation) || target == null) return;
        main.post(() -> {
            if (isCurrentRequest(generation)) target.onStatus(state, detail, elapsedMs);
        });
    }

    private void reportRequestError(int generation, String id, String stage, String detail) {
        Listener target = listener;
        if (!isCurrentRequest(generation) || target == null) return;
        long now = SystemClock.elapsedRealtime();
        main.post(() -> { if (isCurrentRequest(generation)) target.onError(stage, detail, now); });
    }

    private void reportPrepared(String id, IBinder binder) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> { if (isCurrent(id)) target.onPrepared(binder); });
    }

    private void reportStatus(String id, String state, String detail, long elapsedMs) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> {
            if (!isCurrent(id)) return;
            target.onStatus(state, detail, elapsedMs);
            if ("stopped".equals(state)) {
                synchronized (lock) {
                    if (id.equals(sessionId)) {
                        sessionId = null;
                        worker = null;
                        listener = null;
                    }
                }
            }
        });
    }

    private void reportDetection(String id, int level, long elapsedMs, int rawResult,
            int nativeState, long frameNumber, long frameTimestampNs, long warningUntilMs) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> { if (isCurrent(id)) target.onDetection(level, elapsedMs, rawResult,
                nativeState, frameNumber, frameTimestampNs, warningUntilMs); });
    }

    private void reportError(String id, String stage, String detail) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        long now = SystemClock.elapsedRealtime();
        main.post(() -> { if (isCurrent(id)) target.onError(stage, detail, now); });
    }

    private void reportScreen(String id, String state, String detail, long elapsedMs) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> { if (isCurrent(id)) target.onScreenState(state, detail, elapsedMs); });
    }

    private void reportChargeState(String id, CameraChargePolicy.State state) {
        Listener target = listener;
        if (!isCurrent(id) || target == null) return;
        main.post(() -> {
            if (!isCurrent(id)) return;
            CameraChargeTelemetry.publish(state);
            target.onChargeState(state);
        });
    }

    private final class Callback extends Binder {
        final int generation;
        private volatile IBinder boundWorker;
        private volatile int boundWorkerPid;
        private volatile boolean active;
        private final IBinder.DeathRecipient workerDeath = this::workerDied;

        Callback(int generation) { this.generation = generation; }

        void bind(IBinder owner, int pid) throws RemoteException {
            if (owner == null || !owner.isBinderAlive() || pid <= 0)
                throw new IllegalArgumentException("verified Motion worker capability required");
            owner.linkToDeath(workerDeath, 0);
            boundWorker = owner;
            boundWorkerPid = pid;
            active = true;
        }

        boolean matchesWorker(int pid) {
            IBinder owner = boundWorker;
            return active && pid > 0 && pid == boundWorkerPid
                    && owner != null && owner.isBinderAlive();
        }

        boolean isBoundAndAlive() { return matchesWorker(boundWorkerPid); }

        void deactivate() {
            active = false;
            IBinder owner = boundWorker;
            boundWorker = null;
            boundWorkerPid = 0;
            if (owner != null) {
                try { owner.unlinkToDeath(workerDeath, 0); } catch (Throwable ignored) { }
            }
        }

        private void workerDied() {
            boolean notify;
            synchronized (lock) {
                notify = !closed && callback == this && requestGeneration == generation;
                if (notify) {
                    awaitingConfigureCompletion = false;
                    configureCompletionToken = null;
                    configureCompletionJson = null;
                }
            }
            active = false;
            boundWorker = null;
            boundWorkerPid = 0;
            if (notify) reportRequestError(generation, sessionId, "worker_died",
                    "Motion worker Binder died; latest configuration is no longer acknowledged");
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
                return true;
            }
            if (Binder.getCallingUid() != 2000) throw new SecurityException("motion callback sender denied");
            if (!isCurrentCallback(this)) throw new SecurityException("unregistered Motion callback capability");
            data.enforceInterface(MotionDetectorProtocol.CALLBACK_DESCRIPTOR);
            if (code == MotionDetectorProtocol.CB_CONFIGURED || code == MotionDetectorProtocol.CB_CONFIGURE_ERROR) {
                int protocol = data.readInt();
                int build = data.readInt();
                int workerPid = data.readInt();
                String requestToken = data.readString();
                String id = data.readString();
                String requestedConfig = data.readString();
                int retained = data.readInt();
                String stage = code == MotionDetectorProtocol.CB_CONFIGURE_ERROR ? data.readString() : null;
                String detail = code == MotionDetectorProtocol.CB_CONFIGURE_ERROR ? data.readString() : null;
                if (protocol != MotionDetectorProtocol.VERSION || build != BuildConfig.VERSION_CODE
                        || !matchesWorker(workerPid)
                        || !MotionDetectorProtocol.validSession(requestToken)
                        || !validConfiguredSession(id, requestedConfig, retained)
                        || requestedConfig == null || requestedConfig.length() > 8192
                        || data.dataAvail() != 0)
                    throw new SecurityException("invalid configured-session callback");
                if (code == MotionDetectorProtocol.CB_CONFIGURE_ERROR) {
                    if (isPendingConfigure(generation, requestToken, requestedConfig)) {
                        synchronized (lock) {
                            if (matchesPendingConfigureLocked(generation, requestToken, requestedConfig))
                                configureFailed = true;
                        }
                        reportRequestError(generation, id, stage, detail);
                    }
                } else queueConfiguredSession(requestToken, id, requestedConfig, workerPid, retained == 1);
                return true;
            }
            String id = data.readString();
            if (!isCurrent(id)) return true;
            switch (code) {
                case MotionDetectorProtocol.CB_PREPARED: {
                    IBinder preparedBinder = data.readStrongBinder();
                    int protocol = data.readInt();
                    int build = data.readInt();
                    int pid = data.readInt();
                    if (preparedBinder == null || !preparedBinder.isBinderAlive()
                            || protocol != MotionDetectorProtocol.VERSION
                            || build != BuildConfig.VERSION_CODE || !matchesWorker(pid))
                        throw new SecurityException("invalid motion prepared callback");
                    WorkerIdentity preparedIdentity = readIdentity(preparedBinder);
                    if (!matchesCurrentApk(preparedIdentity) || preparedIdentity.pid != pid)
                        throw new SecurityException("prepared Motion worker identity changed");
                    worker = preparedBinder;
                    reportPrepared(id, preparedBinder);
                    break;
                }
                case MotionDetectorProtocol.CB_STATUS:
                    reportStatus(id, data.readString(), data.readString(), data.readLong());
                    break;
                case MotionDetectorProtocol.CB_DETECTION: {
                    int level = data.readInt();
                    long elapsedMs = data.readLong();
                    int raw = data.readInt();
                    int nativeState = data.readInt();
                    long frame = data.readLong();
                    long frameTimeNs = data.readLong();
                    long warningUntil = data.readLong();
                    int expected = raw < 0 ? MotionEventPolicy.LEVEL_ONE : MotionEventPolicy.LEVEL_TWO;
                    if (raw == 0 || raw == -2 || level != expected || elapsedMs < 0 || frame <= 0
                            || frameTimeNs < 0 || warningUntil <= elapsedMs)
                        throw new SecurityException("invalid motion detection callback");
                    reportDetection(id, level, elapsedMs, raw, nativeState, frame, frameTimeNs, warningUntil);
                    break;
                }
                case MotionDetectorProtocol.CB_ERROR:
                    synchronized (lock) {
                        if (awaitingConfigureCompletion && generation == configureCompletionGeneration)
                            configureFailed = true;
                    }
                    reportRequestError(generation, id, data.readString(), data.readString());
                    data.readLong();
                    break;
                case MotionDetectorProtocol.CB_SCREEN:
                    reportScreen(id, data.readString(), data.readString(), data.readLong());
                    break;
                case MotionDetectorProtocol.CB_CHARGE: {
                    CameraChargePolicy.State chargeState = MotionDetectorProtocol.readChargeState(data);
                    if (data.dataAvail() != 0)
                        throw new SecurityException("charge callback has trailing data");
                    reportChargeState(id, chargeState);
                    break;
                }
                default:
                    return false;
            }
            return true;
        }
    }

    private static String launchCommand(Context context) throws Exception {
        String nativeDir = null;
        try {
            nativeDir = context.getPackageManager().getApplicationInfo(
                    MotionDetectorProtocol.OEM_PACKAGE, 0).nativeLibraryDir;
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            // Media-only access does not require Sentry; monitoring reports its missing package.
        }
        return launchCommand(context.getApplicationInfo().sourceDir, android.os.Process.myUid(),
                currentApkIdentity(context), nativeDir, android.os.Process.is64Bit());
    }

    static String launchCommand(String apkPath, int appUid, String identity,
            String nativeDir, boolean is64Bit) {
        String apk = shellQuote(apkPath);
        String quotedIdentity = shellQuote(identity);
        String lib = is64Bit ? "lib64" : "lib";
        String libraryPath = (nativeDir == null || nativeDir.isEmpty() ? "" : nativeDir + ":")
                + "/system/" + lib + ":/vendor/" + lib;
        return "trap '' HUP; LD_LIBRARY_PATH=" + shellQuote(libraryPath)
                + " CLASSPATH=" + apk + " setsid app_process /system/bin --nice-name="
                + MotionDetectorProtocol.PROCESS_NAME + " " + MotionDetectorProtocol.MAIN_CLASS
                + " " + appUid + " " + quotedIdentity + " </dev/null >>"
                + MotionDetectorProtocol.LOG_PATH + " 2>&1 & for i in 1 2 3 4 5 6; do service list 2>/dev/null | grep -q '"
                + MotionDetectorProtocol.SERVICE_NAME + "' && break; sleep 0.5; done; service list 2>/dev/null | grep -q '"
                + MotionDetectorProtocol.SERVICE_NAME + "'";
    }

    static String currentApkIdentity(Context context) {
        try {
            long update = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            return context.getApplicationInfo().sourceDir + ":" + update;
        } catch (Throwable ignored) {
            return context.getApplicationInfo().sourceDir + ":unknown";
        }
    }

    private static String shellQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'";
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    @Override public void close() {
        String id;
        IBinder target;
        Callback closingCallback;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            listener = null;
            awaitingConfigureCompletion = false;
            configureCompletionToken = null;
            configureCompletionJson = null;
            id = sessionId;
            target = worker;
            closingCallback = callback;
            if (closingCallback != null) closingCallback.deactivate();
        }
        try {
            commands.execute(() -> {
                try {
                    if (id != null && target != null && target.isBinderAlive())
                        transactDetachUi(target, id, closingCallback);
                } catch (Throwable ignored) { /* Observer detach is best-effort; worker owns the session. */ }
                finally {
                    worker = null;
                    commands.shutdown();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A concurrent shutdown won before the observer detach could be queued.
            commands.shutdown();
        }
    }

    private static void transactDetachUi(IBinder target, String id, IBinder callback) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MotionDetectorProtocol.DESCRIPTOR);
            data.writeString(id);
            data.writeStrongBinder(callback);
            if (!target.transact(MotionDetectorProtocol.TX_DETACH_UI, data, reply, 0))
                throw new IllegalStateException("motion observer detach unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("motion observer detach refused");
        } finally { data.recycle(); reply.recycle(); }
    }

}
