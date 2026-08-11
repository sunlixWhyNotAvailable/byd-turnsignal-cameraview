package com.byd.turnsignalguard.capture;

import android.app.Application;

public final class TurnSignalGuardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UpdateAutoCheckRuntime.onProcessStarted();
    }
}
