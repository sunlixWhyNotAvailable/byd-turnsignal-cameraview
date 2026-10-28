package com.byd.extend;

/** Live-only view selection; recorder directions remain the original four channels. */
public final class RemoteCameraView {
    public static final String PREFERENCE_KEY = "cameras_viewing_interior";
    public static final String MIRROR_CABIN_PREFERENCE_KEY = "cameras_viewing_mirror_cabin";
    public static final int COMPOSITION = -1, FRONT = 0, REAR = 1, LEFT = 2, RIGHT = 3,
            CABIN = 4, DRIVER = 5, INTERIOR = 6, COMBINED = 7;
    public static final int NONE = 0, ALLOW_CABIN = 1, ALLOW_DRIVER = 2, BOTH = 3;
    private static final String[] NAMES = {
            "composition", "front", "rear", "left", "right", "cabin", "driver", "interior", "combined"
    };

    private RemoteCameraView() {}

    public static boolean valid(int view) { return view >= COMPOSITION && view <= COMBINED; }
    public static void validateMask(int mask) {
        if (mask < NONE || mask > BOTH) throw new IllegalArgumentException("invalid_interior_cameras");
    }
    public static int effectiveInteriorMask(String method, int selectedMask) {
        validateMask(selectedMask);
        if ("vdo".equals(method)) return selectedMask;
        if ("tailscale".equals(method) || "zrok".equals(method)) return BOTH;
        throw new IllegalArgumentException("invalid_viewer_method");
    }
    public static int parse(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i - 1;
        throw new IllegalArgumentException("invalid_view");
    }
    public static String name(int view) {
        if (!valid(view)) throw new IllegalArgumentException("invalid_view");
        return NAMES[view + 1];
    }
    public static boolean allowed(int view, int mask) {
        validateMask(mask);
        if (!valid(view)) return false;
        if (view <= RIGHT) return true;
        if (view == CABIN) return (mask & ALLOW_CABIN) != 0;
        if (view == DRIVER) return (mask & ALLOW_DRIVER) != 0;
        return mask != NONE;
    }
    public static int interiorMask(int view, int allowedMask) {
        if (!allowed(view, allowedMask)) throw new IllegalArgumentException("view_not_allowed");
        if (view == CABIN) return ALLOW_CABIN;
        if (view == DRIVER) return ALLOW_DRIVER;
        return view == INTERIOR || view == COMBINED ? allowedMask : NONE;
    }
    static int retainedInteriors(int previousMask, int requestedMask, int allowedMask) {
        validateMask(previousMask);
        validateMask(requestedMask);
        validateMask(allowedMask);
        if ((requestedMask & ~allowedMask) != 0)
            throw new IllegalArgumentException("requested_interior_not_allowed");
        return (previousMask | requestedMask) & allowedMask;
    }
    public static boolean needsExterior(int view) {
        if (!valid(view)) throw new IllegalArgumentException("invalid_view");
        return view <= RIGHT || view == COMBINED;
    }
    static boolean mirrorsCabinChannel(int channel, boolean mirrorCabin) {
        return mirrorCabin && channel == CABIN;
    }
    static float textureU(float u, boolean mirror) { return mirror ? 1f - u : u; }
    public static int defaultVdoView(int mask) {
        validateMask(mask);
        return mask == NONE ? COMPOSITION : COMBINED;
    }
    public static int retainAllowed(int view, int mask) {
        return allowed(view, mask) ? view : COMPOSITION;
    }
}
