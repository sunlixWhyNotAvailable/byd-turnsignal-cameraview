package com.byd.extend;

import android.content.Intent;
import android.os.ResultReceiver;

/** Immutable snapshot of one start command; the mutable Intent never enters runtime work. */
final class ServiceRuntimeCommand {
    final String action;
    final String reason;
    final int startId;
    final boolean enabled;
    final boolean fullImport;
    final boolean mirrorSourceAction;
    final boolean mirrorVisibilityAction;
    final String weatherReason;
    final ResultReceiver flushReceiver;
    final ResultReceiver weatherReceiver;

    private ServiceRuntimeCommand(
            String action,
            String reason,
            int startId,
            boolean enabled,
            boolean fullImport,
            boolean mirrorSourceAction,
            boolean mirrorVisibilityAction,
            String weatherReason,
            ResultReceiver flushReceiver,
            ResultReceiver weatherReceiver) {
        this.action = action;
        this.reason = reason;
        this.startId = startId;
        this.enabled = enabled;
        this.fullImport = fullImport;
        this.mirrorSourceAction = mirrorSourceAction;
        this.mirrorVisibilityAction = mirrorVisibilityAction;
        this.weatherReason = weatherReason;
        this.flushReceiver = flushReceiver;
        this.weatherReceiver = weatherReceiver;
    }

    static ServiceRuntimeCommand capture(Intent intent, int startId, String defaultAction) {
        if (intent == null) {
            return new ServiceRuntimeCommand(
                    defaultAction, "", startId, true, false,
                    false, false,
                    "manual", null, null);
        }
        return new ServiceRuntimeCommand(
                intent.getAction(),
                valueOrEmpty(intent.getStringExtra(CameraHelperService.EXTRA_REASON)),
                startId,
                intent.getBooleanExtra(CameraHelperService.EXTRA_ENABLED, true),
                intent.getBooleanExtra(CameraHelperService.EXTRA_FULL_IMPORT, false),
                intent.getBooleanExtra(CameraHelperService.EXTRA_MIRROR_SOURCE_ACTION, false),
                intent.getBooleanExtra(CameraHelperService.EXTRA_MIRROR_VISIBILITY_ACTION, false),
                valueOrDefault(
                        intent.getStringExtra(CameraHelperService.EXTRA_WEATHER_REASON), "manual"),
                intent.getParcelableExtra(CameraHelperService.EXTRA_FLUSH_RECEIVER),
                intent.getParcelableExtra(CameraHelperService.EXTRA_WEATHER_RECEIVER));
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
