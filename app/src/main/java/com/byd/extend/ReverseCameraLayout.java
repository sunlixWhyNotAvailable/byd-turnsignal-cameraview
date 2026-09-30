package com.byd.extend;

final class ReverseCameraLayout {
    static final int WIDGET_PANE_ID = -2;
    static final int BACKGROUND_PANE_ID = -1;
    static final int VISIBILITY_BACKGROUND = 1;
    static final int VISIBILITY_REAR = 1 << 1;
    static final int VISIBILITY_REAR_LEFT = 1 << 2;
    static final int VISIBILITY_REAR_RIGHT = 1 << 3;
    static final int VISIBILITY_ALL = VISIBILITY_BACKGROUND | VISIBILITY_REAR
            | VISIBILITY_REAR_LEFT | VISIBILITY_REAR_RIGHT;
    static final String REAR = "rear";
    static final String REAR_LEFT = "rear-left";
    static final String REAR_RIGHT = "rear-right";

    static final int REAR_CAMERA_INDEX = 1;
    static final int REAR_LEFT_CAMERA_INDEX = 2;
    static final int REAR_RIGHT_CAMERA_INDEX = 3;
    static final float MIN_DESTINATION_SIZE = 0.08f;
    static final float MIN_WIDGET_WIDTH = 0.05f;
    static final float MIN_WIDGET_HEIGHT = 0.16f;
    static final int DISPLAY_MODE_FIT = 0;
    static final int DISPLAY_MODE_FILL = 1;
    static final int DISPLAY_MODE_STRETCH = 2;
    static final int DEFAULT_DISPLAY_MODE = DISPLAY_MODE_FIT;

    private static final int BACK_Z = 0;
    private static final int FRONT_Z = 2;
    private static final int ELEMENT_COUNT = 5;
    private static final int TARGET_TABLET = CameraDisplayTarget.TABLET;
    private static final int TARGET_CLUSTER = CameraDisplayTarget.CLUSTER;

    final Rect background;
    final Rect widget;
    final Pane rear;
    final Pane rearLeft;
    final Pane rearRight;
    private final Rect[] clusterRects;
    private final int[] displayTargets;
    private final int[] clusterZOrders;

    private ReverseCameraLayout(
            Rect background, Rect widget, Pane rear, Pane rearLeft, Pane rearRight) {
        this(background, widget, rear, rearLeft, rearRight,
                defaultClusterRects(background, widget, rear, rearLeft, rearRight),
                new int[]{TARGET_TABLET, TARGET_TABLET, TARGET_TABLET,
                        TARGET_TABLET, TARGET_TABLET},
                new int[]{rear.zOrder, rearLeft.zOrder, rearRight.zOrder});
    }

    private ReverseCameraLayout(
            Rect background, Rect widget, Pane rear, Pane rearLeft, Pane rearRight,
            Rect[] clusterRects, int[] displayTargets, int[] clusterZOrders) {
        if (background == null || widget == null) {
            throw new IllegalArgumentException("reverse overlay geometry is required");
        }
        if (rear.zOrder == rearLeft.zOrder || rear.zOrder == rearRight.zOrder
                || rearLeft.zOrder == rearRight.zOrder) {
            throw new IllegalArgumentException("pane z-orders must be unique");
        }
        if (clusterRects == null || clusterRects.length != ELEMENT_COUNT
                || displayTargets == null || displayTargets.length != ELEMENT_COUNT
                || clusterZOrders == null || clusterZOrders.length != 3) {
            throw new IllegalArgumentException("reverse display geometry is required");
        }
        for (int i = 0; i < ELEMENT_COUNT; i++) {
            if (clusterRects[i] == null || !CameraDisplayTarget.isValid(displayTargets[i])) {
                throw new IllegalArgumentException("invalid reverse display element");
            }
        }
        boolean[] clusterZSeen = new boolean[3];
        for (int zOrder : clusterZOrders) {
            if (zOrder < BACK_Z || zOrder > FRONT_Z || clusterZSeen[zOrder]) {
                throw new IllegalArgumentException("cluster pane z-orders must be unique");
            }
            clusterZSeen[zOrder] = true;
        }
        this.background = background;
        this.widget = widget;
        this.rear = rear;
        this.rearLeft = rearLeft;
        this.rearRight = rearRight;
        this.clusterRects = clusterRects.clone();
        this.displayTargets = displayTargets.clone();
        this.clusterZOrders = clusterZOrders.clone();
    }

