package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
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
    private Surface cameraSurface;
    private CameraDewarpRenderer dewarpRenderer;
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
    private boolean externalTransform;
    private float dewarpRoiCenterX = 0.5f;
    private float dewarpRoiCenterY = 0.5f;
    private DirectCameraCrop requestedCrop = DirectCameraCrop.defaultFor(false);
    private DirectCameraCrop rawFallbackCrop = DirectCameraCrop.defaultFor(false);
    private boolean rawFallbackActive;
    private int dewarpStatsRequestId;
    private int dewarpStatsGeneration;
    private int bufferWidth = BUFFER_WIDTH;
    private int bufferHeight = BUFFER_HEIGHT;

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

    boolean isCameraSurfaceReady() {
        return cameraSurface != null && cameraSurface.isValid();
    }

    void setPaneBoundedBuffer(int paneWidth, int paneHeight, int quality) {
        if (cameraSurface != null) {
            throw new IllegalStateException("camera buffer must be sized before attach");
        }
        int[] size = paneBoundedBufferSize(paneWidth, paneHeight, quality);
        bufferWidth = size[0];
        bufferHeight = size[1];
        configureBuffer();
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
        int scalePercent = CameraBufferQuality.scalePercent(quality);
        if (quality == CameraBufferQuality.ORIGINAL) {
            return new int[]{BUFFER_WIDTH, BUFFER_HEIGHT};
        }
        if (paneWidth <= 1 || paneHeight <= 1) {
            return new int[]{BUFFER_WIDTH, BUFFER_HEIGHT};
        }
        int width = (int) Math.min(BUFFER_WIDTH,
                Math.max(1L, Math.round(paneWidth * scalePercent / 100.0)));
        int height = (int) Math.min(BUFFER_HEIGHT,
                Math.max(1L, Math.round(paneHeight * scalePercent / 100.0)));
        if ((long) width * BUFFER_HEIGHT <= (long) height * BUFFER_WIDTH) {
            height = Math.min(height, Math.max(1,
                    Math.round(width * (float) BUFFER_HEIGHT / BUFFER_WIDTH)));
        } else {
            width = Math.min(width, Math.max(1,
                    Math.round(height * (float) BUFFER_WIDTH / BUFFER_HEIGHT)));
        }
        return new int[]{width, height};
    }

    void retireCameraInput() {
        dewarpGeneration++;
        if (dewarpRenderer != null) {
            dewarpRenderer.release();
            dewarpRenderer = null;
        } else if (cameraSurface != null) {
            cameraSurface.release();
        }
        cameraSurface = null;
        rawFallbackActive = false;
        dewarpStatsRequestId = 0;
        dewarpStatsGeneration = 0;
    }

    int ensureCameraInput() {
        if (isCameraSurfaceReady()) return inputGeneration.current();
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null) return 0;
        createCameraInput(texture, getWidth(), getHeight());
        return cameraInputGeneration();
    }

    void setForceDewarpPipeline(boolean value) {
        if (cameraSurface != null) {
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

    void applyDirectCameraCrop(DirectCameraCrop crop) {
        requestedCrop = crop;
        configureBuffer();
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
        CameraRotation.setSourceCropTransform(
                transform,
                new RectF(
                        directCrop.left * width,
                        directCrop.top * height,
                        directCrop.right() * width,
                        directCrop.bottom() * height),
                new RectF(0.0f, 0.0f, width, height),
                directCrop.rotationDegrees,
                directCrop.rotationMode,
                new RectF(0.0f, 0.0f, width, height),
                false);
        setRotation(0.0f);
        setScaleX(1.0f);
        setScaleY(1.0f);
        setTransform(transform);
    }

    private void configureBuffer() {
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null) return;
        texture.setDefaultBufferSize(bufferWidth, bufferHeight);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        createCameraInput(texture, width, height);
    }

    private void createCameraInput(SurfaceTexture texture, int width, int height) {
        retireCameraInput();
        int cameraGeneration = inputGeneration.next();
        configureBuffer();
        if (usesDewarpPipeline()) {
            int rendererGeneration = ++dewarpGeneration;
            dewarpRenderer = CameraDewarpRenderer.start(
                    texture, bufferWidth, bufferHeight,
                    dewarpConfig, dewarpRequestToken, cameraGeneration,
                    dewarpStatsRequestId, dewarpStatsGeneration,
                    getWidth(), getHeight(),
                    stats -> post(() -> {
                        if (rendererGeneration != dewarpGeneration
                                || dewarpRenderer == null) return;
                        Consumer<CameraDewarpRenderer.Stats> sink = dewarpStatsSink;
                        if (sink != null) sink.accept(stats);
                    }), event -> post(() -> {
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
                    }));
            // Startup is synchronous; invalidate any events queued before a timeout fallback.
            if (dewarpRenderer == null) dewarpGeneration++;
            else {
                dewarpRenderer.setRawMirror(rawMirrorTexture);
                dewarpRenderer.setCorrectedMirror(correctedMirrorTexture);
            }
        }
        setAlpha(dewarpRenderer != null && dewarpConfig.enabled ? 0.0f : 1.0f);
        setRawFallbackActive(dewarpRenderer == null && dewarpConfig.enabled);
        if (dewarpRenderer != null) {
            cameraSurface = dewarpRenderer.cameraSurface();
        } else {
            if (usesDewarpPipeline()) {
                Log.e(TAG, "Dewarp renderer unavailable; using direct Surface");
            }
            cameraSurface = new Surface(texture);
        }
        applyCurrentCrop();
        if (callback != null) {
            callback.onCameraSurfaceAvailable(
                    this, cameraSurface, width, height, cameraGeneration);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        configureBuffer();
        applyCurrentCrop();
        if (callback != null && cameraSurface != null) {
            callback.onCameraSurfaceSizeChanged(this, cameraSurface, width, height);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (callback != null) callback.onCameraSurfaceDestroyed(this);
        retireCameraInput();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (callback != null && cameraSurface != null) {
            callback.onCameraFrameUpdated(this, inputGeneration.frame());
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
