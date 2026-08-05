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

        void onCameraSurfaceSizeChanged(
                BlindSpotCameraView view, Surface surface, int width, int height);

        void onCameraSurfaceDestroyed(BlindSpotCameraView view);

        default void onCameraFrameUpdated(BlindSpotCameraView view) {}
        default void onDewarpFallbackChanged(BlindSpotCameraView view) {}
        default void onCameraRenderFailed(
                BlindSpotCameraView view, CameraDewarpRenderer.Event event) {}
    }

    private Callback callback;
    private Surface cameraSurface;
    private CameraDewarpRenderer dewarpRenderer;
    private CameraDewarpConfig dewarpConfig =
            CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
    private Consumer<CameraDewarpRenderer.Stats> dewarpStatsSink;
    private Consumer<CameraDewarpRenderer.Event> dewarpEventSink;
    private int dewarpGeneration;
    private boolean forceDewarpPipeline;
    private boolean externalTransform;
    private DirectCameraCrop requestedCrop = DirectCameraCrop.defaultFor(false);
    private DirectCameraCrop rawFallbackCrop = DirectCameraCrop.defaultFor(false);
    private boolean rawFallbackActive;

    BlindSpotCameraView(Context context) {
        super(context);
        setOpaque(true);
        setSurfaceTextureListener(this);
        addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> applyCurrentCrop());
    }

    void setCallback(Callback value) {
        callback = value;
    }

    Surface getCameraSurface() {
        return cameraSurface;
    }

    boolean isCameraSurfaceReady() {
        return cameraSurface != null && cameraSurface.isValid();
    }

    void setForceDewarpPipeline(boolean value) {
        if (cameraSurface != null) {
            throw new IllegalStateException("dewarp pipeline must be selected before attach");
        }
        forceDewarpPipeline = value;
    }

    void applyDewarpConfig(CameraDewarpConfig value) {
        if (value == null) throw new IllegalArgumentException("dewarp config is required");
        boolean mappingChanged = !dewarpConfig.sameMapping(value);
        dewarpConfig = value;
        if (dewarpRenderer != null) {
            if (mappingChanged) {
                setAlpha(0.0f);
            }
            dewarpRenderer.update(value);
        }
        else if (cameraSurface != null) setRawFallbackActive(value.enabled);
    }

    boolean usesDewarpPipeline() {
        return forceDewarpPipeline || dewarpConfig.usesGpu();
    }

    void setDewarpStatsSink(Consumer<CameraDewarpRenderer.Stats> value) {
        dewarpStatsSink = value;
    }

    void setDewarpEventSink(Consumer<CameraDewarpRenderer.Event> value) {
        dewarpEventSink = value;
    }

    void setExternalTransform(boolean value) {
        externalTransform = value;
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
        texture.setDefaultBufferSize(BUFFER_WIDTH, BUFFER_HEIGHT);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        configureBuffer();
        if (usesDewarpPipeline()) {
            int rendererGeneration = ++dewarpGeneration;
            dewarpRenderer = CameraDewarpRenderer.start(
                    texture, BUFFER_WIDTH, BUFFER_HEIGHT, dewarpConfig,
                    stats -> post(() -> {
                        if (rendererGeneration != dewarpGeneration
                                || dewarpRenderer == null) return;
                        Consumer<CameraDewarpRenderer.Stats> sink = dewarpStatsSink;
                        if (sink != null) sink.accept(stats);
                    }), event -> post(() -> {
                        if (rendererGeneration != dewarpGeneration) return;
                        boolean currentMapping = dewarpConfig.lens == event.lens
                                && dewarpConfig.enabled == event.enabled
                                && dewarpConfig.fovDegrees == event.fovDegrees;
                        if (CameraDewarpRenderer.isFatalEventKind(event.kind)) {
                            setAlpha(0.0f);
                        }
                        if (currentMapping && "dewarp_fallback_raw".equals(event.kind)) {
                            setRawFallbackActive(true);
                            setAlpha(1.0f);
                        } else if (currentMapping && "dewarp_mesh_applied".equals(event.kind)) {
                            setRawFallbackActive(false);
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
        }
        setAlpha(1.0f);
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
            callback.onCameraSurfaceAvailable(this, cameraSurface, width, height);
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
        dewarpGeneration++;
        if (callback != null) callback.onCameraSurfaceDestroyed(this);
        if (dewarpRenderer != null) {
            dewarpRenderer.release();
            dewarpRenderer = null;
        } else if (cameraSurface != null) {
            cameraSurface.release();
        }
        cameraSurface = null;
        rawFallbackActive = false;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (callback != null) callback.onCameraFrameUpdated(this);
    }

    private void setRawFallbackActive(boolean value) {
        if (rawFallbackActive == value) return;
        rawFallbackActive = value;
        applyCurrentCrop();
        if (callback != null) callback.onDewarpFallbackChanged(this);
    }
}
