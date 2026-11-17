package com.byd.extend;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The command may finish before the child publishes its Binder. */
final class CameraLaunchAttempt extends CompletableFuture<LocalAdbClient.Result> {
    static final long STARTUP_TIMEOUT_MS = 20_000;
    private static final Pattern IDENTITY = Pattern.compile("(?m)^camera_process_identity=(\\d+):(\\d+)\\s*$");
    final long generation, startedAtMs, deadlineMs;
    final boolean replacement;
    volatile boolean ready;
    volatile int pid;
    volatile long startTicks;

    CameraLaunchAttempt(long generation, long startedAtMs, boolean replacement) {
        this.generation = generation;
        this.startedAtMs = startedAtMs;
        this.deadlineMs = startedAtMs + STARTUP_TIMEOUT_MS;
        this.replacement = replacement;
    }

    boolean canJoin(long nowMs, boolean replacing) {
        return !ready && nowMs < deadlineMs && (!replacing || replacement);
    }

    void recordIdentity(String output) {
        Matcher match = IDENTITY.matcher(output);
        if (!match.find()) return;
        try {
            int parsedPid = Integer.parseInt(match.group(1));
            long parsedTicks = Long.parseLong(match.group(2));
            if (parsedPid > 0 && parsedTicks > 0) { pid = parsedPid; startTicks = parsedTicks; }
        } catch (NumberFormatException ignored) { /* An invalid identity must never authorize a kill. */ }
    }
}
