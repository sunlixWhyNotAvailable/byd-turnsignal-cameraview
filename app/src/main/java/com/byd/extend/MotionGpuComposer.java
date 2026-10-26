package com.byd.extend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.TimeZone;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Shared owned camera snapshots directly to the encoder; no intermediate Surface queues. */
final class MotionGpuComposer implements AutoCloseable {
    private static final String TAG = "MotionGpuComposer";
    private static final long STARTUP_INPUT_TIMEOUT_MS = 10_000L;
    private static final long GL_TASK_TIMEOUT_MS = 5_000L;
    private final HandlerThread thread = new HandlerThread("motion-video-gpu");
    private final Handler handler;
    private final Object lifecycleLock = new Object();
    private final CameraFramePump[][] incoming = new CameraFramePump[6][CameraFrameMailbox.SLOT_COUNT];
    private final CameraFramePump[] pumps = new CameraFramePump[6];
    private final int[] slots = {-1, -1, -1, -1, -1, -1};
    private final boolean[] seen = new boolean[6];
    private final boolean[] unavailable = new boolean[6];
    private final float[][] transforms = new float[6][16];
    private final int[] names = new int[6];
    private final CameraFramePump[] liveInputPumps = new CameraFramePump[2];
    private final int[] liveInputWidths = new int[2];
    private final int[] liveInputHeights = new int[2];
    private final int[] unavailableTextures = new int[6];
    private final int[] unavailableTextureWidths = new int[6];
    private final int[] unavailableTextureHeights = new int[6];
    private final String[] unavailableTextureLocales = new String[6];
    private final int[] iconTextures = new int[4];
    private final int[] timestampTexture = new int[1];
    private final MotionCompositionLayout.Rect[] cameraRects = new MotionCompositionLayout.Rect[4];
    private final int width, height;
    private final int[] order;
    private final MotionFrameTimestamp frameTimestamp;
    private final Consumer<String> errors;
    private final BiConsumer<Integer, String> channelFailure;
    private final Context appContext;
    private final String sessionId;
    private final boolean shellWorker;
    private final boolean remoteLive;
    private int liveView;
    private int allowedInteriorMask;
    private int availableInteriorMask;
    private int[] interiorWidths;
    private int[] interiorHeights;
    private String liveLocale;
    private boolean includeExterior;
    private final EGLContext sharedContext;
    private final int glVersion;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int selectedDirection = -1;
    private int program, iconProgram, position, uv, matrix, sampler;
    private int iconPosition, iconUv, iconSampler;
    private String renderedTimestamp;
    private long timestampSecond = Long.MIN_VALUE;
    private int timestampWidth, timestampHeight, timestampInset;
    private boolean queued;
    private volatile boolean closed;
    private boolean initializationRequested;
    private FutureTask<Void> cleanupTask;
    private Runnable closeCallback;
    private boolean cleanupResultPublished;
    private boolean cleanupSucceeded;
    private boolean closeCallbackDelivered;
    private boolean startupMissingReported;
    private boolean firstCompositionLogged;
    private final boolean[] firstInputLogged = new boolean[6];
    private final long[] firstInputTimestampNs = new long[6];
    private long lastFrameMs;
    private final Runnable missingInputDeadline = this::reportMissingInputs;
    private final FloatBuffer vertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer coordinates = floats(0,0, 1,0, 0,1, 1,1);
    private final FloatBuffer iconVertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    // Android bitmaps upload top row at GL v=0; flip v to keep the OEM PNG upright.
    private final FloatBuffer iconCoordinates = floats(0,1, 1,1, 0,0, 1,0);
    private final Runnable draw = this::render;

    MotionGpuComposer(Context resourcesContext, int width, int height,
            int[] order, String sessionId, MotionFrameTimestamp frameTimestamp,
            EGLContext sharedContext, int glVersion, Consumer<String> errors) throws Exception {
        this(resourcesContext, width, height, order, sessionId, frameTimestamp, sharedContext, glVersion,
                false, RemoteCameraView.COMPOSITION, RemoteCameraView.NONE, RemoteCameraView.NONE,
                new int[2], new int[2], "en", true, errors, (channel, reference) -> { });
    }

    MotionGpuComposer(Context resourcesContext, int width, int height,
            int[] order, String sessionId, MotionFrameTimestamp frameTimestamp,
            EGLContext sharedContext, int glVersion, boolean remoteLive, int liveView,
            int allowedInteriorMask, int availableInteriorMask, int[] interiorWidths,
            int[] interiorHeights, String liveLocale, boolean includeExterior,
            Consumer<String> errors) throws Exception {
        this(resourcesContext, width, height, order, sessionId, frameTimestamp, sharedContext, glVersion,
                remoteLive, liveView, allowedInteriorMask, availableInteriorMask, interiorWidths,
                interiorHeights, liveLocale, includeExterior, errors, (channel, reference) -> { });
    }

    MotionGpuComposer(Context resourcesContext, int width, int height,
            int[] order, String sessionId, MotionFrameTimestamp frameTimestamp,
            EGLContext sharedContext, int glVersion, boolean remoteLive, int liveView,
            int allowedInteriorMask, int availableInteriorMask, int[] interiorWidths,
            int[] interiorHeights, String liveLocale, boolean includeExterior,
            Consumer<String> errors, BiConsumer<Integer, String> channelFailure) throws Exception {
        this.appContext = resourcesContext;
        this.width = width; this.height = height; this.order = order.clone();
        this.remoteLive = remoteLive;
        this.liveView = liveView;
        this.allowedInteriorMask = allowedInteriorMask;
        this.availableInteriorMask = availableInteriorMask;
        this.interiorWidths = interiorWidths.clone();
        this.interiorHeights = interiorHeights.clone();
        this.liveLocale = liveLocale;
        this.includeExterior = includeExterior;
        if (remoteLive) selectedDirection = liveView;
        if (frameTimestamp == null) throw new IllegalArgumentException("Motion frame clock is required");
        this.frameTimestamp = frameTimestamp;
        for (int cell = 0; cell < cameraRects.length; cell++) {
            cameraRects[cell] = MotionCompositionLayout.cameraRectForCell(cell, width, height);
            if (cameraRects[cell] == null)
                throw new IllegalArgumentException("Motion output geometry is invalid");
        }
        this.errors = errors == null ? ignored -> { } : errors;
        this.channelFailure = channelFailure == null ? (channel, reference) -> { } : channelFailure;
        this.sessionId = sessionId;
        this.shellWorker = true;
        this.sharedContext = sharedContext;
        this.glVersion = glVersion;
        thread.start();
        Looper looper = thread.getLooper();
        if (looper == null) {
            thread.quitSafely();
            throw new IllegalStateException("GPU worker looper unavailable");
        }
        handler = new Handler(looper);
    }

