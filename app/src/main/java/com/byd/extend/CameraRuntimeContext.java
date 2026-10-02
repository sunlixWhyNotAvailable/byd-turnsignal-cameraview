package com.byd.extend;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.provider.Settings;

/** Shell identity for platform calls; app resources and a replicated camera settings snapshot. */
final class CameraRuntimeContext extends ContextWrapper {
    final CameraRuntimePreferences settings = new CameraRuntimePreferences();
    private final CameraRuntimePreferences counters = new CameraRuntimePreferences();
    private final ApplicationInfo application;
    private final Resources resources;
    private final Context resourceContext;
    volatile boolean overlayAllowed;
    volatile boolean uiVisible;

    CameraRuntimeContext(Context system, int appUid) throws Exception {
        super(TurnSignalShellMain.shellContext(system));
        application = system.getPackageManager().getApplicationInfo(CameraHelperMain.PACKAGE_NAME, 0);
        if (application.uid != appUid) throw new SecurityException("camera owner uid mismatch");
        resources = system.getPackageManager().getResourcesForApplication(application);
        resourceContext = system.createPackageContext(CameraHelperMain.PACKAGE_NAME, 0);
    }

    @Override public Context getApplicationContext() { return this; }
    @Override public ApplicationInfo getApplicationInfo() { return application; }
    @Override public Resources getResources() { return resources; }
    @Override public Context createConfigurationContext(android.content.res.Configuration configuration) {
        Resources localized = resourceContext.createConfigurationContext(configuration).getResources();
        return new ContextWrapper(super.createConfigurationContext(configuration)) {
            @Override public Resources getResources() { return localized; }
        };
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if ("settings".equals(name)) return settings;
        if ("lifetime_counters".equals(name)) return counters;
        throw new IllegalArgumentException("unsupported runtime preferences: " + name);
    }

    static boolean overlayAllowed(Context context) {
        return context instanceof CameraRuntimeContext
                ? ((CameraRuntimeContext) context).overlayAllowed : Settings.canDrawOverlays(context);
    }
}
