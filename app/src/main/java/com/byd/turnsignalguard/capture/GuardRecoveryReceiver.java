package com.byd.turnsignalguard.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class GuardRecoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!GuardRecovery.shouldRecover(context)) {
            GuardRecovery.schedule(context);
            return;
        }
        String action = intent == null ? "" : intent.getAction();
        GuardRecovery.startService(context, "receiver:" + action);
        GuardRecovery.scheduleSoon(context);
    }
}
