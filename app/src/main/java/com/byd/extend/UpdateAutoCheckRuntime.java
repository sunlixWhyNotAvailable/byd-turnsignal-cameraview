package com.byd.extend;

import android.os.SystemClock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Main-thread, process-owned update cycle. Invalid HTTP work owns its slot until done. */
final class UpdateAutoCheckRuntime implements Runnable {
    static final long AUTO_CHECK_DELAY_MS = 30_000L;
    private static final long ENTRY_REFRESH_AGE_MS = 3_600_000L;
    private static long processStartedAt = -1L;

    static final class Request {
        final long id;
        boolean manual;
        private boolean valid = true;

        Request(long id, boolean manual) { this.id = id; this.manual = manual; }
    }

    private final LongSupplier clock;
    private final BiConsumer<Runnable, Long> post;
    private final Consumer<Runnable> cancel;
    private final BooleanSupplier enabled;
    private final Consumer<Request> check;
    private final Consumer<String> event;
    private Request active;
    private long sequence;
    private long dueAt;
    private long lastWake;
    private long lastSuccessfulCheckAt = -1L;
    private int failures;
    private boolean automatic;
    private boolean sleeping;
    private boolean stopped;
    private boolean downloading;
    private boolean manualPending;
    private boolean displayStateKnown;
    private boolean displayReady;

    UpdateAutoCheckRuntime(BiConsumer<Runnable, Long> post, Consumer<Runnable> cancel,
            BooleanSupplier enabled, Consumer<Request> check, Consumer<String> event) {
        this(SystemClock::elapsedRealtime, startedAt(), post, cancel, enabled, check, event);
    }

    // Tests run the production queue/admission path with a controlled clock and transport.
    UpdateAutoCheckRuntime(LongSupplier clock, long startedAt,
            BiConsumer<Runnable, Long> post, Consumer<Runnable> cancel,
            BooleanSupplier enabled, Consumer<Request> check, Consumer<String> event) {
        this.clock = clock;
        this.post = post;
        this.cancel = cancel;
        this.enabled = enabled;
        this.check = check;
        this.event = event;
        automatic = enabled.getAsBoolean();
        lastWake = startedAt;
        dueAt = automatic ? startedAt + AUTO_CHECK_DELAY_MS : -1L;
        if (automatic) event.accept("scheduled reason=startup delay_ms="
                + Math.max(0L, dueAt - clock.getAsLong()));
    }

    static synchronized void onProcessStarted() {
        if (processStartedAt < 0L) processStartedAt = SystemClock.elapsedRealtime();
    }

    private static synchronized long startedAt() {
        onProcessStarted();
        return processStartedAt;
    }

    void refresh() {
        boolean next = enabled.getAsBoolean();
        if (automatic != next) {
            automatic = next;
            failures = 0;
            invalidateAutomatic("preference");
            dueAt = -1L;
            if (next && !stopped && !sleeping) schedule(AUTO_CHECK_DELAY_MS, "enabled");
        }
        queue();
    }

    boolean isChecking() { return active != null || manualPending; }

    boolean requestManual() {
        refresh();
        if (stopped || downloading) return false;
        cancel.accept(this);
        dueAt = -1L;
        if (active != null) {
            if (active.valid) {
                active.manual = true;
                event.accept("adopted request=" + active.id + " manual=true");
            } else manualPending = true;
        } else start(true);
        return true;
    }

    /** Returns whether the result is current; releases the slot even for invalidated work. */
    boolean complete(Request request, boolean success) {
        refresh(); // Observe OFF even if its preference listener is still queued.
        if (active != request) return false;
        active = null;
        boolean accepted = request.valid && !stopped;
        if (accepted) {
            dueAt = -1L;
            if (success) {
                failures = 0;
                lastSuccessfulCheckAt = clock.getAsLong();
            } else if (automatic && !sleeping) {
                long delay = retryDelay(failures);
                failures = Math.min(4, failures + 1);
                schedule(delay, "failure_" + failures);
            }
        }
        event.accept("completed request=" + request.id + " manual=" + request.manual
                + " outcome=" + (success ? "success" : "error") + " accepted=" + accepted);
        queue(); // Never start inline before the caller finishes publishing/discarding.
        return accepted;
    }

