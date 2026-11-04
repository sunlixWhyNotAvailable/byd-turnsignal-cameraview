package com.byd.extend;

import java.util.ArrayList;
import java.util.Arrays;

/** Thread-confined extended-log aggregates; the first metric identifies the slowest sample. */
final class DiagnosticTimingWindow {
    private final String[] names;
    private final long[] sums, maxima, slowest;
    private long revision = -1, start, count;

    DiagnosticTimingWindow(String... names) {
        this.names = names.clone();
        sums = new long[names.length]; maxima = new long[names.length]; slowest = new long[names.length];
    }

    boolean enabled() {
        long next = DiagnosticLogPolicy.revision();
        if (revision != next) { clear(); revision = next; }
        return DiagnosticLogPolicy.extended();
    }

    Object[] record(long nowMs, long... values) {
        if (!enabled()) return null;
        if (values.length != names.length) throw new IllegalArgumentException("Timing metric count");
        if (count == 0) start = nowMs;
        if (count == 0 || values[0] > slowest[0]) System.arraycopy(values, 0, slowest, 0, values.length);
        count++;
        for (int i = 0; i < values.length; i++) {
            sums[i] += values[i]; maxima[i] = Math.max(maxima[i], values[i]);
        }
        if (nowMs - start < 5000) return null;
        ArrayList<Object> fields = new ArrayList<>();
        fields.add("samples"); fields.add(count);
        fields.add("window_ms"); fields.add(nowMs - start);
        for (int i = 0; i < names.length; i++) {
            fields.add(names[i] + "_avg"); fields.add((double) sums[i] / count);
            fields.add(names[i] + "_max"); fields.add(maxima[i]);
            fields.add(names[i] + "_at_slowest"); fields.add(slowest[i]);
        }
        clear();
        return fields.toArray();
    }

    void clear() {
        count = 0;
        Arrays.fill(sums, 0); Arrays.fill(maxima, 0); Arrays.fill(slowest, 0);
    }
}
