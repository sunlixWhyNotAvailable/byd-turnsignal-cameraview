package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

final class BlindSpotCameraView extends TextureView
        implements TextureView.SurfaceTextureListener {
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

    void applyDirectCameraCrop(DirectCameraCrop crop) {
        directCrop = crop;
        configureBuffer();
        applyCurrentCrop();
    }

    private void applyCurrentCrop() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        Matrix transform = new Matrix();
        transform.setRectToRect(
                new RectF(
                        directCrop.left * width,
                        directCrop.top * height,
                        directCrop.right() * width,
                        directCrop.bottom() * height),
                new RectF(0.0f, 0.0f, width, height),
                Matrix.ScaleToFit.FILL);
        setTransform(transform);
        float[] scale = CameraRotation.scaleToRotatedBounds(
                width, height, directCrop.outputAspect(), directCrop.rotationDegrees);
        setPivotX(width / 2.0f);
        setPivotY(height / 2.0f);
        setRotation(directCrop.rotationDegrees);
        setScaleX(scale[0]);
        setScaleY(scale[1]);
    }

    private void configureBuffer() {
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null) return;
        texture.setDefaultBufferSize(BUFFER_WIDTH, BUFFER_HEIGHT);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        configureBuffer();
        cameraSurface = new Surface(texture);
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
        if (cameraSurface != null) cameraSurface.release();
        cameraSurface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (callback != null) callback.onCameraFrameUpdated(this);
    }
}
