package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.view.Surface;

import com.byd.extend.remote.CameraViewingDiagnostics;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Viewing-helper-owned stream fan-in for exterior and on-demand interior channels. */
public final class RemoteCameraSource implements AutoCloseable {
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger();
    private final Context context;
    private final IBinder owner = new Binder();
    private final String session = UUID.randomUUID().toString().replace("-", "");
    private final Consumer<Throwable> failure;
    private final RemoteInteriorCameraSources interiors;
    private final PowerManager.WakeLock wakeLock;
    private volatile IBinder host;
    private IBinder.DeathRecipient death;
    private volatile boolean attached;
    private volatile boolean closing;
    private volatile Throwable closeFailure;
    private boolean preparationRequested;
    private volatile boolean exteriorReady;
    private volatile RemoteExteriorAttachment exteriorAttachment = new RemoteExteriorAttachment(false, null);
    private long outputGeneration;
    private boolean mirrorCabin;
    private int requestId;
    private int preparedView = RemoteCameraView.COMPOSITION;
    private int allowedInteriorMask;
    private RemoteInteriorCameraSources.Specs specs =
            new RemoteInteriorCameraSources.Specs(RemoteCameraView.NONE, RemoteCameraView.NONE,
                    new int[2], new int[2], new String[2]);

    public RemoteCameraSource(Context context, Consumer<Throwable> failure) {
        if (context == null) throw new IllegalArgumentException("context required");
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.failure = failure == null ? ignored -> { } : failure;
        interiors = new RemoteInteriorCameraSources(session, this::sourceChannelUnavailable);
        PowerManager power = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        if (power == null) throw new IllegalStateException("PowerManager unavailable");
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydextend:camera-viewer");
        wakeLock.setReferenceCounted(false);
    }

    /** Lazily opens selected interiors and retains previously opened permitted sources through view changes. */
    public boolean prepare(int view, int interiorMask) throws Exception {
        if (closing) throw new IllegalStateException("remote camera source cleanup is pending", closeFailure);
        validateSelection(view, interiorMask);
        int previousView = preparedView;
        int previousAllowedMask = allowedInteriorMask;
        int previousAvailableMask = specs.availableMask;
        boolean previousExteriorReady = exteriorReady;
        // Keep the viewing process alive for interior-only capture as well. The OEM clients
        // own their camera threads, but that does not make the shell process independently
        // immune to suspend while no external camera lease is held.
        if (!wakeLock.isHeld()) wakeLock.acquire();
        int requested = RemoteCameraView.interiorMask(view, interiorMask);
        int retained = RemoteCameraView.retainedInteriors(specs.attemptedMask, requested, interiorMask);
        try { interiors.retain(retained); }
        catch (Throwable cleanup) {
            CameraViewingDiagnostics.failure("interior_release_before_prepare", "shared", cleanup);
            if (cleanup instanceof Exception) throw (Exception) cleanup;
            if (cleanup instanceof Error) throw (Error) cleanup;
            throw new IllegalStateException("interior camera release before prepare failed", cleanup);
        }
        RemoteInteriorCameraSources.Specs nextSpecs = interiors.prepare(requested);
        preparedView = view;
        allowedInteriorMask = interiorMask;
        specs = nextSpecs;

        if (RemoteCameraView.needsExterior(view)) {
            preparationRequested = true;
            try {
                exteriorReady = RemoteCameraPreparation.prepare(context, owner, session);
                CameraViewingDiagnostics.detail("exterior_prepare", "shared",
                        "view=" + RemoteCameraView.name(view) + " ready=" + exteriorReady);
            } catch (Throwable cause) {
                CameraViewingDiagnostics.failure("exterior_prepare", "shared", cause);
                exteriorReady = false;
            }
        }
        if (previousView != preparedView || previousAllowedMask != allowedInteriorMask
                || previousAvailableMask != specs.availableMask || previousExteriorReady != exteriorReady) {
            CameraViewingDiagnostics.detail("source_selection", "shared", "session=" + session
                    + " view=" + RemoteCameraView.name(previousView) + "->" + RemoteCameraView.name(preparedView)
                    + " allowed=" + previousAllowedMask + "->" + allowedInteriorMask
                    + " requested=" + requested + " attempted=" + specs.attemptedMask
                    + " available=" + previousAvailableMask + "->" + specs.availableMask
                    + " exteriorReady=" + previousExteriorReady + "->" + exteriorReady);
        }
        // Combined and interior-only output remain useful while an exterior lease is unavailable.
        return !RemoteCameraView.needsExterior(view) || view == RemoteCameraView.COMBINED || exteriorReady;
    }

