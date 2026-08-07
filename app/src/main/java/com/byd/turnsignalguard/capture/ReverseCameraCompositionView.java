package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

final class ReverseCameraCompositionView extends FrameLayout {
    static final int SOURCE_WIDTH = 1920;
    static final int SOURCE_HEIGHT = 1300;
    private static final int DEFAULT_CORNER_RADIUS_DP = 8;

    interface Callback {
        void onReverseSurfacesReady(int[] generations);
        void onReverseFramesReady(int requestId, int[] generations);
        void onReverseSurfaceLost(int cameraIndex, int generation);
        default void onReverseSurfaceRecreationFailed(
                int cameraIndex, Throwable error) {}
        default void onReverseDewarpStats(
                int cameraIndex, CameraDewarpRenderer.Stats stats) {}
        default void onReverseDewarpEvent(
                int cameraIndex, CameraDewarpRenderer.Event event) {}
    }

    private final View backgroundPane;
    private final PaneView[] panes = new PaneView[3];
    private TextureView previewBase;
    private View previewBaseCover;
    private Surface previewBaseSurface;
    private int previewBaseGeneration;
    private int previewBaseArmedGeneration;
    private boolean previewBaseFreshFrame;
    private Callback callback;
    private ReverseCameraLayout model = ReverseCameraLayout.defaults();
    private ReverseCameraLayout rawFallbackModel = ReverseCameraLayout.defaults();
    private int cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP;
    private int armedRequestId;
    private boolean framesReported;
    private boolean directInputResetPending;
    private int[] directInputResetGenerations;