    boolean offer(int sourceIndex, CameraFramePump pump, int slot) {
        int direction = sourceIndex == 4 ? 0 : sourceIndex;
        if (direction < 0 || direction >= 4) return false;
        synchronized (lifecycleLock) {
            if (closed) return false;
            incoming[direction][slot] = pump;
            boolean posted = handler.post(() -> {
                try { receive(direction, pump, slot); }
                catch (RuntimeException failure) {
                    if (remoteLive) failLiveChannelOnGlThread(direction, "gpu_receive", failure, direction >= 4);
                    else {
                        closed = true;
                        logDiagnostic("snapshot_receive_failed", failure.toString());
                        errors.accept(failure.toString());
                    }
                }
            });
            if (!posted) incoming[direction][slot] = null;
            return posted;
        }
    }

    boolean offerInterior(int channel, CameraFramePump pump, int slot) {
        if (!remoteLive || channel < 0 || channel > 1) return false;
        int direction = channel + 4;
        synchronized (lifecycleLock) {
            if (closed) return false;
            incoming[direction][slot] = pump;
            boolean posted = handler.post(() -> {
                try { receive(direction, pump, slot); }
                catch (RuntimeException failure) {
                    failLiveChannelOnGlThread(direction, "interior_snapshot_receive", failure, true);
                }
            });
            if (!posted) incoming[direction][slot] = null;
            return posted;
        }
    }

    boolean offerComposition(CameraFramePump pump, int slot) {
        synchronized (lifecycleLock) {
            if (closed) return false;
            incoming[0][slot] = pump;
            boolean posted = handler.post(() -> {
                try {
                    receive(0, pump, slot);
                    if (closed) return;
                    for (int direction = 0; direction < 4; direction++) {
                        names[direction] = pump.frames[slot].texture;
                        android.opengl.Matrix.setIdentityM(transforms[direction], 0);
                        transforms[direction][0] = .5f;
                        transforms[direction][5] = .5f;
                        transforms[direction][12] = (direction % 2) * .5f;
                        transforms[direction][13] = direction < 2 ? .5f : 0f;
                        seen[direction] = true;
                        frame(direction);
                    }
                } catch (RuntimeException failure) {
                    closed = true;
                    logDiagnostic("shared_snapshot_failed", failure.toString());
                    errors.accept(failure.toString());
                }
            });
            if (!posted) incoming[0][slot] = null;
            return posted;
        }
    }

    private void receive(int direction, CameraFramePump pump, int slot) {
        require(EGL14.eglMakeCurrent(display, window, window, context), "snapshot receive current");
        pump.awaitRead(slot);
        if (closed) {
            releaseIncoming(direction, slot);
            return;
        }
        releaseFrame(direction);
        pumps[direction] = pump; slots[direction] = slot;
        synchronized (lifecycleLock) { incoming[direction][slot] = null; }
        names[direction] = pump.frames[slot].texture;
        android.opengl.Matrix.setIdentityM(transforms[direction], 0);
        seen[direction] = true;
        if (firstInputTimestampNs[direction] == 0) {
            firstInputTimestampNs[direction] = pump.frames[slot].producerNs;
            logDiagnostic("first_input_frame", "direction=" + directionName(direction)
                    + " source_timestamp_ns=" + firstInputTimestampNs[direction]);
        }
        frame(direction);
    }

