package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

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
    }

    private Callback callback;
    private Surface cameraSurface;
    private CameraDewarpRenderer dewarpRenderer;
    private CameraDewarpConfig dewarpConfig = CameraDewarpConfig.disabled();
    private boolean forceDewarpPipeline;
    private boolean externalTransform;
    private DirectCameraCrop directCrop = DirectCameraCrop.defaultFor(false);

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
        dewarpConfig = value;
        if (dewarpRenderer != null) dewarpRenderer.update(value);
    }

    boolean usesDewarpPipeline() {
        return forceDewarpPipeline || dewarpConfig.usesGpu();
    }

    void setExternalTransform(boolean value) {
        externalTransform = value;
    }

    void applyDirectCameraCrop(DirectCameraCrop crop) {
        directCrop = crop;
        configureBuffer();
        applyCurrentCrop();
    }

    private void applyCurrentCrop() {
        if (externalTransform) return;
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
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
            dewarpRenderer = CameraDewarpRenderer.start(
                    texture, BUFFER_WIDTH, BUFFER_HEIGHT, dewarpConfig);
        }
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
        if (callback != null) callback.onCameraSurfaceDestroyed(this);
        if (dewarpRenderer != null) {
            dewarpRenderer.release();
            dewarpRenderer = null;
        } else if (cameraSurface != null) {
            cameraSurface.release();
        }
        cameraSurface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (callback != null) callback.onCameraFrameUpdated(this);
    }
}
