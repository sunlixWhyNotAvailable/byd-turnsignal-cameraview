package com.byd.extend;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.util.IdentityHashMap;

/** Only ordinary OEM inputs; no experimental control, frame observer or polling. */
final class CameraInputQueue {
    private static boolean attempted;
    private static volatile boolean loaded;
    private static final IdentityHashMap<Surface, Long> inputs = new IdentityHashMap<>();

    static synchronized void initialize(Context context) {
        if (attempted) return;
        attempted = true;
        try {
            if (!android.os.Process.is64Bit()
                    || !java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
                Log.w("CameraInputQueue", "unsupported process ABI; retaining OEM input capacity");
                return;
            }
            String directory = context.getPackageManager()
                    .getApplicationInfo("com.byd.extend", 0).nativeLibraryDir;
            System.load(new File(directory, "libcamera_input_queue.so").getPath());
            loaded = true;
        } catch (Exception | LinkageError error) {
            Log.e("CameraInputQueue", "load input capacity support; retaining OEM capacity", error);
        }
    }

    static long begin(int index) { return loaded ? beforeAdd(index) : 0; }
    static synchronized void end(Surface surface, long token, boolean added) {
        if (token == 0) return;
        afterAdd(token, added);
        if (added) {
            Long previous = inputs.put(surface, token);
            if (previous != null) removed(previous);
        }
    }
    static synchronized void detach(Surface surface) {
        Long token = inputs.remove(surface);
        if (token != null) removed(token);
    }
    static synchronized void close() {
        inputs.clear();
        if (loaded) closed();
    }

    private static native long beforeAdd(int index);
    private static native void afterAdd(long token, boolean added);
    private static native void removed(long token);
    private static native void closed();
}
