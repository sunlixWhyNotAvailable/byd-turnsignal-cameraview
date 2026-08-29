package com.byd.extend;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

final class ReverseCameraCompositionView extends FrameLayout {
    private static final String TAG = "ReverseCameraView";
    static final int SOURCE_WIDTH = 1920;
    static final int SOURCE_HEIGHT = 1300;
    private static final int PREVIEW_BASE_SOURCE_HEIGHT = 990;
    private static final int DEFAULT_CORNER_RADIUS_DP = 8;

    interface Callback {
        void onReverseSurfacesReady(int[] generations);
        void onReverseFramesReady(int requestId, int[] generations);
        void onReverseSurfaceLost(int cameraIndex, int generation);
        default void onReverseFrameGateBlocked(
                int requestId, String reason, int source, int expectedGeneration,
                int actualGeneration) {}
        default void onReverseDewarpStats(
                int cameraIndex, CameraDewarpRenderer.Stats stats) {}
        default void onReverseDewarpEvent(
                int cameraIndex, CameraDewarpRenderer.Event event) {}
    }

    private final View backgroundPane;
    private final ReverseSideSelectorView sideSelector;
    private final PaneView[] panes = new PaneView[3];
    private PaneView centralFrontPane;
    private TextureView previewBase;
    private View previewBaseCover;
    private Surface previewBaseSurface;
    private final BlindSpotCameraView.InputGeneration previewBaseInputGeneration =
            new BlindSpotCameraView.InputGeneration();
    private int previewBaseGeneration;
    private Callback callback;
    private ReverseCameraLayout model = ReverseCameraLayout.defaults();
    private ReverseCameraLayout rawFallbackModel = ReverseCameraLayout.defaults();
    private ReverseCameraLayout frontModel = ReverseCameraLayout.defaults();
    private ReverseCameraLayout frontRawFallbackModel = ReverseCameraLayout.defaults();
    private CameraDewarpConfig rearDewarp = CameraDewarpConfig.disabled(
            CameraDewarpConfig.LENS_REAR);
    private CameraDewarpConfig leftDewarp = CameraDewarpConfig.disabled(
            CameraDewarpConfig.LENS_LEFT);
    private CameraDewarpConfig rightDewarp = CameraDewarpConfig.disabled(
            CameraDewarpConfig.LENS_RIGHT);
    private CameraDewarpConfig frontLeftDewarp = CameraDewarpConfig.disabled(
            CameraDewarpConfig.LENS_LEFT);
    private CameraDewarpConfig frontRightDewarp = CameraDewarpConfig.disabled(
            CameraDewarpConfig.LENS_RIGHT);
    private CameraDewarpConfig centralFrontDewarp = CameraDewarpConfig.disabled(
            CameraDewarpConfig.LENS_FRONT);
    private boolean frontLeftIntegrated;
    private boolean frontRightIntegrated;
    private boolean centralFrontIntegrated;
    private boolean centralFrontSourceEnabled;
    private boolean centralFrontFrameReady;
    private boolean centralFrontDiscardNextFrame;
    private boolean centralFrontSurfaceRecoveryPending;
    private boolean widgetVisible;
    private boolean widgetAvailable = true;
    private int sideMode = ReverseSideSelectorView.MODE_REAR;
    private int cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP;
    private final FrameBarrier frameBarrier = new FrameBarrier();
    private int paneBufferViewportWidth;
    private int paneBufferViewportHeight;
    private int automaticBufferQuality = CameraBufferQuality.ORIGINAL;
    private int visibilityMask = ReverseCameraLayout.VISIBILITY_ALL;
    private boolean forceDewarpPipeline;

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
        sideSelector = new ReverseSideSelectorView(context);
        sideSelector.setListener(this::setSideMode);
        addView(sideSelector, new FrameLayout.LayoutParams(1, 1));
        sideSelector.setZ(5.0f);
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
                createPreviewBaseInput(texture, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture texture, int width, int height) {
                if (previewBaseSurface == null) {
                    configurePreviewBaseBuffer(texture, width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                int lostGeneration = previewBaseGeneration;
                if (previewBaseSurface != null) previewBaseSurface.release();
                previewBaseSurface = null;
                clearFrames();
                if (callback != null) callback.onReverseSurfaceLost(0, lostGeneration);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                acceptFrame(
                        FrameBarrier.SOURCE_BASE, previewBaseInputGeneration.frame());
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
        if (centralFrontPane != null) centralFrontPane.setCornerRadiusDp(cornerRadiusDp);
    }

    void setForceDewarpPipeline(boolean value) {
        forceDewarpPipeline = value;
        for (PaneView pane : panes) pane.texture.setForceDewarpPipeline(value);
        if (centralFrontPane != null) {
            centralFrontPane.texture.setForceDewarpPipeline(value);
        }
    }

    void setDewarpPipelineRequirements(
            CameraDewarpConfig rear, CameraDewarpConfig left, CameraDewarpConfig right,
            CameraDewarpConfig frontLeft, CameraDewarpConfig frontRight,
            boolean integrateFrontLeft, boolean integrateFrontRight) {
        setDewarpPipelineRequirements(rear, left, right, frontLeft, frontRight,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_FRONT),
                integrateFrontLeft, integrateFrontRight, false);
    }

    void setDewarpPipelineRequirements(
            CameraDewarpConfig rear, CameraDewarpConfig left, CameraDewarpConfig right,
            CameraDewarpConfig frontLeft, CameraDewarpConfig frontRight,
            CameraDewarpConfig nextCentralFrontDewarp,
            boolean integrateFrontLeft, boolean integrateFrontRight,
            boolean centralFrontSource) {
        if (centralFrontSource) ensureCentralFrontPane();
        panes[0].texture.setForceDewarpPipeline(rear.usesGpu());
        panes[1].texture.setForceDewarpPipeline(
                left.usesGpu() || (integrateFrontLeft && frontLeft.usesGpu()));
        panes[2].texture.setForceDewarpPipeline(
                right.usesGpu() || (integrateFrontRight && frontRight.usesGpu()));
        if (centralFrontPane != null) {
            centralFrontPane.texture.setForceDewarpPipeline(
                    forceDewarpPipeline || (nextCentralFrontDewarp != null
                            && nextCentralFrontDewarp.usesGpu()));
        }
    }

    void setAutomaticBufferQuality(int quality) {
        CameraBufferQuality.scalePercent(quality);
        automaticBufferQuality = quality;
        for (PaneView pane : panes) pane.texture.setAutomaticBufferQuality(quality);
        if (centralFrontPane != null) {
            centralFrontPane.texture.setAutomaticBufferQuality(quality);
        }
    }

    void applyDewarpConfigs(
            CameraDewarpConfig rear,
            CameraDewarpConfig left,
            CameraDewarpConfig right) {
        rearDewarp = rear;
        leftDewarp = left;
        rightDewarp = right;
        applyActiveDewarpConfigs();
        applyModel();
    }

    void applyDewarpConfigs(
            CameraDewarpConfig rear,
            CameraDewarpConfig left,
            CameraDewarpConfig right,
            CameraDewarpConfig nextCentralFrontDewarp) {
        applyDewarpConfigs(rear, left, right);
        if (nextCentralFrontDewarp != null) {
            centralFrontDewarp = nextCentralFrontDewarp;
            if (centralFrontPane != null) centralFrontPane.applyDewarpConfig(centralFrontDewarp);
        }
        applyModel();
    }

    void configureIntegratedFront(
            ReverseCameraLayout nextFrontModel,
            ReverseCameraLayout nextFrontRawFallbackModel,
            CameraDewarpConfig nextFrontLeftDewarp,
            CameraDewarpConfig nextFrontRightDewarp,
            boolean nextFrontLeftIntegrated,
            boolean nextFrontRightIntegrated,
            boolean nextWidgetVisible) {
        configureIntegratedFront(nextFrontModel, nextFrontRawFallbackModel,
                nextFrontLeftDewarp, nextFrontRightDewarp,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_FRONT),
                nextFrontLeftIntegrated, nextFrontRightIntegrated,
                false, false, nextWidgetVisible);
    }