    private static Rect[] defaultClusterRects(
            Rect background, Rect widget, Pane rear, Pane rearLeft, Pane rearRight) {
        return new Rect[]{centered(background),
                widgetDestination((1f - widget.width) / 2f, (1f - widget.height) / 2f,
                        widget.width, widget.height), centered(rear.destination),
                centered(rearLeft.destination), centered(rearRight.destination)};
    }

    private static Rect centered(Rect rect) {
        return destination((1.0f - rect.width) / 2.0f, (1.0f - rect.height) / 2.0f,
                rect.width, rect.height);
    }

    static ReverseCameraLayout defaults() {
        return new ReverseCameraLayout(
                CameraDefaults.reverseBackground(), CameraDefaults.reverseWidget(),
                new Pane(REAR, REAR_CAMERA_INDEX,
                        destination(0.43216026f, 0.0015433729f,
                                0.564868f, 0.7758869f),
                        sourceCrop(0.0f, 0.0f, 1.0f, 0.85f), 0, 0,
                        DISPLAY_MODE_FILL),
                new Pane(REAR_LEFT, REAR_LEFT_CAMERA_INDEX,
                        destination(0.4349628f, 0.7960598f,
                                0.25351316f, 0.20394021f),
                        sourceCrop(2.9802322e-8f, 0.25f, 0.384127f, 0.55f), 1, 35,
                        DISPLAY_MODE_FILL),
                new Pane(REAR_RIGHT, REAR_RIGHT_CAMERA_INDEX,
                        destination(0.74648684f, 0.7960598f,
                                0.25351316f, 0.20394021f),
                        sourceCrop(0.615873f, 0.25f, 0.384127f, 0.55f), 2, -35,
                        DISPLAY_MODE_FILL));
    }

    static boolean mirrorHorizontally(int cameraIndex) {
        return cameraIndex == REAR_CAMERA_INDEX
                || cameraIndex == REAR_LEFT_CAMERA_INDEX
                || cameraIndex == REAR_RIGHT_CAMERA_INDEX;
    }

    static boolean isValidVisibilityMask(int visibilityMask) {
        return (visibilityMask & ~VISIBILITY_ALL) == 0;
    }

    static int requireVisibilityMask(int visibilityMask) {
        if (!isValidVisibilityMask(visibilityMask)) {
            throw new IllegalArgumentException("invalid reverse visibility mask");
        }
        return visibilityMask;
    }

    static int visibilityBitForPane(int paneId) {
        switch (paneId) {
            case BACKGROUND_PANE_ID:
                return VISIBILITY_BACKGROUND;
            case REAR_CAMERA_INDEX:
                return VISIBILITY_REAR;
            case REAR_LEFT_CAMERA_INDEX:
                return VISIBILITY_REAR_LEFT;
            case REAR_RIGHT_CAMERA_INDEX:
                return VISIBILITY_REAR_RIGHT;
            default:
                throw new IllegalArgumentException("unsupported reverse pane id: " + paneId);
        }
    }

    static boolean isVisible(int visibilityMask, int paneId) {
        return (requireVisibilityMask(visibilityMask) & visibilityBitForPane(paneId)) != 0;
    }

    static Rect destination(float left, float top, float width, float height) {
        return boundedDestination(left, top, width, height,
                MIN_DESTINATION_SIZE, MIN_DESTINATION_SIZE);
    }

    static Rect widgetDestination(float left, float top, float width, float height) {
        return boundedDestination(left, top, width, height,
                MIN_WIDGET_WIDTH, MIN_WIDGET_HEIGHT);
    }

    private static Rect boundedDestination(
            float left, float top, float width, float height,
            float minimumWidth, float minimumHeight) {
        requireFinite(left, top, width, height);
        float safeWidth = clamp(width, minimumWidth, 1.0f);
        float safeHeight = clamp(height, minimumHeight, 1.0f);
        return new Rect(
                clamp(left, 0.0f, 1.0f - safeWidth),
                clamp(top, 0.0f, 1.0f - safeHeight),
                safeWidth,
                safeHeight);
    }

    static Rect sourceCrop(float left, float top, float width, float height) {
        requireFinite(left, top, width, height);
        SourceCropPolicy.requireMinimumSize(width, height);
        float safeWidth = Math.min(width, 1.0f);
        float safeHeight = Math.min(height, 1.0f);
        return new Rect(
                clamp(left, 0.0f, 1.0f - safeWidth),
                clamp(top, 0.0f, 1.0f - safeHeight),
                safeWidth,
                safeHeight);
    }

