package com.byd.turnsignalguard.capture;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.util.List;

public final class TurnSignalGuardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (isDefaultProcess(getPackageName(), currentProcessName(this))) {
            UpdateAutoCheckRuntime.onProcessStarted();
        }
    }

    static boolean isDefaultProcess(String packageName, String processName) {
        return packageName != null && packageName.equals(processName);
    }

    private static String currentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return null;
        List<ActivityManager.RunningAppProcessInfo> processes =
                manager.getRunningAppProcesses();
        if (processes == null) return null;
        int pid = Process.myPid();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == pid) return process.processName;
        }
        return null;
    }
}