    public synchronized void attach(Surface encoder, int width, int height, int view,
            int interiorMask, String locale, boolean mirrorCabin) throws Exception {
        validateSelection(view, interiorMask);
        if (attached) throw new IllegalStateException("viewer already attached");
        if (!matchesPrepared(view, interiorMask)) throw new IllegalStateException("viewer source not prepared");
        if (encoder == null || !encoder.isValid()) throw new IllegalArgumentException("viewer encoder Surface unavailable");
        if (width < 2 || height < 2 || width > 4096 || height > 4096)
            throw new IllegalArgumentException("invalid viewer encoder dimensions");
        String language = validateLocale(locale);

        IBinder candidate = findHost();
        host = candidate;
        death = () -> {
            attached = false;
            exteriorAttachment = new RemoteExteriorAttachment(false, null);
            failure.accept(new IllegalStateException("camera helper died; viewer session=" + session));
        };
        candidate.linkToDeath(death, 0);
        MotionRecordingOutput output = new MotionRecordingOutput(width, height,
                new int[]{0, 1, 2, 3}, session, MotionFrameTimestamp.capture(), failure,
                this::composerChannelUnavailable);
        output.initialize(encoder);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(CameraRuntimeHost.VIEW_ATTACH);
            data.writeStrongBinder(owner);
            data.writeString(session);
            data.writeTypedArray(output.inputs(), 0);
            requestId = nextRequestId();
            data.writeInt(requestId);
            output.writeViewingToParcel(data, view, interiorMask, exteriorReady,
                    specs.availableMask, specs.widths, specs.heights, language, mirrorCabin);
            if (!candidate.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera viewer attach transaction unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("camera viewer attach refused");
            RemoteExteriorAttachment exterior = RemoteExteriorAttachment.readFromParcel(reply);
            Surface[] inputs = reply.createTypedArray(Surface.CREATOR);
            long[] inputGenerations = reply.createLongArray();
            this.mirrorCabin = mirrorCabin;
            attached = true;
            interiors.attachInputs(inputs, specs.availableMask, inputGenerations);
            acknowledgeExterior(exterior);
        } catch (Throwable error) {
            if (error instanceof Exception) throw (Exception) error;
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException("camera viewer attach failed", error);
        } finally {
            data.recycle(); reply.recycle();
        }
    }

