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
    private final PaneView[] panes = new PaneView[3];
    private TextureView previewBase;
    private View previewBaseCover;
    private Surface previewBaseSurface;
    private final BlindSpotCameraView.InputGeneration previewBaseInputGeneration =
            new BlindSpotCameraView.InputGeneration();
    private int previewBaseGeneration;
    private Callback callback;
    private ReverseCameraLayout model = ReverseCameraLayout.defaults();
    private ReverseCameraLayout rawFallbackModel = ReverseCameraLayout.defaults();
    private int cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP;
    private final FrameBarrier frameBarrier = new FrameBarrier();
    private int paneBufferViewportWidth;
    private int paneBufferViewportHeight;

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
                createPreviewBaseInput(texture);
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

    void setDewarpStatsContext(int requestId, int[] generations) {
        if (generations == null || generations.length != panes.length) return;
        for (int i = 0; i < panes.length; i++) {
            panes[i].texture.setDewarpStatsContext(requestId, generations[i]);
        }
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

    void setPaneBoundedBuffers(int viewportWidth, int viewportHeight, int quality) {
        int[][] bounds = paneBounds(model, viewportWidth, viewportHeight);
        for (int i = 0; i < panes.length; i++) {
            panes[i].texture.setPaneBoundedBuffer(
                    bounds[i][0], bounds[i][1], quality);
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

    void retirePreviewInputs() {
        clearFrames();
        if (previewBaseSurface != null) previewBaseSurface.release();
        previewBaseSurface = null;
        for (PaneView pane : panes) {
            pane.texture.retireCameraInput();
            pane.surface = null;
        }
    }

    void ensurePreviewInputs() {
        if (previewBase != null && previewBaseSurface == null
                && previewBase.isAvailable() && previewBase.getSurfaceTexture() != null) {
            createPreviewBaseInput(previewBase.getSurfaceTexture());
        }
        for (PaneView pane : panes) pane.texture.ensureCameraInput();
        notifySurfacesReady();
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
        int[] generations = new int[direct.generations.length + 1];
        surfaces[0] = previewBaseSurface;
        System.arraycopy(direct.surfaces, 0, surfaces, 1, direct.surfaces.length);
        generations[0] = previewBaseGeneration;
        System.arraycopy(direct.generations, 0, generations, 1,
                direct.generations.length);
        return new SurfaceBundle(requestId, generations, surfaces);
    }

    void armPreviewFrames(int requestId, int[] expectedGenerations) {
        if (requestId <= 0 || expectedGenerations == null
                || expectedGenerations.length != panes.length + 1
                || expectedGenerations[0] != previewBaseGeneration
                || previewBaseSurface == null || !previewBaseSurface.isValid()) {
            throw new IllegalStateException("stale reverse preview input");
        }
        int[] directGenerations = new int[panes.length];
        for (int i = 0; i < panes.length; i++) {
            directGenerations[i] = expectedGenerations[i + 1];
            if (directGenerations[i] != panes[i].generation || panes[i].surface == null) {
                throw new IllegalStateException("stale reverse Surface");
            }
        }
        setAllCovers(View.VISIBLE);
        frameBarrier.arm(requestId, expectedGenerations[0], directGenerations);
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
        }
        int baseGeneration = 0;
        if (previewBase != null) {
            if (previewBaseSurface == null || !previewBaseSurface.isValid()) {
                throw new IllegalStateException("stale reverse preview base Surface");
            }
            baseGeneration = previewBaseGeneration;
        }
        setAllCovers(View.VISIBLE);
        frameBarrier.arm(requestId, baseGeneration, expectedGenerations);
    }

    void clearFrames() {
        frameBarrier.clear();
        setAllCovers(View.VISIBLE);
    }

    boolean framesReady(int requestId, int[] expectedGenerations) {
        return frameBarrier.readyEventRecorded(
                requestId, previewBase == null ? 0 : previewBaseGeneration,
                expectedGenerations);
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
                onCameraSurfaceAvailable(view, surface, width, height,
                        view.cameraInputGeneration());
            }

            @Override
            public void onCameraSurfaceAvailable(
                    BlindSpotCameraView view, Surface surface, int width, int height,
                    int inputGeneration) {
                pane.surface = surface;
                pane.generation = inputGeneration;
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
                clearFrames();
                if (callback != null) {
                    callback.onReverseSurfaceLost(cameraIndex, lostGeneration);
                }
            }

            @Override
            public void onCameraFrameUpdated(BlindSpotCameraView view) {
                onCameraFrameUpdated(view, view.cameraInputGeneration());
            }

            @Override
            public void onCameraFrameUpdated(
                    BlindSpotCameraView view, int inputGeneration) {
                acceptFrame(cameraIndex,
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
        int[] values = new int[panes.length];
        for (int i = 0; i < panes.length; i++) values[i] = panes[i].generation;
        return values;
    }

    private void createPreviewBaseInput(SurfaceTexture texture) {
        texture.setDefaultBufferSize(1920, 990);
        if (previewBaseSurface != null) previewBaseSurface.release();
        previewBaseSurface = new Surface(texture);
        previewBaseGeneration = previewBaseInputGeneration.next();
        clearFrames();
        notifySurfacesReady();
    }

    static final class FrameBarrier {
        static final int SOURCE_BASE = 0;
        private static final int SOURCE_COUNT = 4;

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

        void arm(int nextRequestId, int baseGeneration, int[] directGenerations) {
            if (nextRequestId <= 0 || baseGeneration < 0 || directGenerations == null
                    || directGenerations.length != SOURCE_COUNT - 1) {
                throw new IllegalArgumentException("invalid reverse frame identity");
            }
            requestId = nextRequestId;
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
            for (boolean value : fresh) if (!value) return false;
            return true;
        }

        private boolean matches(
                int expectedRequestId, int baseGeneration, int[] directGenerations) {
            if (expectedRequestId != requestId || baseGeneration != generations[SOURCE_BASE]
                    || directGenerations == null
                    || directGenerations.length != SOURCE_COUNT - 1) return false;
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
