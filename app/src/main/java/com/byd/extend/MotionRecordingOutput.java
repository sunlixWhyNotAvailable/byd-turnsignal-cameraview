package com.byd.extend;

import android.content.Context;
import android.opengl.EGLContext;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Encoder ownership for local shared-YUV recording and legacy helper consumers. */
final class MotionRecordingOutput {
    private static final String DESCRIPTOR = "com.byd.extend.MotionRecordingOutput";
    static final int VIEWING_PARCEL_VERSION = 3;
    private static final IdentityHashMap<Surface, MotionRecordingOutput> OUTPUTS = new IdentityHashMap<>();
    final int width, height;
    final int[] order;
    final String session;
    final MotionFrameTimestamp clock;
    private final IBinder callback;
    private Surface[] surfaces;
    private MotionGpuComposer composer;
    private int attached;
    private MotionSharedSource localSource;
    private final java.util.ArrayList<Surface> viewingEncoders = new java.util.ArrayList<>();
    private int viewingDirection = -1;
    private boolean remoteLive;
    private int liveView = RemoteCameraView.COMPOSITION;
    private int allowedInteriorMask;
    private int availableInteriorMask;
    private int[] interiorWidths = new int[2];
    private int[] interiorHeights = new int[2];
    private String liveLocale = "en";
    private boolean mirrorCabin;
    private boolean includeExterior = true;

    static final class LiveInputs {
        final Surface[] surfaces;
        final long[] generations;

        LiveInputs(Surface[] surfaces, long[] generations) {
            if (surfaces == null || surfaces.length != 2
                    || generations == null || generations.length != 2)
                throw new IllegalArgumentException("two live interior inputs required");
            this.surfaces = surfaces.clone();
            this.generations = generations.clone();
        }
    }

