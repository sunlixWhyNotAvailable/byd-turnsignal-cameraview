package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
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
    private int cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP;
    private int armedRequestId;
    private boolean framesReported;

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

    void applyLayout(ReverseCameraLayout value) {
        if (value == null) throw new IllegalArgumentException("reverse layout is required");
        model = value;
        applyModel();
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
        pane.texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(SOURCE_WIDTH, SOURCE_HEIGHT);
                if (pane.surface != null) pane.surface.release();
                pane.surface = new Surface(texture);
                pane.generation++;
                pane.freshFrame = false;
                pane.discardNextFrame = false;
                pane.applyCrop(model.pane(cameraIndex).sourceCrop);
                notifySurfacesReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(SOURCE_WIDTH, SOURCE_HEIGHT);
                pane.applyCrop(model.pane(cameraIndex).sourceCrop);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                int lostGeneration = pane.generation;
                if (pane.surface != null) pane.surface.release();
                pane.surface = null;
                pane.freshFrame = false;
                pane.cover.setVisibility(View.VISIBLE);
                if (callback != null) {
                    callback.onReverseSurfaceLost(cameraIndex, lostGeneration);
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                if (armedRequestId <= 0 || pane.armedGeneration != pane.generation) return;
                if (pane.discardNextFrame) {
                    pane.discardNextFrame = false;
                    return;
                }
                pane.freshFrame = true;
                maybeReportFrames();
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
            ReverseCameraLayout.PixelRect rect =
                    ReverseCameraLayout.project(value.destination, width, height);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) pane.getLayoutParams();
            params.width = Math.max(1, rect.width);
            params.height = Math.max(1, rect.height);
            params.leftMargin = rect.left;
            params.topMargin = rect.top;
            pane.setLayoutParams(params);
            pane.setZ(1.0f + value.zOrder);
            pane.applyCrop(value.sourceCrop);
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
        final TextureView texture;
        final View cover;
        Surface surface;
        int generation;
        int armedGeneration;
        boolean discardNextFrame;
        boolean freshFrame;
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(0, 0, 1, 1);

        PaneView(Context context, int cameraIndex) {
            super(context);
            this.cameraIndex = cameraIndex;
            setClipChildren(true);
            setClipToOutline(true);
            setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.BLACK);
            background.setCornerRadius(dp(context, DEFAULT_CORNER_RADIUS_DP));
            setBackground(background);

            texture = new TextureView(context);
            texture.setOpaque(true);
            if (ReverseCameraLayout.mirrorHorizontally(cameraIndex)) {
                texture.setScaleX(-1.0f);
            }
            addView(texture, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            cover = new View(context);
            cover.setBackgroundColor(Color.BLACK);
            addView(cover, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            texture.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> applyCrop(crop));
        }

        void applyCrop(ReverseCameraLayout.Rect value) {
            crop = value;
            int width = texture.getWidth();
            int height = texture.getHeight();
            if (width <= 0 || height <= 0) return;

            float cropLeft = value.left;
            float cropTop = value.top;
            float cropWidth = value.width;
            float cropHeight = value.height;
            float sourceAspect = cropWidth * SOURCE_WIDTH / (cropHeight * SOURCE_HEIGHT);
            float targetAspect = (float) width / height;
            if (sourceAspect > targetAspect) {
                float nextWidth = cropHeight * SOURCE_HEIGHT * targetAspect / SOURCE_WIDTH;
                cropLeft += (cropWidth - nextWidth) / 2.0f;
                cropWidth = nextWidth;
            } else if (sourceAspect < targetAspect) {
                float nextHeight = cropWidth * SOURCE_WIDTH / (targetAspect * SOURCE_HEIGHT);
                cropTop += (cropHeight - nextHeight) / 2.0f;
                cropHeight = nextHeight;
            }

            Matrix transform = new Matrix();
            transform.setRectToRect(
                    new RectF(cropLeft * width, cropTop * height,
                            (cropLeft + cropWidth) * width,
                            (cropTop + cropHeight) * height),
                    new RectF(0, 0, width, height), Matrix.ScaleToFit.FILL);
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
}
