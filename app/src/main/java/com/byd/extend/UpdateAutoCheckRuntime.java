package com.byd.extend;

import android.os.SystemClock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Main-thread, process-owned update cycle. Invalid HTTP work owns its slot until done. */
final class UpdateAutoCheckRuntime implements Runnable {

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
    private long dueAt = -1L;
    private int failures;
    private boolean automatic;
    private boolean eligible;
    private boolean stopped;
    private boolean downloading;
    private boolean manualPending;

    UpdateAutoCheckRuntime(BiConsumer<Runnable, Long> post, Consumer<Runnable> cancel,
            BooleanSupplier enabled, Consumer<Request> check, Consumer<String> event) {
        this(SystemClock::elapsedRealtime, post, cancel, enabled, check, event);
    }

    UpdateAutoCheckRuntime(LongSupplier clock,
            BiConsumer<Runnable, Long> post, Consumer<Runnable> cancel,
            BooleanSupplier enabled, Consumer<Request> check, Consumer<String> event) {
        this.clock = clock;
        this.post = post;
        this.cancel = cancel;
        this.enabled = enabled;
        this.check = check;
        this.event = event;
        automatic = enabled.getAsBoolean();
    }

    void refresh() {
        boolean next = enabled.getAsBoolean();
        if (automatic != next) {
            automatic = next;
            failures = 0;
            invalidateAutomatic("preference");
            dueAt = -1L;
            if (next && !stopped && eligible) schedule(0L, "enabled");
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
            } else if (automatic && eligible) {
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

    void setEligible(boolean value) {
        if (eligible == value) { refresh(); return; }
        eligible = value;
        failures = 0;
        dueAt = -1L;
        if (!value) invalidateAutomatic("vehicle_or_screen_off");
        else if (!stopped && enabled.getAsBoolean()
                && !(active != null && active.valid) && !manualPending) schedule(0L, "vehicle_and_screen_on");
        refresh();
    }

    void entry() { stopped = false; refresh(); }

    void shutdown() {
        stopped = true;
        eligible = false;
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
        else if (automatic && eligible && dueAt >= 0L) {
            post.accept(this, Math.max(0L, dueAt - clock.getAsLong()));
        }
    }

    @Override public void run() {
        refresh();
        if (stopped || downloading || active != null) return;
        if (manualPending) start(true);
        else if (automatic && eligible && dueAt >= 0L && clock.getAsLong() >= dueAt) start(false);
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