    static Rect centeredSourceCrop(Rect crop) {
        if (crop == null) throw new IllegalArgumentException("source crop is required");
        return sourceCrop(
                (1.0f - crop.width) / 2.0f,
                (1.0f - crop.height) / 2.0f,
                crop.width,
                crop.height);
    }

    int targetFor(int paneId) {
        return displayTargets[elementIndex(paneId)];
    }

    boolean containsTarget(int target) {
        for (int selected : displayTargets) if (selected == target) return true;
        return false;
    }

    Rect rectFor(int paneId, int target) {
        if (!CameraDisplayTarget.isValid(target)) {
            throw new IllegalArgumentException("invalid reverse display target");
        }
        if (target == TARGET_TABLET) {
            if (paneId == BACKGROUND_PANE_ID) return background;
            if (paneId == WIDGET_PANE_ID) return widget;
            return pane(paneId).destination;
        }
        return clusterRects[elementIndex(paneId)];
    }

    int zOrderFor(int cameraIndex, int target) {
        if (!CameraDisplayTarget.isValid(target)) {
            throw new IllegalArgumentException("invalid reverse display target");
        }
        if (cameraIndex < REAR_CAMERA_INDEX || cameraIndex > REAR_RIGHT_CAMERA_INDEX) {
            throw new IllegalArgumentException("unsupported reverse camera index: "
                    + cameraIndex);
        }
        return target == TARGET_CLUSTER
                ? clusterZOrders[cameraIndex - REAR_CAMERA_INDEX]
                : pane(cameraIndex).zOrder;
    }

    static ReverseCameraLayout withClusterZOrders(
            ReverseCameraLayout layout, int[] zOrders) {
        if (layout == null || zOrders == null || zOrders.length != 3) {
            throw new IllegalArgumentException("three Cluster z-orders are required");
        }
        boolean[] seen = new boolean[3];
        for (int zOrder : zOrders) {
            if (zOrder < BACK_Z || zOrder > FRONT_Z || seen[zOrder]) {
                throw new IllegalArgumentException("Cluster z-orders must be unique");
            }
            seen[zOrder] = true;
        }
        return copy(layout, layout.background, layout.widget, layout.rear,
                layout.rearLeft, layout.rearRight, layout.clusterRects,
                layout.displayTargets, zOrders);
    }

    static ReverseCameraLayout withTarget(
            ReverseCameraLayout layout, int paneId, int target) {
        if (layout == null || !CameraDisplayTarget.isValid(target)) {
            throw new IllegalArgumentException("reverse display target is required");
        }
        int index = elementIndex(paneId);
        if (layout.displayTargets[index] == target) return layout;
        int[] targets = layout.displayTargets.clone();
        targets[index] = target;
        return copy(layout, layout.background, layout.widget, layout.rear,
                layout.rearLeft, layout.rearRight, layout.clusterRects, targets,
                layout.clusterZOrders);
    }

    static ReverseCameraLayout withRect(
            ReverseCameraLayout layout, int paneId, int target, Rect rect) {
        if (layout == null || rect == null || !CameraDisplayTarget.isValid(target)) {
            throw new IllegalArgumentException("reverse display geometry is required");
        }
        Rect safe = paneId == WIDGET_PANE_ID
                ? widgetDestination(rect.left, rect.top, rect.width, rect.height)
                : destination(rect.left, rect.top, rect.width, rect.height);
        if (target == TARGET_TABLET) {
            if (paneId == BACKGROUND_PANE_ID) return withBackground(layout, safe);
            if (paneId == WIDGET_PANE_ID) return withWidget(layout, safe);
            Pane pane = layout.pane(paneId);
            return withPane(layout, paneId, safe, pane.sourceCrop);
        }
        int index = elementIndex(paneId);
        Rect[] cluster = layout.clusterRects.clone();
        cluster[index] = safe;
        return copy(layout, layout.background, layout.widget, layout.rear,
                layout.rearLeft, layout.rearRight, cluster, layout.displayTargets,
                layout.clusterZOrders);
    }

