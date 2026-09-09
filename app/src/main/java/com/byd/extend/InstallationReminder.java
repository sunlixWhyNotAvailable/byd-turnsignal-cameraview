package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

/** Separate from application settings: a reminder marker must not influence locale migration. */
final class InstallationReminder {
    private static final String ACKNOWLEDGED = "acknowledged_installation";
    private final SharedPreferences preferences;
    private final String identity;

    InstallationReminder(Context context) {
        this(context.getSharedPreferences("installation_reminder", Context.MODE_PRIVATE),
                installedIdentity(context));
    }

    InstallationReminder(SharedPreferences preferences, String identity) {
        this.preferences = preferences;
        this.identity = identity;
    }

    boolean pending() {
        return !identity.equals(preferences.getString(ACKNOWLEDGED, ""));
    }

    void acknowledge() { preferences.edit().putString(ACKNOWLEDGED, identity).apply(); }

    static String identity(long firstInstallTime, long lastUpdateTime) {
        return firstInstallTime + ":" + lastUpdateTime;
    }

    private static String installedIdentity(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return identity(info.firstInstallTime, info.lastUpdateTime);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            // Own package normally always exists; stable fallback avoids repeated prompts on resume.
            return BuildConfig.VERSION_NAME + ":" + BuildConfig.VERSION_CODE;
        }
    }
}
