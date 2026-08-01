package com.byd.turnsignalguard.capture;

final class DirectCameraCrop {
    static final String PREF_LEFT_X = "direct_crop_left_x";
    static final String PREF_LEFT_Y = "direct_crop_left_y";
    static final String PREF_LEFT_WIDTH = "direct_crop_left_width";
    static final String PREF_LEFT_HEIGHT = "direct_crop_left_height";
    static final String PREF_LEFT_ASPECT = "direct_crop_left_aspect";
    static final String PREF_RIGHT_X = "direct_crop_right_x";
    static final String PREF_RIGHT_Y = "direct_crop_right_y";
    static final String PREF_RIGHT_WIDTH = "direct_crop_right_width";
    static final String PREF_RIGHT_HEIGHT = "direct_crop_right_height";
    static final String PREF_RIGHT_ASPECT = "direct_crop_right_aspect";
    static final String PREF_FRONT_LEFT_X = "direct_crop_front_left_x";
    static final String PREF_FRONT_LEFT_Y = "direct_crop_front_left_y";
    static final String PREF_FRONT_LEFT_WIDTH = "direct_crop_front_left_width";
    static final String PREF_FRONT_LEFT_HEIGHT = "direct_crop_front_left_height";
    static final String PREF_FRONT_LEFT_ASPECT = "direct_crop_front_left_aspect";
    static final String PREF_FRONT_RIGHT_X = "direct_crop_front_right_x";
    static final String PREF_FRONT_RIGHT_Y = "direct_crop_front_right_y";
    static final String PREF_FRONT_RIGHT_WIDTH = "direct_crop_front_right_width";
    static final String PREF_FRONT_RIGHT_HEIGHT = "direct_crop_front_right_height";
    static final String PREF_FRONT_RIGHT_ASPECT = "direct_crop_front_right_aspect";

    static final int ASPECT_FOUR_THREE = 0;
    static final int ASPECT_SIXTEEN_NINE = 1;
    static final int ASPECT_ONE_ONE = 2;
    static final int ASPECT_FREE = 3;

    static final int EDGE_LEFT = 1;
    static final int EDGE_TOP = 2;
    static final int EDGE_RIGHT = 4;
    static final int EDGE_BOTTOM = 8;

    static final float SOURCE_WIDTH = 1920.0f;
    static final float SOURCE_HEIGHT = 1300.0f;
    static final float OUTPUT_ASPECT = 4.0f / 3.0f;
    private static final float MIN_WIDTH = 0.18f;
    private static final float MIN_HEIGHT = 0.18f;

    final float left;
    final float top;
    final float width;
    final float height;
    final int aspectMode;