    void detachSource(int sourceIndex) {
        int direction = sourceIndex == 4 ? 0 : sourceIndex;
        FutureTask<Void> task = new FutureTask<>(() -> {
            require(EGL14.eglMakeCurrent(display, window, window, context), "snapshot detach current");
            for (int slot = 0; slot < incoming[direction].length; slot++)
                releaseIncoming(direction, slot);
            releaseFrame(direction);
            seen[direction] = false;
            return null;
        });
        if (!handler.post(task)) throw new IllegalStateException("composer refused detach");
        try { await(task, "snapshot detach"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    void detachInterior(int channel) {
        if (channel < 0 || channel > 1) throw new IllegalArgumentException("invalid interior channel");
        FutureTask<Void> task = new FutureTask<>(() -> {
            markUnavailableOnGlThread(channel + 4, "interior_detached");
            return null;
        });
        if (!handler.post(task)) throw new IllegalStateException("composer refused interior detach");
        try { await(task, "interior detach"); }
        catch (Exception failure) { throw new IllegalStateException("interior detach failed", failure); }
    }

    private void releaseIncoming(int direction, int slot) {
        synchronized (lifecycleLock) {
            CameraFramePump pump = incoming[direction][slot];
            if (pump == null) return;
            pump.awaitRead(slot);
            pump.releaseRead(slot);
            incoming[direction][slot] = null;
        }
    }

    private void releaseFrame(int direction) {
        if (slots[direction] < 0) return;
        pumps[direction].releaseRead(slots[direction]);
        slots[direction] = -1;
        pumps[direction] = null;
    }

    /** Initializes EGL only after the owner has retained this composer for teardown. */
    void initialize(Surface encoder) throws Exception {
        if (encoder == null) throw new IllegalArgumentException("Motion encoder surface is required");
        final FutureTask<Void> task;
        synchronized (lifecycleLock) {
            if (closed || cleanupTask != null)
                throw new IllegalStateException("GPU composer is closing");
            if (initializationRequested)
                throw new IllegalStateException("GPU composer is already initialized or initializing");
            initializationRequested = true;
            task = new FutureTask<>(() -> {
                initializeOnGlThread(encoder);
                if (remoteLive) {
                    require(EGL14.eglMakeCurrent(display, window, window, context), "live source current");
                    for (int channel = 0; channel < 2; channel++) {
                        int bit = 1 << channel;
                        if ((RemoteCameraView.interiorMask(liveView, allowedInteriorMask) & bit) != 0
                                && (availableInteriorMask & bit) == 0)
                            unavailable[channel + 4] = true;
                    }
                    if (!includeExterior && RemoteCameraView.needsExterior(liveView))
                        for (int channel = 0; channel < 4; channel++) unavailable[channel] = true;
                    configureLiveInteriors(null);
                    frame(0);
                }
                scheduleMissingInputDeadline();
                return null;
            });
            if (!handler.post(task))
                throw new IllegalStateException("GPU worker rejected initialization");
        }
        await(task, "GPU initialization");
    }

    private void scheduleMissingInputDeadline() {
        handler.removeCallbacks(missingInputDeadline);
        if (!closed) handler.postDelayed(missingInputDeadline, STARTUP_INPUT_TIMEOUT_MS);
    }

    private void initializeOnGlThread(Surface encoder) {
        // The hub owns the shared display; only its final teardown may terminate it.
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] values = new int[2];
        EGLConfig[] configs = new EGLConfig[1];
        require(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                0x3142,1,EGL14.EGL_NONE},
                0, configs, 0, 1, values, 0) && values[0] > 0, "recordable EGL config");
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, sharedContext,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,glVersion,EGL14.EGL_NONE},0);
        window = EGL14.eglCreateWindowSurface(display, configs[0], encoder,
                new int[]{EGL14.EGL_NONE},0);
        require(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE, "encoder EGL surface");
        require(EGL14.eglMakeCurrent(display,window,window,context), "EGL current");
        program = CameraDewarpRenderer.linkProgram("attribute vec2 p;attribute vec2 t;uniform mat4 m;varying vec2 uv;"
                + "void main(){gl_Position=vec4(p,0.,1.);uv=(m*vec4(t,0.,1.)).xy;}",
                "precision mediump float;"
                + "uniform sampler2D s;varying vec2 uv;void main(){gl_FragColor=texture2D(s,uv);}");
        iconProgram = CameraDewarpRenderer.linkProgram("attribute vec2 p;attribute vec2 t;varying vec2 uv;"
                + "void main(){gl_Position=vec4(p,0.,1.);uv=t;}",
                "precision mediump float;uniform sampler2D s;varying vec2 uv;"
                + "void main(){gl_FragColor=texture2D(s,uv);}");
        position=GLES20.glGetAttribLocation(program,"p"); uv=GLES20.glGetAttribLocation(program,"t");
        matrix=GLES20.glGetUniformLocation(program,"m"); sampler=GLES20.glGetUniformLocation(program,"s");
        iconPosition=GLES20.glGetAttribLocation(iconProgram,"p");
        iconUv=GLES20.glGetAttribLocation(iconProgram,"t");
        iconSampler=GLES20.glGetUniformLocation(iconProgram,"s");
        loadDirectionIcons();
        GLES20.glGenTextures(timestampTexture.length, timestampTexture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private void loadDirectionIcons() {
        GLES20.glGenTextures(iconTextures.length, iconTextures, 0);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        for (int direction = 0; direction < iconTextures.length; direction++) {
            Bitmap bitmap = BitmapFactory.decodeResource(
                    appContext.getResources(), MotionDirectionIcon.drawableFor(direction), options);
            if (bitmap == null) throw new IllegalStateException(
                    "Motion OEM direction icon unavailable: " + direction);
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iconTextures[direction]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            } finally {
                bitmap.recycle();
            }
        }
    }

    private void frame(int index) {
        if(closed) return;
        if (!firstInputLogged[index]) {
            firstInputLogged[index] = true;
            logDiagnostic("first_input_callback", "direction=" + directionName(index));
        }
        if(!queued) {
            queued=true;
            handler.postDelayed(draw,Math.max(0,40-(SystemClock.elapsedRealtime()-lastFrameMs)));
        }
    }

    /** Switch only the encoder queue; source consumers and retained camera snapshots stay alive. */
    void updateViewingOutput(Surface encoder, int direction) throws Exception {
        if (direction < -1 || direction > 3 || encoder != null && !encoder.isValid())
            throw new IllegalArgumentException("invalid viewer output");
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (closed || window == EGL14.EGL_NO_SURFACE)
                throw new IllegalStateException("viewer composer unavailable");
            replaceWindow(encoder);
            selectedDirection = direction;
            timestampSecond = Long.MIN_VALUE;
            renderedTimestamp = null;
            frame(direction < 0 ? 0 : direction);
            return null;
        });
        synchronized (lifecycleLock) {
            if (closed || !handler.post(task)) throw new IllegalStateException("viewer composer closed");
        }
        await(task, "viewer output update");
    }

    Surface[] updateLiveViewingOutput(Surface encoder, int view, int allowedMask,
            boolean exteriorReady, int availableMask, int[] widths, int[] heights,
            String locale) throws Exception {
        validateLiveSelection(view, allowedMask, exteriorReady, availableMask, widths, heights, locale);
        final FutureTask<Surface[]> task = new FutureTask<>(() -> {
            if (!remoteLive || closed || window == EGL14.EGL_NO_SURFACE)
                throw new IllegalStateException("remote live composer unavailable");
            require(EGL14.eglMakeCurrent(display, window, window, context), "live output update current");
            replaceWindow(encoder);
            liveView = view; selectedDirection = view; allowedInteriorMask = allowedMask;
            includeExterior = exteriorReady; availableInteriorMask = availableMask;
            interiorWidths = widths.clone(); interiorHeights = heights.clone(); liveLocale = locale;
            startupMissingReported = false;
            for (int channel = 0; channel < 4; channel++) {
                boolean expected = channelExpected(channel);
                if (!expected) unavailable[channel] = false;
                else unavailable[channel] = !includeExterior;
            }
            int requested = RemoteCameraView.interiorMask(liveView, allowedInteriorMask);
            for (int channel = 0; channel < 2; channel++) {
                int bit = 1 << channel;
                unavailable[channel + 4] = (requested & bit) != 0
                        && (availableInteriorMask & bit) == 0;
            }
            Surface[] created = new Surface[2];
            configureLiveInteriors(created);
            timestampSecond = Long.MIN_VALUE;
            renderedTimestamp = null;
            frame(0);
            scheduleMissingInputDeadline();
            return created;
        });
        synchronized (lifecycleLock) {
            if (closed || !handler.post(task)) throw new IllegalStateException("viewer composer closed");
        }
        try { return awaitResult(task, "live output update"); }
        catch (Exception failure) { throw failure; }
    }

    Surface[] liveInteriorInputs() throws Exception {
        FutureTask<Surface[]> task = new FutureTask<>(() -> {
            Surface[] surfaces = new Surface[2];
            for (int channel = 0; channel < surfaces.length; channel++)
                if (liveInputPumps[channel] != null) surfaces[channel] = liveInputPumps[channel].surface;
            return surfaces;
        });
        if (!handler.post(task)) throw new IllegalStateException("composer refused live inputs");
        return awaitResult(task, "live input surfaces");
    }

    void markLiveChannelUnavailable(int channel, String reference) {
        if (!remoteLive || channel < 0 || channel > 5) return;
        handler.post(() -> markUnavailableOnGlThread(channel,
                reference == null || reference.isEmpty() ? "source_failed" : reference));
    }

    private void failLiveChannelOnGlThread(int channel, String stage, Throwable failure,
            boolean notifySource) {
        String reference = java.util.UUID.randomUUID().toString().replace("-", "");
        try { markUnavailableOnGlThread(channel, reference, notifySource, failure); }
        catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        com.byd.extend.remote.CameraViewingDiagnostics.failure(
                "live_" + stage + "_" + directionName(channel) + "_" + reference,
                "shared", failure);
    }

    private void replaceWindow(Surface encoder) {
        if (encoder == null) return;
        EGLSurface next = EGL14.eglCreateWindowSurface(display, config, encoder,
                new int[]{EGL14.EGL_NONE}, 0);
        require(next != EGL14.EGL_NO_SURFACE, "viewer replacement surface");
        EGLSurface previous = window;
        try {
            require(EGL14.eglMakeCurrent(display, previous, previous, context), "viewer previous current");
            GLES20.glFinish();
            require(EGL14.eglMakeCurrent(display, next, next, context), "viewer replacement current");
            require(EGL14.eglDestroySurface(display, previous), "viewer previous detach");
            window = next;
        } catch (RuntimeException failure) {
            if (!EGL14.eglMakeCurrent(display, previous, previous, context))
                failure.addSuppressed(new IllegalStateException("viewer surface restore failed"));
            if (!EGL14.eglDestroySurface(display, next))
                failure.addSuppressed(new IllegalStateException("viewer replacement cleanup failed"));
            throw failure;
        }
    }

    private void configureLiveInteriors(Surface[] created) {
        int requested = RemoteCameraView.interiorMask(liveView, allowedInteriorMask);
        for (int channel = 0; channel < 2; channel++) {
            int bit = 1 << channel;
            CameraFramePump current = liveInputPumps[channel];
            if ((requested & bit) == 0 || (availableInteriorMask & bit) == 0) {
                if (current != null) closeLiveInput(channel, current);
                continue;
            }
            if (current != null && (liveInputWidths[channel] != interiorWidths[channel]
                    || liveInputHeights[channel] != interiorHeights[channel])) {
                closeLiveInput(channel, current);
                current = null;
            }
            if (current == null) {
                final int channelIndex = channel;
                int sourceWidth = interiorWidths[channel], sourceHeight = interiorHeights[channel];
                CameraFramePump pump = new CameraFramePump(display, config, context, glVersion,
                        channel + 5, sourceWidth, sourceHeight, new CameraFramePump.Listener() {
                    @Override public void available() { captureLiveInput(channelIndex, pumpRef[channelIndex]); }
                    @Override public void failed(Throwable error) {
                        handler.post(() -> failLiveChannelOnGlThread(
                                channelIndex + 4, "camera_acquire", error, true));
                    }
                    @Override public void acquired(CameraFramePump.Acquisition sample) { }
                    private final CameraFramePump[] pumpRef = liveInputPumps;
                });
                liveInputPumps[channel] = pump;
                liveInputWidths[channel] = sourceWidth;
                liveInputHeights[channel] = sourceHeight;
                try {
                    pump.start();
                    RemoteCameraLiveLayout.Rect bounds = RemoteCameraLiveLayout.channelBounds(
                            liveView, allowedInteriorMask, channel + 4, width, height);
                    RemoteCameraLiveLayout.Rect fit = RemoteCameraLiveLayout.fit(
                            bounds, sourceWidth, sourceHeight);
                    pump.setOutputSize(fit == null ? sourceWidth : fit.width(),
                            fit == null ? sourceHeight : fit.height());
                    unavailable[channel + 4] = false;
                    if (created != null) created[channel] = pump.surface;
                    logDiagnostic("interior_input_ready", "channel=" + directionName(channel + 4)
                            + " source=" + sourceWidth + "x" + sourceHeight);
                } catch (Throwable failure) {
                    unavailable[channel + 4] = true;
                    availableInteriorMask &= ~bit;
                    liveInputPumps[channel] = null;
                    try { pump.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                    com.byd.extend.remote.CameraViewingDiagnostics.failure(
                            "live_camera_input_" + directionName(channel + 4), "shared", failure);
                    logDiagnostic("interior_input_unavailable", "channel=" + directionName(channel + 4)
                            + " cause=" + failure);
                }
            } else {
                RemoteCameraLiveLayout.Rect bounds = RemoteCameraLiveLayout.channelBounds(
                        liveView, allowedInteriorMask, channel + 4, width, height);
                RemoteCameraLiveLayout.Rect fit = RemoteCameraLiveLayout.fit(
                        bounds, interiorWidths[channel], interiorHeights[channel]);
                current.setOutputSize(fit == null ? interiorWidths[channel] : fit.width(),
                        fit == null ? interiorHeights[channel] : fit.height());
            }
        }
    }

    private void captureLiveInput(int channel, CameraFramePump pump) {
        if (pump == null || closed) return;
        int slot = pump.mailbox.acquire();
        if (slot < 0) return;
        boolean transferred = false;
        try {
            pump.transferRead(slot);
            transferred = offerInterior(channel, pump, slot);
            if (!transferred) pump.awaitRead(slot);
        } catch (Throwable failure) {
            handler.post(() -> failLiveChannelOnGlThread(
                    channel + 4, "camera_frame_handoff", failure, true));
        } finally {
            if (!transferred) {
                try { pump.releaseRead(slot); }
                catch (Throwable failure) {
                    Log.w(TAG, "Interior camera frame release failed: " + directionName(channel + 4), failure);
                }
            }
        }
    }

    private void closeLiveInput(int channel, CameraFramePump pump) {
        liveInputPumps[channel] = null;
        liveInputWidths[channel] = liveInputHeights[channel] = 0;
        seen[channel + 4] = false;
        try { pump.close(); }
        catch (Throwable failure) {
            com.byd.extend.remote.CameraViewingDiagnostics.failure(
                    "live_camera_cleanup_" + directionName(channel + 4), "shared", failure);
        }
    }

    private void markUnavailableOnGlThread(int channel, String reference) {
        markUnavailableOnGlThread(channel, reference, false, null);
    }

    private void markUnavailableOnGlThread(int channel, String reference,
            boolean notifySource, Throwable primary) {
        if (!remoteLive || channel < 0 || channel > 5 || closed) return;
        require(EGL14.eglMakeCurrent(display, window, window, context), "live failure current");
        for (int slot = 0; slot < incoming[channel].length; slot++) {
            try { releaseIncoming(channel, slot); }
            catch (Throwable failure) {
                if (primary != null) primary.addSuppressed(failure);
                else com.byd.extend.remote.CameraViewingDiagnostics.failure(
                        "live_input_cleanup_" + directionName(channel) + "_" + reference, "shared", failure);
            }
        }
        try { releaseFrame(channel); }
        catch (Throwable failure) {
            if (primary != null) primary.addSuppressed(failure);
            else com.byd.extend.remote.CameraViewingDiagnostics.failure(
                    "live_frame_cleanup_" + directionName(channel) + "_" + reference, "shared", failure);
        }
        unavailable[channel] = true;
        seen[channel] = false;
        if (channel >= 4) {
            int index = channel - 4;
            CameraFramePump pump = liveInputPumps[index];
            if (pump != null) closeLiveInput(index, pump);
            availableInteriorMask &= ~(1 << index);
        }
        logDiagnostic("live_channel_unavailable", "channel=" + directionName(channel)
                + " ref=" + reference);
        if (primary == null) com.byd.extend.remote.CameraViewingDiagnostics.detail(
                "live_channel_unavailable", "shared",
                "channel=" + directionName(channel) + " diagnostic_ref=" + reference);
        if (notifySource && channel >= 4) {
            try { channelFailure.accept(channel, reference); }
            catch (Throwable failure) {
                if (primary != null) primary.addSuppressed(failure);
                else com.byd.extend.remote.CameraViewingDiagnostics.failure(
                        "live_channel_callback_" + directionName(channel) + "_" + reference,
                        "shared", failure);
            }
        }
        frame(channel);
    }

    private boolean channelExpected(int channel) {
        if (channel < 4) {
            if (!RemoteCameraView.needsExterior(liveView)) return false;
            return liveView == RemoteCameraView.COMPOSITION || liveView == RemoteCameraView.COMBINED
                    || liveView == channel;
        }
        int bit = 1 << (channel - 4);
        return (RemoteCameraView.interiorMask(liveView, allowedInteriorMask) & bit) != 0;
    }

    private static void validateLiveSelection(int view, int allowedMask, boolean exteriorReady,
            int availableMask, int[] widths, int[] heights, String locale) {
        RemoteCameraView.validateMask(allowedMask);
        RemoteCameraView.validateMask(availableMask);
        if (!RemoteCameraView.valid(view) || !RemoteCameraView.allowed(view, allowedMask)
                || widths == null || widths.length != 2 || heights == null || heights.length != 2
                || locale == null || !locale.matches("[A-Za-z-]{1,16}")
                || exteriorReady && !RemoteCameraView.needsExterior(view))
            throw new IllegalArgumentException("invalid remote live source selection");
        for (int channel = 0; channel < 2; channel++) {
            if ((availableMask & (1 << channel)) != 0
                    && (widths[channel] < 2 || heights[channel] < 2))
                throw new IllegalArgumentException("invalid live interior dimensions");
        }
    }

    private static <T> T awaitResult(FutureTask<T> task, String operation) throws Exception {
        try { return task.get(GL_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (TimeoutException pending) {
            throw new IllegalStateException(operation + " remains pending", pending);
        } catch (ExecutionException execution) {
            Throwable failure = execution.getCause() == null ? execution : execution.getCause();
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException(operation + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw new IllegalStateException(operation + " was cancelled", cancelled);
        }
    }

    private void render() {
        queued=false;
        if(closed) return;
        try {
            require(EGL14.eglMakeCurrent(display,window,window,context),"EGL render current");
            if (remoteLive) {
                if (!remoteFrameCanStart()) return;
            } else if (selectedDirection < 0) {
                // Motion recording keeps its strict four-exterior startup contract.
                for (int direction = 0; direction < 4; direction++) if (!seen[direction]) return;
            } else if (selectedDirection >= 0 && selectedDirection < 4
                    && !seen[selectedDirection]) return;
            long presentationTimeNs=SystemClock.elapsedRealtimeNanos();
            updateTimestampTexture(presentationTimeNs/1_000_000L);
            GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (remoteLive) drawRemoteLiveChannels();
            else drawMotionRecordingChannels();
            EGLExt.eglPresentationTimeANDROID(display,window,presentationTimeNs);
            require(EGL14.eglSwapBuffers(display,window),"encoder swap");
            lastFrameMs=presentationTimeNs/1_000_000;
            if (remoteLive && !queued) {
                queued = true;
                handler.postDelayed(draw, 40);
            }
            if (!firstCompositionLogged) {
                firstCompositionLogged = true;
                logDiagnostic("first_composed_frame", "presentation_timestamp_ns=" + presentationTimeNs);
            }
        } catch(RuntimeException failure) {
            closed=true;
            if (remoteLive) {
                String reference = java.util.UUID.randomUUID().toString().replace("-", "");
                com.byd.extend.remote.CameraViewingDiagnostics.failure(
                        "live_render_failed_" + reference, "shared", failure);
                logDiagnostic("render_failed", "diagnostic_ref=" + reference);
                errors.accept(reference);
            } else {
                logDiagnostic("render_failed", failure.toString());
                errors.accept(failure.toString());
            }
        }
    }

    private boolean remoteFrameCanStart() {
        if (startupMissingReported) return true;
        for (int channel = 0; channel < seen.length; channel++)
            if (channelExpected(channel) && (seen[channel] || unavailable[channel])) return true;
        return false;
    }

    private void drawMotionRecordingChannels() {
        for (int cell = 0; cell < (selectedDirection < 0 ? 4 : 1); cell++) {
            int direction = selectedDirection < 0 ? order[cell] : selectedDirection;
            MotionCompositionLayout.Rect camera = selectedDirection < 0 ? cameraRects[cell]
                    : MotionCompositionLayout.singleCameraRect(width, height);
            if (camera != null) drawCamera(direction, camera.left, camera.top,
                    camera.width(), camera.height(), true);
        }
    }

    private void drawRemoteLiveChannels() {
        int requested = RemoteCameraView.interiorMask(liveView, allowedInteriorMask);
        for (int channel = 0; channel < seen.length; channel++) {
            RemoteCameraLiveLayout.Rect bounds = RemoteCameraLiveLayout.channelBounds(
                    liveView, allowedInteriorMask, channel, width, height);
            if (bounds == null) continue;
            if (seen[channel]) {
                int sourceWidth = channel < 4 ? 1920 : interiorWidths[channel - 4];
                int sourceHeight = channel < 4 ? 1300 : interiorHeights[channel - 4];
                RemoteCameraLiveLayout.Rect fitted = RemoteCameraLiveLayout.fit(
                        bounds, sourceWidth, sourceHeight);
                if (fitted != null) drawCamera(channel, fitted.left, fitted.top,
                        fitted.width(), fitted.height(), channel < 4);
            } else if (unavailable[channel] || startupMissingReported) {
                drawUnavailable(channel, bounds);
            }
        }
    }

    private void drawCamera(int direction, int left, int top, int drawWidth, int drawHeight,
            boolean exterior) {
        GLES20.glViewport(left, height - top - drawHeight, drawWidth, drawHeight);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[direction]);
        GLES20.glUniform1i(sampler, 0);
        GLES20.glUniformMatrix4fv(matrix, 1, false, transforms[direction], 0);
        vertices.position(0); coordinates.position(0);
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(uv);
        if (!exterior) return;
        int size = Math.max(1, Math.round(drawWidth * .05f));
        int inset = Math.max(0, Math.round(drawWidth * .0083f));
        drawOverlay(iconTextures[direction], left + inset, top + inset, size, size);
        drawOverlay(timestampTexture[0], left + timestampInset,
                top + drawHeight - timestampInset - timestampHeight,
                timestampWidth, timestampHeight);
    }

    private void drawUnavailable(int channel, RemoteCameraLiveLayout.Rect bounds) {
        int texture = unavailableTexture(channel, bounds.width(), bounds.height());
        // The opaque tile covers only its viewport; glClear would erase every camera.
        drawOverlay(texture, bounds.left, bounds.top, bounds.width(), bounds.height());
    }

    private int unavailableTexture(int channel, int textureWidth, int textureHeight) {
        if (unavailableTextures[channel] != 0
                && unavailableTextureWidths[channel] == textureWidth
                && unavailableTextureHeights[channel] == textureHeight
                && java.util.Objects.equals(unavailableTextureLocales[channel], liveLocale))
            return unavailableTextures[channel];
        Bitmap bitmap = Bitmap.createBitmap(textureWidth, textureHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(12, 14, 17));
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            text.setTextSize(Math.max(1f, Math.min(textureWidth * .045f, textureHeight * .12f)));
            String[] lines = unavailableLabel(channel).split("\\n");
            Paint.FontMetrics metrics = text.getFontMetrics();
            float lineHeight = metrics.descent - metrics.ascent;
            float firstBaseline = (textureHeight - lines.length * lineHeight) / 2f - metrics.ascent;
            for (int line = 0; line < lines.length; line++) {
                float x = (textureWidth - text.measureText(lines[line])) / 2f;
                canvas.drawText(lines[line], x, firstBaseline + line * lineHeight, text);
            }
            if (unavailableTextures[channel] == 0) {
                GLES20.glGenTextures(1, unavailableTextures, channel);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, unavailableTextures[channel]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            unavailableTextureWidths[channel] = textureWidth;
            unavailableTextureHeights[channel] = textureHeight;
            unavailableTextureLocales[channel] = liveLocale;
            return unavailableTextures[channel];
        } finally { bitmap.recycle(); }
    }

    private String unavailableLabel(int channel) {
        String language = liveLocale == null ? "en" : liveLocale.toLowerCase(java.util.Locale.ROOT);
        if (language.startsWith("uk")) {
            switch (channel) {
                case 0: return "ПЕРЕДНЯ КАМЕРА\nНЕДОСТУПНА";
                case 1: return "ЗАДНЯ КАМЕРА\nНЕДОСТУПНА";
                case 2: return "ЛІВА КАМЕРА\nНЕДОСТУПНА";
                case 3: return "ПРАВА КАМЕРА\nНЕДОСТУПНА";
                case 4: return "КАМЕРА САЛОНУ\nНЕДОСТУПНА";
                default: return "КАМЕРА ВОДІЯ\nНЕДОСТУПНА";
            }
        }
        if (language.startsWith("ru")) {
            switch (channel) {
                case 0: return "ПЕРЕДНЯЯ КАМЕРА\nНЕДОСТУПНА";
                case 1: return "ЗАДНЯЯ КАМЕРА\nНЕДОСТУПНА";
                case 2: return "ЛЕВАЯ КАМЕРА\nНЕДОСТУПНА";
                case 3: return "ПРАВАЯ КАМЕРА\nНЕДОСТУПНА";
                case 4: return "КАМЕРА САЛОНА\nНЕДОСТУПНА";
                default: return "КАМЕРА ВОДИТЕЛЯ\nНЕДОСТУПНА";
            }
        }
        if (language.startsWith("zh")) {
            switch (channel) {
                case 0: return "前置摄像头不可用";
                case 1: return "后置摄像头不可用";
                case 2: return "左侧摄像头不可用";
                case 3: return "右侧摄像头不可用";
                case 4: return "车内摄像头不可用";
                default: return "驾驶员摄像头不可用";
            }
        }
        switch (channel) {
            case 0: return "FRONT CAMERA\nUNAVAILABLE";
            case 1: return "REAR CAMERA\nUNAVAILABLE";
            case 2: return "LEFT CAMERA\nUNAVAILABLE";
            case 3: return "RIGHT CAMERA\nUNAVAILABLE";
            case 4: return "CABIN CAMERA\nUNAVAILABLE";
            default: return "DRIVER CAMERA\nUNAVAILABLE";
        }
    }

    private void updateTimestampTexture(long elapsedMs) {
        long second = frameTimestamp.wallTimeMillis(elapsedMs) / 1_000L;
        if (second == timestampSecond) return;
        timestampSecond = second;
        TimeZone zone = TimeZone.getDefault();
        String next = frameTimestamp.format(elapsedMs, zone);
        if (next.equals(renderedTimestamp)) return;

        Bitmap bitmap = timestampBitmap(next);
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            renderedTimestamp = next;
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap timestampBitmap(String value) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        int cameraWidth = selectedDirection < 0 ? cameraRects[0].width()
                : MotionCompositionLayout.singleCameraRect(width, height).width();
        text.setTextSize(Math.max(1f, cameraWidth * .025f));
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        Paint.FontMetrics metrics = text.getFontMetrics();
        float padding = text.getTextSize() * .3f;
        float widest = text.measureText("00.00.0000 00:00:00+05:30");
        timestampWidth = (int) Math.ceil(widest + padding * 2);
        timestampHeight = (int) Math.ceil(metrics.descent - metrics.ascent + padding * 2);
        timestampInset = Math.max(0, Math.round(cameraWidth * .0083f));

        Bitmap bitmap = Bitmap.createBitmap(timestampWidth, timestampHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(Color.argb(160, 0, 0, 0));
        canvas.drawRect(0, 0, timestampWidth, timestampHeight, background);
        canvas.drawText(value, padding, padding - metrics.ascent, text);
        return bitmap;
    }

    private void drawOverlay(int texture, int left, int top, int viewWidth, int viewHeight) {
        GLES20.glViewport(left, height - top - viewHeight, viewWidth, viewHeight);
        GLES20.glUseProgram(iconProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(iconSampler, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        iconVertices.position(0); iconCoordinates.position(0);
        GLES20.glEnableVertexAttribArray(iconPosition);
        GLES20.glEnableVertexAttribArray(iconUv);
        GLES20.glVertexAttribPointer(iconPosition, 2, GLES20.GL_FLOAT, false, 0, iconVertices);
        GLES20.glVertexAttribPointer(iconUv, 2, GLES20.GL_FLOAT, false, 0, iconCoordinates);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(iconPosition);
        GLES20.glDisableVertexAttribArray(iconUv);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void reportMissingInputs() {
        if (closed || startupMissingReported) return;
        StringBuilder missing = new StringBuilder();
        for (int direction = 0; direction < (remoteLive ? seen.length : 4); direction++) {
            if (!channelExpected(direction) || seen[direction] || unavailable[direction]) continue;
            if (missing.length() > 0) missing.append(',');
            missing.append(directionName(direction));
            if (remoteLive) {
                unavailable[direction] = true;
                if (direction >= 4) {
                    String reference = java.util.UUID.randomUUID().toString().replace("-", "");
                    com.byd.extend.remote.CameraViewingDiagnostics.detail(
                            "live_first_frame_missing_" + directionName(direction) + "_" + reference,
                            "shared", "no first GPU frame after " + STARTUP_INPUT_TIMEOUT_MS
                                    + "ms; channel=" + directionName(direction));
                    markUnavailableOnGlThread(direction, reference, true, null);
                }
            }
        }
        if (missing.length() == 0) return;
        startupMissingReported = true;
        logDiagnostic("startup_inputs_missing", "directions=" + missing
                + " timeout_ms=" + STARTUP_INPUT_TIMEOUT_MS);
        if (remoteLive) frame(0);
        else errors.accept("motion_input_frames_missing:" + missing);
    }

    private void logDiagnostic(String event, String details) {
        String message = "session=" + sessionId + " pid=" + Process.myPid()
                + " elapsed_ms=" + SystemClock.elapsedRealtime() + " event=" + event
                + (details == null || details.isEmpty() ? "" : " " + details);
        Log.i(TAG, message);
        if (shellWorker) {
            System.out.println("MOTION " + message);
            System.out.flush();
        }
    }

    private static String directionName(int direction) {
        switch (direction) {
            case 0: return "front";
            case 1: return "rear";
            case 2: return "left";
            case 3: return "right";
            case 4: return "cabin";
            case 5: return "driver";
            default: return "unknown-" + direction;
        }
    }

    /**
     * Schedules one retained cleanup task. A timeout means cleanup is still pending, not complete;
     * the callback fires later only if GL cleanup finishes successfully.
     */
    void close(Runnable onClosed) {
        final FutureTask<Void> task;
        Runnable callbackNow = null;
        synchronized (lifecycleLock) {
            closed = true;
            if (onClosed != null && closeCallback == null && !closeCallbackDelivered
                    && (!cleanupResultPublished || cleanupSucceeded)) {
                closeCallback = onClosed;
                if (cleanupSucceeded) {
                    closeCallbackDelivered = true;
                    callbackNow = onClosed;
                }
            }
            if (cleanupTask == null) {
                cleanupTask = new FutureTask<Void>(() -> {
                    cleanupOnGlThread();
                    publishCleanupSuccess();
                    return null;
                }) {
                    @Override protected void done() {
                        publishCleanupFailure(this);
                    }
                };
                if (!handler.post(cleanupTask)) cleanupTask.cancel(false);
            }
            task = cleanupTask;
        }
        if (callbackNow != null) runCloseCallback(callbackNow);
        // A close invoked from this looper can safely run the retained task inline; otherwise it
        // would wait on itself. A previously queued copy becomes a harmless FutureTask no-op.
        if (Looper.myLooper() == thread.getLooper() && !task.isDone()) task.run();
        try {
            await(task, "GPU cleanup");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("GPU cleanup did not complete", failure);
        }
    }

    @Override public void close() { close(null); }

    private void cleanupOnGlThread() {
        handler.removeCallbacksAndMessages(null);
        boolean hasEgl = display != EGL14.EGL_NO_DISPLAY;
        boolean hasContext = context != EGL14.EGL_NO_CONTEXT;
        boolean hasWindow = window != EGL14.EGL_NO_SURFACE;
        if (hasEgl && hasContext && hasWindow) {
            require(EGL14.eglMakeCurrent(display, window, window, context), "EGL cleanup current");
            GLES20.glFinish(); // Teardown-only completion barrier before releasing encoder surfaces.
            for (int direction = 0; direction < slots.length; direction++) {
                for (int slot = 0; slot < incoming[direction].length; slot++)
                    releaseIncoming(direction, slot);
                releaseFrame(direction);
            }
            Throwable sourceCleanupFailure = null;
            for (int channel = 0; channel < liveInputPumps.length; channel++) {
                CameraFramePump pump = liveInputPumps[channel];
                if (pump == null) continue;
                liveInputPumps[channel] = null;
                try { pump.close(); }
                catch (Throwable failure) {
                    if (sourceCleanupFailure == null) sourceCleanupFailure = failure;
                    else sourceCleanupFailure.addSuppressed(failure);
                }
            }
            if (sourceCleanupFailure != null) {
                com.byd.extend.remote.CameraViewingDiagnostics.failure(
                        "live_camera_cleanup", "shared", sourceCleanupFailure);
                if (sourceCleanupFailure instanceof RuntimeException)
                    throw (RuntimeException) sourceCleanupFailure;
                throw new IllegalStateException("interior camera cleanup failed", sourceCleanupFailure);
            }
            GLES20.glDeleteTextures(iconTextures.length, iconTextures, 0);
            GLES20.glDeleteTextures(timestampTexture.length, timestampTexture, 0);
            GLES20.glDeleteTextures(unavailableTextures.length, unavailableTextures, 0);
            if (program != 0) GLES20.glDeleteProgram(program);
            if (iconProgram != 0) GLES20.glDeleteProgram(iconProgram);
        }
        if (hasEgl) {
            if (hasContext && hasWindow)
                require(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "EGL cleanup unbind");
            if (hasWindow)
                require(EGL14.eglDestroySurface(display, window), "EGL destroy encoder surface");
            if (hasContext)
                require(EGL14.eglDestroyContext(display, context), "EGL destroy context");
        }
    }

    private void publishCleanupSuccess() {
        thread.quitSafely();
        Runnable callback;
        synchronized (lifecycleLock) {
            cleanupResultPublished = true;
            cleanupSucceeded = true;
            callback = closeCallbackDelivered ? null : closeCallback;
            if (callback != null) closeCallbackDelivered = true;
        }
        if (callback != null) runCloseCallback(callback);
    }

    private void publishCleanupFailure(FutureTask<Void> task) {
        Throwable failure = null;
        if (task.isCancelled()) {
            failure = new CancellationException("GPU cleanup task was not accepted");
        } else {
            try { task.get(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (ExecutionException execution) {
                failure = execution.getCause() == null ? execution : execution.getCause();
            } catch (CancellationException cancelled) {
                failure = cancelled;
            }
        }
        if (failure == null) return;
        synchronized (lifecycleLock) {
            if (cleanupResultPublished) return;
            cleanupResultPublished = true;
            cleanupSucceeded = false;
            closeCallback = null;
        }
        logDiagnostic("gpu_cleanup_failed", failure.toString());
        try { errors.accept("GPU cleanup: " + failure); }
        catch (RuntimeException callbackFailure) {
            Log.w(TAG, "gpu_cleanup_error_callback_failed", callbackFailure);
        }
    }

    private void runCloseCallback(Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException failure) { Log.w(TAG, "gpu_close_callback_failed", failure); }
    }

    private static void await(FutureTask<Void> task, String operation) throws Exception {
        try {
            task.get(GL_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
            throw new IllegalStateException(operation + " remains pending", pending);
        } catch (ExecutionException execution) {
            Throwable failure = execution.getCause() == null ? execution : execution.getCause();
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException(operation + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw new IllegalStateException(operation + " was cancelled", cancelled);
        }
    }
    private static FloatBuffer floats(float... values) {
        FloatBuffer buffer=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0); return buffer;
    }
    private static void require(boolean condition,String stage) {
        if(!condition) throw new IllegalStateException(stage+" EGL="+EGL14.eglGetError());
    }
}
