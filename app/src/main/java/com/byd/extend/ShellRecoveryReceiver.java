package com.byd.extend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class ShellRecoveryReceiver extends BroadcastReceiver {
    private static final String TAG = "BydExtendRecovery";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !GuardRecovery.ACTION_SHELL_RECOVERY.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "recovery_receiver_entered");
        AvasRecoveryJournal.event(context, "shell_recovery_receiver_entered");
        boolean accepted = GuardRecovery.startService(context, "shell_helper_wake");
        Log.i(TAG, "recovery_receiver_complete accepted=" + accepted);
        AvasRecoveryJournal.event(context, "shell_recovery_receiver_complete",
                "accepted", accepted);
    }
}
