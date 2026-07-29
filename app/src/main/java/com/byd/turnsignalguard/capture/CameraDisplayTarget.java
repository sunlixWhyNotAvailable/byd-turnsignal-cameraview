package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

final class CameraDisplayTarget {
    static final int TABLET = 0;
    static final int CLUSTER = 1;
    static final int CLUSTER_REFERENCE_WIDTH = 1920;
    static final int CLUSTER_REFERENCE_HEIGHT = 720;

    private static final String CLUSTER_MARKER = "XDJAScreenProjection";

    private CameraDisplayTarget() {}

    static boolean isValid(int target) {
        return target == TABLET || target == CLUSTER;
    }

    static Display resolve(Context context, int target) {
        if (!isValid(target)) throw new IllegalArgumentException("invalid display target");
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (target == TABLET) return windows == null ? null : windows.getDefaultDisplay();
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) return null;
        Display selected = null;
        int selectedRank = Integer.MAX_VALUE;
        int defaultId = windows == null || windows.getDefaultDisplay() == null
                ? Display.DEFAULT_DISPLAY : windows.getDefaultDisplay().getDisplayId();
        for (Display candidate : manager.getDisplays()) {
            if (candidate == null || candidate.getDisplayId() == defaultId) continue;
            int rank = clusterNameRank(candidate.getName());
            if (rank < selectedRank || rank == selectedRank && selected != null
                    && candidate.getDisplayId() < selected.getDisplayId()) {
                selected = candidate;
                selectedRank = rank;
            }
        }
        return selectedRank == Integer.MAX_VALUE ? null : selected;
    }

    static int[] displaySize(Context context, int target) {
        Display display = resolve(context, target);
        if (display == null) {
            return target == CLUSTER
                    ? new int[]{CLUSTER_REFERENCE_WIDTH, CLUSTER_REFERENCE_HEIGHT}
                    : new int[]{1, 1};
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return new int[]{Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels)};
    }

    static int clusterNameRank(String name) {
        if (name == null || !name.contains(CLUSTER_MARKER)) return Integer.MAX_VALUE;
        if (name.endsWith(CLUSTER_MARKER + "_1")) return 0;
        if (name.endsWith(CLUSTER_MARKER + "_0")) return 1;
        return Integer.MAX_VALUE;
    }

    static String name(int target) {
        return target == CLUSTER ? "cluster" : "tablet";
    }
}