    ReverseCameraCompositionView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setClipChildren(true);
        backgroundPane = new View(context);
        backgroundPane.setBackgroundColor(Color.BLACK);
        addView(backgroundPane, new FrameLayout.LayoutParams(1, 1));
        panes[0] = addPane(ReverseCameraLayout.REAR_CAMERA_INDEX);
        panes[1] = addPane(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        panes[2] = addPane(ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX);
        addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> applyModel());
    }

    void setCallback(Callback value) {
        callback = value;
    }

    void enablePreviewBase() {
        if (previewBase != null) return;
        previewBase = new TextureView(getContext());
        previewBase.setOpaque(true);
        previewBase.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(1920, 990);
                if (previewBaseSurface != null) previewBaseSurface.release();
                previewBaseSurface = new Surface(texture);
                previewBaseGeneration++;
                previewBaseFreshFrame = false;
                notifySurfacesReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(1920, 990);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                int lostGeneration = previewBaseGeneration;
                if (previewBaseSurface != null) previewBaseSurface.release();
                previewBaseSurface = null;
                previewBaseFreshFrame = false;
                previewBaseCover.setVisibility(View.VISIBLE);
                if (callback != null) callback.onReverseSurfaceLost(0, lostGeneration);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                if (armedRequestId <= 0
                        || previewBaseArmedGeneration != previewBaseGeneration) return;
                previewBaseFreshFrame = true;
                maybeReportFrames();
            }
        });
        addView(previewBase, 0, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        previewBaseCover = new View(getContext());
        previewBaseCover.setBackgroundColor(Color.BLACK);
        addView(previewBaseCover, 1, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        previewBase.setZ(-2.0f);
        previewBaseCover.setZ(-1.0f);
        notifySurfacesReady();
    }

    void setCornerRadiusDp(int value) {
        cornerRadiusDp = Math.max(0, Math.min(48, value));
        for (PaneView pane : panes) pane.setCornerRadiusDp(cornerRadiusDp);
    }

    void setForceDewarpPipeline(boolean value) {
        for (PaneView pane : panes) pane.texture.setForceDewarpPipeline(value);
    }

    void applyDewarpConfigs(
            CameraDewarpConfig rear,
            CameraDewarpConfig left,
            CameraDewarpConfig right) {
        panes[0].applyDewarpConfig(rear);
        panes[1].applyDewarpConfig(left);
        panes[2].applyDewarpConfig(right);
        applyModel();
    }

    boolean dewarpPipelineCompatible(
            CameraDewarpConfig rear,
            CameraDewarpConfig left,
            CameraDewarpConfig right) {
        CameraDewarpConfig[] values = {rear, left, right};
        for (int i = 0; i < panes.length; i++) {
            if (panes[i].texture.usesDewarpPipeline() != values[i].usesGpu()) return false;
        }
        return true;
    }

    void applyLayout(ReverseCameraLayout value) {
        if (value == null) throw new IllegalArgumentException("reverse layout is required");
        model = value;
        applyModel();
    }

    void applyRawFallbackLayout(ReverseCameraLayout value) {
        if (value == null) throw new IllegalArgumentException("raw fallback layout is required");
        rawFallbackModel = value;
        applyModel();
    }

    void setEditorRawMirror(int cameraIndex, SurfaceTexture texture) {
        paneForCamera(cameraIndex).texture.setRawMirrorTexture(texture);
    }

    void setEditorCorrectedMirror(int cameraIndex, SurfaceTexture texture) {
        paneForCamera(cameraIndex).texture.setCorrectedMirrorTexture(texture);
    }

    boolean editorUsesRawFallback(int cameraIndex) {
        return paneForCamera(cameraIndex).texture.usesRawFallback();
    }

    private PaneView paneForCamera(int cameraIndex) {
        for (PaneView pane : panes) {
            if (pane.cameraIndex == cameraIndex) return pane;
        }
        throw new IllegalArgumentException("unsupported reverse camera index: " + cameraIndex);
    }

    boolean surfacesReady() {
        for (PaneView pane : panes) {
            if (pane.surface == null || !pane.surface.isValid()) return false;
        }
        return true;
    }

    boolean previewSurfacesReady() {
        return surfacesReady() && previewBaseSurface != null && previewBaseSurface.isValid();
    }

    int[] recreateDirectInputSurfaces() {
        if (directInputResetPending) return null;
        for (PaneView pane : panes) {
            if (!pane.texture.canRecreateCameraInputSurface()) {
                throw new IllegalStateException("reverse renderer input unavailable");
            }
        }
        directInputResetGenerations = currentGenerations();
        directInputResetPending = true;
        clearFrames();
        for (PaneView pane : panes) pane.texture.recreateCameraInputSurface();
        return directInputResetGenerations.clone();
    }

    static boolean generationsAdvanced(int[] previous, int[] current) {
        if (previous == null || current == null || previous.length != current.length) return false;
        for (int i = 0; i < previous.length; i++) {
            if (current[i] <= previous[i]) return false;
        }
        return true;
    }

    SurfaceBundle acquireSurfaces(int requestId) {
        if (requestId <= 0 || !surfacesReady()) {
            throw new IllegalStateException("reverse Surfaces unavailable");
        }
        Surface[] surfaces = new Surface[panes.length];
        int[] generations = new int[panes.length];
        for (int i = 0; i < panes.length; i++) {
            surfaces[i] = panes[i].surface;
            generations[i] = panes[i].generation;
        }
        return new SurfaceBundle(requestId, generations, surfaces);
    }

    SurfaceBundle acquirePreviewSurfaces(int requestId) {
        if (requestId <= 0 || !previewSurfacesReady()) {
            throw new IllegalStateException("reverse preview Surfaces unavailable");
        }
        SurfaceBundle direct = acquireSurfaces(requestId);
        Surface[] surfaces = new Surface[direct.surfaces.length + 1];
        surfaces[0] = previewBaseSurface;
        System.arraycopy(direct.surfaces, 0, surfaces, 1, direct.surfaces.length);
        return new SurfaceBundle(requestId, direct.generations, surfaces);
    }

    void armFrames(int requestId, int[] expectedGenerations) {
        if (requestId <= 0 || expectedGenerations == null
                || expectedGenerations.length != panes.length) {
            throw new IllegalArgumentException("invalid reverse frame identity");
        }
        for (int i = 0; i < panes.length; i++) {
            if (expectedGenerations[i] != panes[i].generation || panes[i].surface == null) {
                throw new IllegalStateException("stale reverse Surface");
            }
            panes[i].armedGeneration = expectedGenerations[i];
            panes[i].discardNextFrame = true;
            panes[i].freshFrame = false;
            panes[i].cover.setVisibility(View.VISIBLE);
        }
        if (previewBase != null) {
            if (previewBaseSurface == null || !previewBaseSurface.isValid()) {
                throw new IllegalStateException("stale reverse preview base Surface");
            }
            previewBaseArmedGeneration = previewBaseGeneration;
            previewBaseFreshFrame = false;
            previewBaseCover.setVisibility(View.VISIBLE);
        }
        armedRequestId = requestId;
        framesReported = false;
    }

    void clearFrames() {
        armedRequestId = 0;
        framesReported = false;
        for (PaneView pane : panes) {
            pane.discardNextFrame = false;
            pane.freshFrame = false;
            pane.cover.setVisibility(View.VISIBLE);
        }
        previewBaseFreshFrame = false;
        if (previewBaseCover != null) previewBaseCover.setVisibility(View.VISIBLE);
    }

    boolean framesReady(int requestId, int[] expectedGenerations) {
        if (requestId != armedRequestId || expectedGenerations == null
                || expectedGenerations.length != panes.length) return false;
        for (int i = 0; i < panes.length; i++) {
            if (panes[i].generation != expectedGenerations[i] || !panes[i].freshFrame) {
                return false;
            }
        }
        if (previewBase != null && !previewBaseFreshFrame) return false;
        return true;
    }

    private PaneView addPane(int cameraIndex) {
        PaneView pane = new PaneView(getContext(), cameraIndex);
        pane.texture.setDewarpStatsSink(stats -> {
            if (callback != null) callback.onReverseDewarpStats(cameraIndex, stats);
        });
        pane.texture.setDewarpEventSink(event -> {
            if (callback != null) callback.onReverseDewarpEvent(cameraIndex, event);
        });
        pane.texture.setCallback(new BlindSpotCameraView.Callback() {
            @Override
            public void onCameraSurfaceAvailable(
                    BlindSpotCameraView view, Surface surface, int width, int height) {
                pane.surface = surface;
                pane.generation++;
                pane.freshFrame = false;
                pane.discardNextFrame = false;
                if (directInputResetPending && generationsAdvanced(
                        directInputResetGenerations, currentGenerations())) {
                    directInputResetPending = false;
                    directInputResetGenerations = null;
                }
                applyModel();
                notifySurfacesReady();
            }

            @Override
            public void onCameraSurfaceSizeChanged(
                    BlindSpotCameraView view, Surface surface, int width, int height) {
                applyModel();
            }

            @Override
            public void onCameraSurfaceDestroyed(BlindSpotCameraView view) {
                int lostGeneration = pane.generation;
                pane.surface = null;
                pane.freshFrame = false;
                pane.cover.setVisibility(View.VISIBLE);
                if (callback != null) {
                    callback.onReverseSurfaceLost(cameraIndex, lostGeneration);
                }
            }

            @Override
            public void onCameraFrameUpdated(BlindSpotCameraView view) {
                if (armedRequestId <= 0 || pane.armedGeneration != pane.generation) return;
                if (pane.discardNextFrame) {
                    pane.discardNextFrame = false;
                    return;
                }
                pane.freshFrame = true;
                maybeReportFrames();
            }

            @Override
            public void onDewarpFallbackChanged(BlindSpotCameraView view) {
                applyModel();
            }

            @Override
            public void onCameraSurfaceRecreationFailed(
                    BlindSpotCameraView view, Throwable error) {
                directInputResetPending = false;
                directInputResetGenerations = null;
                if (callback != null) {
                    callback.onReverseSurfaceRecreationFailed(cameraIndex, error);
                }
            }
        });
        addView(pane, new FrameLayout.LayoutParams(1, 1));
        return pane;
    }

    private void notifySurfacesReady() {
        boolean ready = previewBase == null ? surfacesReady() : previewSurfacesReady();
        if (callback != null && ready) {
            callback.onReverseSurfacesReady(currentGenerations());
        }
    }

    private void maybeReportFrames() {
        if (framesReported) return;
        int[] generations = currentGenerations();
        if (!framesReady(armedRequestId, generations)) return;
        framesReported = true;
        for (PaneView pane : panes) pane.cover.setVisibility(View.GONE);
        if (previewBaseCover != null) previewBaseCover.setVisibility(View.GONE);
        if (callback != null) callback.onReverseFramesReady(armedRequestId, generations);
    }

    private int[] currentGenerations() {
        int[] values = new int[panes.length];
        for (int i = 0; i < panes.length; i++) values[i] = panes[i].generation;
        return values;
    }

    private void applyModel() {
        int width = getWidth();
        int height = getHeight();
        for (PaneView pane : panes) {
            ReverseCameraLayout.Rect rawCrop =
                    rawFallbackModel.pane(pane.cameraIndex).sourceCrop;
            pane.texture.applyDewarpSourceRoi(
                    rawCrop.left, rawCrop.top, rawCrop.width, rawCrop.height);
        }
        if (width <= 0 || height <= 0) return;
        ReverseCameraLayout.PixelRect backgroundRect =
                ReverseCameraLayout.project(model.background, width, height);
        FrameLayout.LayoutParams backgroundParams =
                (FrameLayout.LayoutParams) backgroundPane.getLayoutParams();
        backgroundParams.width = Math.max(1, backgroundRect.width);
        backgroundParams.height = Math.max(1, backgroundRect.height);
        backgroundParams.leftMargin = backgroundRect.left;
        backgroundParams.topMargin = backgroundRect.top;
        backgroundPane.setLayoutParams(backgroundParams);
        backgroundPane.setZ(0.0f);
        for (PaneView pane : panes) {
            ReverseCameraLayout.Pane value = model.pane(pane.cameraIndex);
            ReverseCameraLayout.Rect rawCrop =
                    rawFallbackModel.pane(pane.cameraIndex).sourceCrop;
            ReverseCameraLayout.Rect sourceCrop = pane.dewarpConfig.enabled
                    && !pane.texture.usesRawFallback()
                    ? value.sourceCrop
                    : rawCrop;
            ReverseCameraLayout.PixelRect baseRect =
                    ReverseCameraLayout.project(value.destination, width, height);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) pane.getLayoutParams();
            params.width = Math.max(1, baseRect.width);
            params.height = Math.max(1, baseRect.height);
            params.leftMargin = baseRect.left;
            params.topMargin = baseRect.top;
            pane.setLayoutParams(params);
            pane.setZ(1.0f + value.zOrder);
            pane.applyTransform(sourceCrop, value.rotationDegrees, value.displayMode);
        }
    }

    static final class SurfaceBundle {
        final int requestId;
        final int[] generations;
        final Surface[] surfaces;

        SurfaceBundle(int requestId, int[] generations, Surface[] surfaces) {
            this.requestId = requestId;
            this.generations = generations;
            this.surfaces = surfaces;
        }
    }

    private static final class PaneView extends FrameLayout {
        final int cameraIndex;
        final BlindSpotCameraView texture;
        final CropMaskView cropMask;
        final View cover;
        CameraDewarpConfig dewarpConfig;
        Surface surface;
        int generation;
        int armedGeneration;
        boolean discardNextFrame;
        boolean freshFrame;
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(0, 0, 1, 1);
        int rotationDegrees;
        int displayMode = ReverseCameraLayout.DEFAULT_DISPLAY_MODE;

        PaneView(Context context, int cameraIndex) {
            super(context);
            this.cameraIndex = cameraIndex;
            dewarpConfig = CameraDewarpConfig.disabled(
                    CameraDewarpConfig.lensForReverseCamera(cameraIndex));
            setClipChildren(true);
            setClipToOutline(true);
            setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.BLACK);
            background.setCornerRadius(dp(context, DEFAULT_CORNER_RADIUS_DP));
            setBackground(background);

            texture = new BlindSpotCameraView(context);
            texture.setOpaque(true);
            texture.setExternalTransform(true);
            addView(texture, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            cropMask = new CropMaskView(context);
            addView(cropMask, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            cover = new View(context);
            cover.setBackgroundColor(Color.BLACK);
            addView(cover, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) ->
                    applyTransform(crop, rotationDegrees, displayMode));
        }

        void applyDewarpConfig(CameraDewarpConfig value) {
            if (value == null) throw new IllegalArgumentException("dewarp config is required");
            dewarpConfig = value;
            texture.applyDewarpConfig(value);
        }

        void applyTransform(
                ReverseCameraLayout.Rect value, int degrees, int nextDisplayMode) {
            crop = value;
            rotationDegrees = CameraRotation.clamp(degrees);
            displayMode = ReverseCameraLayout.normalizeDisplayMode(nextDisplayMode);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;

            boolean fit = displayMode == ReverseCameraLayout.DISPLAY_MODE_FIT;
            ReverseCameraLayout.PixelRect fitted = fit
                    ? ReverseCameraLayout.fitSourceCrop(
                            value, width, height, SOURCE_WIDTH, SOURCE_HEIGHT,
                            rotationDegrees)
                    : new ReverseCameraLayout.PixelRect(0, 0, width, height);
            FrameLayout.LayoutParams textureParams =
                    (FrameLayout.LayoutParams) texture.getLayoutParams();
            if (textureParams.width != fitted.width || textureParams.height != fitted.height
                    || textureParams.leftMargin != fitted.left
                    || textureParams.topMargin != fitted.top) {
                textureParams.width = fitted.width;
                textureParams.height = fitted.height;
                textureParams.leftMargin = fitted.left;
                textureParams.topMargin = fitted.top;
                texture.setLayoutParams(textureParams);
            }
            Matrix transform = new Matrix();
            if (displayMode == ReverseCameraLayout.DISPLAY_MODE_STRETCH) {
                cropMask.setCrop(null);
                CameraRotation.setSourceCropTransform(
                        transform,
                        new RectF(value.left * fitted.width,
                                value.top * fitted.height,
                                value.right() * fitted.width,
                                value.bottom() * fitted.height),
                        new RectF(0, 0, fitted.width, fitted.height),
                        rotationDegrees,
                        CameraRotation.MODE_ALIGNED,
                        new RectF(0, 0, fitted.width, fitted.height),
                        ReverseCameraLayout.mirrorHorizontally(cameraIndex));
            } else {
                transform.setValues(ReverseCameraLayout.rotatedSourceCropTransform(
                        value, fitted.width, fitted.height, SOURCE_WIDTH, SOURCE_HEIGHT,
                        rotationDegrees,
                        displayMode == ReverseCameraLayout.DISPLAY_MODE_FILL));
                if (ReverseCameraLayout.mirrorHorizontally(cameraIndex)) {
                    transform.postScale(-1.0f, 1.0f,
                            fitted.width / 2.0f, fitted.height / 2.0f);
                }
                float[] visibleCrop = new float[]{
                        value.left * fitted.width, value.top * fitted.height,
                        value.right() * fitted.width, value.top * fitted.height,
                        value.right() * fitted.width, value.bottom() * fitted.height,
                        value.left * fitted.width, value.bottom() * fitted.height
                };
                transform.mapPoints(visibleCrop);
                for (int i = 0; i < visibleCrop.length; i += 2) {
                    visibleCrop[i] += fitted.left;
                    visibleCrop[i + 1] += fitted.top;
                }
                cropMask.setCrop(visibleCrop);
            }
            texture.setRotation(0.0f);
            texture.setScaleX(1.0f);
            texture.setScaleY(1.0f);
            texture.setTransform(transform);
        }

        void setCornerRadiusDp(int value) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.BLACK);
            background.setCornerRadius(dp(getContext(), value));
            setBackground(background);
            invalidateOutline();
        }

        private static float dp(Context context, int value) {
            return value * context.getResources().getDisplayMetrics().density;
        }
    }

    private static final class CropMaskView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float[] crop;

        CropMaskView(Context context) {
            super(context);
            paint.setColor(Color.BLACK);
        }

        void setCrop(float[] value) {
            crop = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (crop == null) return;
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            path.moveTo(crop[0], crop[1]);
            for (int i = 2; i < crop.length; i += 2) {
                path.lineTo(crop[i], crop[i + 1]);
            }
            path.close();
            canvas.drawPath(path, paint);
        }
    }
}
