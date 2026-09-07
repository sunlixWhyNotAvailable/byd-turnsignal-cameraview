package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Application-owned locale boundary.  Runtime messages and Compose resources must use this
 * locale, rather than the vehicle's system locale.  The persisted wire values are deliberately
 * stable and are also understood by the Kotlin UI state reader.
 */
public final class AppLanguage {
    public static final String KEY = "ui_language";
    public static final String KEY_INITIALIZED = "ui_language_initialized";
    public static final String ENGLISH = "en";
    public static final String UKRAINIAN = "uk";
    public static final String CHINESE = "zh-CN";

    private AppLanguage() {}

    public static String normalize(String value) {
        if (CHINESE.equals(value) || "zh".equalsIgnoreCase(value) || "zh-rCN".equals(value)) {
            return CHINESE;
        }
        if (UKRAINIAN.equalsIgnoreCase(value) || "ua".equalsIgnoreCase(value)) {
            return UKRAINIAN;
        }
        return ENGLISH;
    }

    /** Reads an explicit choice, retaining old installs' effective Ukrainian default. */
    public static String read(SharedPreferences preferences) {
        if (preferences.contains(KEY)) return normalize(preferences.getString(KEY, ENGLISH));
        // A pre-1.1 install had no language key and rendered Ukrainian.  Treat any persisted
        // application setting as evidence of that install, rather than guessing from a short
        // allow-list that can miss newer settings or onboarding markers.  The two locale keys
        // are the only values allowed to exist before this method has initialized a fresh store.
        for (String key : preferences.getAll().keySet()) {
            if (!KEY.equals(key) && !KEY_INITIALIZED.equals(key)) return UKRAINIAN;
        }
        return ENGLISH;
    }

    /** Initializes the key once, preserving the current effective language on upgrades. */
    public static String initialize(Context context, SharedPreferences preferences) {
        String current = initialLanguage(preferences, !preferences.contains(KEY)
                && wasUpdatedInstall(context));
        if (!preferences.contains(KEY)) {
            preferences.edit().putString(KEY, current).putBoolean(KEY_INITIALIZED, true).apply();
        } else if (!preferences.contains(KEY_INITIALIZED)) {
            preferences.edit().putBoolean(KEY_INITIALIZED, true).apply();
        }
        return current;
    }

    static String initialLanguage(SharedPreferences preferences, boolean updatedInstall) {
        String current = read(preferences);
        // A pre-1.1 user may only have opened the app and never written a setting. Package
        // history distinguishes that empty legacy store without changing explicit choices.
        return !preferences.contains(KEY) && updatedInstall && ENGLISH.equals(current)
                ? UKRAINIAN : current;
    }

    private static boolean wasUpdatedInstall(Context context) {
        if (context == null) return false; // Resource-free JVM callers use the pure decision above.
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return installed.firstInstallTime != installed.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return false;
        }
    }

    public static void write(SharedPreferences preferences, String language) {
        preferences.edit().putString(KEY, normalize(language)).putBoolean(KEY_INITIALIZED, true).apply();
    }

    public static Locale locale(String language) {
        return Locale.forLanguageTag(normalize(language));
    }

    /** Creates a resource context with the selected locale and leaves the base context untouched. */
    public static Context localizedContext(Context context, String language) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        Locale locale = locale(language);
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            configuration.setLocales(new LocaleList(locale));
        } else {
            //noinspection deprecation
            configuration.setLocale(locale);
        }
        return context.createConfigurationContext(configuration);
    }

    public static boolean isChinese(String language) { return CHINESE.equals(normalize(language)); }
    public static boolean isUkrainian(String language) { return UKRAINIAN.equals(normalize(language)); }
}
