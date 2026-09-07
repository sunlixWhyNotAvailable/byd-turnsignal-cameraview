package com.byd.extend;

import android.app.Application;

public final class TurnSignalGuardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLanguage.initialize(this, getSharedPreferences("settings", MODE_PRIVATE));
        UpdateAutoCheckRuntime.onProcessStarted();
    }
}
