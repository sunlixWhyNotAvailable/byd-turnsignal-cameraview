package com.byd.extend;

/** Physical AVM source behind the application's logical panorama requests. */
final class PanoCameraSource {
    interface IdLookup {
        int cameraId(String tag) throws Exception;
    }

    final int id;
    final String tag;

    private PanoCameraSource(int id, String tag) {
        this.id = id;
        this.tag = tag;
    }

    static boolean isPanorama(String tag) {
        return "pano_h".equals(tag) || "pano_l".equals(tag);
    }

    static int physicalPreviewIndex(String tag, int logicalIndex) {
        // The Seal DM-i pano_l backend reports front/right as 3/4, while the
        // application's pano_h-based logical order is right/front at 3/4.
        // Keep logical routing intact and translate only at the OEM boundary.
        if ("pano_l".equals(tag)) {
            if (logicalIndex == 3) return 4;
            if (logicalIndex == 4) return 3;
        }
        return logicalIndex;
    }

    static PanoCameraSource resolve(IdLookup lookup) throws Exception {
        // Prefer the existing high-resolution path. A present but unsupported ID must
        // still reach the open-time safety guard; it is not a missing camera.
        for (String tag : new String[]{"pano_h", "pano_l"}) {
            int id = lookup.cameraId(tag);
            if (id >= 0) return new PanoCameraSource(id, tag);
        }
        throw new IllegalStateException("No panorama camera: pano_h and pano_l are unavailable");
    }
}