    static long retryDelay(int failures) {
        switch (failures) {
            case 0: return 30_000L;
            case 1: return 60_000L;
            case 2: return 120_000L;
            default: return 300_000L;
        }
    }

    void setDownloading(boolean value) {
        downloading = value;
        refresh();
    }

    void sleep() {
        if (sleeping) return;
        sleeping = true;
        dueAt = -1L;
        invalidateAutomatic("sleep");
        cancel.accept(this);
        event.accept("paused reason=sleep");
    }

    void wake(String action) {
        wake(action, false);
    }

    void onDisplayState(boolean ready) {
        boolean wasKnown = displayStateKnown;
        boolean wasReady = displayReady;
        displayStateKnown = true;
        displayReady = ready;
        if (!ready) {
            sleep();
        } else if (wasKnown && !wasReady) {
            wake("screen_on", true);
        }
    }

    private void wake(String action, boolean displayTransition) {
        if (stopped || (displayStateKnown && !displayReady)) return;
        long now = clock.getAsLong();
        boolean quickBoot = "android.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!displayTransition && !sleeping
                && (!quickBoot || now - lastWake < AUTO_CHECK_DELAY_MS)) return;
        sleeping = false;
        lastWake = now;
        automatic = enabled.getAsBoolean();
        failures = 0;
        invalidateAutomatic("wake");
        dueAt = -1L;
        if (automatic && !(active != null && active.valid && active.manual) && !manualPending) {
            schedule(AUTO_CHECK_DELAY_MS, "wake:" + action);
        }
        queue();
    }

    void entry(boolean interactive) {
        entry(interactive, false);
    }

    void entry(boolean displayReady, boolean offerPending) {
        if (stopped) {
            stopped = false;
            sleeping = true;
        }
        onDisplayState(displayReady);
        if (displayReady) wake("user_entry");
        refresh();
        long now = clock.getAsLong();
        if (displayReady && !offerPending && automatic && !sleeping && !stopped
                && lastSuccessfulCheckAt >= 0L
                && now - lastSuccessfulCheckAt >= ENTRY_REFRESH_AGE_MS
                && active == null && !manualPending && !downloading && dueAt < 0L) {
            schedule(AUTO_CHECK_DELAY_MS, "entry_stale");
        }
        queue();
    }

    void shutdown() {
        stopped = true;
        dueAt = -1L;
        failures = 0;
        manualPending = false;
        if (active != null) active.valid = false;
        cancel.accept(this);
        event.accept("cancelled reason=shutdown");
    }

    private void invalidateAutomatic(String reason) {
        if (active != null && !active.manual && active.valid) {
            active.valid = false;
            event.accept("invalidated request=" + active.id + " reason=" + reason);
        }
        if (dueAt >= 0L) event.accept("cancelled reason=" + reason);
    }

    private void schedule(long delay, String reason) {
        dueAt = clock.getAsLong() + delay;
        event.accept("scheduled reason=" + reason + " delay_ms=" + delay);
    }

    private void queue() {
        cancel.accept(this);
        if (stopped || downloading || active != null) return;
        if (manualPending) post.accept(this, 0L);
        else if (automatic && !sleeping && dueAt >= 0L) {
            post.accept(this, Math.max(0L, dueAt - clock.getAsLong()));
        }
    }

    @Override public void run() {
        refresh();
        if (stopped || downloading || active != null) return;
        if (manualPending) start(true);
        else if (automatic && !sleeping && dueAt >= 0L && clock.getAsLong() >= dueAt) start(false);
    }

    private void start(boolean manual) {
        cancel.accept(this);
        dueAt = -1L;
        manualPending = false;
        active = new Request(++sequence, manual);
        event.accept("started request=" + active.id + " manual=" + manual);
        check.accept(active);
    }
}
