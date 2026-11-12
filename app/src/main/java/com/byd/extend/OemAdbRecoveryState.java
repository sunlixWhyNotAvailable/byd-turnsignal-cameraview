package com.byd.extend;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.UUID;

/** Durable dispatch intent; a client restart is not cancellation of an OEM transaction. */
final class OemAdbRecoveryState {
    private static final String CYCLE = "oem_attempted_cycle";
    private static final String PENDING = "oem_pending_token";
    private static final String STAGE = "oem_pending_stage";
    private static final String BOOT = "oem_pending_boot";
    private static final String COUNT = "oem_pending_boot_count";
    private final SharedPreferences preferences;

    OemAdbRecoveryState(SharedPreferences preferences) { this.preferences = preferences; }

    synchronized boolean eligible(long cycle, boolean explicitRetry, String boot, int count)
            throws IOException {
        if (preferences.contains(PENDING)) {
            String previousBoot = preferences.getString(BOOT, "");
            int previousCount = preferences.getInt(COUNT, -1);
            boolean newBoot = !boot.isEmpty() && !previousBoot.isEmpty()
                    ? !boot.equals(previousBoot) : count >= 0 && previousCount >= 0 && count != previousCount;
            if (!newBoot) return false;
            save(preferences.edit().remove(PENDING).remove(STAGE).remove(BOOT).remove(COUNT)
                    .remove(CYCLE), "clear_previous_boot");
        }
        return explicitRetry || preferences.getLong(CYCLE, -1L) != cycle;
    }

    synchronized void attempted(long cycle) throws IOException {
        save(preferences.edit().putLong(CYCLE, cycle), "attempted_cycle");
    }

    synchronized void dispatch(String token, String stage, String boot, int count) throws IOException {
        save(preferences.edit().putString(PENDING, token).putString(STAGE, stage)
                .putString(BOOT, boot).putInt(COUNT, count), "mutation_intent");
    }

    synchronized void completed(String token) throws IOException {
        if (token.equals(preferences.getString(PENDING, ""))) {
            save(preferences.edit().remove(PENDING).remove(STAGE).remove(BOOT).remove(COUNT),
                    "mutation_completed");
        }
    }

    synchronized void restored() throws IOException { save(preferences.edit().remove(CYCLE), "restored"); }

    synchronized String pendingStage() { return preferences.getString(STAGE, "none"); }

    private static void save(SharedPreferences.Editor edit, String operation) throws IOException {
        if (!edit.commit()) throw new IOException("OEM recovery persistence failed: " + operation);
    }

    /** Real admission lifetime, separate from the lifetime of a transmitted Binder call. */
    static final class Ticket {
        static final long BUDGET_MS = 15_000L;
        final String id = UUID.randomUUID().toString();
        final long started;
        private String invalidation;

        Ticket(long elapsed) { started = elapsed; }

        synchronized boolean valid(long elapsed) {
            if (invalidation == null && elapsed - started >= BUDGET_MS) invalidation = "deadline";
            return invalidation == null;
        }

        synchronized boolean invalidate(String reason) {
            if (invalidation != null) return false;
            invalidation = reason;
            return true;
        }

        synchronized String invalidation() { return invalidation; }

        long remaining(long elapsed) { return Math.max(0, BUDGET_MS - (elapsed - started)); }
    }
}
