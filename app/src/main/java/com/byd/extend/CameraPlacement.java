package com.byd.extend;

/**
 * Immutable destination rectangle expressed as a fraction of the complete
 * display (not the remaining travel after margins).  This is shared by camera
 * editors and overlays so a placement cannot silently change meaning when the
 * usable viewport changes.
 */
public final class CameraPlacement {
    public static final float MIN_SIZE = 0.05f;
    /** Existing source-crop policy minimum, expressed in normalized units. */
    public static final float MIN_SOURCE_SIZE = 0.01f;
    public static final float MAX_SIZE = 1.0f;
    public static final int TOP_LEFT = 1;
    public static final int TOP_RIGHT = 2;
    public static final int BOTTOM_LEFT = 3;
    public static final int BOTTOM_RIGHT = 4;

    public final float x;
    public final float y;
    public final float width;
    public final float height;

    private CameraPlacement(float x, float y, float width, float height) {
        requireFinite(x, y, width, height);
        // `of` and pixel/legacy migration preserve an already persisted
        // rectangle exactly, including legacy crops smaller than the new
        // five-percent destination editor minimum.  New explicit edits use
        // bounded()/resize*(), which apply MIN_SIZE.
        if (width <= 0.0f || width > MAX_SIZE || height <= 0.0f
                || height > MAX_SIZE || x < 0.0f || y < 0.0f
                || x + width > 1.0f || y + height > 1.0f) {
            throw new IllegalArgumentException("invalid normalized camera placement");
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static CameraPlacement of(float x, float y, float width, float height) {
        return new CameraPlacement(x, y, width, height);
    }

    /** Validates a persisted camera source rectangle using the crop policy's 1% minimum. */
    public static CameraPlacement source(float x, float y, float width, float height) {
        CameraPlacement value = new CameraPlacement(x, y, width, height);
        if (value.width < MIN_SOURCE_SIZE || value.height < MIN_SOURCE_SIZE) {
            throw new IllegalArgumentException("source placement below minimum");
        }
        return value;
    }

    /** Returns a bounded placement, using the editor's five-percent minimum. */
    public static CameraPlacement bounded(float x, float y, float width, float height) {
        requireFinite(x, y, width, height);
        float safeWidth = clamp(width, MIN_SIZE, MAX_SIZE);
        float safeHeight = clamp(height, MIN_SIZE, MAX_SIZE);
        return new CameraPlacement(
                clamp(x, 0.0f, 1.0f - safeWidth),
                clamp(y, 0.0f, 1.0f - safeHeight), safeWidth, safeHeight);
    }

    /** Converts a legacy scale/remaining-space anchor into the exact old pixel rectangle. */
    public static CameraPlacement fromLegacy(
            int displayWidth, int displayHeight, int scalePercent, float frameAspect,
            float normalizedX, float normalizedY,
            int marginX, int topMargin, int bottomMargin) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("display dimensions must be positive");
        }
        int[] rectangle = BlindSpotOverlayController.overlayGeometry(
                displayWidth, displayHeight, scalePercent, frameAspect,
                normalizedX, normalizedY, marginX, topMargin, bottomMargin);
        return fromPixelRect(rectangle[0], rectangle[1], rectangle[2], rectangle[3],
                displayWidth, displayHeight);
    }

    /** Compatibility migration for the Preview mirror's old 35% centered-top demo. */
    public static CameraPlacement mirrorDemo() {
        return of(0.325f, 0.0f, 0.35f, 0.35f);
    }

