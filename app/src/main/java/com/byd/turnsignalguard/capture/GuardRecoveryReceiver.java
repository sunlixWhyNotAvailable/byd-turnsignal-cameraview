package com.byd.turnsignalguard.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class GuardRecoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!isAllowedAction(action)) return;
        if (!GuardRecovery.shouldRecover(context)) {
            GuardRecovery.schedule(context);
            return;
        }
        GuardRecovery.startService(context, "receiver:" + action);
        GuardRecovery.scheduleSoon(context);
    }

    static boolean isAllowedAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }
}
