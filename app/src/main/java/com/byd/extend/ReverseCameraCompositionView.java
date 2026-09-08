package com.byd.extend;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
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
        /** Reports the transient RAW fallback for a live pane without changing preferences. */
        default void onReverseDewarpFallbackChanged(
                int cameraIndex, boolean frontSource, boolean active) {}
        /** Requests the existing persistent fan-out target to pause/resume. */
        default void onReverseTargetActive(
                int sourceIndex, int generation, boolean active) {}
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
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop
                    || right != oldRight || bottom != oldBottom) applyModel();
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        if (width <= 0 || height <= 0) return;
        applyModel(width, height);
        // applyModel updates the projected child LayoutParams.  Measure once more so
        // SurfaceTexture children are created with those bounds instead of the 1x1
        // constructor placeholders.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(
            boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Frames can become ready while the first native measure still has 1x1
        // children.  Re-check after every layout pass; FrameBarrier keeps the
        // pending readiness one-shot and no request/re-arm is performed here.
        maybeReportFrames();
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
            if (centralFrontPane != null) applyPaneDewarpConfig(centralFrontPane, centralFrontDewarp);
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
        if (centralFrontPane != null) applyPaneDewarpConfig(centralFrontPane, centralFrontDewarp);
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

    /** Updates the persisted widget visibility without resetting the selected front/rear side. */
    void setWidgetVisible(boolean visible) {
        widgetVisible = visible;
        applyModel();
    }

    void setSideMode(int mode) {
        if (mode != ReverseSideSelectorView.MODE_REAR
                && mode != ReverseSideSelectorView.MODE_FRONT) {
            throw new IllegalArgumentException("invalid reverse side mode");
        }
        boolean modeChanged = sideMode != mode;
        sideMode = mode;
        if (sideSelector.mode() != mode) sideSelector.setMode(mode);
        if (modeChanged && centralFrontSourceEnabled) resetCentralFrontFreshness();
        applyActiveDewarpConfigs();
        applyModel();
    }

    int sideMode() {
        return sideMode;
    }

    void setSelectorPressed(int mode, boolean pressed) {
        if (mode != ReverseSideSelectorView.MODE_REAR
                && mode != ReverseSideSelectorView.MODE_FRONT) {
            throw new IllegalArgumentException("invalid reverse selector mode");
        }
        sideSelector.setExternalPressedMode(pressed ? mode : -1);
    }

    /** Cancels a latched selector press when its window host is hidden or detached. */
    void cancelPendingSelectorPress() {
        sideSelector.cancelPendingPress();
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
        boolean centerWasVisible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_CAMERA_INDEX);
        visibilityMask = ReverseCameraLayout.requireVisibilityMask(mask);
        boolean centerNowVisible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_CAMERA_INDEX);
        if (!centerWasVisible && centerNowVisible
                && sideMode == ReverseSideSelectorView.MODE_FRONT
                && centralFrontSourceEnabled && centralFrontIntegrated) {
            resetCentralFrontFreshness();
            return;
        }
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
        return firstPaneBufferMismatch(layout, viewportWidth, viewportHeight, quality) == null;
    }

    /** Returns the first retained pane-buffer comparison that does not match the requested spec. */
    PaneBufferMismatch firstPaneBufferMismatch(
            ReverseCameraLayout layout, int viewportWidth, int viewportHeight, int quality) {
        int[][] bounds = paneBounds(layout, viewportWidth, viewportHeight);
        for (int i = 0; i < panes.length; i++) {
            int expectedWidth = bounds[i][0];
            int expectedHeight = bounds[i][1];
            int[] expected = BlindSpotCameraView.paneBoundedBufferSize(
                    expectedWidth, expectedHeight, quality);
            int actualWidth = panes[i].texture.cameraBufferWidth();
            int actualHeight = panes[i].texture.cameraBufferHeight();
            if (actualWidth != expected[0] || actualHeight != expected[1]) {
                return new PaneBufferMismatch(
                        panes[i].sourceIndex, viewportWidth, viewportHeight,
                        paneBufferViewportWidth, paneBufferViewportHeight,
                        expectedWidth, expectedHeight, expected[0], expected[1],
                        actualWidth, actualHeight, quality);
            }
        }
        if (centralFrontSourceEnabled && centralFrontPane != null) {
            int expectedWidth = bounds[0][0];
            int expectedHeight = bounds[0][1];
            int[] expected = BlindSpotCameraView.paneBoundedBufferSize(
                    expectedWidth, expectedHeight, quality);
            int actualWidth = centralFrontPane.texture.cameraBufferWidth();
            int actualHeight = centralFrontPane.texture.cameraBufferHeight();
            if (actualWidth != expected[0] || actualHeight != expected[1]) {
                return new PaneBufferMismatch(
                        centralFrontPane.sourceIndex, viewportWidth, viewportHeight,
                        paneBufferViewportWidth, paneBufferViewportHeight,
                        expectedWidth, expectedHeight, expected[0], expected[1],
                        actualWidth, actualHeight, quality);
            }
        }
        return null;
    }

    static final class PaneBufferMismatch {
        final int sourceIndex;
        final int viewportWidth;
        final int viewportHeight;
        final int retainedViewportWidth;
        final int retainedViewportHeight;
        final int paneWidth;
        final int paneHeight;
        final int expectedBufferWidth;
        final int expectedBufferHeight;
        final int actualBufferWidth;
        final int actualBufferHeight;
        final int quality;

        PaneBufferMismatch(
                int sourceIndex, int viewportWidth, int viewportHeight,
                int retainedViewportWidth, int retainedViewportHeight,
                int paneWidth, int paneHeight,
                int expectedBufferWidth, int expectedBufferHeight,
                int actualBufferWidth, int actualBufferHeight, int quality) {
            this.sourceIndex = sourceIndex;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.retainedViewportWidth = retainedViewportWidth;
            this.retainedViewportHeight = retainedViewportHeight;
            this.paneWidth = paneWidth;
            this.paneHeight = paneHeight;
            this.expectedBufferWidth = expectedBufferWidth;
            this.expectedBufferHeight = expectedBufferHeight;
            this.actualBufferWidth = actualBufferWidth;
            this.actualBufferHeight = actualBufferHeight;
            this.quality = quality;
        }
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

    boolean centralFrontSurfaceRecoveryPending() {
        return centralFrontSurfaceRecoveryPending;
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
        resetPaneFreshness();
        setAllCovers(View.VISIBLE);
        // The optional central Front source must not delay the ordinary Rear
        // composition.  Its first frame is tracked independently and only
        // enables the central pane once it arrives.
        frameBarrier.arm(requestId, expectedGenerations[0], directGenerations,
                centralFrontSourceEnabled ? panes.length : directCount, false);
        applyEffectiveVisibility();
    }

    /** Keeps the stock background covered for this preview request after a scoped failure. */
    void markPreviewBackgroundUnavailable(int requestId) {
        if (!frameBarrier.markBaseUnavailable(requestId)) return;
        if (previewBaseCover != null) previewBaseCover.setVisibility(View.VISIBLE);
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
        resetPaneFreshness();
        setAllCovers(View.VISIBLE);
        frameBarrier.arm(requestId, baseGeneration, expectedGenerations,
                centralFrontSourceEnabled ? panes.length : directCount);
        applyEffectiveVisibility();
    }

    void clearFrames() {
        frameBarrier.clear();
        resetPaneFreshness();
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
                pane.targetActive = false;
                pane.frameFresh = false;
                pane.discardNextFrame = true;
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
                pane.targetActive = false;
                pane.frameFresh = false;
                pane.discardNextFrame = true;
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
                boolean generationMatches = inputGeneration == pane.generation;
                boolean fresh = generationMatches && !pane.discardNextFrame;
                if (generationMatches) pane.discardNextFrame = false;
                if (sourceIndex == 4) {
                    if (generationMatches) {
                        if (centralFrontDiscardNextFrame) {
                            centralFrontDiscardNextFrame = false;
                        } else {
                            centralFrontFrameReady = true;
                            pane.frameFresh = fresh;
                            pane.cover.setVisibility(View.GONE);
                            applyEffectiveVisibility();
                        }
                    }
                } else if (fresh) {
                    pane.frameFresh = true;
                    pane.cover.setVisibility(View.GONE);
                    applyEffectiveVisibility();
                }
                acceptFrame(sourceIndex,
                        previewBase == null ? pane.generation : inputGeneration);
            }

            @Override
            public void onDewarpFallbackChanged(BlindSpotCameraView view) {
                applyModel();
                if (callback != null) {
                    callback.onReverseDewarpFallbackChanged(
                            cameraIndex, pane.frontCalibration, view.usesRawFallback());
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

    private FrameBarrier.FrameResult acceptFrame(int source, int generation) {
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
        if (source == FrameBarrier.SOURCE_BASE
                && (result == FrameBarrier.FrameResult.ACCEPTED
                || result == FrameBarrier.FrameResult.READY)
                && previewBaseCover != null) {
            previewBaseCover.setVisibility(View.GONE);
        }
        return result;
    }

    private void maybeReportFrames() {
        if (!primaryRendererBoundsReady()) return;
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
        setDirectCovers(View.GONE);
        applyEffectiveVisibility();
    }

    /**
     * The three primary panes are required by FrameBarrier before reveal.  Check their
     * laid-out renderer bounds, not camera buffer dimensions; source 4 is an optional
     * central-front identity and may intentionally remain hidden at 1x1.
     */
    private boolean primaryRendererBoundsReady() {
        if (getWidth() <= 1 || getHeight() <= 1) return false;
        for (PaneView pane : panes) {
            if (!laidOutBoundsMatch(pane) || !laidOutBoundsMatch(pane.texture)) return false;
        }
        return true;
    }

    private static boolean laidOutBoundsMatch(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        return params != null && params.width > 0 && params.height > 0
                && params.width == view.getWidth() && params.height == view.getHeight();
    }

    private void setAllCovers(int visibility) {
        setDirectCovers(visibility);
        if (previewBaseCover != null) previewBaseCover.setVisibility(visibility);
    }

    private void setDirectCovers(int visibility) {
        for (PaneView pane : panes) pane.cover.setVisibility(visibility);
        if (centralFrontPane != null) centralFrontPane.cover.setVisibility(visibility);
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
        private boolean requireBase;
        private boolean baseUnavailable;
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
            arm(nextRequestId, baseGeneration, directGenerations,
                    nextRequiredDirectCount, true);
        }

        void arm(
                int nextRequestId, int baseGeneration, int[] directGenerations,
                int nextRequiredDirectCount, boolean nextRequireBase) {
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
            requireBase = nextRequireBase && baseGeneration != 0;
            baseUnavailable = false;
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
            requireBase = true;
            baseUnavailable = false;
        }

        int requestId() {
            return requestId;
        }

        int expectedGeneration(int source) {
            return validSource(source) ? generations[source] : 0;
        }

        boolean markBaseUnavailable(int expectedRequestId) {
            if (expectedRequestId <= 0 || expectedRequestId != requestId || requireBase) {
                return false;
            }
            baseUnavailable = true;
            fresh[SOURCE_BASE] = generations[SOURCE_BASE] == 0;
            discardNext[SOURCE_BASE] = generations[SOURCE_BASE] != 0;
            return true;
        }

        FrameResult frame(int frameRequestId, int source, int generation) {
            if (requestId <= 0 || !validSource(source)) return FrameResult.IGNORED;
            if (frameRequestId != requestId || generations[source] <= 0
                    || generation != generations[source]) {
                if (staleDiagnosticReported) return FrameResult.IGNORED;
                staleDiagnosticReported = true;
                return FrameResult.BLOCKED_STALE;
            }
            if (source == SOURCE_BASE && baseUnavailable) return FrameResult.IGNORED;
            if (readyPending && (requireBase || source != SOURCE_BASE || fresh[SOURCE_BASE])) {
                return FrameResult.IGNORED;
            }
            if (discardNext[source]) {
                discardNext[source] = false;
                if (guardDiagnosticReported) return FrameResult.IGNORED;
                guardDiagnosticReported = true;
                return FrameResult.BLOCKED_GUARD;
            }
            fresh[source] = true;
            if (readyPending) return FrameResult.ACCEPTED;
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
            if (requireBase && !fresh[SOURCE_BASE]) return false;
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
        applyModel(getWidth(), getHeight());
    }

    private void applyModel(int width, int height) {
        applyEffectiveVisibility();
        for (PaneView pane : panes) {
            boolean frontSource = effectiveSourceIsFront(pane.sourceIndex, sideMode,
                    frontLeftIntegrated, frontRightIntegrated);
            ReverseCameraLayout centerRawFallback = frontSource
                    ? frontRawFallbackModel : rawFallbackModel;
            ReverseCameraLayout.Rect rawCrop =
                    centerRawFallback.pane(pane.cameraIndex).sourceCrop;
            pane.texture.applyDewarpSourceRoi(
                    rawCrop.left, rawCrop.top, rawCrop.width, rawCrop.height);
        }
        if (width <= 0 || height <= 0) return;
        ReverseCameraLayout.PixelRect backgroundRect =
                ReverseCameraLayout.project(model.background, width, height);
        applyFrameBounds(backgroundPane, backgroundRect);
        backgroundPane.setZ(0.0f);
        ReverseCameraLayout.PixelRect widgetRect =
                ReverseCameraLayout.project(model.widget, width, height);
        applyFrameBounds(sideSelector, widgetRect);
        sideSelector.setZ(5.0f);
        for (PaneView pane : panes) {
            boolean frontSource = effectiveSourceIsFront(pane.sourceIndex, sideMode,
                    frontLeftIntegrated, frontRightIntegrated);
            ReverseCameraLayout centerFallback = frontSource ? frontModel : model;
            ReverseCameraLayout centerRawFallback = frontSource
                    ? frontRawFallbackModel : rawFallbackModel;
            ReverseCameraLayout.Pane value = centerFallback.pane(pane.cameraIndex);
            ReverseCameraLayout.Rect rawCrop =
                    centerRawFallback.pane(pane.cameraIndex).sourceCrop;
            ReverseCameraLayout.Rect sourceCrop = pane.dewarpConfig.enabled
                    && !pane.texture.usesRawFallback()
                    ? value.sourceCrop
                    : rawCrop;
            ReverseCameraLayout.PixelRect baseRect =
                    ReverseCameraLayout.project(value.destination, width, height);
            applyFrameBounds(pane, baseRect);
            pane.setZ(1.0f + value.zOrder);
            pane.applyTransform(sourceCrop, value.rotationDegrees, value.displayMode,
                    value.mirrorHorizontally, baseRect.width, baseRect.height);
        }
        if (centralFrontPane != null) {
            ReverseCameraLayout.Pane centerValue = frontModel.pane(
                    ReverseCameraLayout.REAR_CAMERA_INDEX);
            ReverseCameraLayout.Rect centerRawCrop = frontRawFallbackModel.pane(
                    ReverseCameraLayout.REAR_CAMERA_INDEX).sourceCrop;
            centralFrontPane.texture.applyDewarpSourceRoi(
                    centerRawCrop.left, centerRawCrop.top,
                    centerRawCrop.width, centerRawCrop.height);
            ReverseCameraLayout.Rect centerSourceCrop = centralFrontPane.dewarpConfig.enabled
                    && !centralFrontPane.texture.usesRawFallback()
                    ? centerValue.sourceCrop : centerRawCrop;
            ReverseCameraLayout.PixelRect centerRect =
                    ReverseCameraLayout.project(centerValue.destination, width, height);
            applyFrameBounds(centralFrontPane, centerRect);
            centralFrontPane.setZ(1.1f + centerValue.zOrder);
            centralFrontPane.applyTransform(centerSourceCrop, centerValue.rotationDegrees,
                    centerValue.displayMode, centerValue.mirrorHorizontally,
                    centerRect.width, centerRect.height);
        }
    }

    /** Applies projected bounds only when they differ, avoiding retained layout churn. */
    private static boolean applyFrameBounds(View view, ReverseCameraLayout.PixelRect rect) {
        if (view == null || rect == null) return false;
        int width = Math.max(1, rect.width);
        int height = Math.max(1, rect.height);
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) view.getLayoutParams();
        if (params != null && params.width == width && params.height == height
                && params.leftMargin == rect.left && params.topMargin == rect.top) return false;
        if (params == null) params = new FrameLayout.LayoutParams(width, height);
        params.width = width;
        params.height = height;
        params.leftMargin = rect.left;
        params.topMargin = rect.top;
        view.setLayoutParams(params);
        return true;
    }

    private void applyActiveDewarpConfigs() {
        applyPaneDewarpConfig(panes[0], rearDewarp);
        applyPaneDewarpConfig(panes[1], sideMode == ReverseSideSelectorView.MODE_FRONT
                && frontLeftIntegrated
                ? frontLeftDewarp : leftDewarp);
        applyPaneDewarpConfig(panes[2], sideMode == ReverseSideSelectorView.MODE_FRONT
                && frontRightIntegrated
                ? frontRightDewarp : rightDewarp);
        if (centralFrontPane != null) {
            applyPaneDewarpConfig(centralFrontPane, centralFrontDewarp);
        }
    }

    static boolean fallbackSourceIsFront(int sourceIndex, int sideMode) {
        return sourceIndex == 4 || (sideMode == ReverseSideSelectorView.MODE_FRONT
                && (sourceIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX
                || sourceIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
    }

    static boolean effectiveSourceIsFront(int sourceIndex, int sideMode,
            boolean frontLeftIntegrated, boolean frontRightIntegrated) {
        if (!fallbackSourceIsFront(sourceIndex, sideMode)) return false;
        if (sourceIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) {
            return frontLeftIntegrated;
        }
        if (sourceIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) {
            return frontRightIntegrated;
        }
        return true;
    }

    private void applyPaneDewarpConfig(PaneView pane, CameraDewarpConfig value) {
        boolean previousFront = pane.frontCalibration;
        pane.frontCalibration = effectiveSourceIsFront(pane.sourceIndex, sideMode,
                frontLeftIntegrated, frontRightIntegrated);
        boolean sourceChanged = previousFront != pane.frontCalibration;
        if (sourceChanged && callback != null) {
            callback.onReverseDewarpFallbackChanged(pane.cameraIndex, previousFront, false);
        }
        // Capture the role before applying config; renderer request-token checks reject old events.
        pane.applyDewarpConfig(value);
        if (sourceChanged && callback != null) {
            callback.onReverseDewarpFallbackChanged(
                    pane.cameraIndex, pane.frontCalibration, pane.texture.usesRawFallback());
        }
    }

    private void applyEffectiveVisibility() {
        backgroundPane.setAlpha(alphaForVisibility(
                visibilityMask, ReverseCameraLayout.BACKGROUND_PANE_ID));
        boolean centerVisible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_CAMERA_INDEX);
        boolean centralFrontEligible = centerVisible
                && sideMode == ReverseSideSelectorView.MODE_FRONT
                && centralFrontIntegrated && centralFrontSourceEnabled
                && centralFrontPane != null;
        boolean centralFrontVisible = centralFrontEligible
                && centralFrontFrameReady && centralFrontPane.frameFresh;
        boolean front = sideMode == ReverseSideSelectorView.MODE_FRONT;
        boolean leftEligible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        boolean rightEligible = ReverseCameraLayout.isVisible(
                visibilityMask, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX);
        boolean preReveal = frameBarrier.requestId() > 0 && !frameBarrier.revealed();
        setPaneTargetActive(panes[0], preReveal || centerVisible && !centralFrontVisible);
        setPaneTargetActive(panes[1], preReveal || leftEligible);
        setPaneTargetActive(panes[2], preReveal || rightEligible);
        if (centralFrontPane != null) {
            setPaneTargetActive(centralFrontPane, centralFrontEligible);
        }
        panes[0].setAlpha(centerVisible && !centralFrontVisible && panes[0].frameFresh
                ? 1.0f : 0.0f);
        if (centralFrontPane != null) {
            centralFrontPane.setAlpha(centralFrontVisible ? 1.0f : 0.0f);
        }
        panes[1].setAlpha(leftEligible && panes[1].frameFresh ? 1.0f : 0.0f);
        panes[2].setAlpha(rightEligible && panes[2].frameFresh ? 1.0f : 0.0f);
        sideSelector.setEffectiveVisibility(
                leftEligible, rightEligible, front ? centralFrontVisible : centerVisible);
        sideSelector.setVisibility(widgetVisible && widgetAvailable
                ? View.VISIBLE : View.GONE);
    }

    private void setPaneTargetActive(PaneView pane, boolean active) {
        if (pane == null) return;
        if (pane.targetActive == active) return;
        pane.targetActive = active;
        pane.frameFresh = false;
        pane.discardNextFrame = true;
        pane.cover.setVisibility(View.VISIBLE);
        if (pane.surface != null && pane.surface.isValid() && callback != null) {
            callback.onReverseTargetActive(pane.sourceIndex, pane.generation, active);
        }
    }

    private void resetPaneFreshness() {
        for (PaneView pane : panes) {
            pane.frameFresh = false;
            pane.discardNextFrame = true;
            pane.cover.setVisibility(View.VISIBLE);
        }
        if (centralFrontPane != null) {
            centralFrontPane.frameFresh = false;
            centralFrontPane.discardNextFrame = true;
            centralFrontPane.cover.setVisibility(View.VISIBLE);
        }
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
        boolean frontCalibration;
        Surface surface;
        int generation;
        boolean targetActive;
        boolean frameFresh;
        boolean discardNextFrame = true;
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(0, 0, 1, 1);
        int rotationDegrees;
        int displayMode = ReverseCameraLayout.DEFAULT_DISPLAY_MODE;
        boolean mirrorHorizontally = true;

        PaneView(Context context, int cameraIndex, int sourceIndex, int dewarpLens) {
            super(context);
            this.cameraIndex = cameraIndex;
            this.sourceIndex = sourceIndex;
            frontCalibration = fallbackSourceIsFront(sourceIndex, ReverseSideSelectorView.MODE_REAR);
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
            applyTransform(value, degrees, nextDisplayMode, mirror, getWidth(), getHeight());
        }

        void applyTransform(
                ReverseCameraLayout.Rect value, int degrees, int nextDisplayMode,
                boolean mirror, int targetWidth, int targetHeight) {
            crop = value;
            rotationDegrees = CameraRotation.clamp(degrees);
            displayMode = ReverseCameraLayout.normalizeDisplayMode(nextDisplayMode);
            mirrorHorizontally = mirror;
            int width = targetWidth;
            int height = targetHeight;
            if (width <= 0 || height <= 0) return;

            int safeMode = ReverseCameraLayout.normalizeDisplayMode(nextDisplayMode);
            boolean fit = safeMode == ReverseCameraLayout.DISPLAY_MODE_FIT;
            ReverseCameraLayout.PixelRect fitted = fit
                    ? ReverseCameraLayout.fitSourceCrop(
                            value, width, height, SOURCE_WIDTH, SOURCE_HEIGHT, rotationDegrees)
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
            int cameraMode = safeMode == ReverseCameraLayout.DISPLAY_MODE_FILL
                    ? CameraRotation.MODE_FILL
                    : safeMode == ReverseCameraLayout.DISPLAY_MODE_STRETCH
                    ? CameraRotation.MODE_ALIGNED : CameraRotation.MODE_FIT;
            RectF destination = new RectF(0, 0, fitted.width, fitted.height);
            CameraRotation.setSourceCropTransformForInput(
                    transform, value.left, value.top, value.width, value.height,
                    destination, rotationDegrees, cameraMode,
                    SOURCE_WIDTH, SOURCE_HEIGHT, fitted.width, fitted.height,
                    mirrorHorizontally);
            float[] transformed = CameraRotation.transformedCropCornersForInput(
                    value.left, value.top, value.width, value.height,
                    destination, rotationDegrees, cameraMode,
                    SOURCE_WIDTH, SOURCE_HEIGHT, fitted.width, fitted.height,
                    mirrorHorizontally);
            for (int i = 0; i + 1 < transformed.length; i += 2) {
                transformed[i] += fitted.left;
                transformed[i + 1] += fitted.top;
            }
            cropMask.setCrop(transformed);
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

}