    private DirectCameraCrop(
            float left, float top, float width, float height, int aspectMode) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.aspectMode = aspectMode;
    }

    static DirectCameraCrop defaultFor(boolean rightCamera) {
        return defaultFor(rightCamera, ASPECT_FOUR_THREE);
    }

    static DirectCameraCrop defaultFor(CameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        return defaultFor(profile.right());
    }

    static String preferenceKey(CameraProfile profile, int field) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        if (profile.rear()) {
            if (field == 0) return profile.right() ? PREF_RIGHT_X : PREF_LEFT_X;
            if (field == 1) return profile.right() ? PREF_RIGHT_Y : PREF_LEFT_Y;
            if (field == 2) return profile.right() ? PREF_RIGHT_WIDTH : PREF_LEFT_WIDTH;
            if (field == 3) return profile.right() ? PREF_RIGHT_HEIGHT : PREF_LEFT_HEIGHT;
            if (field == 4) return profile.right() ? PREF_RIGHT_ASPECT : PREF_LEFT_ASPECT;
        } else {
            if (field == 0) return profile.right() ? PREF_FRONT_RIGHT_X : PREF_FRONT_LEFT_X;
            if (field == 1) return profile.right() ? PREF_FRONT_RIGHT_Y : PREF_FRONT_LEFT_Y;
            if (field == 2) return profile.right()
                    ? PREF_FRONT_RIGHT_WIDTH : PREF_FRONT_LEFT_WIDTH;
            if (field == 3) return profile.right()
                    ? PREF_FRONT_RIGHT_HEIGHT : PREF_FRONT_LEFT_HEIGHT;
            if (field == 4) return profile.right()
                    ? PREF_FRONT_RIGHT_ASPECT : PREF_FRONT_LEFT_ASPECT;
        }
        throw new IllegalArgumentException("invalid crop field: " + field);
    }

    static DirectCameraCrop defaultFor(boolean rightCamera, int aspectMode) {
        float width = 0.65f;
        float height = width * heightPerWidth(
                aspectMode == ASPECT_FREE ? ASPECT_FOUR_THREE : aspectMode);
        return of(rightCamera ? 0.35f : 0.0f, 0.04f, width, height, aspectMode);
    }

    static DirectCameraCrop of(float left, float top, float width) {
        return of(left, top, width, width * heightPerWidth(ASPECT_FOUR_THREE),
                ASPECT_FOUR_THREE);
    }

    static DirectCameraCrop of(
            float left, float top, float width, float height, int aspectMode) {
        int safeMode = sanitizeAspectMode(aspectMode);
        if (safeMode != ASPECT_FREE) {
            float ratio = heightPerWidth(safeMode);
            float maxWidth = Math.min(1.0f, 1.0f / ratio);
            float safeWidth = clamp(finite(width) ? width : 0.65f,
                    Math.min(MIN_WIDTH, maxWidth), maxWidth);
            float safeHeight = safeWidth * ratio;
            return new DirectCameraCrop(
                    clamp(finite(left) ? left : 0.0f, 0.0f, 1.0f - safeWidth),
                    clamp(finite(top) ? top : 0.04f, 0.0f, 1.0f - safeHeight),
                    safeWidth, safeHeight, safeMode);
        }

        float safeWidth = clamp(finite(width) ? width : 0.65f, MIN_WIDTH, 1.0f);
        float safeHeight = clamp(finite(height) ? height
                : safeWidth * heightPerWidth(ASPECT_FOUR_THREE), MIN_HEIGHT, 1.0f);
        return new DirectCameraCrop(
                clamp(finite(left) ? left : 0.0f, 0.0f, 1.0f - safeWidth),
                clamp(finite(top) ? top : 0.04f, 0.0f, 1.0f - safeHeight),
                safeWidth, safeHeight, safeMode);
    }

    DirectCameraCrop withAspectMode(int mode) {
        int safeMode = sanitizeAspectMode(mode);
        if (safeMode == aspectMode) return this;
        return of(left, top, width, height, safeMode);
    }

    DirectCameraCrop move(float dx, float dy) {
        return of(left + dx, top + dy, width, height, aspectMode);
    }

    DirectCameraCrop resize(int edges, float dx, float dy) {
        if (aspectMode == ASPECT_FREE) return resizeFree(edges, dx, dy);

        boolean dragLeft = (edges & EDGE_LEFT) != 0;
        boolean dragRight = (edges & EDGE_RIGHT) != 0;
        boolean dragTop = (edges & EDGE_TOP) != 0;
        boolean dragBottom = (edges & EDGE_BOTTOM) != 0;
        if (!(dragLeft || dragRight || dragTop || dragBottom)) return move(dx, dy);

        float ratio = heightPerWidth(aspectMode);
        if ((dragLeft || dragRight) && (dragTop || dragBottom)) {
            float anchorX = dragLeft ? right() : left;
            float anchorY = dragTop ? bottom() : top;
            float movingX = (dragLeft ? left : right()) + dx;
            float movingY = (dragTop ? top : bottom()) + dy;
            float widthFromX = Math.abs(anchorX - movingX);
            float widthFromY = Math.abs(anchorY - movingY) / ratio;
            float requestedWidth = Math.abs(dx * SOURCE_WIDTH)
                    >= Math.abs(dy * SOURCE_HEIGHT) ? widthFromX : widthFromY;
            float maxWidthX = dragLeft ? anchorX : 1.0f - anchorX;
            float maxHeight = dragTop ? anchorY : 1.0f - anchorY;
            float safeWidth = clamp(requestedWidth, MIN_WIDTH,
                    Math.min(maxWidthX, maxHeight / ratio));
            return new DirectCameraCrop(
                    dragLeft ? anchorX - safeWidth : anchorX,
                    dragTop ? anchorY - safeWidth * ratio : anchorY,
                    safeWidth, safeWidth * ratio, aspectMode);
        }

        if (dragLeft || dragRight) {
            float anchorX = dragLeft ? right() : left;
            float movingX = (dragLeft ? left : right()) + dx;
            float centerY = top + height / 2.0f;
            float maxWidthX = dragLeft ? anchorX : 1.0f - anchorX;
            float maxWidthY = 2.0f * Math.min(centerY, 1.0f - centerY) / ratio;
            float safeWidth = clamp(Math.abs(anchorX - movingX), MIN_WIDTH,
                    Math.min(maxWidthX, maxWidthY));
            return new DirectCameraCrop(dragLeft ? anchorX - safeWidth : anchorX,
                    centerY - safeWidth * ratio / 2.0f,
                    safeWidth, safeWidth * ratio, aspectMode);
        }

        float anchorY = dragTop ? bottom() : top;
        float movingY = (dragTop ? top : bottom()) + dy;
        float centerX = left + width / 2.0f;
        float maxHeight = dragTop ? anchorY : 1.0f - anchorY;
        float maxWidthX = 2.0f * Math.min(centerX, 1.0f - centerX);
        float safeWidth = clamp(Math.abs(anchorY - movingY) / ratio,
                MIN_WIDTH, Math.min(maxWidthX, maxHeight / ratio));
        return new DirectCameraCrop(centerX - safeWidth / 2.0f,
                dragTop ? anchorY - safeWidth * ratio : anchorY,
                safeWidth, safeWidth * ratio, aspectMode);
    }

    private DirectCameraCrop resizeFree(int edges, float dx, float dy) {
        boolean dragLeft = (edges & EDGE_LEFT) != 0;
        boolean dragRight = (edges & EDGE_RIGHT) != 0;
        boolean dragTop = (edges & EDGE_TOP) != 0;
        boolean dragBottom = (edges & EDGE_BOTTOM) != 0;
        if (!(dragLeft || dragRight || dragTop || dragBottom)) return move(dx, dy);

        float nextLeft = left;
        float nextTop = top;
        float nextRight = right();
        float nextBottom = bottom();
        if (dragLeft) nextLeft = clamp(left + dx, 0.0f, nextRight - MIN_WIDTH);
        if (dragRight) nextRight = clamp(right() + dx, nextLeft + MIN_WIDTH, 1.0f);
        if (dragTop) nextTop = clamp(top + dy, 0.0f, nextBottom - MIN_HEIGHT);
        if (dragBottom) nextBottom = clamp(bottom() + dy, nextTop + MIN_HEIGHT, 1.0f);
        return new DirectCameraCrop(nextLeft, nextTop,
                nextRight - nextLeft, nextBottom - nextTop, ASPECT_FREE);
    }

    float outputAspect() {
        return width * SOURCE_WIDTH / (height * SOURCE_HEIGHT);
    }

    float right() {
        return left + width;
    }

    float bottom() {
        return top + height;
    }

    static String aspectLabel(int mode) {
        switch (sanitizeAspectMode(mode)) {
            case ASPECT_SIXTEEN_NINE: return "16:9";
            case ASPECT_ONE_ONE: return "1:1";
            case ASPECT_FREE: return "Вільний";
            default: return "4:3";
        }
    }

    private static int sanitizeAspectMode(int mode) {
        return mode >= ASPECT_FOUR_THREE && mode <= ASPECT_FREE
                ? mode : ASPECT_FOUR_THREE;
    }

    private static float heightPerWidth(int mode) {
        float outputAspect;
        switch (sanitizeAspectMode(mode)) {
            case ASPECT_SIXTEEN_NINE:
                outputAspect = 16.0f / 9.0f;
                break;
            case ASPECT_ONE_ONE:
                outputAspect = 1.0f;
                break;
            default:
                outputAspect = OUTPUT_ASPECT;
                break;
        }
        return SOURCE_WIDTH / (SOURCE_HEIGHT * outputAspect);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (maximum < minimum) return maximum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