    void configureIntegratedFront(
            ReverseCameraLayout nextFrontModel,
            ReverseCameraLayout nextFrontRawFallbackModel,
            CameraDewarpConfig nextFrontLeftDewarp,
            CameraDewarpConfig nextFrontRightDewarp,
            CameraDewarpConfig nextCentralFrontDewarp,
            boolean nextFrontLeftIntegrated,
            boolean nextFrontRightIntegrated,
            boolean nextCentralFrontIntegrated,
            boolean nextCentralFrontSourceEnabled,
            boolean nextWidgetVisible) {
        if (nextFrontModel == null || nextFrontRawFallbackModel == null
                || nextFrontLeftDewarp == null || nextFrontRightDewarp == null) {
            throw new IllegalArgumentException("reverse front configuration is required");
        }
        frontModel = withCentralAndSideCalibration(model, nextFrontModel);
        frontRawFallbackModel = withCentralAndSideCalibration(
                rawFallbackModel, nextFrontRawFallbackModel);
        frontLeftDewarp = nextFrontLeftDewarp;
        frontRightDewarp = nextFrontRightDewarp;
        centralFrontDewarp = nextCentralFrontDewarp == null
                ? CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_FRONT)
                : nextCentralFrontDewarp;
        frontLeftIntegrated = nextFrontLeftIntegrated;
        frontRightIntegrated = nextFrontRightIntegrated;
        centralFrontIntegrated = nextCentralFrontIntegrated;
        centralFrontSourceEnabled = nextCentralFrontSourceEnabled;
        if (centralFrontSourceEnabled) ensureCentralFrontPane();
        if (centralFrontPane != null) centralFrontPane.applyDewarpConfig(centralFrontDewarp);
        widgetVisible = nextWidgetVisible;
        widgetAvailable = true;
        sideMode = ReverseSideSelectorView.MODE_REAR;
        sideSelector.setMode(sideMode);
        applyActiveDewarpConfigs();
        applyModel();
    }

    void setCentralFrontSourceEnabled(boolean enabled) {
        centralFrontSourceEnabled = enabled;
        if (!enabled) centralFrontSurfaceRecoveryPending = false;
        if (enabled) ensureCentralFrontPane();
        resetCentralFrontFreshness();
        applyModel();
        notifySurfacesReady();
    }

    void setWidgetAvailable(boolean available) {
        widgetAvailable = available;
        if (!available) {
            sideMode = ReverseSideSelectorView.MODE_REAR;
            sideSelector.setMode(sideMode);
            applyActiveDewarpConfigs();
        }
        applyModel();
    }

    void setSideMode(int mode) {
        if (mode != ReverseSideSelectorView.MODE_REAR
                && mode != ReverseSideSelectorView.MODE_FRONT) {
            throw new IllegalArgumentException("invalid reverse side mode");
        }
        sideMode = mode;
        if (sideSelector.mode() != mode) sideSelector.setMode(mode);
        applyActiveDewarpConfigs();
        applyModel();
    }

    int sideMode() {
        return sideMode;
    }

    ReverseCameraLayout.PixelRect selectorButtonBounds(
            int mode, int viewportWidth, int viewportHeight) {
        ReverseCameraLayout.PixelRect widget = ReverseCameraLayout.project(
                model.widget, viewportWidth, viewportHeight);
        float[] button = ReverseSideSelectorView.normalizedButtonRect(mode);
        int left = widget.left + Math.round(button[0] * widget.width);
        int top = widget.top + Math.round(button[1] * widget.height);
        int right = widget.left + Math.round(button[2] * widget.width);
        int bottom = widget.top + Math.round(button[3] * widget.height);
        return new ReverseCameraLayout.PixelRect(
                left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    void applyVisibility(int mask) {
        visibilityMask = ReverseCameraLayout.requireVisibilityMask(mask);
        applyEffectiveVisibility();
    }

    static float alphaForVisibility(int mask, int paneId) {
        return ReverseCameraLayout.isVisible(mask, paneId) ? 1.0f : 0.0f;
    }

    void setDewarpStatsContext(int requestId, int[] generations) {
        if (generations == null || (generations.length != panes.length
                && generations.length != panes.length + 1)) return;
        for (int i = 0; i < panes.length; i++) {
            panes[i].texture.setDewarpStatsContext(requestId, generations[i]);
        }
        if (centralFrontPane != null && generations.length == panes.length + 1) {
            centralFrontPane.texture.setDewarpStatsContext(requestId, generations[3]);
        }
    }

    boolean dewarpPipelineCompatible(
            CameraDewarpConfig rear,
            CameraDewarpConfig left,
            CameraDewarpConfig right,
            CameraDewarpConfig frontLeft,
            CameraDewarpConfig frontRight,
            boolean integrateFrontLeft,
            boolean integrateFrontRight) {
        for (int i = 0; i < panes.length; i++) {
            boolean needsGpu = i == 0 ? rear.usesGpu()
                    : i == 1 ? left.usesGpu() || (integrateFrontLeft && frontLeft.usesGpu())
                    : right.usesGpu() || (integrateFrontRight && frontRight.usesGpu());
            if (panes[i].texture.usesDewarpPipeline() != needsGpu) return false;
        }
        return true;
    }

    boolean dewarpPipelineCompatible(
            CameraDewarpConfig rear,
            CameraDewarpConfig left,
            CameraDewarpConfig right,
            CameraDewarpConfig frontLeft,
            CameraDewarpConfig frontRight,
            CameraDewarpConfig nextCentralFrontDewarp,
            boolean integrateFrontLeft,
            boolean integrateFrontRight,
            boolean nextCentralFrontSource) {
        if (!dewarpPipelineCompatible(rear, left, right, frontLeft, frontRight,
                integrateFrontLeft, integrateFrontRight)) return false;
        if (!nextCentralFrontSource) return true;
        return centralFrontPane != null
                && centralFrontPane.texture.usesDewarpPipeline()
                == (forceDewarpPipeline || nextCentralFrontDewarp.usesGpu());
    }

    void applyLayout(ReverseCameraLayout value) {
        if (value == null) throw new IllegalArgumentException("reverse layout is required");
        model = value;
        frontModel = withCentralAndSideCalibration(model, frontModel);
        applyModel();
    }

    void applyRawFallbackLayout(ReverseCameraLayout value) {
        if (value == null) throw new IllegalArgumentException("raw fallback layout is required");
        rawFallbackModel = value;
        frontRawFallbackModel = withCentralAndSideCalibration(
                rawFallbackModel, frontRawFallbackModel);
        applyModel();
    }

    void setPaneBoundedBuffers(int viewportWidth, int viewportHeight, int quality) {
        int[][] bounds = paneBounds(model, viewportWidth, viewportHeight);
        for (int i = 0; i < panes.length; i++) {
            panes[i].texture.setPaneBoundedBuffer(
                    bounds[i][0], bounds[i][1], quality);
        }
        if (centralFrontPane != null) {
            centralFrontPane.texture.setPaneBoundedBuffer(
                    bounds[0][0], bounds[0][1], quality);
        }
        paneBufferViewportWidth = viewportWidth;
        paneBufferViewportHeight = viewportHeight;
    }

    boolean usesPaneGeometry(
            ReverseCameraLayout layout, int viewportWidth, int viewportHeight) {
        return samePaneGeometry(
                model, paneBufferViewportWidth, paneBufferViewportHeight,
                layout, viewportWidth, viewportHeight);
    }

    boolean usesPaneBoundedBuffers(
            ReverseCameraLayout layout, int viewportWidth, int viewportHeight, int quality) {
        int[][] bounds = paneBounds(layout, viewportWidth, viewportHeight);
        for (int i = 0; i < panes.length; i++) {
            if (!panes[i].texture.usesPaneBoundedBuffer(
                    bounds[i][0], bounds[i][1], quality)) return false;
        }
        if (centralFrontSourceEnabled && centralFrontPane != null
                && !centralFrontPane.texture.usesPaneBoundedBuffer(
                        bounds[0][0], bounds[0][1], quality)) return false;
        return true;
    }

    static int[][] paneBounds(
            ReverseCameraLayout layout, int viewportWidth, int viewportHeight) {
        if (layout == null) throw new IllegalArgumentException("reverse layout is required");
        ReverseCameraLayout.Pane[] values = layout.panes();
        int[][] result = new int[values.length][2];
        for (int i = 0; i < values.length; i++) {
            ReverseCameraLayout.PixelRect rect = ReverseCameraLayout.project(
                    values[i].destination, viewportWidth, viewportHeight);
            result[i][0] = Math.max(1, rect.width);
            result[i][1] = Math.max(1, rect.height);
        }
        return result;
    }

    static boolean samePaneGeometry(
            ReverseCameraLayout current, int currentViewportWidth, int currentViewportHeight,
            ReverseCameraLayout next, int nextViewportWidth, int nextViewportHeight) {
        if (currentViewportWidth != nextViewportWidth
                || currentViewportHeight != nextViewportHeight) return false;
        int[][] currentBounds = paneBounds(
                current, currentViewportWidth, currentViewportHeight);
        int[][] nextBounds = paneBounds(next, nextViewportWidth, nextViewportHeight);
        if (currentBounds.length != nextBounds.length) return false;
        for (int i = 0; i < currentBounds.length; i++) {
            if (currentBounds[i][0] != nextBounds[i][0]
                    || currentBounds[i][1] != nextBounds[i][1]) return false;
        }
        return true;
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
        // During Front calibration the logical center pane (index 1) is backed
        // by the independent physical pano_h index 4 source.  Route editor
        // mirror surfaces to that source so LIVE/raw/corrected previews never
        // accidentally bind to the Rear producer.
        if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX
                && sideMode == ReverseSideSelectorView.MODE_FRONT
                && centralFrontSourceEnabled && centralFrontPane != null) {
            return centralFrontPane;
        }
        for (PaneView pane : panes) {
            if (pane.cameraIndex == cameraIndex) return pane;
        }
        throw new IllegalArgumentException("unsupported reverse camera index: " + cameraIndex);
    }

    boolean surfacesReady() {
        for (PaneView pane : panes) {
            if (pane.surface == null || !pane.surface.isValid()) return false;
        }
        if (centralFrontSourceEnabled && (centralFrontPane == null
                || centralFrontPane.surface == null || !centralFrontPane.surface.isValid())) {
            return false;
        }
        return true;
    }

    boolean previewSurfacesReady() {
        return surfacesReady() && previewBaseSurface != null && previewBaseSurface.isValid();
    }

    void retirePreviewInputs() {
        clearFrames();
        if (previewBaseSurface != null) previewBaseSurface.release();
        previewBaseSurface = null;
        for (PaneView pane : panes) {
            pane.texture.retireCameraInput();
            pane.surface = null;
        }
        if (centralFrontPane != null) {
            centralFrontPane.texture.retireCameraInput();
            centralFrontPane.surface = null;
            centralFrontFrameReady = false;
        }
    }

    void ensurePreviewInputs() {
        if (previewBase != null && previewBaseSurface == null
                && previewBase.isAvailable() && previewBase.getSurfaceTexture() != null) {
            createPreviewBaseInput(
                    previewBase.getSurfaceTexture(), previewBase.getWidth(), previewBase.getHeight());
        }
        for (PaneView pane : panes) pane.texture.ensureCameraInput();
        if (centralFrontSourceEnabled && centralFrontPane != null) {
            centralFrontPane.texture.ensureCameraInput();
        }
        notifySurfacesReady();
    }

    SurfaceBundle acquireSurfaces(int requestId) {
        if (requestId <= 0 || !surfacesReady()) {
            throw new IllegalStateException("reverse Surfaces unavailable");
        }
        int directCount = centralFrontSourceEnabled ? panes.length + 1 : panes.length;
        Surface[] surfaces = new Surface[directCount];
        int[] generations = new int[directCount];
        for (int i = 0; i < panes.length; i++) {
            surfaces[i] = panes[i].surface;
            generations[i] = panes[i].generation;
        }
        if (centralFrontSourceEnabled) {
            surfaces[3] = centralFrontPane.surface;
            generations[3] = centralFrontPane.generation;
            centralFrontSurfaceRecoveryPending = false;
        }
        return new SurfaceBundle(requestId, generations, surfaces);
    }

    SurfaceBundle acquirePreviewSurfaces(int requestId) {
        if (requestId <= 0 || !previewSurfacesReady()) {
            throw new IllegalStateException("reverse preview Surfaces unavailable");
        }
        SurfaceBundle direct = acquireSurfaces(requestId);
        Surface[] surfaces = new Surface[direct.surfaces.length + 1];
        int[] generations = new int[direct.generations.length + 1];
        surfaces[0] = previewBaseSurface;
        System.arraycopy(direct.surfaces, 0, surfaces, 1, direct.surfaces.length);
        generations[0] = previewBaseGeneration;
        System.arraycopy(direct.generations, 0, generations, 1,
                direct.generations.length);
        return new SurfaceBundle(requestId, generations, surfaces);
    }

    void armPreviewFrames(int requestId, int[] expectedGenerations) {
        int directCount = centralFrontSourceEnabled ? panes.length + 1 : panes.length;
        if (requestId <= 0 || expectedGenerations == null
                || expectedGenerations.length != directCount + 1
                || expectedGenerations[0] != previewBaseGeneration
                || previewBaseSurface == null || !previewBaseSurface.isValid()) {
            throw new IllegalStateException("stale reverse preview input");
        }
        int[] directGenerations = new int[directCount];
        for (int i = 0; i < panes.length; i++) {
            directGenerations[i] = expectedGenerations[i + 1];
            if (directGenerations[i] != panes[i].generation || panes[i].surface == null) {
                throw new IllegalStateException("stale reverse Surface");
            }
        }
        if (directCount > panes.length) {
            directGenerations[3] = expectedGenerations[4];
            if (centralFrontPane == null || centralFrontPane.surface == null
                    || centralFrontPane.generation != directGenerations[3]) {
                throw new IllegalStateException("stale reverse central front Surface");
            }
        }
        resetCentralFrontFreshness();
        setAllCovers(View.VISIBLE);
        // The optional central Front source must not delay the ordinary Rear
        // composition.  Its first frame is tracked independently and only
        // enables the central pane once it arrives.
        frameBarrier.arm(requestId, expectedGenerations[0], directGenerations,
                centralFrontSourceEnabled ? panes.length : directCount);
    }

    void armFrames(int requestId, int[] expectedGenerations) {
        int directCount = centralFrontSourceEnabled ? panes.length + 1 : panes.length;
        if (requestId <= 0 || expectedGenerations == null
                || expectedGenerations.length != directCount) {
            throw new IllegalArgumentException("invalid reverse frame identity");
        }
        for (int i = 0; i < panes.length; i++) {
            if (expectedGenerations[i] != panes[i].generation || panes[i].surface == null) {
                throw new IllegalStateException("stale reverse Surface");
            }
        }
        if (directCount > panes.length
                && (centralFrontPane == null || centralFrontPane.surface == null
                || expectedGenerations[3] != centralFrontPane.generation)) {
            throw new IllegalStateException("stale reverse central front Surface");
        }
        int baseGeneration = 0;
        if (previewBase != null) {
            if (previewBaseSurface == null || !previewBaseSurface.isValid()) {
                throw new IllegalStateException("stale reverse preview base Surface");
            }
            baseGeneration = previewBaseGeneration;
        }
        resetCentralFrontFreshness();
        setAllCovers(View.VISIBLE);
        frameBarrier.arm(requestId, baseGeneration, expectedGenerations,
                centralFrontSourceEnabled ? panes.length : directCount);
    }

    void clearFrames() {
        frameBarrier.clear();
        resetCentralFrontFreshness();
        setAllCovers(View.VISIBLE);
    }

    boolean framesReady(int requestId, int[] expectedGenerations) {
        return frameBarrier.readyEventRecorded(
                requestId, previewBase == null ? 0 : previewBaseGeneration,
                expectedGenerations);
    }

    private PaneView addPane(int cameraIndex) {
        return addPane(cameraIndex, cameraIndex,
                CameraDewarpConfig.lensForReverseCamera(cameraIndex));
    }

    private PaneView addPane(int cameraIndex, int sourceIndex, int dewarpLens) {
        PaneView pane = new PaneView(getContext(), cameraIndex, sourceIndex, dewarpLens);
        pane.texture.setDewarpStatsSink(stats -> {
            if (callback != null) callback.onReverseDewarpStats(sourceIndex, stats);
        });
        pane.texture.setDewarpEventSink(event -> {
            if (callback != null) callback.onReverseDewarpEvent(sourceIndex, event);
        });
        pane.texture.setCallback(new BlindSpotCameraView.Callback() {
            @Override
            public void onCameraSurfaceAvailable(
                    BlindSpotCameraView view, Surface surface, int width, int height) {
                onCameraSurfaceAvailable(view, surface, width, height,
                        view.cameraInputGeneration());
            }

            @Override
            public void onCameraSurfaceAvailable(
                    BlindSpotCameraView view, Surface surface, int width, int height,
                    int inputGeneration) {
                boolean optionalRecovery = sourceIndex == 4
                        && centralFrontSurfaceRecoveryPending;
                pane.surface = surface;
                pane.generation = inputGeneration;
                if (sourceIndex == 4) {
                    resetCentralFrontFreshness();
                    if (optionalRecovery) {
                        // Keep the already-revealed Rear composition alive.
                        // The replacement Surface is acquired on the next full
                        // reverse request, where it receives a new identity.
                        applyModel();
                        return;
                    }
                }
                clearFrames();
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
                if (sourceIndex == 4 && centralFrontSourceEnabled
                        && frameBarrier.revealed()) {
                    centralFrontSurfaceRecoveryPending = true;
                    resetCentralFrontFreshness();
                    applyModel();
                    if (callback != null) {
                        callback.onReverseSurfaceLost(sourceIndex, lostGeneration);
                    }
                    return;
                }
                clearFrames();
                if (callback != null) {
                    callback.onReverseSurfaceLost(sourceIndex, lostGeneration);
                }
            }

            @Override
            public void onCameraFrameUpdated(BlindSpotCameraView view) {
                onCameraFrameUpdated(view, view.cameraInputGeneration());
            }

            @Override
            public void onCameraFrameUpdated(
                    BlindSpotCameraView view, int inputGeneration) {
                if (sourceIndex == 4) {
                    if (inputGeneration == pane.generation) {
                        if (centralFrontDiscardNextFrame) {
                            centralFrontDiscardNextFrame = false;
                        } else {
                            centralFrontFrameReady = true;
                            applyEffectiveVisibility();
                        }
                    }
                }
                acceptFrame(sourceIndex,
                        previewBase == null ? pane.generation : inputGeneration);
            }

            @Override
            public void onDewarpFallbackChanged(BlindSpotCameraView view) {
                applyModel();
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

    private void acceptFrame(int source, int generation) {
        int requestId = frameBarrier.requestId();
        FrameBarrier.FrameResult result = frameBarrier.frame(requestId, source, generation);
        if (result == FrameBarrier.FrameResult.BLOCKED_GUARD
                || result == FrameBarrier.FrameResult.BLOCKED_STALE) {
            String reason = result == FrameBarrier.FrameResult.BLOCKED_GUARD
                    ? "post_arm_guard" : "stale_identity";
            int expected = frameBarrier.expectedGeneration(source);
            Log.w(TAG, "reverse frame blocked: request=" + requestId
                    + " source=" + source + " reason=" + reason
                    + " expected=" + expected + " actual=" + generation);
            if (callback != null) {
                callback.onReverseFrameGateBlocked(
                        requestId, reason, source, expected, generation);
            }
        }
        if (result == FrameBarrier.FrameResult.READY) maybeReportFrames();
    }

    private void maybeReportFrames() {
        int requestId = frameBarrier.requestId();
        int[] directGenerations = currentDirectGenerations();
        int baseGeneration = previewBase == null ? 0 : previewBaseGeneration;
        if (!frameBarrier.readyPending(requestId, baseGeneration, directGenerations)
                || callback == null) return;
        int[] generations = currentGenerations();
        callback.onReverseFramesReady(requestId, generations);
        if (!frameBarrier.recordReadyEvent(requestId, baseGeneration, directGenerations)
                || !frameBarrier.reveal(
                        requestId, baseGeneration, directGenerations)) return;
        setAllCovers(View.GONE);
    }

    private void setAllCovers(int visibility) {
        for (PaneView pane : panes) pane.cover.setVisibility(visibility);
        if (centralFrontPane != null) centralFrontPane.cover.setVisibility(visibility);
        if (previewBaseCover != null) previewBaseCover.setVisibility(visibility);
    }

    private int[] currentGenerations() {
        int[] direct = currentDirectGenerations();
        if (previewBase == null) return direct;
        int[] values = new int[direct.length + 1];
        values[0] = previewBaseGeneration;
        System.arraycopy(direct, 0, values, 1, direct.length);
        return values;
    }

    private int[] currentDirectGenerations() {
        int directCount = centralFrontSourceEnabled ? panes.length + 1 : panes.length;
        int[] values = new int[directCount];
        for (int i = 0; i < panes.length; i++) values[i] = panes[i].generation;
        if (centralFrontSourceEnabled) values[3] = centralFrontPane.generation;
        return values;
    }

    private void createPreviewBaseInput(SurfaceTexture texture, int width, int height) {
        configurePreviewBaseBuffer(texture, width, height);
        if (previewBaseSurface != null) previewBaseSurface.release();
        previewBaseSurface = new Surface(texture);
        previewBaseGeneration = previewBaseInputGeneration.next();
        clearFrames();
        notifySurfacesReady();
    }

    private void configurePreviewBaseBuffer(
            SurfaceTexture texture, int width, int height) {
        int[] size = CameraBufferQuality.bufferSizeForPane(
                width, height, SOURCE_WIDTH, PREVIEW_BASE_SOURCE_HEIGHT,
                automaticBufferQuality);
        texture.setDefaultBufferSize(size[0], size[1]);
    }

    static final class FrameBarrier {
        static final int SOURCE_BASE = 0;
        private static final int SOURCE_COUNT = 5;

        enum FrameResult {
            IGNORED,
            BLOCKED_GUARD,
            BLOCKED_STALE,
            ACCEPTED,
            READY
        }

        private final int[] generations = new int[SOURCE_COUNT];
        private final boolean[] discardNext = new boolean[SOURCE_COUNT];
        private final boolean[] fresh = new boolean[SOURCE_COUNT];
        private int requestId;
        private boolean readyPending;
        private boolean readyEventRecorded;
        private boolean revealed;
        private boolean guardDiagnosticReported;
        private boolean staleDiagnosticReported;
        private int directCount = SOURCE_COUNT - 1;
        private int requiredDirectCount = SOURCE_COUNT - 1;

        void arm(int nextRequestId, int baseGeneration, int[] directGenerations) {
            arm(nextRequestId, baseGeneration, directGenerations,
                    directGenerations == null ? 0 : directGenerations.length);
        }

        void arm(
                int nextRequestId, int baseGeneration, int[] directGenerations,
                int nextRequiredDirectCount) {
            if (nextRequestId <= 0 || baseGeneration < 0 || directGenerations == null
                    || (directGenerations.length != SOURCE_COUNT - 2
                    && directGenerations.length != SOURCE_COUNT - 1)
                    || nextRequiredDirectCount < SOURCE_COUNT - 2
                    || nextRequiredDirectCount > directGenerations.length) {
                throw new IllegalArgumentException("invalid reverse frame identity");
            }
            requestId = nextRequestId;
            directCount = directGenerations.length;
            requiredDirectCount = nextRequiredDirectCount;
            generations[SOURCE_BASE] = baseGeneration;
            fresh[SOURCE_BASE] = baseGeneration == 0;
            discardNext[SOURCE_BASE] = baseGeneration != 0;
            for (int i = 0; i < directGenerations.length; i++) {
                if (directGenerations[i] <= 0) {
                    throw new IllegalArgumentException("invalid reverse Surface generation");
                }
                int source = i + 1;
                generations[source] = directGenerations[i];
                fresh[source] = false;
                discardNext[source] = true;
            }
            for (int i = directCount + 1; i < SOURCE_COUNT; i++) {
                generations[i] = 0;
                discardNext[i] = false;
                fresh[i] = true;
            }
            readyPending = false;
            readyEventRecorded = false;
            revealed = false;
            guardDiagnosticReported = false;
            staleDiagnosticReported = false;
        }

        void clear() {
            requestId = 0;
            readyPending = false;
            readyEventRecorded = false;
            revealed = false;
            guardDiagnosticReported = false;
            staleDiagnosticReported = false;
            for (int i = 0; i < SOURCE_COUNT; i++) {
                generations[i] = 0;
                discardNext[i] = false;
                fresh[i] = false;
            }
            directCount = SOURCE_COUNT - 1;
            requiredDirectCount = SOURCE_COUNT - 1;
        }

        int requestId() {
            return requestId;
        }

        int expectedGeneration(int source) {
            return validSource(source) ? generations[source] : 0;
        }

        FrameResult frame(int frameRequestId, int source, int generation) {
            if (requestId <= 0 || !validSource(source)) return FrameResult.IGNORED;
            if (frameRequestId != requestId || generations[source] <= 0
                    || generation != generations[source]) {
                if (staleDiagnosticReported) return FrameResult.IGNORED;
                staleDiagnosticReported = true;
                return FrameResult.BLOCKED_STALE;
            }
            if (readyPending) return FrameResult.IGNORED;
            if (discardNext[source]) {
                discardNext[source] = false;
                if (guardDiagnosticReported) return FrameResult.IGNORED;
                guardDiagnosticReported = true;
                return FrameResult.BLOCKED_GUARD;
            }
            fresh[source] = true;
            if (!allFresh()) return FrameResult.ACCEPTED;
            readyPending = true;
            return FrameResult.READY;
        }

        boolean readyPending(int expectedRequestId, int baseGeneration, int[] directGenerations) {
            return readyPending && matches(expectedRequestId, baseGeneration, directGenerations);
        }

        boolean recordReadyEvent(
                int expectedRequestId, int baseGeneration, int[] directGenerations) {
            if (!readyPending(expectedRequestId, baseGeneration, directGenerations)
                    || readyEventRecorded) return false;
            readyEventRecorded = true;
            return true;
        }

        boolean readyEventRecorded(
                int expectedRequestId, int baseGeneration, int[] directGenerations) {
            return readyEventRecorded
                    && matches(expectedRequestId, baseGeneration, directGenerations);
        }

        boolean reveal(int expectedRequestId, int baseGeneration, int[] directGenerations) {
            if (!readyEventRecorded(expectedRequestId, baseGeneration, directGenerations)
                    || revealed) return false;
            revealed = true;
            return true;
        }

        boolean revealed() {
            return revealed;
        }

        private boolean allFresh() {
            if (!fresh[SOURCE_BASE]) return false;
            for (int i = 1; i <= requiredDirectCount; i++) {
                if (!fresh[i]) return false;
            }
            return true;
        }

        private boolean matches(
                int expectedRequestId, int baseGeneration, int[] directGenerations) {
            if (expectedRequestId != requestId || baseGeneration != generations[SOURCE_BASE]
                    || directGenerations == null
                    || directGenerations.length != directCount) return false;
            for (int i = 0; i < directGenerations.length; i++) {
                if (directGenerations[i] != generations[i + 1]) return false;
            }
            return true;
        }

        private static boolean validSource(int source) {
            return source >= 0 && source < SOURCE_COUNT;
        }
    }

    private void applyModel() {
        applyEffectiveVisibility();
        ReverseCameraLayout activeModel = sideMode == ReverseSideSelectorView.MODE_FRONT
                ? frontModel : model;
        ReverseCameraLayout activeRawFallback = sideMode == ReverseSideSelectorView.MODE_FRONT
                ? frontRawFallbackModel : rawFallbackModel;
        boolean showCentralFront = sideMode == ReverseSideSelectorView.MODE_FRONT
                && centralFrontIntegrated && centralFrontSourceEnabled
                && centralFrontPane != null && centralFrontFrameReady;
        int width = getWidth();
        int height = getHeight();
        for (PaneView pane : panes) {
            ReverseCameraLayout centerRawFallback = pane.cameraIndex
                    == ReverseCameraLayout.REAR_CAMERA_INDEX && !showCentralFront
                    ? rawFallbackModel : activeRawFallback;
            ReverseCameraLayout.Rect rawCrop =
                    centerRawFallback.pane(pane.cameraIndex).sourceCrop;
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
        ReverseCameraLayout.PixelRect widgetRect =
                ReverseCameraLayout.project(model.widget, width, height);
        FrameLayout.LayoutParams widgetParams =
                (FrameLayout.LayoutParams) sideSelector.getLayoutParams();
        widgetParams.width = Math.max(1, widgetRect.width);
        widgetParams.height = Math.max(1, widgetRect.height);
        widgetParams.leftMargin = widgetRect.left;
        widgetParams.topMargin = widgetRect.top;
        sideSelector.setLayoutParams(widgetParams);
        sideSelector.setZ(5.0f);
        for (PaneView pane : panes) {
            ReverseCameraLayout centerFallback = pane.cameraIndex
                    == ReverseCameraLayout.REAR_CAMERA_INDEX && !showCentralFront
                    ? model : activeModel;
            ReverseCameraLayout centerRawFallback = pane.cameraIndex
                    == ReverseCameraLayout.REAR_CAMERA_INDEX && !showCentralFront
                    ? rawFallbackModel : activeRawFallback;
            ReverseCameraLayout.Pane value = centerFallback.pane(pane.cameraIndex);
            ReverseCameraLayout.Rect rawCrop =
                    centerRawFallback.pane(pane.cameraIndex).sourceCrop;
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
            pane.applyTransform(sourceCrop, value.rotationDegrees, value.displayMode,
                    value.mirrorHorizontally);
        }
        if (centralFrontPane != null) {
            ReverseCameraLayout.Pane centerValue = activeModel.pane(
                    ReverseCameraLayout.REAR_CAMERA_INDEX);
            ReverseCameraLayout.Rect centerRawCrop = activeRawFallback.pane(
                    ReverseCameraLayout.REAR_CAMERA_INDEX).sourceCrop;
            centralFrontPane.texture.applyDewarpSourceRoi(
                    centerRawCrop.left, centerRawCrop.top,
                    centerRawCrop.width, centerRawCrop.height);
            ReverseCameraLayout.Rect centerSourceCrop = centralFrontPane.dewarpConfig.enabled
                    && !centralFrontPane.texture.usesRawFallback()
                    ? centerValue.sourceCrop : centerRawCrop;
            ReverseCameraLayout.PixelRect centerRect =
                    ReverseCameraLayout.project(centerValue.destination, width, height);
            FrameLayout.LayoutParams centerParams =
                    (FrameLayout.LayoutParams) centralFrontPane.getLayoutParams();
            centerParams.width = Math.max(1, centerRect.width);
            centerParams.height = Math.max(1, centerRect.height);
            centerParams.leftMargin = centerRect.left;
            centerParams.topMargin = centerRect.top;
            centralFrontPane.setLayoutParams(centerParams);
            centralFrontPane.setZ(1.1f + centerValue.zOrder);
            centralFrontPane.applyTransform(centerSourceCrop, centerValue.rotationDegrees,
                    centerValue.displayMode, centerValue.mirrorHorizontally);
        }
    }

    private void applyActiveDewarpConfigs() {
        panes[0].applyDewarpConfig(rearDewarp);
        panes[1].applyDewarpConfig(sideMode == ReverseSideSelectorView.MODE_FRONT
                ? frontLeftDewarp : leftDewarp);
        panes[2].applyDewarpConfig(sideMode == ReverseSideSelectorView.MODE_FRONT
                ? frontRightDewarp : rightDewarp);
        if (centralFrontPane != null) {
            centralFrontPane.applyDewarpConfig(centralFrontDewarp);
        }
    }

    private void applyEffectiveVisibility() {
        backgroundPane.setAlpha(alphaForVisibility(
                visibilityMask, ReverseCameraLayout.BACKGROUND_PANE_ID));
        boolean centerVisible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_CAMERA_INDEX);
        boolean centralFrontVisible = centerVisible
                && sideMode == ReverseSideSelectorView.MODE_FRONT
                && centralFrontIntegrated && centralFrontSourceEnabled
                && centralFrontPane != null && centralFrontFrameReady;
        panes[0].setAlpha(centerVisible && !centralFrontVisible ? 1.0f : 0.0f);
        if (centralFrontPane != null) {
            centralFrontPane.setAlpha(centralFrontVisible ? 1.0f : 0.0f);
        }
        boolean front = sideMode == ReverseSideSelectorView.MODE_FRONT;
        boolean leftVisible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX)
                && (!front || frontLeftIntegrated);
        boolean rightVisible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX)
                && (!front || frontRightIntegrated);
        panes[1].setAlpha(leftVisible ? 1.0f : 0.0f);
        panes[2].setAlpha(rightVisible ? 1.0f : 0.0f);
        sideSelector.setEffectiveVisibility(
                leftVisible, rightVisible, front ? centralFrontVisible : centerVisible);
        sideSelector.setVisibility(widgetVisible && widgetAvailable
                ? View.VISIBLE : View.GONE);
    }

    private PaneView ensureCentralFrontPane() {
        if (centralFrontPane != null) return centralFrontPane;
        centralFrontPane = addPane(
                ReverseCameraLayout.REAR_CAMERA_INDEX,
                4, CameraDewarpConfig.LENS_FRONT);
        centralFrontPane.texture.setAutomaticBufferQuality(automaticBufferQuality);
        centralFrontPane.texture.setForceDewarpPipeline(forceDewarpPipeline);
        centralFrontPane.setCornerRadiusDp(cornerRadiusDp);
        centralFrontPane.setAlpha(0.0f);
        applyModel();
        return centralFrontPane;
    }

    private void resetCentralFrontFreshness() {
        centralFrontFrameReady = false;
        centralFrontDiscardNextFrame = centralFrontSourceEnabled;
        applyEffectiveVisibility();
    }

    private static ReverseCameraLayout withCentralAndSideCalibration(
            ReverseCameraLayout shared, ReverseCameraLayout calibration) {
        ReverseCameraLayout result = ReverseCameraLayout.withSideCalibration(
                shared, calibration);
        ReverseCameraLayout.Pane calibrated = calibration.pane(
                ReverseCameraLayout.REAR_CAMERA_INDEX);
        ReverseCameraLayout.Pane target = result.pane(
                ReverseCameraLayout.REAR_CAMERA_INDEX);
        result = ReverseCameraLayout.withPane(result,
                ReverseCameraLayout.REAR_CAMERA_INDEX, target.destination,
                calibrated.sourceCrop, calibrated.rotationDegrees);
        result = ReverseCameraLayout.withDisplayMode(result,
                ReverseCameraLayout.REAR_CAMERA_INDEX, calibrated.displayMode);
        return ReverseCameraLayout.withMirrorHorizontally(result,
                ReverseCameraLayout.REAR_CAMERA_INDEX, calibrated.mirrorHorizontally);
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
        final int sourceIndex;
        final BlindSpotCameraView texture;
        final CropMaskView cropMask;
        final View cover;
        CameraDewarpConfig dewarpConfig;
        Surface surface;
        int generation;
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(0, 0, 1, 1);
        int rotationDegrees;
        int displayMode = ReverseCameraLayout.DEFAULT_DISPLAY_MODE;
        boolean mirrorHorizontally = true;

        PaneView(Context context, int cameraIndex, int sourceIndex, int dewarpLens) {
            super(context);
            this.cameraIndex = cameraIndex;
            this.sourceIndex = sourceIndex;
            dewarpConfig = CameraDewarpConfig.disabled(
                    dewarpLens);
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
                    applyTransform(crop, rotationDegrees, displayMode, mirrorHorizontally));
        }

        void applyDewarpConfig(CameraDewarpConfig value) {
            if (value == null) throw new IllegalArgumentException("dewarp config is required");
            dewarpConfig = value;
            texture.applyDewarpConfig(value);
        }

        void applyTransform(
                ReverseCameraLayout.Rect value, int degrees, int nextDisplayMode,
                boolean mirror) {
            crop = value;
            rotationDegrees = CameraRotation.clamp(degrees);
            displayMode = ReverseCameraLayout.normalizeDisplayMode(nextDisplayMode);
            mirrorHorizontally = mirror;
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
            }
            CameraRotation.setSourceCropTransformForInput(
                    transform, value.left, value.top, value.width, value.height,
                    new RectF(0, 0, fitted.width, fitted.height), rotationDegrees,
                    displayMode == ReverseCameraLayout.DISPLAY_MODE_FILL
                            ? CameraRotation.MODE_FILL
                            : displayMode == ReverseCameraLayout.DISPLAY_MODE_STRETCH
                                    ? CameraRotation.MODE_ALIGNED : CameraRotation.MODE_FIT,
                    SOURCE_WIDTH, SOURCE_HEIGHT, fitted.width, fitted.height,
                    mirrorHorizontally);
            if (displayMode != ReverseCameraLayout.DISPLAY_MODE_STRETCH) {
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
