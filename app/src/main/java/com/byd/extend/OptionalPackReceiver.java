package com.byd.extend;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class OptionalPackReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        PendingResult result = goAsync();
        // Query our own persisted IDs; never trust an ID/path supplied by the broadcast.
        OptionalPacks.get(context).completed(result::finish);
    }
}
