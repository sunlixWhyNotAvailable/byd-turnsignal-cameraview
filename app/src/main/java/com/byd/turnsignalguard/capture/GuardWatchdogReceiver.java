package com.byd.turnsignalguard.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class GuardWatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean recoveryAllowed = GuardRecovery.shouldRecover(context);
        boolean heartbeatStale = recoveryAllowed && GuardRecovery.stale(context);
        if (!GuardRecovery.shouldAttemptWatchdogRecovery(
                recoveryAllowed, heartbeatStale)) {
            GuardRecovery.schedule(context);
            return;
        }
        String action = intent == null ? "" : intent.getAction();
        if (GuardRecovery.startService(context, "receiver:" + action)) {
            GuardRecovery.schedule(context);
        } else {
            GuardRecovery.scheduleSoon(context);
        }
    }
}
