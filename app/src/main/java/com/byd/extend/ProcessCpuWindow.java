package com.byd.extend;

/** Process CPU over actual elapsed time; one busy core is 100%, including native threads. */
final class ProcessCpuWindow {
    private int pid;
    private long elapsedMs, uptimeMs;
    long cpuMs;

    boolean started() { return pid > 0; }
    void reset() { pid = 0; }

    Sample sample(int nextPid, long elapsed, long uptime, long cpu) {
        Sample result = pid == nextPid && elapsed > elapsedMs && uptime >= uptimeMs && cpu >= cpuMs
                ? new Sample(elapsed - elapsedMs, uptime - uptimeMs, cpu - cpuMs) : null;
        pid = nextPid;
        elapsedMs = elapsed;
        uptimeMs = uptime;
        cpuMs = cpu;
        return result;
    }

    static final class Sample {
        final long elapsedMs, uptimeMs, cpuMs;
        Sample(long elapsedMs, long uptimeMs, long cpuMs) {
            this.elapsedMs = elapsedMs;
            this.uptimeMs = uptimeMs;
            this.cpuMs = cpuMs;
        }
        double percent() { return 100.0 * cpuMs / elapsedMs; }
    }
}
