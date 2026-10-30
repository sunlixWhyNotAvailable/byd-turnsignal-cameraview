package com.byd.extend;

import android.os.SystemClock;

/** POWER edges schedule checks; ON cancels all OFF work without stopping the observer. */
final class CellularApnRecovery implements AutoCloseable {
    private static final long CHECK_BUDGET_MS = 5_000L;
    private final Object lock = new Object();
    private final Thread monitor = new Thread(this::monitor, "byd-apn2-monitor");
    private boolean stopping;
    private int power = -1;
    private long epoch, nextCheck;
    private final OwnedShellProcess child = new OwnedShellProcess();
    private final OemWakeDiscovery discovery;
    private final CellularRecovery owner;

    CellularApnRecovery(OemWakeDiscovery discovery, CellularRecovery owner) {
        this.discovery = discovery;
        this.owner = owner;
    }

    void start() {
        monitor.setDaemon(true);
        monitor.start();
    }

    void powerChanged(int value) {
        synchronized (lock) {
            if (stopping || power == value) return;
            power = value;
            epoch++;
            nextCheck = value > 0 ? Long.MAX_VALUE : 0L;
            child.cancel();
            log("power_transition", "power=" + value + " epoch=" + epoch + " source=telemetry_callback");
            lock.notifyAll();
        }
    }

    private void monitor() {
        log("monitor_started", "off_interval_ms=" + 30_000L);
        try {
            while (true) {
                long checkEpoch;
                synchronized (lock) {
                    while (!stopping && nextCheck > SystemClock.elapsedRealtime()) {
                        if (nextCheck == Long.MAX_VALUE) lock.wait();
                        else lock.wait(Math.max(1L, nextCheck - SystemClock.elapsedRealtime()));
                    }
                    if (stopping) return;
                    checkEpoch = epoch;
                }
                try { runCheck(checkEpoch); }
                catch (InterruptedException error) { throw error; }
                catch (Exception error) { log("check_error", CellularRecovery.diagnosticDetail(error, "stage=check_process")); }
                synchronized (lock) {
                    if (epoch == checkEpoch) nextCheck = power > 0 ? Long.MAX_VALUE
                            : SystemClock.elapsedRealtime() + 30_000L;
                }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { log("monitor_stopped", "no_sleep_requested=true"); }
    }

    private void runCheck(long checkEpoch) throws Exception {
        long token;
        synchronized (lock) {
            if (stopping || epoch != checkEpoch) return;
            token = child.token();
        }
        WakeDiscoveryState.Selection selected = discovery.selection();
        String request = OemWakeDiscovery.request();
        try {
            OwnedShellProcess.Result result = child.run(token, CellularApnCheck.class.getName(),
                    "bydextend_apn2_check", SystemClock.elapsedRealtime() + CHECK_BUDGET_MS,
                    Integer.toString(selected.reason), selected.source, request);
            recordOutput(checkEpoch, result.output);
            if (result.exitCode != 0) throw new java.io.IOException("APN2 child exit=" + result.exitCode + "\n" + result.output);
            boolean ready = false, needsWake = false;
            for (String line : result.output.split("\\r?\\n")) {
                ready |= line.startsWith("APN2 ready ");
                needsWake |= line.startsWith("APN2 wake_required ");
            }
            synchronized (lock) {
                if (!stopping && epoch == checkEpoch) owner.checked(request, ready, needsWake, selected.reason);
            }
            log("check_finished", "epoch=" + checkEpoch + " exit_code=" + result.exitCode);
        } catch (OwnedShellProcess.Failure error) {
            // Preserve observations even if ON/cancellation interrupts the short check.
            recordOutput(checkEpoch, error.output);
            throw error;
        }
    }

    private void recordOutput(long checkEpoch, String output) {
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith("APN2 ")) continue;
            synchronized (lock) {
                if (!stopping && epoch == checkEpoch && line.matches("APN2 power [0-4]")) {
                    power = line.endsWith(" 0") ? 0 : 1;
                    owner.powerObserved(power);
                }
                CellularRecovery.observation(line);
            }
        }
    }
    private void log(String event, String detail) {
        if (!event.equals("check_finished") || DiagnosticLogPolicy.extended())
            CellularRecovery.log("apn2_" + event + " " + detail);
    }

    @Override public void close() {
        synchronized (lock) {
            stopping = true;
            epoch++;
            child.cancel();
            lock.notifyAll();
        }
        monitor.interrupt();
    }
}