    private static int elementIndex(int paneId) {
        if (paneId == BACKGROUND_PANE_ID) return 0;
        if (paneId == WIDGET_PANE_ID) return 1;
        if (paneId >= REAR_CAMERA_INDEX && paneId <= REAR_RIGHT_CAMERA_INDEX) {
            return paneId + 1;
        }
        throw new IllegalArgumentException("unsupported reverse pane id: " + paneId);
    }

    private static ReverseCameraLayout copy(
            ReverseCameraLayout source, Rect background, Rect widget,
            Pane rear, Pane rearLeft, Pane rearRight,
            Rect[] clusterRects, int[] targets, int[] clusterZOrders) {
        return new ReverseCameraLayout(background, widget, rear, rearLeft, rearRight,
                clusterRects, targets, clusterZOrders);
    }

    static ReverseCameraLayout withPane(
            ReverseCameraLayout layout, int cameraIndex, Rect destination, Rect sourceCrop) {
        if (layout == null || destination == null || sourceCrop == null) {
            throw new IllegalArgumentException("layout geometry is required");
        }
        Rect safeDestination = destination(
                destination.left, destination.top, destination.width, destination.height);
        Rect safeSourceCrop = sourceCrop(
                sourceCrop.left, sourceCrop.top, sourceCrop.width, sourceCrop.height);
        return layout.replace(cameraIndex,
                new Pane(name(cameraIndex), cameraIndex, safeDestination, safeSourceCrop,
                        layout.pane(cameraIndex).zOrder,
                        layout.pane(cameraIndex).rotationDegrees,
                        layout.pane(cameraIndex).displayMode,
                        layout.pane(cameraIndex).mirrorHorizontally));
    }

    static ReverseCameraLayout withPane(
            ReverseCameraLayout layout, int cameraIndex, Rect destination, Rect sourceCrop,
            int rotationDegrees) {
        ReverseCameraLayout next = withPane(layout, cameraIndex, destination, sourceCrop);
        return withRotation(next, cameraIndex, rotationDegrees);
    }

