package com.byd.extend;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.view.Surface;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Per-window mapping endpoint; only configuration/acks cross Binder, never camera frames. */
final class CameraGpuOutput extends Binder {
    private static final String DESCRIPTOR = "com.byd.extend.CameraGpuOutput";
    private static final int BIND = FIRST_CALL_TRANSACTION;
    private static final int APPLIED = BIND + 1;
    private static final int DETACHED = BIND + 2;
    private static final int UPDATE = BIND + 3;
    private static final int RELEASE = BIND + 4;
    private static final int STOCK_CANVAS = BIND + 5;
    private static final int FRAME = BIND + 6;
    private static final int GEOMETRY = BIND + 7;
    private static final int PRESENTATION = BIND + 8;
    static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final Map<Surface, Endpoint> OUTPUTS = new WeakHashMap<>();
    private static final class Endpoint {
        final IBinder binder;
        final int uid;
        Endpoint(IBinder binder, int uid) { this.binder = binder; this.uid = uid; }
    }

    private final int width;
    private final int height;
    private final int captureWidth;
    private final int captureHeight;
    private final boolean lifecycleOnly;
    private final Consumer<CameraDewarpRenderer.Event> events;
    private CameraDewarpRenderer.MappingRequest request;
    private float[] geometry = IDENTITY;
    private long geometryRevision;
    private IBinder control;
    private Runnable stopped;
    private boolean released;
    private java.util.function.BiConsumer<Integer, Integer> stockCanvas;
    interface FrameListener { void frame(long timestamp, long presentation, long mapping, long geometry); }
    private FrameListener frameListener;
    private Surface alternateSurface;
    private boolean staging;
    private int outputSlot;
    private long presentationRevision;
    private long presentationGeometry;
    private final DeathRecipient death = () -> connectionLost("death_recipient", "binder_died", null);

    CameraGpuOutput(int width, int height, CameraDewarpConfig config, long token,
            Consumer<CameraDewarpRenderer.Event> events) {
        this(width, height, config, token, events, false);
    }

    CameraGpuOutput(int width, int height, CameraDewarpConfig config, long token,
            Consumer<CameraDewarpRenderer.Event> events, boolean lifecycleOnly) {
        this(width, height, width, height, config, token, events, lifecycleOnly);
    }

    CameraGpuOutput(int width, int height, int captureWidth, int captureHeight,
            CameraDewarpConfig config, long token,
            Consumer<CameraDewarpRenderer.Event> events, boolean lifecycleOnly) {
        this.width = width;
        this.height = height;
        this.captureWidth = captureWidth;
        this.captureHeight = captureHeight;
        this.events = events;
        this.lifecycleOnly = lifecycleOnly;
        request = new CameraDewarpRenderer.MappingRequest(config, token);
    }

    static void register(Surface surface, CameraGpuOutput output) {
        synchronized (OUTPUTS) { OUTPUTS.put(surface, new Endpoint(output, Process.myUid())); }
    }

    static void unregister(Surface surface) {
        synchronized (OUTPUTS) { OUTPUTS.remove(surface); }
    }

    static void writeSurface(Parcel data, Surface surface) {
        surface.writeToParcel(data, 0);
        synchronized (OUTPUTS) {
            Endpoint endpoint = OUTPUTS.get(surface);
            data.writeStrongBinder(endpoint == null ? null : endpoint.binder);
        }
    }

    static Surface readSurface(Parcel data) {
        return readSurface(data, Binder.getCallingUid());
    }

    static Surface readSurface(Parcel data, int ownerUid) {
        Surface surface = Surface.CREATOR.createFromParcel(data);
        try {
            IBinder endpoint = data.readStrongBinder();
            if (endpoint != null) synchronized (OUTPUTS) {
                OUTPUTS.put(surface, new Endpoint(endpoint, ownerUid));
            }
            return surface;
        } catch (RuntimeException error) {
            surface.release();
            throw error;
        }
    }

    static Binding bind(Surface surface, Runnable releaseTarget) throws RemoteException {
        Endpoint endpoint;
        synchronized (OUTPUTS) { endpoint = OUTPUTS.get(surface); }
        return endpoint == null ? null : new Binding(endpoint.binder, endpoint.uid, releaseTarget);
    }

    void setStockCanvasHandler(java.util.function.BiConsumer<Integer, Integer> handler) {
        stockCanvas = handler;
    }

    void setFrameListener(FrameListener listener) { frameListener = listener; }

    synchronized long beginPresentation(Surface alternate, int slot) {
        if (released || slot < 0 || slot > 1 || alternate == null || !alternate.isValid())
            throw new IllegalStateException("GPU presentation unavailable");
        alternateSurface = alternate;
        outputSlot = slot;
        staging = true;
        return ++presentationRevision;
    }

