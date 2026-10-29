package com.byd.extend;

import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.util.List;

/** Read-only child: the parent alone commits evidence after rechecking request ownership. */
public final class OemWakeDiscoveryMain {
    public static void main(String[] args) {
        int exit = 1;
        try {
            if (args.length != 2) throw new IllegalArgumentException("Expected deadline and firmware identity");
            long deadline = Long.parseLong(args[0]);
            CellularApnCheck.ownLifetime(deadline, () -> "wake_discovery");
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            List<File> sources = WakeDiscoveryState.sources();
            if (!WakeDiscoveryState.identity(sources).equals(args[1])) throw new java.io.IOException("Firmware changed before analysis");
            JSONObject hashes = WakeDiscoveryState.hashes(sources);
            System.out.println("WAKE_SOURCES " + hashes);
            System.out.flush();
            OemWakeAnalyzer.Result result = new OemWakeAnalyzer(Math.max(1, deadline - SystemClock.elapsedRealtime())).analyze(sources);
            if (!WakeDiscoveryState.identity(sources).equals(args[1])) throw new java.io.IOException("Firmware changed during analysis");
            System.out.println("WAKE_RESULT " + new JSONObject().put("reason", result.reason)
                    .put("evidence", result.evidence).put("hashes", hashes));
            exit = 0;
        } catch (Throwable error) {
            System.out.println("WAKE_ERROR " + CellularRecovery.diagnosticDetail(error, "stage=wake_discovery"));
        }
        System.out.flush();
        System.exit(exit);
    }
}
