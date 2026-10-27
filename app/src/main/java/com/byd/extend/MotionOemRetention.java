package com.byd.extend;

import com.byd.extend.remote.CameraViewingDiagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.system.Os;
import java.io.File;

/** One shell-owned cleanup queue, independent of camera sessions and Activity lifetime. */
final class MotionOemRetention {
    private static final long RECONCILE_MS = 5 * 60_000L;
    private static final Uri NORMAL_URI = Uri.parse("content://cdr/cdr_video_normal");
    private final MotionShellMediaContext mediaContext;
    private final MotionMediaLibrary library;
    private final File stateFile;
    private final Handler handler;
    private final Object lock = new Object();
    private final ContentObserver observer;
    private final BroadcastReceiver mounts;
    private volatile MotionOemRetentionState state;
    private String pendingReason;
    private String lastOutcome;
    private boolean observing;
    private boolean receiving;
    private final Runnable sweep = this::run;

    MotionOemRetention(MotionShellMediaContext context, MotionMediaLibrary library, Handler control)
            throws Exception {
        this.mediaContext = context;
        this.library = library;
        stateFile = new File(context.privateDirectory(), "oem-retention.json");
        state = MotionOemRetentionState.read(stateFile, context.appUid());
        library.storage().setOemRetention(state);
        HandlerThread thread = new HandlerThread("motion-oem-retention");
        thread.start();
        handler = new Handler(thread.getLooper());
        observer = new ContentObserver(control) {
            @Override public void onChange(boolean selfChange) { request("archive_changed"); }
        };
        mounts = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { request("volume_changed"); }
        };
        request("worker_restored");
    }

    void configure(MotionConfig config) throws Exception { apply(MotionOemRetentionState.from(config), "settings_applied"); }

    void suspend() throws Exception {
        MotionOemRetentionState old = state;
        apply(new MotionOemRetentionState(old.enabled, old.primarySd, old.limitGb, true), "explicit_shutdown");
    }

    private void apply(MotionOemRetentionState next, String reason) throws Exception {
        synchronized (lock) {
            if (next.sameAs(state)) return;
            // Invalidate the running sweep before acknowledging a changed policy.
            state = next;
            library.storage().setOemRetention(next);
            handler.removeCallbacks(sweep);
            pendingReason = null;
            try {
                next.write(stateFile, mediaContext.appUid());
                Os.chmod(stateFile.getAbsolutePath(), 0600);
            } catch (Exception failure) {
                state = new MotionOemRetentionState(next.enabled, next.primarySd, next.limitGb, true);
                library.storage().setOemRetention(state);
                throw failure;
            }
            System.out.println("oem_retention_policy reason=" + reason + " enabled=" + next.enabled
                    + " suspended=" + next.suspended + " sd=" + next.primarySd + " limit_gb=" + next.limitGb);
            if (next.active()) request(reason);
            else handler.post(this::updateListeners);
        }
    }

    private void request(String reason) {
        synchronized (lock) {
            if (!state.active()) return;
            pendingReason = reason;
            handler.removeCallbacks(sweep);
            handler.post(sweep);
        }
    }

    private void updateListeners() {
        Context context = mediaContext.systemContext();
        if (state.active()) {
            if (!observing) try {
                context.getContentResolver().registerContentObserver(NORMAL_URI, true, observer);
                observing = true;
            } catch (RuntimeException failure) { failure("subscribe_archive", failure); }
            if (!receiving) try {
                IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
                filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
                filter.addAction(Intent.ACTION_MEDIA_EJECT);
                filter.addDataScheme("file");
                context.registerReceiver(mounts, filter);
                receiving = true;
            } catch (RuntimeException failure) { failure("subscribe_volume", failure); }
        } else {
            if (observing) try {
                context.getContentResolver().unregisterContentObserver(observer);
                observing = false;
            } catch (RuntimeException failure) { failure("unsubscribe_archive", failure); }
            if (receiving) try {
                context.unregisterReceiver(mounts);
                receiving = false;
            } catch (RuntimeException failure) { failure("unsubscribe_volume", failure); }
        }
    }

    private void run() {
        final MotionOemRetentionState current;
        final String reason;
        synchronized (lock) {
            current = state;
            reason = pendingReason == null ? "reconcile" : pendingReason;
            pendingReason = null;
        }
        if (!current.active()) return;
        long started = SystemClock.elapsedRealtime();
        try {
            updateListeners();
            String volumeId = library.selectedVolumeId(current.primarySd);
            MotionStorage storage = library.storage();
            MotionStorage.Volume volume = volumeId == null ? null : storage.findVolume(volumeId);
            MotionStorage.CleanupResult result;
            if (volume == null || !volume.mounted || volume.readOnly || volume.totalBytes <= 0)
                result = MotionStorage.CleanupResult.unavailable("selected_volume_unavailable");
            else result = storage.cleanOemNormalToLimit(volumeId, current.limitBytes(volume.totalBytes),
                    () -> state != current || !current.active(), file -> {
                        synchronized (lock) { return state == current && current.active() && file.delete(); }
                    });
            String outcome = volumeId + ":" + result.available + ":" + result.complete + ":" + result.reason;
            if (!outcome.equals(lastOutcome) || result.deletedBytes > 0 || "settings_applied".equals(reason)) {
                System.out.println("oem_retention_result source=" + reason + " volume=" + volumeId
                        + " available=" + result.available + " complete=" + result.complete
                        + " before=" + result.bytesBefore + " deleted=" + result.deletedBytes
                        + " remaining=" + result.remainingBytes + " reason=" + result.reason
                        + " skipped=" + result.skippedRecords()
                        + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
                lastOutcome = outcome;
            }
        } catch (Exception failure) { failure("cleanup_" + reason, failure); }
        finally {
            synchronized (lock) {
                if (state.active() && pendingReason == null) handler.postDelayed(sweep, RECONCILE_MS);
            }
        }
    }

    private static void failure(String operation, Exception error) {
        CameraViewingDiagnostics.failure("oem_retention", operation, error);
        System.err.println("oem_retention_failed operation=" + operation);
        error.printStackTrace(System.err);
    }
}