    static ReverseCameraLayout withRotation(
            ReverseCameraLayout layout, int cameraIndex, int rotationDegrees) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        Pane pane = layout.pane(cameraIndex);
        int safeDegrees = CameraRotation.clamp(rotationDegrees);
        if (safeDegrees == pane.rotationDegrees) return layout;
        return layout.replace(cameraIndex, new Pane(
                pane.name, pane.cameraIndex, pane.destination, pane.sourceCrop,
                pane.zOrder, safeDegrees, pane.displayMode, pane.mirrorHorizontally));
    }

    static ReverseCameraLayout withDisplayMode(
            ReverseCameraLayout layout, int cameraIndex, int displayMode) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        Pane pane = layout.pane(cameraIndex);
        int safeMode = normalizeDisplayMode(displayMode);
        if (safeMode == pane.displayMode) return layout;
        return layout.replace(cameraIndex, new Pane(
                pane.name, pane.cameraIndex, pane.destination, pane.sourceCrop,
                pane.zOrder, pane.rotationDegrees, safeMode, pane.mirrorHorizontally));
    }

    static ReverseCameraLayout withMirrorHorizontally(
            ReverseCameraLayout layout, int cameraIndex, boolean mirror) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        Pane pane = layout.pane(cameraIndex);
        if (pane.mirrorHorizontally == mirror) return layout;
        return layout.replace(cameraIndex, new Pane(
                pane.name, pane.cameraIndex, pane.destination, pane.sourceCrop,
                pane.zOrder, pane.rotationDegrees, pane.displayMode, mirror));
    }

    static ReverseCameraLayout withBackground(
            ReverseCameraLayout layout, Rect destination) {
        if (layout == null || destination == null) {
            throw new IllegalArgumentException("background geometry is required");
        }
        Rect safe = destination(
                destination.left, destination.top, destination.width, destination.height);
        return new ReverseCameraLayout(
                safe, layout.widget, layout.rear, layout.rearLeft, layout.rearRight,
                layout.clusterRects, layout.displayTargets, layout.clusterZOrders);
    }

    static ReverseCameraLayout withWidget(
            ReverseCameraLayout layout, Rect destination) {
        if (layout == null || destination == null) {
            throw new IllegalArgumentException("widget geometry is required");
        }
        Rect safe = widgetDestination(
                destination.left, destination.top, destination.width, destination.height);
        return new ReverseCameraLayout(
                layout.background, safe, layout.rear, layout.rearLeft, layout.rearRight,
                layout.clusterRects, layout.displayTargets, layout.clusterZOrders);
    }

    static ReverseCameraLayout withSideCalibration(
            ReverseCameraLayout shared, ReverseCameraLayout sideCalibration) {
        if (shared == null || sideCalibration == null) {
            throw new IllegalArgumentException("reverse layouts are required");
        }
        ReverseCameraLayout result = shared;
        for (int cameraIndex = REAR_LEFT_CAMERA_INDEX;
                cameraIndex <= REAR_RIGHT_CAMERA_INDEX; cameraIndex++) {
            Pane calibrated = sideCalibration.pane(cameraIndex);
            Pane target = result.pane(cameraIndex);
            result = withPane(result, cameraIndex, target.destination,
                    calibrated.sourceCrop, calibrated.rotationDegrees);
            result = withDisplayMode(result, cameraIndex, calibrated.displayMode);
            result = withMirrorHorizontally(
                    result, cameraIndex, calibrated.mirrorHorizontally);
        }
        return result;
    }

    static ReverseCameraLayout move(
            ReverseCameraLayout layout, int cameraIndex, float deltaX, float deltaY) {
        return move(layout, cameraIndex, deltaX, deltaY, TARGET_TABLET);
    }

    static ReverseCameraLayout move(
            ReverseCameraLayout layout, int cameraIndex,
            float deltaX, float deltaY, int target) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        Rect current = layout.rectFor(cameraIndex, target);
        Rect moved = cameraIndex == WIDGET_PANE_ID
                ? widgetDestination(current.left + deltaX, current.top + deltaY,
                        current.width, current.height)
                : destination(current.left + deltaX, current.top + deltaY,
                        current.width, current.height);
        return withRect(layout, cameraIndex, target, moved);
    }

    static ReverseCameraLayout bringToFront(
            ReverseCameraLayout layout, int cameraIndex) {
        return reorder(layout, cameraIndex, true);
    }

    static ReverseCameraLayout raise(ReverseCameraLayout layout, int cameraIndex) {
        return moveOne(layout, cameraIndex, 1);
    }

    static ReverseCameraLayout raise(
            ReverseCameraLayout layout, int cameraIndex, int target) {
        return movePeer(layout, cameraIndex, target, 1);
    }

    static ReverseCameraLayout lower(ReverseCameraLayout layout, int cameraIndex) {
        return moveOne(layout, cameraIndex, -1);
    }

    static ReverseCameraLayout lower(
            ReverseCameraLayout layout, int cameraIndex, int target) {
        return movePeer(layout, cameraIndex, target, -1);
    }

    static PixelRect project(Rect normalized, int pixelWidth, int pixelHeight) {
        if (normalized == null || pixelWidth <= 0 || pixelHeight <= 0) {
            throw new IllegalArgumentException("positive projection bounds are required");
        }
        int left = Math.round(normalized.left * pixelWidth);
        int top = Math.round(normalized.top * pixelHeight);
        int right = Math.round(normalized.right() * pixelWidth);
        int bottom = Math.round(normalized.bottom() * pixelHeight);
        return new PixelRect(left, top, right - left, bottom - top);
    }

    static PixelRect fitSourceCrop(
            Rect crop, int destinationWidth, int destinationHeight,
            int sourceWidth, int sourceHeight, int rotationDegrees) {
        if (crop == null || destinationWidth <= 0 || destinationHeight <= 0
                || sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("positive crop and bounds are required");
        }
        float contentAspect = rotatedAspect(
                crop.width * sourceWidth, crop.height * sourceHeight, rotationDegrees);
        float destinationAspect = (float) destinationWidth / destinationHeight;
        int width = destinationWidth;
        int height = destinationHeight;
        if (destinationAspect > contentAspect) {
            width = Math.max(1, Math.round(destinationHeight * contentAspect));
        } else if (destinationAspect < contentAspect) {
            height = Math.max(1, Math.round(destinationWidth / contentAspect));
        }
        return new PixelRect(
                (destinationWidth - width) / 2,
                (destinationHeight - height) / 2,
                width, height);
    }

    static float[] rotatedSourceCropTransform(
            Rect crop, int destinationWidth, int destinationHeight,
            int sourceWidth, int sourceHeight, int rotationDegrees, boolean fill) {
        if (crop == null || destinationWidth <= 0 || destinationHeight <= 0
                || sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("positive crop and bounds are required");
        }
        return CameraRotation.sourceAwareProportionalTransformValues(
                crop.left, crop.top, crop.width, crop.height,
                0.0f, 0.0f, destinationWidth, destinationHeight,
                rotationDegrees, fill ? CameraRotation.MODE_FILL : CameraRotation.MODE_FIT,
                sourceWidth, sourceHeight, destinationWidth, destinationHeight, false);
    }

    private static float rotatedAspect(float width, float height, int rotationDegrees) {
        double radians = Math.toRadians(CameraRotation.clamp(rotationDegrees));
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        return (float) ((cosine * width + sine * height)
                / (sine * width + cosine * height));
    }

    static boolean isValidDisplayMode(int displayMode) {
        return displayMode >= DISPLAY_MODE_FIT && displayMode <= DISPLAY_MODE_STRETCH;
    }

    static int normalizeDisplayMode(int displayMode) {
        return isValidDisplayMode(displayMode) ? displayMode : DEFAULT_DISPLAY_MODE;
    }

    Pane pane(int cameraIndex) {
        switch (cameraIndex) {
            case REAR_CAMERA_INDEX:
                return rear;
            case REAR_LEFT_CAMERA_INDEX:
                return rearLeft;
            case REAR_RIGHT_CAMERA_INDEX:
                return rearRight;
            default:
                throw new IllegalArgumentException("unsupported reverse camera index: "
                        + cameraIndex);
        }
    }

    Pane[] panes() {
        return new Pane[]{rear, rearLeft, rearRight};
    }

    private ReverseCameraLayout replace(int cameraIndex, Pane replacement) {
        switch (cameraIndex) {
            case REAR_CAMERA_INDEX:
                return new ReverseCameraLayout(
                        background, widget, replacement, rearLeft, rearRight,
                        clusterRects, displayTargets, clusterZOrders);
            case REAR_LEFT_CAMERA_INDEX:
                return new ReverseCameraLayout(
                        background, widget, rear, replacement, rearRight,
                        clusterRects, displayTargets, clusterZOrders);
            case REAR_RIGHT_CAMERA_INDEX:
                return new ReverseCameraLayout(
                        background, widget, rear, rearLeft, replacement,
                        clusterRects, displayTargets, clusterZOrders);
            default:
                throw new IllegalArgumentException("unsupported reverse camera index: "
                        + cameraIndex);
        }
    }

    private static ReverseCameraLayout reorder(
            ReverseCameraLayout layout, int cameraIndex, boolean toFront) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        int targetZ = layout.pane(cameraIndex).zOrder;
        int edgeZ = toFront ? FRONT_Z : BACK_Z;
        if (targetZ == edgeZ) return layout;
        return new ReverseCameraLayout(layout.background, layout.widget,
                reordered(layout.rear, cameraIndex, targetZ, edgeZ),
                reordered(layout.rearLeft, cameraIndex, targetZ, edgeZ),
                reordered(layout.rearRight, cameraIndex, targetZ, edgeZ),
                layout.clusterRects, layout.displayTargets, layout.clusterZOrders);
    }

    private static ReverseCameraLayout moveOne(
            ReverseCameraLayout layout, int cameraIndex, int delta) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        int from = layout.pane(cameraIndex).zOrder;
        int to = Math.max(BACK_Z, Math.min(FRONT_Z, from + delta));
        if (from == to) return layout;
        return new ReverseCameraLayout(layout.background, layout.widget,
                swapped(layout.rear, cameraIndex, from, to),
                swapped(layout.rearLeft, cameraIndex, from, to),
                swapped(layout.rearRight, cameraIndex, from, to),
                layout.clusterRects, layout.displayTargets, layout.clusterZOrders);
    }

    private static ReverseCameraLayout movePeer(
            ReverseCameraLayout layout, int cameraIndex, int target, int delta) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        int from = layout.zOrderFor(cameraIndex, target);
        if (layout.targetFor(cameraIndex) != target) return layout;
        int to = from;
        for (Pane peer : layout.panes()) {
            if (layout.targetFor(peer.cameraIndex) != target) continue;
            int z = layout.zOrderFor(peer.cameraIndex, target);
            if ((z - from) * delta > 0
                    && (to == from || Math.abs(z - from) < Math.abs(to - from))) to = z;
        }
        if (from == to) return layout;
        if (target == TARGET_TABLET) return new ReverseCameraLayout(
                layout.background, layout.widget,
                swapped(layout.rear, cameraIndex, from, to),
                swapped(layout.rearLeft, cameraIndex, from, to),
                swapped(layout.rearRight, cameraIndex, from, to),
                layout.clusterRects, layout.displayTargets, layout.clusterZOrders);
        int[] zOrders = layout.clusterZOrders.clone();
        for (int i = 0; i < zOrders.length; i++) {
            if (i == cameraIndex - REAR_CAMERA_INDEX) zOrders[i] = to;
            else if (zOrders[i] == to) zOrders[i] = from;
        }
        return copy(layout, layout.background, layout.widget, layout.rear,
                layout.rearLeft, layout.rearRight, layout.clusterRects,
                layout.displayTargets, zOrders);
    }

    static ReverseCameraLayout resetLayer(
            ReverseCameraLayout layout, int cameraIndex, int target) {
        ReverseCameraLayout defaults = defaults();
        int rank = 0;
        for (Pane peer : layout.panes()) {
            if (layout.targetFor(peer.cameraIndex) == target
                    && defaults.zOrderFor(peer.cameraIndex, target)
                    < defaults.zOrderFor(cameraIndex, target)) rank++;
        }
        // At most two peers: retain the other display's order and avoid unreachable global z slots.
        for (int i = 0; i < 2; i++) layout = lower(layout, cameraIndex, target);
        for (int i = 0; i < rank; i++) layout = raise(layout, cameraIndex, target);
        return layout;
    }

    private static Pane swapped(Pane pane, int cameraIndex, int from, int to) {
        if (pane.cameraIndex == cameraIndex) return pane.withZOrder(to);
        if (pane.zOrder == to) return pane.withZOrder(from);
        return pane;
    }

    private static Pane reordered(Pane pane, int cameraIndex, int targetZ, int edgeZ) {
        int zOrder = pane.zOrder;
        if (pane.cameraIndex == cameraIndex) {
            zOrder = edgeZ;
        } else if (edgeZ == FRONT_Z && zOrder > targetZ) {
            zOrder--;
        } else if (edgeZ == BACK_Z && zOrder < targetZ) {
            zOrder++;
        }
        return zOrder == pane.zOrder ? pane : pane.withZOrder(zOrder);
    }

    private static String name(int cameraIndex) {
        switch (cameraIndex) {
            case REAR_CAMERA_INDEX:
                return REAR;
            case REAR_LEFT_CAMERA_INDEX:
                return REAR_LEFT;
            case REAR_RIGHT_CAMERA_INDEX:
                return REAR_RIGHT;
            default:
                throw new IllegalArgumentException("unsupported reverse camera index: "
                        + cameraIndex);
        }
    }

    private static void requireFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("normalized geometry must be finite");
            }
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Pane {
        final String name;
        final int cameraIndex;
        final Rect destination;
        final Rect sourceCrop;
        final int zOrder;
        final int rotationDegrees;
        final boolean mirrorHorizontally;

        private Pane(
                String name, int cameraIndex, Rect destination, Rect sourceCrop, int zOrder,
                int rotationDegrees, int displayMode) {
            this(name, cameraIndex, destination, sourceCrop, zOrder,
                    rotationDegrees, displayMode, mirrorHorizontally(cameraIndex));
        }

        private Pane(
                String name, int cameraIndex, Rect destination, Rect sourceCrop, int zOrder,
                int rotationDegrees, int displayMode, boolean mirrorHorizontally) {
            this.name = name;
            this.cameraIndex = cameraIndex;
            this.destination = destination;
            this.sourceCrop = sourceCrop;
            this.zOrder = zOrder;
            this.rotationDegrees = CameraRotation.clamp(rotationDegrees);
            this.displayMode = normalizeDisplayMode(displayMode);
            this.mirrorHorizontally = mirrorHorizontally;
        }

        final int displayMode;

        private Pane withZOrder(int nextZOrder) {
            return new Pane(name, cameraIndex, destination, sourceCrop,
                    nextZOrder, rotationDegrees, displayMode, mirrorHorizontally);
        }
    }

    static final class Rect {
        final float left;
        final float top;
        final float width;
        final float height;

        private Rect(float left, float top, float width, float height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        float right() {
            return left + width;
        }

        float bottom() {
            return top + height;
        }
    }

    static final class PixelRect {
        final int left;
        final int top;
        final int width;
        final int height;

        PixelRect(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }
}
