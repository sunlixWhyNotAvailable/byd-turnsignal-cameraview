package com.byd.extend;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded receipt-side evidence; JSON is created only when reporting an incident. */
final class TurnSignalTelemetryHistory {
    private final Entry[] controls = new Entry[64];
    private int next;
    private int size;
    private Entry steering;
    private Entry speed;
    private long steeringCount;
    private long speedCount;
    private long getDue = -1;
    private long getStart = -1;
    private long getEnd = -1;
    private String getResult = "not_started";
    private final Operation[] operations = new Operation[16];
    private int operationNext, operationSize;

    static final class Operation {
        final String name;
        final long queued, started, cpuStarted;
        long finished = -1, cpuNs = -1;
        Operation(String name, long queued, long started, long cpuStarted) {
            this.name = name; this.queued = queued; this.started = started; this.cpuStarted = cpuStarted;
        }
    }

    synchronized Operation started(String name, long queued, long started, long cpu) {
        Operation operation = new Operation(name, queued, started, cpu);
        operations[operationNext] = operation;
        operationNext = (operationNext + 1) % operations.length;
        operationSize = Math.min(operations.length, operationSize + 1);
        return operation;
    }

    synchronized void finished(Operation operation, long now, long cpu) {
        operation.finished = now;
        operation.cpuNs = Math.max(0, cpu - operation.cpuStarted);
    }

    synchronized void clear() {
        java.util.Arrays.fill(controls, null);
        next = size = 0;
        steering = speed = null;
        steeringCount = speedCount = 0;
        getDue = getStart = getEnd = -1;
        getResult = "not_started";
        java.util.Arrays.fill(operations, null);
        operationNext = operationSize = 0;
    }

    synchronized void received(int device, int fid, int raw, long received,
            long generation, long sequence) {
        Entry entry = new Entry(device, fid, raw, received, generation, sequence);
        if (fid == TurnSignalTelemetryController.STALK_FID
                || fid == TurnSignalTelemetryController.BLINK_FID) {
            controls[next] = entry;
            next = (next + 1) % controls.length;
            size = Math.min(size + 1, controls.length);
        } else if (fid == TurnSignalTelemetryController.STEERING_FID) {
            steering = entry;
            steeringCount++;
        } else if (fid == TurnSignalTelemetryController.SPEED_FID) {
            speed = entry;
            speedCount++;
        }
    }

    synchronized void processed(int device, int fid, long generation, long sequence,
            long applied, String disposition) {
        if (mark(steering, device, fid, generation, sequence, applied, disposition)
                || mark(speed, device, fid, generation, sequence, applied, disposition)) return;
        for (Entry entry : controls) {
            if (mark(entry, device, fid, generation, sequence, applied, disposition)) return;
        }
    }

    synchronized void getStarted(long due, long start) {
        getDue = due;
        getStart = start;
        getEnd = -1;
        getResult = "in_progress";
    }

    synchronized void getFinished(long end, String result) {
        getEnd = end;
        getResult = result;
    }

    synchronized String snapshot(long now, long lastSuccessfulAge) {
        try {
            JSONArray history = new JSONArray();
            JSONArray work = new JSONArray();
            for (int i = 0; i < operationSize; i++) {
                Operation op = operations[(operationNext - operationSize + i + operations.length) % operations.length];
                work.put(new JSONObject().put("operation", op.name).put("queued_ms", op.queued)
                        .put("started_ms", op.started).put("finished_ms", op.finished)
                        .put("queue_ms", Math.max(0, op.started - op.queued))
                        .put("wall_ms", (op.finished < 0 ? now : op.finished) - op.started)
                        .put("cpu_ns", op.cpuNs));
            }
            for (int index = 0; index < size; index++) {
                history.put(json(controls[(next - size + index + controls.length) % controls.length]));
            }
            return new JSONObject().put("captured_t_ms", now)
                    .put("operations", work)
                    .put("control_callbacks", history)
                    .put("steering_latest", json(steering)).put("steering_count", steeringCount)
                    .put("speed_latest", json(speed)).put("speed_count", speedCount)
                    .put("get_due_t_ms", getDue).put("get_start_t_ms", getStart)
                    .put("get_end_t_ms", getEnd)
                    .put("get_queue_lag_ms", getStart < 0 ? -1 : Math.max(0, getStart - getDue))
                    .put("get_duration_ms", getEnd < 0 ? -1 : Math.max(0, getEnd - getStart))
                    .put("get_result", getResult)
                    .put("last_successful_get_age_ms", lastSuccessfulAge).toString();
        } catch (JSONException failure) {
            throw new IllegalStateException("turn telemetry history serialization", failure);
        }
    }

    private static boolean mark(Entry entry, int device, int fid, long generation,
            long sequence, long applied, String disposition) {
        if (entry == null || entry.device != device || entry.fid != fid
                || entry.generation != generation || entry.sequence != sequence) return false;
        entry.processed = applied;
        entry.disposition = disposition;
        return true;
    }

    private static Object json(Entry entry) throws JSONException {
        if (entry == null) return JSONObject.NULL;
        return new JSONObject().put("device", entry.device).put("fid", entry.fid)
                .put("raw", entry.raw).put("received_t_ms", entry.received)
                .put("processed_t_ms", entry.processed)
                .put("dispatch_age_ms", entry.processed < 0 ? -1
                        : Math.max(0, entry.processed - entry.received))
                .put("generation", entry.generation).put("sequence", entry.sequence)
                .put("disposition", entry.disposition);
    }

    private static final class Entry {
        final int device, fid, raw;
        final long received, generation, sequence;
        long processed = -1;
        String disposition = "queued";
        Entry(int device, int fid, int raw, long received, long generation, long sequence) {
            this.device = device;
            this.fid = fid;
            this.raw = raw;
            this.received = received;
            this.generation = generation;
            this.sequence = sequence;
        }
    }
}
