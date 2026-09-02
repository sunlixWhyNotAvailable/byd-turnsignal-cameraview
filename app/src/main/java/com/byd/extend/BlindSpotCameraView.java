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

import java.util.function.Consumer;

final class BlindSpotCameraView extends TextureView
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Surface cameraSurface;
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
        setOpaque(true);
        setSurfaceTextureListener(this);
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
        if (dewarpRenderer != null) {
            if (!mappingChanged) return;
            setAlpha(0.0f);
            dewarpRenderer.update(value, dewarpRequestToken);
        }
        else if (cameraSurface != null) setRawFallbackActive(value.enabled);
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
        setTransform(transform);
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
        if (automaticBufferQuality >= 0) {
            int[] size = paneBoundedBufferSize(width, height, automaticBufferQuality);
            bufferWidth = size[0];
            bufferHeight = size[1];
        }
        int cameraGeneration = inputGeneration.next();
        configureBuffer();
        if (usesDewarpPipeline()) {
            int rendererGeneration = ++dewarpGeneration;
            rendererTexture = texture;
            dewarpRenderer = CameraDewarpRenderer.startAsync(
                    texture, bufferWidth, bufferHeight,
                    dewarpConfig, dewarpRequestToken, cameraGeneration,
                    dewarpStatsRequestId, dewarpStatsGeneration,
                    getWidth(), getHeight(),
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
        cameraSurface = new Surface(texture);
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
            cameraSurface = new Surface(texture);
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
        CameraDewarpRenderer renderer = dewarpRenderer;
        if (renderer != null) {
            dewarpRenderer = null;
            retiringRenderer = renderer;
            retiringTexture = rendererTexture;
            rendererTexture = null;
            cameraSurface = null;
            renderer.releaseAsync();
        } else if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
    }

    private boolean hasCameraInputLifecycle() {
        return cameraSurface != null || dewarpRenderer != null;
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
        return !rendererOwnsTexture;
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
