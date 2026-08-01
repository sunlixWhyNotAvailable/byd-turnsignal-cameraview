package com.byd.turnsignalguard.capture;

final class ReverseCameraLayout {
    static final int BACKGROUND_PANE_ID = -1;
    static final String REAR = "rear";
    static final String REAR_LEFT = "rear-left";
    static final String REAR_RIGHT = "rear-right";

    static final int REAR_CAMERA_INDEX = 1;
    static final int REAR_LEFT_CAMERA_INDEX = 2;
    static final int REAR_RIGHT_CAMERA_INDEX = 3;
    static final float MIN_DESTINATION_SIZE = 0.08f;

    private static final int BACK_Z = 0;
    private static final int FRONT_Z = 2;

    final Rect background;
    final Pane rear;
    final Pane rearLeft;
    final Pane rearRight;

    private ReverseCameraLayout(Rect background, Pane rear, Pane rearLeft, Pane rearRight) {
        if (background == null) throw new IllegalArgumentException("background is required");
        if (rear.zOrder == rearLeft.zOrder || rear.zOrder == rearRight.zOrder
                || rearLeft.zOrder == rearRight.zOrder) {
            throw new IllegalArgumentException("pane z-orders must be unique");
        }
        this.background = background;
        this.rear = rear;
        this.rearLeft = rearLeft;
        this.rearRight = rearRight;
    }

    static ReverseCameraLayout defaults() {
        Rect fullCrop = sourceCrop(0.0f, 0.0f, 1.0f, 1.0f);
        return new ReverseCameraLayout(
                destination(0.0f, 0.0f, 1.0f, 1.0f),
                new Pane(REAR, REAR_CAMERA_INDEX,
                        destination(0.0f, 0.0f, 1.0f, 0.5f), fullCrop, 0),
                new Pane(REAR_LEFT, REAR_LEFT_CAMERA_INDEX,
                        destination(0.0f, 0.5f, 0.5f, 0.5f), fullCrop, 1),
                new Pane(REAR_RIGHT, REAR_RIGHT_CAMERA_INDEX,
                        destination(0.5f, 0.5f, 0.5f, 0.5f), fullCrop, 2));
    }

    static boolean mirrorHorizontally(int cameraIndex) {
        return cameraIndex == REAR_CAMERA_INDEX;
    }

    static Rect destination(float left, float top, float width, float height) {
        requireFinite(left, top, width, height);
        float safeWidth = clamp(width, MIN_DESTINATION_SIZE, 1.0f);
        float safeHeight = clamp(height, MIN_DESTINATION_SIZE, 1.0f);
        return new Rect(
                clamp(left, 0.0f, 1.0f - safeWidth),
                clamp(top, 0.0f, 1.0f - safeHeight),
                safeWidth,
                safeHeight);
    }

    static Rect sourceCrop(float left, float top, float width, float height) {
        requireFinite(left, top, width, height);
        if (width <= 0.0f || height <= 0.0f) {
            throw new IllegalArgumentException("source crop size must be positive");
        }
        float safeWidth = Math.min(width, 1.0f);
        float safeHeight = Math.min(height, 1.0f);
        return new Rect(
                clamp(left, 0.0f, 1.0f - safeWidth),
                clamp(top, 0.0f, 1.0f - safeHeight),
                safeWidth,
                safeHeight);
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
                        layout.pane(cameraIndex).zOrder));
    }

    static ReverseCameraLayout withBackground(
            ReverseCameraLayout layout, Rect destination) {
        if (layout == null || destination == null) {
            throw new IllegalArgumentException("background geometry is required");
        }
        Rect safe = destination(
                destination.left, destination.top, destination.width, destination.height);
        return new ReverseCameraLayout(safe, layout.rear, layout.rearLeft, layout.rearRight);
    }

    static ReverseCameraLayout bringToFront(
            ReverseCameraLayout layout, int cameraIndex) {
        return reorder(layout, cameraIndex, true);
    }

    static ReverseCameraLayout sendToBack(
            ReverseCameraLayout layout, int cameraIndex) {
        return reorder(layout, cameraIndex, false);
    }

    static ReverseCameraLayout raise(ReverseCameraLayout layout, int cameraIndex) {
        return moveOne(layout, cameraIndex, 1);
    }

    static ReverseCameraLayout lower(ReverseCameraLayout layout, int cameraIndex) {
        return moveOne(layout, cameraIndex, -1);
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
                return new ReverseCameraLayout(background, replacement, rearLeft, rearRight);
            case REAR_LEFT_CAMERA_INDEX:
                return new ReverseCameraLayout(background, rear, replacement, rearRight);
            case REAR_RIGHT_CAMERA_INDEX:
                return new ReverseCameraLayout(background, rear, rearLeft, replacement);
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
        return new ReverseCameraLayout(layout.background,
                reordered(layout.rear, cameraIndex, targetZ, edgeZ),
                reordered(layout.rearLeft, cameraIndex, targetZ, edgeZ),
                reordered(layout.rearRight, cameraIndex, targetZ, edgeZ));
    }

    private static ReverseCameraLayout moveOne(
            ReverseCameraLayout layout, int cameraIndex, int delta) {
        if (layout == null) throw new IllegalArgumentException("layout is required");
        int from = layout.pane(cameraIndex).zOrder;
        int to = Math.max(BACK_Z, Math.min(FRONT_Z, from + delta));
        if (from == to) return layout;
        return new ReverseCameraLayout(layout.background,
                swapped(layout.rear, cameraIndex, from, to),
                swapped(layout.rearLeft, cameraIndex, from, to),
                swapped(layout.rearRight, cameraIndex, from, to));
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

        private Pane(
                String name, int cameraIndex, Rect destination, Rect sourceCrop, int zOrder) {
            this.name = name;
            this.cameraIndex = cameraIndex;
            this.destination = destination;
            this.sourceCrop = sourceCrop;
            this.zOrder = zOrder;
        }

        private Pane withZOrder(int nextZOrder) {
            return new Pane(name, cameraIndex, destination, sourceCrop, nextZOrder);
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

        private PixelRect(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }
}
