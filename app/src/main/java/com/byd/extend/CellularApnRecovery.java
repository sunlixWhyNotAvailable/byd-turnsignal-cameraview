package com.byd.extend;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Process;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** POWER edges schedule checks; ON cancels all OFF work without stopping the observer. */
final class CellularApnRecovery implements AutoCloseable {
    private static final long CHECK_BUDGET_MS = 5_000L;
    private final Object lock = new Object();
    private final Thread monitor = new Thread(this::monitor, "byd-apn2-monitor");
    private boolean stopping;
    private long epoch;
    private final CellularApnSchedule schedule = new CellularApnSchedule();
    private final Map<Network, String> cellularInterfaces = new HashMap<>();
    private ConnectivityManager connectivity;
    private boolean networkRegistered;
    private final OwnedShellProcess child = new OwnedShellProcess();
    private final OemWakeDiscovery discovery;
    private final CellularRecovery owner;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLost(Network network) {
            String iface;
            synchronized (lock) { iface = cellularInterfaces.remove(network); }
            networkChanged(network, iface, "lost");
        }

        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            String iface = properties.getInterfaceName();
            boolean changed;
            synchronized (lock) {
                if (stopping) return;
                boolean known = cellularInterfaces.containsKey(network);
                String previous = cellularInterfaces.put(network, iface);
                changed = known && !Objects.equals(previous, iface);
            }
            if (changed) networkChanged(network, iface, "interface_changed");
        }
    };

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
            if (stopping || !schedule.powerChanged(value, SystemClock.elapsedRealtime())) return;
            epoch++;
            child.cancel();
            log("power_transition", "power=" + value + " epoch=" + epoch + " source=telemetry_callback");
            lock.notifyAll();
        }
    }

    private void monitor() {
        log("monitor_started", "fast_interval_ms=" + CellularApnSchedule.FAST_INTERVAL_MS
                + " fast_window_ms=" + CellularApnSchedule.FAST_WINDOW_MS
                + " off_interval_ms=" + CellularApnSchedule.STEADY_INTERVAL_MS);
        try {
            registerNetworkCallback();
            while (true) {
                long checkEpoch;
                boolean fromNetwork;
                synchronized (lock) {
                    long delay;
                    while (!stopping && (delay = schedule.delayMs(SystemClock.elapsedRealtime())) > 0L) {
                        if (delay == Long.MAX_VALUE) lock.wait();
                        else lock.wait(delay);
                    }
                    if (stopping) return;
                    checkEpoch = epoch;
                    fromNetwork = schedule.checkStarted(SystemClock.elapsedRealtime());
                }
                if (fromNetwork || DiagnosticLogPolicy.extended())
                    log("check_started", "epoch=" + checkEpoch + " trigger=" + (fromNetwork ? "network" : "timer")
                            + " elapsed_ms=" + SystemClock.elapsedRealtime());
                try { runCheck(checkEpoch); }
                catch (InterruptedException error) { throw error; }
                catch (Exception error) { log("check_error", CellularRecovery.diagnosticDetail(error, "stage=check_process")); }
                synchronized (lock) {
                    if (epoch == checkEpoch) schedule.checkFinished(SystemClock.elapsedRealtime());
                }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally {
            unregisterNetworkCallback();
            log("monitor_stopped", "no_sleep_requested=true");
        }
    }

    private void registerNetworkCallback() {
        // This monitor thread has the shell process identity, never an incoming app Binder caller.
        try {
            if (Process.myUid() != 2000) throw new SecurityException("APN2 observer requires shell UID 2000");
            Context shell = TurnSignalShellMain.shellContext(TurnSignalShellMain.systemContext());
            if (!"com.android.shell".equals(shell.getOpPackageName()) || shell.getApplicationInfo().uid != 2000)
                throw new SecurityException("APN2 observer attribution is not shell");
            if (shell.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("APN2 observer lacks ACCESS_NETWORK_STATE");
            connectivity = (ConnectivityManager) shell.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) throw new IllegalStateException("ConnectivityManager unavailable");
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build();
            connectivity.registerNetworkCallback(request, networkCallback);
            networkRegistered = true;
            log("network_registered", "uid=" + Process.myUid() + " pid=" + Process.myPid()
                    + " user=0 package=" + shell.getOpPackageName() + " transport=cellular passive=true");
        } catch (Exception error) {
            log("network_registration_error", CellularRecovery.diagnosticDetail(error,
                    "operation=registerNetworkCallback uid=" + Process.myUid() + " pid=" + Process.myPid()
                            + " package=com.android.shell polling_retained=true"));
        }
    }

    private void networkChanged(Network network, String iface, String reason) {
        synchronized (lock) {
            if (stopping || !schedule.networkChanged()) return;
            log("network_event", "reason=" + reason + " network=" + network + " interface=" + iface
                    + " elapsed_ms=" + SystemClock.elapsedRealtime());
            lock.notifyAll();
        }
    }

    private void unregisterNetworkCallback() {
        if (networkRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (Exception error) { log("network_cleanup_error", CellularRecovery.diagnosticDetail(error,
                    "operation=unregisterNetworkCallback uid=" + Process.myUid() + " pid=" + Process.myPid())); }
            networkRegistered = false;
        }
        synchronized (lock) { cellularInterfaces.clear(); }
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
                    int observedPower = line.endsWith(" 0") ? 0 : 1;
                    schedule.powerChanged(observedPower, SystemClock.elapsedRealtime());
                    owner.powerObserved(observedPower);
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