    public static CameraPlacement fromPixelRect(
            int left, int top, int width, int height,
            int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0
                || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("positive display rectangle required");
        }
        return of((float) left / displayWidth, (float) top / displayHeight,
                (float) width / displayWidth, (float) height / displayHeight);
    }

    /** Returns rounded physical pixels in {left, top, width, height} order. */
    public int[] toPixelRect(int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("display dimensions must be positive");
        }
        int pixelWidth = Math.max(1, Math.round(width * displayWidth));
        int pixelHeight = Math.max(1, Math.round(height * displayHeight));
        int pixelLeft = Math.max(0, Math.min(displayWidth - pixelWidth,
                Math.round(x * displayWidth)));
        int pixelTop = Math.max(0, Math.min(displayHeight - pixelHeight,
                Math.round(y * displayHeight)));
        return new int[]{pixelLeft, pixelTop, pixelWidth, pixelHeight};
    }

    public CameraPlacement move(float dx, float dy) {
        return of(clamp(x + dx, 0, 1 - width), clamp(y + dy, 0, 1 - height), width, height);
    }

    /** A position edit must not resize or round an existing legacy rectangle. */
    public CameraPlacement positionTenths(float left, float top) {
        requireFinite(left, top);
        float maxX = (float) Math.floor((1.0 - width) * 1000.0 + 0.0001) / 1000.0f;
        float maxY = (float) Math.floor((1.0 - height) * 1000.0 + 0.0001) / 1000.0f;
        return of(clamp(roundTenths(left), 0, maxX), clamp(roundTenths(top), 0, maxY), width, height);
    }

    /** Bottom-right resize keeps the top-left corner fixed. */
    public CameraPlacement resize(float dw, float dh) {
        return bounded(x, y, width + dw, height + dh);
    }

    /** Resizes one corner while keeping the opposite corner fixed. */
    public CameraPlacement resizeCorner(float dx, float dy, int corner) {
        if (corner < TOP_LEFT || corner > BOTTOM_RIGHT) {
            throw new IllegalArgumentException("invalid placement corner");
        }
        float left = x;
        float top = y;
        float right = x + width;
        float bottom = y + height;
        if (corner == TOP_LEFT || corner == BOTTOM_LEFT) {
            left = clamp(left + dx, 0.0f, right - MIN_SIZE);
        } else {
            right = clamp(right + dx, left + MIN_SIZE, 1.0f);
        }
        if (corner == TOP_LEFT || corner == TOP_RIGHT) {
            top = clamp(top + dy, 0.0f, bottom - MIN_SIZE);
        } else {
            bottom = clamp(bottom + dy, top + MIN_SIZE, 1.0f);
        }
        return of(left, top, right - left, bottom - top);
    }

    /** Rounds an explicit UI edit to the contract's one-fractional-digit grid. */
    public CameraPlacement roundedTenths() {
        // The public model stores fractions (0.001 = 0.1%), unlike the
        // Preview's percentage strings.  Keep three fraction digits here.
        return bounded(roundTenths(x), roundTenths(y), roundTenths(width), roundTenths(height));
    }

    public CameraPlacement roundedTenths(float minWidth, float minHeight) {
        if (!Float.isFinite(minWidth) || !Float.isFinite(minHeight)
                || minWidth < MIN_SIZE || minHeight < MIN_SIZE) {
            throw new IllegalArgumentException("invalid placement minimum");
        }
        float safeWidth = clamp(roundTenths(width), minWidth, 1.0f);
        float safeHeight = clamp(roundTenths(height), minHeight, 1.0f);
        return new CameraPlacement(
                clamp(roundTenths(x), 0.0f, 1.0f - safeWidth),
                clamp(roundTenths(y), 0.0f, 1.0f - safeHeight), safeWidth, safeHeight);
    }

    private static float roundTenths(float value) {
        return Math.round(value * 1000.0f) / 1000.0f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void requireFinite(float... values) {
        for (float value : values) if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("placement must be finite");
        }
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CameraPlacement)) return false;
        CameraPlacement value = (CameraPlacement) other;
        return Float.compare(x, value.x) == 0 && Float.compare(y, value.y) == 0
                && Float.compare(width, value.width) == 0
                && Float.compare(height, value.height) == 0;
    }

    @Override public int hashCode() {
        int result = Float.floatToIntBits(x);
        result = 31 * result + Float.floatToIntBits(y);
        result = 31 * result + Float.floatToIntBits(width);
        return 31 * result + Float.floatToIntBits(height);
    }

    @Override public String toString() {
        return "CameraPlacement{" + x + "," + y + "," + width + "," + height + '}';
    }
}
