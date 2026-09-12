package com.byd.extend;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

/** Exported Messenger endpoint for update-hint coordination protocol v1. */
public final class UpdateHintCoordinationService extends Service {
    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        UpdateHintCoordinator coordinator = UpdateHintCoordinator.get(this);
        messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == UpdateHintCoordinator.SUBSCRIBE) coordinator.subscribe(message);
            else if (message.what == UpdateHintCoordinator.STATE) coordinator.receiveState(message);
            else if (message.what == UpdateHintCoordinator.UNSUBSCRIBE) coordinator.unsubscribe(message);
            return true;
        }));
    }

    @Override public IBinder onBind(Intent intent) {
        return intent != null && UpdateHintCoordinator.ACTION.equals(intent.getAction())
                ? messenger.getBinder() : null;
    }
}
