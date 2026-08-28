package com.byd.extend;

import android.app.Application;

public final class TurnSignalGuardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UpdateAutoCheckRuntime.onProcessStarted();
    }
}