    synchronized void submitPresentation() {
        if (!staging || released) return;
        staging = false;
        presentationGeometry = geometryRevision;
        if (control == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            writePresentation(data);
            if (!control.transact(PRESENTATION, data, null, FLAG_ONEWAY))
                connectionLost("presentation", "transact_rejected", null);
        } catch (RemoteException error) { connectionLost("presentation", "remote_exception", error); }
        finally { data.recycle(); }
    }

    synchronized boolean acceptsFrame(long presentation, long mapping, long geometryVersion) {
        return !released && !staging && presentation == presentationRevision
                && mapping == request.token && geometryVersion >= presentationGeometry;
    }

    private void writePresentation(Parcel data) {
        data.writeLong(presentationRevision);
        data.writeInt(outputSlot);
        data.writeTypedObject(alternateSurface, 0);
        writeMapping(data, request);
        data.writeLong(geometryRevision);
        data.writeFloatArray(geometry);
    }

    static void configureStockCanvas(Surface surface, int width, int height) throws RemoteException {
        Endpoint endpoint;
        synchronized (OUTPUTS) { endpoint = OUTPUTS.get(surface); }
        if (endpoint == null) throw new IllegalStateException("Stock canvas endpoint missing");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(width);
            data.writeInt(height);
            if (!endpoint.binder.transact(STOCK_CANVAS, data, reply, 0))
                throw new IllegalStateException("Stock canvas configuration rejected");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    synchronized void update(CameraDewarpConfig config, long token) {
        if (released || token <= request.token) return;
        request = new CameraDewarpRenderer.MappingRequest(config, token);
        if (control == null || staging) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            writeMapping(data, request);
            if (!control.transact(UPDATE, data, null, FLAG_ONEWAY))
                connectionLost("update", "transact_rejected", null);
        } catch (RemoteException error) { connectionLost("update", "remote_exception", error); }
        finally { data.recycle(); }
    }

