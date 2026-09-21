package com.byd.extend;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Small worker-side orchestration seam for silence-to-file NAV_SOURCE admission. */
final class AvasNavSourcePlayback {
    interface ZeroWriter {
        long prefill(long frames) throws Exception;
        long write(long frames) throws Exception;
    }

    interface Starter { long start() throws Exception; }

    static final class Trace {
        int initialStatus = Integer.MIN_VALUE;
        int initialValue = Integer.MIN_VALUE;
        int finalStatus = Integer.MIN_VALUE;
        int finalValue = Integer.MIN_VALUE;
        long silenceFrames;
        long playCalledMs = -1;
        long zeroStartedMs = -1;
        long gateOpenedMs = -1;
        String outcome = "not_started";
    }

    static final class Result {
        final AvasNavSourceGate.Snapshot initial;
        final AvasNavSourceGate.Snapshot finalSnapshot;
        final long silenceFrames;
        final long playCalledMs;
        final boolean cancelled;
        final long zeroStartedMs;
        final long gateOpenedMs;

        Result(AvasNavSourceGate.Snapshot initial, AvasNavSourceGate.Snapshot finalSnapshot,
                long silenceFrames, long playCalledMs, boolean cancelled,
                long zeroStartedMs, long gateOpenedMs) {
            this.initial = initial;
            this.finalSnapshot = finalSnapshot;
            this.silenceFrames = silenceFrames;
            this.playCalledMs = playCalledMs;
            this.cancelled = cancelled;
            this.zeroStartedMs = zeroStartedMs;
            this.gateOpenedMs = gateOpenedMs;
        }
    }

    private AvasNavSourcePlayback() { }

    static Result await(AvasNavSourceGate gate, long prefillFrames, long chunkFrames,
            BooleanSupplier cancelled, ZeroWriter zeros, Starter starter, LongSupplier now)
            throws Exception {
        return await(gate, prefillFrames, chunkFrames, cancelled, zeros, starter, now, new Trace());
    }

    static Result await(AvasNavSourceGate gate, long prefillFrames, long chunkFrames,
            BooleanSupplier cancelled, ZeroWriter zeros, Starter starter, LongSupplier now,
            Trace trace)
            throws Exception {
        AvasNavSourceGate.Snapshot initial = gate.refresh();
        trace.initialStatus = initial.status;
        trace.initialValue = initial.value;
        trace.outcome = "waiting";
        long silenceFrames = 0;
        long zeroStartedMs = -1;
        if (!gate.ready()) {
            zeroStartedMs = now.getAsLong();
            silenceFrames += checked(zeros.prefill(prefillFrames), prefillFrames);
            trace.zeroStartedMs = zeroStartedMs;
            trace.silenceFrames = silenceFrames;
        }
        if (cancelled.getAsBoolean()) {
            trace.outcome = "cancelled_before_play";
            return new Result(initial, null, silenceFrames, -1, true, zeroStartedMs, -1);
        }
        long playCalledMs = starter.start();
        trace.playCalledMs = playCalledMs;
        if (playCalledMs < 0 || cancelled.getAsBoolean()) {
            trace.outcome = "cancelled_at_play";
            return new Result(initial, null, silenceFrames, playCalledMs, true, zeroStartedMs, -1);
        }
        long deadline = playCalledMs + AvasNavSourceGate.TIMEOUT_MILLIS;
        AvasNavSourceGate.Snapshot finalSnapshot = gate.refresh();
        gate.beginWait();
        while (!cancelled.getAsBoolean()) {
            if (now.getAsLong() >= deadline) {
                finalSnapshot = gate.refresh();
                break;
            }
            // A callback can arrive during a zero write or between loop conditions.
            // Confirm every ready observation here; a newer nonzero GET keeps waiting.
            if (gate.ready()) {
                finalSnapshot = gate.refresh();
                if (gate.ready()) break;
                continue;
            }
            gate.awaitChangeOrPoll(deadline, cancelled);
            if (!gate.ready() && !cancelled.getAsBoolean() && now.getAsLong() < deadline) {
                if (zeroStartedMs < 0) zeroStartedMs = now.getAsLong();
                silenceFrames += checked(zeros.write(chunkFrames), chunkFrames);
                trace.zeroStartedMs = zeroStartedMs;
                trace.silenceFrames = silenceFrames;
            }
        }
        if (cancelled.getAsBoolean()) {
            trace.outcome = "cancelled_waiting";
            return new Result(initial, null, silenceFrames, playCalledMs, true, zeroStartedMs, -1);
        }
        trace.finalStatus = finalSnapshot.status;
        trace.finalValue = finalSnapshot.value;
        if (!gate.ready()) {
            trace.outcome = "timeout";
            throw new IllegalStateException("NAV_SOURCE did not become valid 0");
        }
        trace.gateOpenedMs = now.getAsLong();
        trace.outcome = "open";
        return new Result(initial, finalSnapshot, silenceFrames, playCalledMs, false,
                zeroStartedMs, now.getAsLong());
    }

    private static long checked(long written, long requested) {
        if (written < 0 || written > requested) {
            throw new IllegalStateException("Invalid zero write " + written + "/" + requested);
        }
        return written;
    }
}
