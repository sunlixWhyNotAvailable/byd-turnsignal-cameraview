package com.byd.extend;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/** Discovers only Android's local ADB TLS connect endpoint. */
final class AdbTlsDiscovery implements AutoCloseable, NsdManager.DiscoveryListener {
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    private final NsdManager manager;
    private final Consumer<Integer> candidate;
    private final Consumer<String> log;
    private final Queue<NsdServiceInfo> pending = new ArrayDeque<>();
    private final StartGate startGate = new StartGate();
    private boolean resolving;
    private volatile boolean closed;

    AdbTlsDiscovery(Context context, Consumer<Integer> candidate, Consumer<String> log) {
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.candidate = candidate;
        this.log = log;
    }

    synchronized void start() {
        int propertyPort = propertyPort();
        if (propertyPort > 0) candidate.accept(propertyPort);
        if (manager == null || !startGate.begin(closed)) return;
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);
        } catch (RuntimeException error) {
            startGate.failed();
            log.accept("adb_tls_mdns_start_failed:" + error.getClass().getSimpleName());
        }
    }

    static int propertyPort() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Method getInt = type.getDeclaredMethod("getInt", String.class, int.class);
            getInt.setAccessible(true);
            int value = (Integer) getInt.invoke(null, "service.adb.tls.port", -1);
            return value > 0 && value <= 65535 ? value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @Override public synchronized void onServiceFound(NsdServiceInfo service) {
        if (closed) return;
        pending.offer(service);
        resolveNext();
    }

    private synchronized void resolveNext() {
        if (closed || resolving || manager == null) return;
        NsdServiceInfo service = pending.poll();
        if (service == null) return;
        resolving = true;
        try {
            manager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    finishResolve();
                }

                @Override public void onServiceResolved(NsdServiceInfo info) {
                    try {
                        int port = info.getPort();
                        if (!closed && port > 0 && port <= 65535 && info.getHost() != null
                                && (info.getHost().isLoopbackAddress()
                                || NetworkInterface.getByInetAddress(info.getHost()) != null)) {
                            candidate.accept(port);
                        }
                    } catch (Throwable ignored) {
                        // A non-local or vanished address is not a candidate.
                    } finally {
                        finishResolve();
                    }
                }
            });
        } catch (RuntimeException error) {
            resolving = false;
            log.accept("adb_tls_mdns_resolve_failed:" + error.getClass().getSimpleName());
            resolveNext();
        }
    }

    private synchronized void finishResolve() {
        resolving = false;
        resolveNext();
    }

    @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
    @Override public void onDiscoveryStarted(String serviceType) { }
    @Override public void onDiscoveryStopped(String serviceType) { startGate.failed(); }
    @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        log.accept("adb_tls_mdns_start_failed:" + errorCode);
        startGate.failed();
        try { manager.stopServiceDiscovery(this); } catch (RuntimeException ignored) { }
    }
    @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        log.accept("adb_tls_mdns_stop_failed:" + errorCode);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        if (startGate.started() && manager != null) {
            try { manager.stopServiceDiscovery(this); } catch (RuntimeException ignored) { }
        }
        startGate.failed();
    }

    /** Small JVM-testable lifecycle gate; a failed start remains retryable until close. */
    static final class StartGate {
        private boolean started;

        synchronized boolean begin(boolean closed) {
            if (closed || started) return false;
            started = true;
            return true;
        }

        synchronized void failed() { started = false; }
        synchronized boolean started() { return started; }
    }
}