    public synchronized void updateOutput(Surface encoder, int view, int interiorMask, String locale,
            boolean mirrorCabin) throws Exception {
        validateSelection(view, interiorMask);
        if (!attached || host == null) throw new IllegalStateException("viewer not attached");
        if (!matchesPrepared(view, interiorMask)) throw new IllegalStateException("viewer source not prepared");
        String language = validateLocale(locale);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(CameraRuntimeHost.VIEW_UPDATE_OUTPUT);
            data.writeStrongBinder(owner); data.writeString(session);
            long generation = nextOutputGeneration();
            data.writeLong(generation);
            data.writeInt(MotionRecordingOutput.VIEWING_PARCEL_VERSION);
            data.writeInt(view); data.writeInt(interiorMask); data.writeInt(exteriorReady ? 1 : 0);
            data.writeInt(specs.availableMask); data.writeIntArray(specs.widths); data.writeIntArray(specs.heights);
            data.writeString(language); data.writeInt(mirrorCabin ? 1 : 0);
            data.writeInt(encoder == null ? 0 : 1);
            if (encoder != null) encoder.writeToParcel(data, 0);
            IBinder target = host;
            if (target == null || !target.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("viewer output update unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("viewer output update refused");
            RemoteExteriorAttachment exterior = RemoteExteriorAttachment.readFromParcel(reply);
            Surface[] inputs = reply.createTypedArray(Surface.CREATOR);
            long[] inputGenerations = reply.createLongArray();
            this.mirrorCabin = mirrorCabin;
            interiors.attachInputs(inputs, specs.availableMask, inputGenerations);
            acknowledgeExterior(exterior);
        } finally { data.recycle(); reply.recycle(); }
    }

    public synchronized void setCabinMirrored(boolean mirrorCabin) throws Exception {
        if (closing || !attached || host == null) throw new IllegalStateException("viewer not attached");
        long generation = nextOutputGeneration();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(CameraRuntimeHost.VIEW_UPDATE_CABIN_MIRROR);
            data.writeStrongBinder(owner); data.writeString(session);
            data.writeInt(MotionRecordingOutput.VIEWING_PARCEL_VERSION);
            data.writeLong(generation); data.writeInt(mirrorCabin ? 1 : 0);
            IBinder target = host;
            if (!attached || target == null || !target.isBinderAlive()
                    || !target.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("viewer cabin mirror update unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("viewer cabin mirror update refused");
            this.mirrorCabin = mirrorCabin;
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Last mirror value acknowledged by the helper for this attached source. */
    public synchronized boolean isCabinMirrored() { return mirrorCabin; }

    private IBinder findHost() throws Exception {
        IBinder candidate = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, TurnSignalShellProtocol.SERVICE_NAME);
        TurnSignalController.Ping ping = TurnSignalController.ping(candidate);
        if (!ping.healthy()) throw new IllegalStateException("camera helper compatibility check failed: " + ping.error);
        return candidate;
    }

    private long nextOutputGeneration() {
        if (outputGeneration == Long.MAX_VALUE)
            throw new IllegalStateException("viewer output generation exhausted");
        return ++outputGeneration;
    }

    private void sourceChannelUnavailable(int channel, String reference) {
        if (channel < 4 || channel > 5) return;
        IBinder target = host;
        if (!attached || target == null || !target.isBinderAlive()) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(CameraRuntimeHost.VIEW_MARK_CHANNEL_UNAVAILABLE);
            data.writeStrongBinder(owner); data.writeString(session);
            data.writeInt(MotionRecordingOutput.VIEWING_PARCEL_VERSION);
            data.writeInt(channel); data.writeString(reference);
            if (!target.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera channel failure notification unavailable");
            reply.readException();
            if (reply.readInt() != 1) throw new IllegalStateException("camera channel failure notification refused");
        } catch (Throwable notificationFailure) {
            CameraViewingDiagnostics.failure("interior_channel_notification_" + reference,
                    "shared", notificationFailure);
        } finally { data.recycle(); reply.recycle(); }
    }

    private void composerChannelUnavailable(int channel, String reference) {
        CameraViewingDiagnostics.detail("source_composer_channel_failure", "shared",
                "source=" + session + " channel=" + channel + " ref=" + reference);
        interiors.failedByComposer(channel, reference);
    }

    private boolean matchesPrepared(int view, int mask) {
        int requested = RemoteCameraView.interiorMask(view, mask);
        return preparedView == view && allowedInteriorMask == mask
                && (specs.attemptedMask & requested) == requested;
    }

    private static void validateSelection(int view, int mask) {
        RemoteCameraView.validateMask(mask);
        if (!RemoteCameraView.valid(view) || !RemoteCameraView.allowed(view, mask))
            throw new IllegalArgumentException("viewer selection is not allowed");
    }

    private static String validateLocale(String locale) {
        if (locale == null || locale.length() > 16 || !locale.matches("[A-Za-z-]{1,16}"))
            throw new IllegalArgumentException("invalid viewer locale");
        return locale;
    }

    private static int nextRequestId() {
        return REQUEST_IDS.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
    }

    private void releasePreparation() throws Exception {
        Throwable first = null;
        if (preparationRequested) {
            try {
                RemoteCameraPreparation.release(context, owner, session);
                preparationRequested = false;
            } catch (Throwable error) { first = error; }
        }
        if (first != null) {
            if (first instanceof Exception) throw (Exception) first;
            if (first instanceof Error) throw (Error) first;
            throw new IllegalStateException("camera preparation release failed", first);
        }
    }

    @Override public synchronized void close() throws Exception {
        Throwable first = null;
        closing = true;
        attached = false;
        exteriorAttachment = new RemoteExteriorAttachment(false, null);
        try { interiors.close(); }
        catch (Throwable error) {
            first = error;
            CameraViewingDiagnostics.failure("interior_camera_close", "shared", error);
        }
        if (first != null) {
            closeFailure = first;
            CameraViewingDiagnostics.detail("viewer_detach_pending", "shared",
                    "session=" + session + " owned interior camera cleanup unresolved; helper and encoder ownership retained");
            if (first instanceof Exception) throw (Exception) first;
            if (first instanceof Error) throw (Error) first;
            throw new IllegalStateException("interior camera cleanup remains pending", first);
        }
        IBinder target = host;
        boolean detached = target == null || !target.isBinderAlive();
        if (target != null && !detached) {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
                data.writeInt(CameraRuntimeHost.VIEW_DETACH);
                data.writeStrongBinder(owner); data.writeString(session);
                if (!target.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                    throw new IllegalStateException("camera viewer detach unavailable");
                reply.readException();
                if (reply.readInt() != 1) throw new IllegalStateException("camera viewer detach refused");
                detached = true;
            } catch (Throwable error) {
                CameraViewingDiagnostics.failure("viewer_detach", "shared", error);
                if (first == null) first = error; else first.addSuppressed(error);
                detached = !target.isBinderAlive();
            } finally { data.recycle(); reply.recycle(); }
        }
        if (!detached) {
            if (first == null) first = new IllegalStateException("camera viewer remains attached to a live helper");
            closeFailure = first;
            CameraViewingDiagnostics.detail("viewer_detach_pending", "shared",
                    "session=" + session + " helper_alive=true; encoder and preparation ownership retained");
            if (first instanceof Exception) throw (Exception) first;
            if (first instanceof Error) throw (Error) first;
            throw new IllegalStateException("camera viewer remains attached to a live helper", first);
        }
        if (target != null && death != null) {
            try { target.unlinkToDeath(death, 0); }
            catch (Throwable error) {
                CameraViewingDiagnostics.failure("viewer_death_unlink", "shared", error);
                if (first == null) first = error; else first.addSuppressed(error);
            }
        }
        host = null; death = null;
        try { releasePreparation(); }
        catch (Throwable error) { if (first == null) first = error; else first.addSuppressed(error); }
        if (wakeLock.isHeld()) {
            try { wakeLock.release(); }
            catch (Throwable error) {
                CameraViewingDiagnostics.failure("viewer_wakelock_release", "shared", error);
                if (first == null) first = error; else first.addSuppressed(error);
            }
        }
        if (first != null) closeFailure = first;
        if (first instanceof Exception) throw (Exception) first;
        if (first instanceof Error) throw (Error) first;
        if (first != null) throw new IllegalStateException("remote camera source close failed", first);
        closeFailure = null;
    }

    public boolean isVideoDetached() { return host == null; }

    public String sessionId() { return session; }

    /** Reports the most recent external preparation result without coupling it to interior readiness. */
    public boolean isExteriorReady() { return exteriorReady; }

    public boolean isExteriorAttached() { return exteriorAttachment.attached; }

    public boolean needsExteriorRecovery(int view) { return exteriorAttachment.needsRecovery(view); }

    public String exteriorErrorReference() { return exteriorAttachment.errorReference; }

    private void acknowledgeExterior(RemoteExteriorAttachment state) {
        RemoteExteriorAttachment previous = exteriorAttachment;
        exteriorAttachment = state;
        if (previous.attached != state.attached
                || !java.util.Objects.equals(previous.errorReference, state.errorReference)) {
            CameraViewingDiagnostics.detail("exterior_attachment", "shared", "session=" + session
                    + " prepared=" + exteriorReady + " attached=" + state.attached
                    + " error_ref=" + state.errorReference);
        }
    }
}
