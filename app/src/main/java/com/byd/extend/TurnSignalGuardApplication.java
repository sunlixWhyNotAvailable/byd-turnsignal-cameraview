package com.byd.extend;

import android.app.Application;
import android.content.SharedPreferences;

public final class TurnSignalGuardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CompatibilityExportArtifacts.checkAsync(this);
        SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        DiagnosticLogPolicy.configure(preferences.getBoolean(
                DiagnosticLogPolicy.PREF_ENABLED, false));
        // Inspect the untouched store before language initialization writes keys, then let
        // language keep its established fresh-install behavior before placement materializes.
        boolean existingInstall = DisplayPlacementPersistence.existingInstall(this, preferences);
        AppLanguage.initialize(this, preferences);
        DisplayPlacementPersistence.initialize(this, preferences, existingInstall);
        UpdateAutoCheckRuntime.onProcessStarted();
        UpdateHintRuntime.get(this);
    }
}
