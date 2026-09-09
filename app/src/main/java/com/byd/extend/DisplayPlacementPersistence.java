package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;

/** One-time new-install defaults for display-specific camera placement slots. */
final class DisplayPlacementPersistence {
    static final String PREF_INITIALIZED = "camera_display_placement_v1_seeded";

    private DisplayPlacementPersistence() {}

    static boolean existingInstall(Context context, SharedPreferences preferences) {
        if (context == null || preferences == null) return true;
        try {
            return !preferences.getAll().isEmpty() || wasExistingInstall(context);
        } catch (RuntimeException unavailableStore) {
            return true;
        }
    }

    static void initialize(
            Context context, SharedPreferences preferences, boolean existingInstall) {
        if (context == null || preferences == null || preferences.contains(PREF_INITIALIZED)) return;
        int[] tablet = CameraDisplayTarget.displaySize(context, CameraDisplayTarget.TABLET);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        if (tablet[0] <= 1 && metrics != null && metrics.widthPixels > 1) {
            tablet[0] = metrics.widthPixels;
        }
        if (tablet[1] <= 1 && metrics != null && metrics.heightPixels > 1) {
            tablet[1] = metrics.heightPixels;
        }
        float density = metrics == null || metrics.density <= 0.0f ? 1.0f : metrics.density;
        // Without trustworthy tablet geometry, preservation is safer than inventing a factory size.
        initialize(preferences, existingInstall || tablet[0] <= 1 || tablet[1] <= 1,
                tablet[0], tablet[1],
                Math.round(16.0f * density), Math.round(36.0f * density),
                Math.round(88.0f * density));
    }

    static void initialize(SharedPreferences preferences, boolean existingInstall,
            int tabletWidth, int tabletHeight,
            int tabletMarginX, int tabletTopMargin, int tabletBottomMargin) {
        if (preferences == null || preferences.contains(PREF_INITIALIZED)) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(PREF_INITIALIZED, true);
        if (!existingInstall) {
            RearviewMirrorSettings.writePlacement(editor, CameraDisplayTarget.CLUSTER,
                    RearviewMirrorSettings.defaultPlacement(CameraDisplayTarget.CLUSTER));
            for (CameraProfile profile : CameraProfile.values()) {
                BlindSpotOverlayController.writePlacement(editor, profile,
                        CameraDisplayTarget.CLUSTER,
                        BlindSpotOverlayController.defaultPlacement(profile,
                                CameraDisplayTarget.CLUSTER, tabletWidth, tabletHeight,
                                tabletMarginX, tabletTopMargin, tabletBottomMargin));
            }
        }
        editor.apply();
    }

    /** Any package-history uncertainty is treated as an existing install. */
    private static boolean wasExistingInstall(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime <= 0L || info.lastUpdateTime <= 0L
                    || info.firstInstallTime != info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return true;
        }
    }
}
