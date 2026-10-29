package com.byd.extend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/** Android subscriptions require the real app process; cleanup remains shell-owned. */
final class MotionOemRetentionObserver implements AutoCloseable {
    private final Context context;
    private final Handler handler;
    private final IntConsumer changed;
    private final ContentObserver archive;
    private final BroadcastReceiver mounts;
    private final Map<String, String> failures = new HashMap<>();
    private boolean enabled, observing, receiving;
    private int pendingReason;
    private final Runnable notifyWorker = this::notifyWorker;

    private void notifyWorker() { if (enabled) changed.accept(pendingReason); }

    MotionOemRetentionObserver(Context context, Handler handler, IntConsumer changed) {
        this.context = context;
        this.handler = handler;
        this.changed = changed;
        archive = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { enqueue(1); }
        };
        mounts = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { enqueue(2); }
        };
    }

    private void enqueue(int reason) {
        if (!enabled) return;
        pendingReason = reason;
        handler.removeCallbacks(notifyWorker);
        handler.post(notifyWorker);
    }

    void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value) { close(); return; }
        if (!observing) try {
            context.getContentResolver().registerContentObserver(
                    Uri.parse("content://cdr/cdr_video_normal"), true, archive);
            observing = true;
            failures.remove("subscribe_archive");
        } catch (RuntimeException failure) { failure("subscribe_archive", failure); }
        if (!receiving) try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_EJECT);
            filter.addDataScheme("file");
            context.registerReceiver(mounts, filter, null, handler);
            receiving = true;
            failures.remove("subscribe_volume");
        } catch (RuntimeException failure) { failure("subscribe_volume", failure); }
    }

    void failure(String operation, RuntimeException failure) {
        String cause = Log.getStackTraceString(failure);
        if (cause.equals(failures.put(operation, cause))) return;
        CameraViewingDiagnostics.failure("oem_retention", operation + " app_uid="
                + android.os.Process.myUid() + " pid=" + android.os.Process.myPid(), failure);
        Log.e("MotionStorage", operation, failure);
    }

    @Override public void close() {
        enabled = false;
        handler.removeCallbacks(notifyWorker);
        if (observing) try {
            context.getContentResolver().unregisterContentObserver(archive);
            observing = false;
        } catch (RuntimeException failure) { failure("unsubscribe_archive", failure); }
        if (receiving) try {
            context.unregisterReceiver(mounts);
            receiving = false;
        } catch (RuntimeException failure) { failure("unsubscribe_volume", failure); }
    }
}