    MotionRecordingOutput(int width, int height, int[] order, String session,
            MotionFrameTimestamp clock, Consumer<String> errors) {
        this(width, height, order, session, clock, new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                if (Binder.getCallingUid() != 2000) throw new SecurityException("Motion composer caller denied");
                data.enforceInterface(DESCRIPTOR);
                String error = data.readString();
                if (data.dataAvail() != 0) throw new IllegalArgumentException("invalid composer event");
                errors.accept(error);
                return true;
            }
        });
    }

    MotionRecordingOutput(int width, int height, int[] order, String session,
            MotionFrameTimestamp clock, Consumer<Throwable> errors,
            BiConsumer<Integer, String> unavailable) {
        this(width, height, order, session, clock, new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                if (Binder.getCallingUid() != 2000) throw new SecurityException("Motion composer caller denied");
                data.enforceInterface(DESCRIPTOR);
                int kind = data.readInt();
                if (kind == 1) {
                    String reference = data.readString();
                    if (reference == null || reference.length() > 128 || data.dataAvail() != 0)
                        throw new IllegalArgumentException("invalid composer failure event");
                    if (errors != null) errors.accept(new IllegalStateException(
                            "GPU composer failure diagnostic_ref=" + reference));
                } else if (kind == 2) {
                    int channel = data.readInt();
                    String reference = data.readString();
                    if ((channel != 4 && channel != 5) || reference == null
                            || !reference.matches("[a-f0-9]{32}") || data.dataAvail() != 0)
                        throw new IllegalArgumentException("invalid composer channel event");
                    if (unavailable != null) unavailable.accept(channel, reference);
                } else throw new IllegalArgumentException("unknown composer event");
                return true;
            }
        });
    }

    private MotionRecordingOutput(int width, int height, int[] order, String session,
            MotionFrameTimestamp clock, IBinder callback) {
        if (width < 2 || height < 2 || width > 4096 || height > 4096
                || order == null || order.length != 4 || callback == null
                || !MotionDetectorProtocol.validSession(session))
            throw new IllegalArgumentException("invalid Motion encoder configuration");
        int mask = 0;
        for (int direction : order) {
            if (direction < 0 || direction > 3 || (mask & (1 << direction)) != 0)
                throw new IllegalArgumentException("invalid Motion camera order");
            mask |= 1 << direction;
        }
        this.width = width; this.height = height; this.order = order.clone();
        this.session = session; this.clock = clock; this.callback = callback;
    }

    void initialize(Surface encoder) {
        // Four logical consumers share one encoder queue, not four intermediate buffer queues.
        surfaces = new Surface[]{encoder, encoder, encoder, encoder};
    }

    synchronized Surface[] inputs() {
        if (remoteLive && !viewingEncoders.isEmpty()) {
            Surface encoder = viewingEncoders.get(viewingEncoders.size() - 1);
            return new Surface[]{encoder, encoder, encoder, encoder};
        }
        return surfaces.clone();
    }

    void writeToParcel(Parcel data) {
        data.writeInt(width); data.writeInt(height); data.writeIntArray(order);
        data.writeLong(clock.wallTimeMillis(0)); data.writeStrongBinder(callback);
    }

    void writeViewingToParcel(Parcel data, int view, int allowedMask, boolean exteriorReady,
            int availableMask, int[] widths, int[] heights, String locale, boolean mirrorCabin) {
        remoteLive = true; liveView = view; allowedInteriorMask = allowedMask;
        includeExterior = exteriorReady; availableInteriorMask = availableMask;
        interiorWidths = widths == null ? null : widths.clone();
        interiorHeights = heights == null ? null : heights.clone(); liveLocale = locale;
        this.mirrorCabin = mirrorCabin;
        validateLiveSelection();
        data.writeInt(width); data.writeInt(height); data.writeIntArray(order);
        data.writeLong(clock.wallTimeMillis(0)); data.writeStrongBinder(callback);
        data.writeInt(VIEWING_PARCEL_VERSION);
        data.writeInt(view); data.writeInt(allowedMask); data.writeInt(exteriorReady ? 1 : 0);
        data.writeInt(availableMask);
        data.writeIntArray(widths); data.writeIntArray(heights);
        data.writeString(locale); data.writeInt(mirrorCabin ? 1 : 0);
    }

    static MotionRecordingOutput readFromParcel(Parcel data, String session, Surface[] surfaces) {
        MotionRecordingOutput output = new MotionRecordingOutput(
                data.readInt(), data.readInt(), data.createIntArray(), session,
                new MotionFrameTimestamp(data.readLong(), 0), data.readStrongBinder());
        if (surfaces == null || surfaces.length != 4)
            throw new IllegalArgumentException("four Motion consumers required");
        output.surfaces = surfaces;
        return output;
    }

    static MotionRecordingOutput readViewingFromParcel(Parcel data, String session, Surface[] surfaces) {
        MotionRecordingOutput output = readFromParcel(data, session, surfaces);
        if (data.readInt() != VIEWING_PARCEL_VERSION)
            throw new IllegalArgumentException("stale remote viewing parcel");
        output.remoteLive = true;
        output.liveView = data.readInt();
        output.allowedInteriorMask = data.readInt();
        int exterior = data.readInt();
        if (exterior != 0 && exterior != 1) throw new IllegalArgumentException("invalid exterior readiness flag");
        output.includeExterior = exterior == 1;
        output.availableInteriorMask = data.readInt();
        output.interiorWidths = data.createIntArray();
        output.interiorHeights = data.createIntArray();
        output.liveLocale = data.readString();
        int mirror = data.readInt();
        if (mirror != 0 && mirror != 1) throw new IllegalArgumentException("invalid cabin mirror flag");
        output.mirrorCabin = mirror == 1;
        output.validateLiveSelection();
        return output;
    }

    void register() {
        synchronized (OUTPUTS) { for (Surface surface : surfaces) OUTPUTS.put(surface, this); }
    }

    void unregister() {
        synchronized (OUTPUTS) {
            if (surfaces != null) for (Surface surface : surfaces)
                if (OUTPUTS.get(surface) == this) OUTPUTS.remove(surface);
        }
    }

    void registerAliases(Surface[] aliases) {
        if (aliases == null || aliases.length != 4) throw new IllegalArgumentException("four encoder aliases required");
        synchronized (OUTPUTS) {
            for (Surface surface : aliases) {
                if (surface == null || !surface.isValid()) throw new IllegalArgumentException("invalid encoder alias");
                OUTPUTS.put(surface, this);
            }
        }
    }

    void unregisterAliases(Surface[] aliases) {
        if (aliases == null) return;
        synchronized (OUTPUTS) {
            for (Surface surface : aliases) if (OUTPUTS.get(surface) == this) OUTPUTS.remove(surface);
        }
    }

    static MotionRecordingOutput find(Surface surface) {
        synchronized (OUTPUTS) { return OUTPUTS.get(surface); }
    }

    synchronized void prepare(Context context, EGLContext sharedContext, int glVersion) throws Exception {
        if (composer != null) return;
        composer = new MotionGpuComposer(context, width, height, order, session, clock,
                sharedContext, glVersion, remoteLive, liveView, allowedInteriorMask,
                availableInteriorMask, interiorWidths, interiorHeights, liveLocale,
                includeExterior, mirrorCabin, this::failed, this::notifyLiveChannelUnavailable);
        composer.initialize(viewingEncoders.isEmpty() ? surfaces[0]
                : viewingEncoders.get(viewingEncoders.size() - 1));
        if (viewingDirection >= 0) composer.updateViewingOutput(null, viewingDirection);
    }

    synchronized void prepareStandalone(Context context) throws Exception {
        prepare(context, android.opengl.EGL14.EGL_NO_CONTEXT, 2);
    }

    /** Takes ownership even on timeout: the queued GL operation may still use this Surface. */
    synchronized void updateViewingOutput(Surface encoder, int direction) throws Exception {
        if (encoder != null) viewingEncoders.add(encoder);
        if (composer != null) composer.updateViewingOutput(encoder, direction);
        viewingDirection = direction;
        if (encoder != null) {
            for (int i = viewingEncoders.size() - 2; i >= 0; i--)
                viewingEncoders.remove(i).release();
        }
    }

    synchronized LiveInputs updateLiveViewingOutput(Surface encoder, int view, int allowedMask,
            boolean exteriorReady, int availableMask, int[] widths, int[] heights,
            String locale, boolean mirrorCabin) throws Exception {
        if (!remoteLive) throw new IllegalStateException("remote live composer unavailable");
        if (encoder != null) viewingEncoders.add(encoder);
        liveView = view; allowedInteriorMask = allowedMask; includeExterior = exteriorReady;
        availableInteriorMask = availableMask;
        interiorWidths = widths.clone(); interiorHeights = heights.clone();
        liveLocale = locale;
        this.mirrorCabin = mirrorCabin;
        validateLiveSelection();
        LiveInputs inputs = composer.updateLiveViewingOutput(encoder, liveView,
                allowedInteriorMask, includeExterior, availableInteriorMask,
                interiorWidths, interiorHeights, liveLocale, mirrorCabin);
        if (encoder != null) {
            for (int i = viewingEncoders.size() - 2; i >= 0; i--)
                viewingEncoders.remove(i).release();
        }
        return inputs;
    }

    synchronized void updateCabinMirrored(boolean mirrorCabin) throws Exception {
        if (!remoteLive || composer == null)
            throw new IllegalStateException("remote live composer unavailable");
        composer.updateCabinMirrored(mirrorCabin);
        this.mirrorCabin = mirrorCabin;
    }

    synchronized void markLiveChannelUnavailable(int channel, String reference) {
        if (composer != null) composer.markLiveChannelUnavailable(channel, reference);
    }

    synchronized void closeComposer() {
        if (composer != null) {
            MotionGpuComposer closing = composer;
            // MotionGpuComposer retains a timed-out cleanup task. Keep its owner and all encoder
            // wrappers reachable until that task reports successful GL teardown; the caller can
            // safely retry closeComposer() to await the same task.
            closing.close();
            composer = null;
        }
        // Encoder lifetime spans composer recreation; release on replacement or releaseInputs.
    }

    private void validateLiveSelection() {
        RemoteCameraView.validateMask(allowedInteriorMask);
        if (!RemoteCameraView.valid(liveView) || !RemoteCameraView.allowed(liveView, allowedInteriorMask)
                || availableInteriorMask < 0 || availableInteriorMask > RemoteCameraView.BOTH
                || interiorWidths == null || interiorWidths.length != 2
                || interiorHeights == null || interiorHeights.length != 2
                || (availableInteriorMask & ~allowedInteriorMask) != 0)
            throw new IllegalArgumentException("invalid remote camera source configuration");
        for (int index = 0; index < 2; index++) {
            int bit = 1 << index;
            if ((availableInteriorMask & bit) != 0
                    && (interiorWidths[index] < 2 || interiorHeights[index] < 2))
                throw new IllegalArgumentException("invalid interior camera dimensions");
        }
        if (liveLocale == null || !liveLocale.matches("[A-Za-z-]{1,16}"))
            throw new IllegalArgumentException("invalid remote viewer locale");
    }

    private void releaseViewingEncoders() {
        Throwable first = null;
        for (int index = viewingEncoders.size() - 1; index >= 0; index--) {
            Surface surface = viewingEncoders.get(index);
            try {
                surface.release();
                viewingEncoders.remove(index);
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else if (failure != first) first.addSuppressed(failure);
            }
        }
        if (first instanceof Error) throw (Error) first;
        if (first instanceof RuntimeException) throw (RuntimeException) first;
        if (first != null) throw new IllegalStateException("remote live encoder cleanup failed", first);
    }

    synchronized void releaseInputs() {
        if (composer != null) throw new IllegalStateException("cannot release encoder inputs while composer is active");
        releaseViewingEncoders();
        unregister();
        if (surfaces == null) return;
        IdentityHashMap<Surface, Boolean> released = new IdentityHashMap<>();
        for (Surface surface : surfaces)
            if (surface != null && released.put(surface, Boolean.TRUE) == null) surface.release();
        surfaces = null;
    }

    synchronized void retain() { attached++; }

    synchronized void detach(int sourceIndex) {
        if (composer == null) return;
        composer.detachSource(sourceIndex);
        if (--attached == 0 && !remoteLive) {
            composer.close();
            composer = null;
        }
    }

    synchronized boolean offer(int sourceIndex, CameraFramePump pump, int slot) {
        return composer != null && composer.offer(sourceIndex, pump, slot);
    }

    synchronized void closeUnused() {
        if (!remoteLive && attached == 0 && composer != null) {
            composer.close(); composer = null;
        }
    }

    void startLocal(Context context, MotionSharedSource source) throws Exception {
        localSource = source;
        source.startRecording(context, surfaces[0], width, height, order, session, clock, this::failed);
    }

    void close(Runnable onClosed) {
        if (localSource != null) {
            localSource.stopRecording(onClosed);
            localSource = null;
        } else if (onClosed != null) onClosed.run();
    }

    void failed(String detail) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (remoteLive) { data.writeInt(1); data.writeString(detail); }
            else data.writeString(detail);
            callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            // Host death/session cleanup owns encoder teardown if the Motion worker has gone away.
        } finally { data.recycle(); }
    }

    private void notifyLiveChannelUnavailable(int channel, String reference) {
        if (!remoteLive) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(2);
            data.writeInt(channel); data.writeString(reference);
            callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
        } finally { data.recycle(); }
    }
}
