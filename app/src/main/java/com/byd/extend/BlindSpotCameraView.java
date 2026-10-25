package com.byd.extend;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.util.function.Consumer;

final class BlindSpotCameraView extends FrameLayout
        implements TextureView.SurfaceTextureListener {
    private static final String TAG = "BlindSpotCameraView";
    static final int BUFFER_WIDTH = 1920;
    static final int BUFFER_HEIGHT = 1300;

    interface Callback {
        void onCameraSurfaceAvailable(
                BlindSpotCameraView view, Surface surface, int width, int height);

        default void onCameraSurfaceAvailable(
                BlindSpotCameraView view, Surface surface, int width, int height,
                int inputGeneration) {
            onCameraSurfaceAvailable(view, surface, width, height);
        }

        void onCameraSurfaceSizeChanged(
                BlindSpotCameraView view, Surface surface, int width, int height);

        void onCameraSurfaceDestroyed(BlindSpotCameraView view);

        default void onCameraFrameUpdated(BlindSpotCameraView view) {}
        default void onCameraFrameUpdated(
                BlindSpotCameraView view, int inputGeneration) {
            onCameraFrameUpdated(view);
        }
        default void onDewarpFallbackChanged(BlindSpotCameraView view) {}
        default void onCameraRenderFailed(
                BlindSpotCameraView view, CameraDewarpRenderer.Event event) {}
    }

    private Callback callback;
    private TextureView textureView;
    private DirectCameraSurface directView;
    private CameraGpuOutput directOutput;
    private float[] directGeometry = CameraGpuOutput.IDENTITY;
    private long directFrameTimestamp;
    private long handoverPresentation;
    private boolean handoverReady;
    private Runnable handoverListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Surface cameraSurface;
    private StockAvmOutput stockOutput;
    private CameraDewarpRenderer dewarpRenderer;
    private SurfaceTexture rendererTexture;
    private CameraDewarpRenderer retiringRenderer;
    private SurfaceTexture retiringTexture;
    private SurfaceTexture pendingTexture;
    private int pendingWidth;
    private int pendingHeight;
    private boolean restartAfterRetire;
    private SurfaceTexture deferredReleaseTexture;
    private SurfaceTexture rawMirrorTexture;
    private SurfaceTexture correctedMirrorTexture;
    private CameraDewarpConfig dewarpConfig =
            CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
    private Consumer<CameraDewarpRenderer.Stats> dewarpStatsSink;
    private Consumer<CameraDewarpRenderer.Event> dewarpEventSink;
    private final InputGeneration inputGeneration = new InputGeneration();
    private int dewarpGeneration;
    private long dewarpRequestToken;
    private boolean forceDewarpPipeline;
    private boolean fusedOutput = true;
    private boolean preserveInputFrameTimestamp;
    private boolean externalTransform;
    private float dewarpRoiCenterX = 0.5f;
    private float dewarpRoiCenterY = 0.5f;
    private DirectCameraCrop requestedCrop = DirectCameraCrop.defaultFor(false);
    private DirectCameraCrop rawFallbackCrop = DirectCameraCrop.defaultFor(false);
    private boolean rawFallbackActive;
    private CropMaskView outputCropMask;
    private int dewarpStatsRequestId;
    private int dewarpStatsGeneration;
    private int bufferWidth = BUFFER_WIDTH;
    private int bufferHeight = BUFFER_HEIGHT;
    private int automaticBufferQuality = -1;

    BlindSpotCameraView(Context context) {
        super(context);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            directView = new DirectCameraSurface(context, new DirectCameraSurface.Listener() {
                @Override public void available() { startDirectInput(); }
                @Override public void resized() {
                    applyCurrentCrop();
                    if (callback != null && cameraSurface != null)
                        callback.onCameraSurfaceSizeChanged(BlindSpotCameraView.this,
                                cameraSurface, getWidth(), getHeight());
                }
                @Override public void destroyed() {
                    if (callback != null) callback.onCameraSurfaceDestroyed(BlindSpotCameraView.this);
                    retireCameraInput();
                }
            });
            addView(directView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else installTextureView();
        addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            applyCurrentCrop();
            if (dewarpRenderer != null) {
                dewarpRenderer.updateStatsContext(
                        dewarpStatsRequestId, dewarpStatsGeneration,
                        getWidth(), getHeight());
            }
        });
    }

    private void installTextureView() {
        textureView = new TextureView(getContext());
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(this);
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    TextureView textureView() { return textureView; }
    SurfaceTexture getSurfaceTexture() {
        return textureView == null ? null : textureView.getSurfaceTexture();
    }
    boolean isAvailable() {
        return directView != null ? directView.available() : textureView != null && textureView.isAvailable();
    }
    void setOpaque(boolean opaque) { if (textureView != null) textureView.setOpaque(opaque); }
    void setTransform(Matrix value, int width, int height) {
        if (directView != null) {
            float[] values = new float[9];
            value.getValues(values);
            directGeometry = CameraRotation.gpuTransformValues(values, width, height);
            if (directOutput != null) directOutput.updateGeometry(directGeometry);
        } else if (textureView != null) textureView.setTransform(value);
    }

    void setCallback(Callback value) {
        callback = value;
    }

    Surface getCameraSurface() {
        return cameraSurface;
    }

    int cameraInputGeneration() {
        return cameraSurface == null ? 0 : inputGeneration.current();
    }

    void setPreserveInputFrameTimestamp(boolean value) {
        preserveInputFrameTimestamp = value;
        if (dewarpRenderer != null) dewarpRenderer.setPreserveInputFrameTimestamp(value);
    }

    long cameraFrameTimestampNanos() {
        if (directView != null) return isCameraSurfaceReady() ? directFrameTimestamp : 0L;
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null || !isCameraSurfaceReady()) return 0L;
        try {
            return texture.getTimestamp();
        } catch (RuntimeException releasedTexture) {
            return 0L;
        }
    }

    boolean isCameraSurfaceReady() {
        return cameraSurface != null && cameraSurface.isValid();
    }

    void setPaneBoundedBuffer(int paneWidth, int paneHeight, int quality) {
        if (hasCameraInputLifecycle()) {
            throw new IllegalStateException("camera buffer must be sized before attach");
        }
        int[] size = paneBoundedBufferSize(paneWidth, paneHeight, quality);
        automaticBufferQuality = -1;
        bufferWidth = size[0];
        bufferHeight = size[1];
        configureBuffer();
    }

    void setAutomaticBufferQuality(int quality) {
        CameraBufferQuality.scalePercent(quality);
        automaticBufferQuality = quality;
    }

    boolean usesPaneBoundedBuffer(int paneWidth, int paneHeight, int quality) {
        int[] size = paneBoundedBufferSize(paneWidth, paneHeight, quality);
        return bufferWidth == size[0] && bufferHeight == size[1];
    }

    int cameraBufferWidth() {
        return bufferWidth;
    }

    int cameraBufferHeight() {
        return bufferHeight;
    }

    static int[] paneBoundedBufferSize(int paneWidth, int paneHeight) {
        return paneBoundedBufferSize(
                paneWidth, paneHeight, CameraBufferQuality.PERFORMANCE);
    }

    static int[] paneBoundedBufferSize(int paneWidth, int paneHeight, int quality) {
        return CameraBufferQuality.bufferSizeForPane(
                paneWidth, paneHeight, BUFFER_WIDTH, BUFFER_HEIGHT, quality);
    }

    static int[] cameraBufferSizeForInput(
            int configuredWidth, int configuredHeight,
            int viewWidth, int viewHeight, int automaticQuality) {
        return automaticQuality >= 0
                ? paneBoundedBufferSize(viewWidth, viewHeight, automaticQuality)
                : new int[]{configuredWidth, configuredHeight};
    }

    void retireCameraInput() {
        restartAfterRetire = false;
        pendingTexture = null;
        retireActiveInput();
        rawFallbackActive = false;
        dewarpStatsRequestId = 0;
        dewarpStatsGeneration = 0;
    }

    int ensureCameraInput() {
        if (isCameraSurfaceReady()) return inputGeneration.current();
        if (dewarpRenderer != null) return inputGeneration.current();
        if (directView != null) {
            if (directView.available()) startDirectInput();
            return inputGeneration.current();
        }
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null) return 0;
        requestCameraInput(texture, getWidth(), getHeight());
        return inputGeneration.current();
    }

    void setForceDewarpPipeline(boolean value) {
        if (hasCameraInputLifecycle()) {
            throw new IllegalStateException("dewarp pipeline must be selected before attach");
        }
        forceDewarpPipeline = value;
    }

    void setFusedOutput(boolean value) {
        if (hasCameraInputLifecycle()) {
            throw new IllegalStateException("GPU output path must be selected before attach");
        }
        fusedOutput = value;
        if (!value && directView != null) {
            removeView(directView);
            directView = null;
            installTextureView();
        }
    }

    void applyDewarpConfig(CameraDewarpConfig value) {
        if (value == null) throw new IllegalArgumentException("dewarp config is required");
        applyEffectiveDewarpConfig(value.withRoiCenter(
                dewarpRoiCenterX, dewarpRoiCenterY));
    }

    void applyDewarpSourceRoi(float left, float top, float width, float height) {
        if (!Float.isFinite(left) || !Float.isFinite(top)
                || !Float.isFinite(width) || !Float.isFinite(height)
                || left < 0.0f || top < 0.0f || width <= 0.0f || height <= 0.0f
                || left + width > 1.0001f || top + height > 1.0001f) {
            throw new IllegalArgumentException("invalid normalized dewarp ROI");
        }
        dewarpRoiCenterX = left + width / 2.0f;
        dewarpRoiCenterY = top + height / 2.0f;
        applyEffectiveDewarpConfig(dewarpConfig.withRoiCenter(
                dewarpRoiCenterX, dewarpRoiCenterY));
    }

    private void applyEffectiveDewarpConfig(CameraDewarpConfig value) {
        long nextToken = CameraDewarpRenderer.nextRequestToken(
                dewarpRequestToken, dewarpConfig, value);
        boolean mappingChanged = nextToken != dewarpRequestToken;
        dewarpConfig = value;
        dewarpRequestToken = nextToken;
        if (directOutput != null) {
            // The existing buffer remains visible until EGL swaps in the new mapping.
            if (mappingChanged) directOutput.update(value, nextToken);
            return;
        }
        if (dewarpRenderer != null) {
            if (!mappingChanged) return;
            setAlpha(0.0f);
            dewarpRenderer.update(value, dewarpRequestToken);
        }
        else if (cameraSurface != null) setRawFallbackActive(value.enabled);
    }

    static void syncDirectVisibility(BlindSpotCameraView first, BlindSpotCameraView second) {
        DirectCameraSurface.syncLayers(first.directView, second == null ? null : second.directView);
    }

    boolean canStageHandover() { return directOutput != null && directView.available(); }

    void beginHandover(Runnable ready) {
        int slot = directView.stageOutput();
        handoverPresentation = directOutput.beginPresentation(directView.alternateSurface(), slot);
        handoverReady = false;
        handoverListener = ready;
    }

    void submitHandover() { if (directOutput != null) directOutput.submitPresentation(); }
    boolean handoverReady() { return handoverReady && directOutput != null; }

    static void commitHandovers(BlindSpotCameraView[] views) {
        DirectCameraSurface[] layers = new DirectCameraSurface[views.length];
        for (int i = 0; i < views.length; i++) {
            layers[i] = views[i].directView;
            views[i].handoverListener = null;
            views[i].handoverPresentation = 0;
        }
        DirectCameraSurface.commitStages(layers);
    }

    boolean usesDewarpPipeline() {
        return forceDewarpPipeline || dewarpConfig.usesGpu();
    }

    void setDewarpStatsSink(Consumer<CameraDewarpRenderer.Stats> value) {
        dewarpStatsSink = value;
    }

    void setDewarpStatsContext(int requestId, int generation) {
        dewarpStatsRequestId = Math.max(0, requestId);
        dewarpStatsGeneration = Math.max(0, generation);
        if (dewarpRenderer != null) {
            dewarpRenderer.updateStatsContext(
                    dewarpStatsRequestId, dewarpStatsGeneration,
                    getWidth(), getHeight());
        }
    }

    void setDewarpEventSink(Consumer<CameraDewarpRenderer.Event> value) {
        dewarpEventSink = value;
    }

    void setExternalTransform(boolean value) {
        externalTransform = value;
    }

    void setRawMirrorTexture(SurfaceTexture texture) {
        rawMirrorTexture = texture;
        if (dewarpRenderer != null) dewarpRenderer.setRawMirror(texture);
    }

    void setCorrectedMirrorTexture(SurfaceTexture texture) {
        correctedMirrorTexture = texture;
        if (dewarpRenderer != null) dewarpRenderer.setCorrectedMirror(texture);
    }

    /** Restores the renderer-sized buffer after a retained mirror TextureView resize. */
    void refreshMirrorBuffer(SurfaceTexture texture, boolean raw) {
        if (texture == null) return;
        SurfaceTexture current = raw ? rawMirrorTexture : correctedMirrorTexture;
        if (current != texture) return;
        CameraDewarpRenderer renderer = dewarpRenderer;
        if (renderer != null) {
            renderer.refreshMirrorBuffer(texture, raw);
        } else {
            // Before the renderer is ready, retain the configured camera quality rather than
            // accepting TextureView's view-sized default.
            texture.setDefaultBufferSize(bufferWidth, bufferHeight);
        }
    }

    void applyDirectCameraCrop(DirectCameraCrop crop) {
        if (crop == null) throw new IllegalArgumentException("camera crop is required");
        requestedCrop = crop;
        configureBuffer();
        applyCurrentCrop();
    }

    /** Installs the sibling mask used by production Output/Placement hosts. */
    void setOutputCropMask(CropMaskView value) {
        outputCropMask = value;
        applyCurrentCrop();
    }

    void applyRawFallbackCrop(DirectCameraCrop crop) {
        if (crop == null) throw new IllegalArgumentException("raw fallback crop is required");
        rawFallbackCrop = crop;
        if (rawFallbackActive) applyCurrentCrop();
    }

    boolean usesRawFallback() {
        return rawFallbackActive;
    }

    private void applyCurrentCrop() {
        if (externalTransform) return;
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        DirectCameraCrop directCrop = rawFallbackActive ? rawFallbackCrop : requestedCrop;
        Matrix transform = new Matrix();
        RectF destination = new RectF(0.0f, 0.0f, width, height);
        CameraRotation.setSourceCropTransformForInput(
                transform,
                directCrop.left, directCrop.top,
                directCrop.width, directCrop.height,
                destination,
                directCrop.rotationDegrees,
                directCrop.rotationMode,
                BUFFER_WIDTH, BUFFER_HEIGHT,
                width, height,
                directCrop.mirrorHorizontally);
        setRotation(0.0f);
        setScaleX(1.0f);
        setScaleY(1.0f);
        setTransform(transform, width, height);
        if (outputCropMask != null) {
            outputCropMask.setCrop(CameraRotation.transformedCropCornersForInput(
                    directCrop.left, directCrop.top,
                    directCrop.width, directCrop.height,
                    destination, directCrop.rotationDegrees, directCrop.rotationMode,
                    BUFFER_WIDTH, BUFFER_HEIGHT, width, height,
                    directCrop.mirrorHorizontally));
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Compose/tablet layout can resize an already-live host without recreating its
        // SurfaceTexture.  Recompute both the texture transform and the output mask in-place.
        applyCurrentCrop();
    }

    private void configureBuffer() {
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null) return;
        texture.setDefaultBufferSize(bufferWidth, bufferHeight);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        requestCameraInput(texture, width, height);
    }

    private void requestCameraInput(SurfaceTexture texture, int width, int height) {
        pendingTexture = texture;
        pendingWidth = width;
        pendingHeight = height;
        restartAfterRetire = true;
        if (dewarpRenderer != null || cameraSurface != null) retireActiveInput();
        startPendingInput();
    }

    private void startPendingInput() {
        if (!restartAfterRetire || retiringRenderer != null
                || dewarpRenderer != null || cameraSurface != null) return;
        SurfaceTexture texture = pendingTexture;
        int width = pendingWidth;
        int height = pendingHeight;
        pendingTexture = null;
        restartAfterRetire = false;
        if (texture == null || texture != getSurfaceTexture() || !isAvailable()) return;
        startCameraInput(texture, width, height);
    }

    private void startCameraInput(SurfaceTexture texture, int width, int height) {
        int[] size = cameraBufferSizeForInput(
                bufferWidth, bufferHeight, width, height, automaticBufferQuality);
        bufferWidth = size[0];
        bufferHeight = size[1];
        int cameraGeneration = inputGeneration.next();
        configureBuffer();
        if (usesDewarpPipeline()) {
            int rendererGeneration = ++dewarpGeneration;
            rendererTexture = texture;
            dewarpRenderer = CameraDewarpRenderer.startAsync(
                    getContext(), texture, bufferWidth, bufferHeight,
                    dewarpConfig, dewarpRequestToken, cameraGeneration,
                    dewarpStatsRequestId, dewarpStatsGeneration,
                    getWidth(), getHeight(),
                    fusedOutput,
                    stats -> mainHandler.post(() -> {
                        if (rendererGeneration != dewarpGeneration
                                || texture != getSurfaceTexture()
                                || dewarpRenderer == null) return;
                        Consumer<CameraDewarpRenderer.Stats> sink = dewarpStatsSink;
                        if (sink != null) sink.accept(stats);
                    }), event -> mainHandler.post(() -> {
                        if (rendererGeneration != dewarpGeneration) return;
                        if (!CameraDewarpRenderer.shouldHandleEvent(
                                event.kind, event.requestToken,
                                dewarpRequestToken)) return;
                        if (CameraDewarpRenderer.isFatalEventKind(event.kind)) {
                            setAlpha(0.0f);
                        }
                        if ("dewarp_fallback_raw".equals(event.kind)) {
                            setRawFallbackActive(true);
                            setAlpha(1.0f);
                        } else if ("dewarp_mesh_applied".equals(event.kind)) {
                            setRawFallbackActive(false);
                            applyCurrentCrop();
                            setAlpha(1.0f);
                        }
                        Consumer<CameraDewarpRenderer.Event> sink = dewarpEventSink;
                        if (sink != null) sink.accept(event);
                        if (CameraDewarpRenderer.isFatalEventKind(event.kind)
                                && callback != null) {
                            callback.onCameraRenderFailed(this, event);
                        }
                    }), new CameraDewarpRenderer.LifecycleCallback() {
                        @Override
                        public void onReady(
                                CameraDewarpRenderer renderer, Surface surface) {
                            mainHandler.post(() -> onRendererReady(
                                    renderer, surface, texture,
                                    rendererGeneration, cameraGeneration, width, height));
                        }

                        @Override
                        public void onStopped(
                                CameraDewarpRenderer renderer, Throwable startupError) {
                            mainHandler.post(() -> onRendererStopped(
                                    renderer, startupError, texture,
                                    rendererGeneration, cameraGeneration, width, height));
                        }
                    });
            setAlpha(dewarpConfig.enabled ? 0.0f : 1.0f);
            setRawFallbackActive(false);
            applyCurrentCrop();
            return;
        }
        stockOutput = new StockAvmOutput(texture, bufferWidth, bufferHeight);
        cameraSurface = stockOutput.surface;
        setAlpha(1.0f);
        setRawFallbackActive(false);
        applyCurrentCrop();
        notifyCameraSurfaceAvailable(width, height, cameraGeneration);
    }

    private void onRendererReady(
            CameraDewarpRenderer renderer, Surface surface, SurfaceTexture texture,
            int rendererGeneration, int cameraGeneration, int width, int height) {
        if (!isCurrentRendererCallback(
                rendererGeneration, dewarpGeneration,
                texture, getSurfaceTexture(), renderer, dewarpRenderer)) {
            renderer.releaseAsync();
            return;
        }
        cameraSurface = surface;
        renderer.setPreserveInputFrameTimestamp(preserveInputFrameTimestamp);
        renderer.setRawMirror(rawMirrorTexture);
        renderer.setCorrectedMirror(correctedMirrorTexture);
        setRawFallbackActive(false);
        setAlpha(dewarpConfig.enabled ? 0.0f : 1.0f);
        applyCurrentCrop();
        notifyCameraSurfaceAvailable(width, height, cameraGeneration);
    }

    private void onRendererStopped(
            CameraDewarpRenderer renderer, Throwable startupError, SurfaceTexture texture,
            int rendererGeneration, int cameraGeneration, int width, int height) {
        boolean current = renderer == dewarpRenderer;
        boolean retiring = renderer == retiringRenderer;
        if (!current && !retiring) return;
        if (current) {
            dewarpRenderer = null;
            rendererTexture = null;
            cameraSurface = null;
        }
        if (retiring) {
            retiringRenderer = null;
            retiringTexture = null;
        }
        if (deferredReleaseTexture == texture) {
            deferredReleaseTexture = null;
            texture.release();
        } else if (current && startupError != null
                && rendererGeneration == dewarpGeneration
                && texture == getSurfaceTexture() && isAvailable()) {
            Log.e(TAG, "Dewarp renderer unavailable; using direct Surface", startupError);
            stockOutput = new StockAvmOutput(texture, bufferWidth, bufferHeight);
            cameraSurface = stockOutput.surface;
            setRawFallbackActive(dewarpConfig.enabled);
            setAlpha(1.0f);
            applyCurrentCrop();
            notifyCameraSurfaceAvailable(width, height, cameraGeneration);
        }
        startPendingInput();
    }

    private void notifyCameraSurfaceAvailable(int width, int height, int cameraGeneration) {
        if (callback != null && cameraSurface != null && cameraSurface.isValid()) {
            callback.onCameraSurfaceAvailable(
                    this, cameraSurface, width, height, cameraGeneration);
        }
    }

    private void retireActiveInput() {
        dewarpGeneration++;
        handoverListener = null;
        handoverPresentation = 0;
        handoverReady = false;
        if (directOutput != null) {
            CameraGpuOutput output = directOutput;
            DirectCameraSurface.Output layer = directView.retireOutput();
            directOutput = null;
            directFrameTimestamp = 0;
            cameraSurface = null;
            CameraGpuOutput.unregister(layer.surface);
            output.release(layer::release);
            return;
        }
        CameraDewarpRenderer renderer = dewarpRenderer;
        if (renderer != null) {
            dewarpRenderer = null;
            retiringRenderer = renderer;
            retiringTexture = rendererTexture;
            rendererTexture = null;
            cameraSurface = null;
            renderer.releaseAsync();
        } else if (stockOutput != null) {
            stockOutput.retire();
            stockOutput = null;
            cameraSurface = null;
        } else if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
    }

    private boolean hasCameraInputLifecycle() {
        return cameraSurface != null || dewarpRenderer != null || directOutput != null;
    }

    private void startDirectInput() {
        if (directOutput != null || directView == null || !directView.available()) return;
        int[] size = cameraBufferSizeForInput(bufferWidth, bufferHeight,
                getWidth(), getHeight(), automaticBufferQuality);
        bufferWidth = size[0]; bufferHeight = size[1];
        int generation = inputGeneration.next();
        DirectCameraSurface.Output layer = directView.createOutput(bufferWidth, bufferHeight);
        directOutput = new CameraGpuOutput(bufferWidth, bufferHeight, dewarpConfig,
                dewarpRequestToken, event -> mainHandler.post(() -> {
                    if (inputGeneration.current() != generation || cameraSurface != layer.surface
                            || event.requestToken != dewarpRequestToken) return;
                    setRawFallbackActive("dewarp_fallback_raw".equals(event.kind));
                    applyCurrentCrop();
                    setAlpha(1f);
                    if (dewarpEventSink != null) dewarpEventSink.accept(event);
                }));
        directOutput.setFrameListener((timestamp, presentation, mapping, geometry) -> mainHandler.post(() -> {
            if (inputGeneration.current() != generation || cameraSurface != layer.surface
                    || directOutput == null || handoverPresentation != 0
                    && !directOutput.acceptsFrame(presentation, mapping, geometry)) return;
            directFrameTimestamp = timestamp;
            if (callback != null) callback.onCameraFrameUpdated(this, generation);
            if (handoverPresentation == presentation && !handoverReady) {
                handoverReady = true;
                if (handoverListener != null) handoverListener.run();
            }
        }));
        directOutput.updateGeometry(directGeometry);
        CameraGpuOutput.register(layer.surface, directOutput);
        cameraSurface = layer.surface;
        directFrameTimestamp = 0;
        setAlpha(dewarpConfig.enabled ? 0f : 1f);
        applyCurrentCrop();
        notifyCameraSurfaceAvailable(getWidth(), getHeight(), generation);
    }

    static boolean isCurrentRendererCallback(
            int callbackGeneration, int currentGeneration,
            Object callbackTexture, Object currentTexture,
            Object callbackRenderer, Object currentRenderer) {
        return callbackGeneration == currentGeneration
                && callbackTexture == currentTexture
                && callbackRenderer == currentRenderer;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (pendingTexture == texture) {
            pendingWidth = width;
            pendingHeight = height;
        }
        configureBuffer();
        applyCurrentCrop();
        if (callback != null && cameraSurface != null) {
            callback.onCameraSurfaceSizeChanged(this, cameraSurface, width, height);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (callback != null) callback.onCameraSurfaceDestroyed(this);
        boolean rendererOwnsTexture = texture == rendererTexture
                || texture == retiringTexture;
        if (pendingTexture == texture) {
            pendingTexture = null;
            restartAfterRetire = false;
        }
        if (rendererOwnsTexture) deferredReleaseTexture = texture;
        retireCameraInput();
        return !StockAvmOutput.retainOnDestroy(texture) && !rendererOwnsTexture;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (texture == getSurfaceTexture() && callback != null && cameraSurface != null) {
            // Timestamped shell output proves freshness; other hosts retain their retirement gate.
            callback.onCameraFrameUpdated(this, preserveInputFrameTimestamp
                    ? inputGeneration.current() : inputGeneration.frame());
        }
    }

    private void setRawFallbackActive(boolean value) {
        if (rawFallbackActive == value) return;
        rawFallbackActive = value;
        applyCurrentCrop();
        if (callback != null) callback.onDewarpFallbackChanged(this);
    }

    static final class InputGeneration {
        private int value;
        private int retired;
        private boolean discardNextFrame;

        int next() {
            retired = value;
            value = value == Integer.MAX_VALUE ? 1 : value + 1;
            discardNextFrame = true;
            return value;
        }

        int current() {
            return value;
        }

        int frame() {
            if (!discardNextFrame) return value;
            discardNextFrame = false;
            return retired;
        }
    }
}