    synchronized void updateGeometry(float[] value) {
        validateGeometry(value);
        if (released || java.util.Arrays.equals(geometry, value)) return;
        geometry = value.clone();
        geometryRevision++;
        if (control == null || staging) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeLong(geometryRevision);
            data.writeFloatArray(geometry);
            if (!control.transact(GEOMETRY, data, null, FLAG_ONEWAY))
                connectionLost("geometry", "transact_rejected", null);
        } catch (RemoteException error) { connectionLost("geometry", "remote_exception", error); }
        finally { data.recycle(); }
    }

    private static void validateGeometry(float[] value) {
        if (value == null || value.length != 16)
            throw new IllegalArgumentException("invalid GPU geometry dimensions");
        for (float coefficient : value) {
            if (!Float.isFinite(coefficient)) throw new IllegalArgumentException("invalid GPU geometry");
        }
    }

    synchronized void release(Runnable onStopped) {
        if (released) return;
        released = true;
        stopped = onStopped;
        if (control == null) { disconnected(); return; }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!control.transact(RELEASE, data, null, FLAG_ONEWAY))
                connectionLost("release", "transact_rejected", null);
        } catch (RemoteException error) { connectionLost("release", "remote_exception", error); }
        finally { data.recycle(); }
    }

    private synchronized void connectionLost(String operation, String reason, Throwable failure) {
        try {
            com.byd.extend.remote.CameraViewingDiagnostics.detail("gpu_output_failed", "shared",
                    "operation=" + operation + " reason=" + reason + " width=" + width
                            + " height=" + height + " mapping=" + request.token + "\n"
                            + com.byd.extend.remote.CameraViewingDiagnostics.describe(failure));
        } finally {
            disconnected();
        }
    }

    private synchronized void disconnected() {
        if (control != null) control.unlinkToDeath(death, 0);
        control = null;
        Runnable completion = stopped;
        stopped = null;
        if (completion != null) completion.run();
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (!CameraShellProtocol.isCallerAllowed(Binder.getCallingUid(), Process.myUid())) {
            throw new SecurityException("GPU output sender denied");
        }
        data.enforceInterface(DESCRIPTOR);
        if (code == STOCK_CANVAS) {
            int w = data.readInt(), h = data.readInt();
            if (w < 240 || h < 240 || w > 4096 || h > 4096)
                throw new IllegalArgumentException("Invalid stock canvas dimensions");
            synchronized (this) {
                if (released || !lifecycleOnly || stockCanvas == null)
                    throw new IllegalStateException("Stock canvas unavailable");
            }
            // The main-thread resize must not hold the lease lock used by view retirement.
            stockCanvas.accept(w, h);
            reply.writeNoException();
            return true;
        }
        return transactLease(code, data, reply);
    }

    private synchronized boolean transactLease(int code, Parcel data, Parcel reply)
            throws RemoteException {
        if (code == BIND) {
            if (released || control != null) throw new IllegalStateException("GPU output not available");
            IBinder next = data.readStrongBinder();
            if (next == null) throw new IllegalArgumentException("GPU output control missing");
            next.linkToDeath(death, 0);
            control = next;
            reply.writeNoException();
            reply.writeInt(width);
            reply.writeInt(height);
            reply.writeInt(lifecycleOnly ? 1 : 0);
            reply.writeInt(frameListener != null ? 1 : 0);
            writeMapping(reply, request);
            reply.writeLong(geometryRevision);
            reply.writeFloatArray(geometry);
            writePresentation(reply);
            reply.writeInt(captureWidth);
            reply.writeInt(captureHeight);
            return true;
        }
        IBinder sender = data.readStrongBinder();
        if (sender != control) {
            if (reply != null) reply.writeNoException();
            return true;
        }
        if (code == DETACHED) {
            disconnected();
            if (reply != null) reply.writeNoException();
            return true;
        }
        if (code == FRAME) {
            long timestamp = data.readLong();
            long presentation = data.readLong(), mapping = data.readLong(), geometryVersion = data.readLong();
            if (!released && frameListener != null && timestamp > 0)
                frameListener.frame(timestamp, presentation, mapping, geometryVersion);
            return true;
        }
        if (code == APPLIED) {
            long token = data.readLong();
            boolean fallback = data.readInt() != 0;
            int vertices = data.readInt();
            long generationNs = data.readLong();
            if (!released && token == request.token) {
                events.accept(new CameraDewarpRenderer.Event(fallback
                        ? "dewarp_fallback_raw" : "dewarp_mesh_applied",
                        request, vertices, generationNs, null, width, height));
            }
            return true;
        }
        return false;
    }

    private static void writeMapping(Parcel parcel, CameraDewarpRenderer.MappingRequest value) {
        parcel.writeLong(value.token);
        parcel.writeIntArray(CameraShellProtocol.encodeDewarp(value.config));
        parcel.writeFloat(value.config.roiCenterX);
        parcel.writeFloat(value.config.roiCenterY);
    }

    private static CameraDewarpRenderer.MappingRequest readMapping(Parcel parcel) {
        long token = parcel.readLong();
        if (token < 0) throw new IllegalArgumentException("invalid GPU mapping token");
        CameraDewarpConfig config = CameraShellProtocol.decodeDewarp(parcel.createIntArray())
                .withRoiCenter(parcel.readFloat(), parcel.readFloat());
        return new CameraDewarpRenderer.MappingRequest(config, token);
    }

    /** Source-worker-owned lease. Destruction is acknowledged only after EGL disconnects. */
    static final class Binding extends Binder {
        final IBinder endpoint;
        final Runnable releaseTarget;
        final int ownerUid;
        final int width;
        final int height;
        final int captureWidth;
        final int captureHeight;
        final boolean lifecycleOnly;
        final boolean frameEvents;
        volatile CameraDewarpRenderer.MappingRequest request;
        volatile float[] geometry = IDENTITY;
        private long geometryRevision = -1;
        private long presentationRevision = -1;
        private int outputSlot;
        private Surface alternateSurface;
        private FrameConfiguration cachedFrame;
        private boolean closed;
        private final DeathRecipient ownerDeath;

        Binding(IBinder endpoint, int ownerUid, Runnable releaseTarget) throws RemoteException {
            this.endpoint = endpoint;
            this.ownerUid = ownerUid;
            this.releaseTarget = releaseTarget;
            ownerDeath = releaseTarget::run;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                endpoint.linkToDeath(ownerDeath, 0);
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                if (!endpoint.transact(BIND, data, reply, 0)) {
                    throw new IllegalStateException("GPU output bind rejected");
                }
                reply.readException();
                width = reply.readInt();
                height = reply.readInt();
                lifecycleOnly = reply.readInt() != 0;
                frameEvents = reply.readInt() != 0;
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                    throw new IllegalArgumentException("invalid GPU output dimensions");
                }
                accept(readMapping(reply));
                acceptGeometry(reply);
                acceptPresentation(reply);
                // Old endpoints have equal capture/output dimensions.
                int remaining = reply.dataAvail();
                if (remaining != 0 && remaining != 8)
                    throw new IllegalArgumentException("invalid GPU capture payload");
                captureWidth = remaining == 8 ? reply.readInt() : width;
                captureHeight = remaining == 8 ? reply.readInt() : height;
                if (captureWidth < width || captureHeight < height
                        || captureWidth > 4096 || captureHeight > 4096)
                    throw new IllegalArgumentException("invalid GPU capture dimensions");
            } catch (RemoteException | RuntimeException error) {
                try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            } finally { data.recycle(); reply.recycle(); }
        }

        private synchronized void accept(CameraDewarpRenderer.MappingRequest next) {
            if (!closed && (request == null || next.token > request.token)) {
                request = next;
                cachedFrame = null;
            }
        }

        private synchronized void acceptGeometry(Parcel parcel) {
            long revision = parcel.readLong();
            float[] next = parcel.createFloatArray();
            validateGeometry(next);
            if (revision < 0) throw new IllegalArgumentException("invalid GPU geometry revision");
            // A UI update may arrive before the BIND reply is consumed.
            if (!closed && revision > geometryRevision) {
                geometry = next;
                geometryRevision = revision;
                cachedFrame = null;
            }
        }

        private synchronized void acceptPresentation(Parcel parcel) {
            long revision = parcel.readLong();
            int slot = parcel.readInt();
            Surface alternate = parcel.readTypedObject(Surface.CREATOR);
            try {
                if (revision < 0)
                    throw new IllegalArgumentException("invalid GPU presentation");
                if (closed || revision <= presentationRevision) return;
                if (slot < 0 || slot > 1)
                    throw new IllegalArgumentException("invalid GPU presentation");
                if (slot == 1 && alternate == null)
                    throw new IllegalArgumentException("incomplete GPU presentation");
                if (alternate != null && slot == 1 && !alternate.isValid())
                    throw new IllegalArgumentException("invalid alternate surface");
                CameraDewarpRenderer.MappingRequest next = readMapping(parcel);
                long geometryVersion = parcel.readLong();
                float[] matrix = parcel.createFloatArray();
                validateGeometry(matrix);
                if (geometryVersion < 0)
                    throw new IllegalArgumentException("invalid GPU presentation");
                if (alternateSurface == null && alternate != null) {
                    if (!alternate.isValid()) throw new IllegalArgumentException("invalid alternate surface");
                    alternateSurface = alternate; alternate = null;
                }
                presentationRevision = revision;
                outputSlot = slot;
                cachedFrame = null;
                accept(next);
                if (geometryVersion >= geometryRevision) {
                    geometryRevision = geometryVersion;
                    geometry = matrix;
                }
            } finally { if (alternate != null) alternate.release(); }
        }

        synchronized FrameConfiguration frameConfiguration() {
            if (cachedFrame == null) cachedFrame = new FrameConfiguration(request, geometry, geometryRevision,
                    presentationRevision, outputSlot, alternateSurface);
            return cachedFrame;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (Binder.getCallingUid() != ownerUid) throw new SecurityException("GPU control sender denied");
            data.enforceInterface(DESCRIPTOR);
            if (code == UPDATE) { accept(readMapping(data)); return true; }
            if (code == GEOMETRY) {
                acceptGeometry(data);
                return true;
            }
            if (code == PRESENTATION) { acceptPresentation(data); return true; }
            if (code == RELEASE) { releaseTarget.run(); return true; }
            return false;
        }

        void applied(long token, boolean fallback, int vertices, long generationNs) throws RemoteException {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                data.writeLong(token);
                data.writeInt(fallback ? 1 : 0);
                data.writeInt(vertices);
                data.writeLong(generationNs);
                if (!endpoint.transact(APPLIED, data, null, FLAG_ONEWAY)) {
                    throw new IllegalStateException("GPU output acknowledgement rejected");
                }
            } finally { data.recycle(); }
        }

        void frame(long timestamp, FrameConfiguration frame) throws RemoteException {
            if (!frameEvents) return;
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                data.writeLong(timestamp);
                data.writeLong(frame.presentation);
                data.writeLong(frame.mapping.token);
                data.writeLong(frame.geometryRevision);
                if (!endpoint.transact(FRAME, data, null, FLAG_ONEWAY))
                    throw new IllegalStateException("GPU frame acknowledgement rejected");
            } finally { data.recycle(); }
        }

        void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                if (alternateSurface != null) { alternateSurface.release(); alternateSurface = null; }
            }
            endpoint.unlinkToDeath(ownerDeath, 0);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(this);
                if (endpoint.transact(DETACHED, data, reply, 0)) reply.readException();
            } catch (RemoteException ignored) {
                // A dead owner has no remaining TextureView to release.
            } finally { data.recycle(); reply.recycle(); }
        }
    }

    static final class FrameConfiguration {
        final CameraDewarpRenderer.MappingRequest mapping;
        final float[] geometry;
        final long geometryRevision, presentation;
        final int slot;
        final Surface alternate;
        FrameConfiguration(CameraDewarpRenderer.MappingRequest mapping, float[] geometry,
                long geometryRevision, long presentation, int slot, Surface alternate) {
            this.mapping = mapping; this.geometry = geometry; this.geometryRevision = geometryRevision;
            this.presentation = presentation; this.slot = slot; this.alternate = alternate;
        }
    }
}
