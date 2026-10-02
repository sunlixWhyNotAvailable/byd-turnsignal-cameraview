package com.byd.extend;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local RSA attempt budget and priority over our own recovery I/O. */
final class AdbAuthorizationSession {
    private final Set<String> attemptedKeys = new HashSet<>();
    private final Set<RecoveryOperation> recovery = new HashSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private int requests;

    Attempt begin() {
        synchronized (this) {
            requests++;
            for (RecoveryOperation operation : recovery) operation.cancel();
        }
        changed();
        return new Attempt();
    }

    synchronized boolean pending() { return requests > 0; }

    synchronized boolean claimPrompt(String fingerprint, boolean force) {
        boolean allowed = LocalAdbClient.shouldSendPublicKey(force
                ? LocalAdbClient.PromptMode.FORCE : LocalAdbClient.PromptMode.AUTO_ONCE,
                attemptedKeys.contains(fingerprint));
        attemptedKeys.add(fingerprint);
        return allowed;
    }

    synchronized RecoveryOperation beginRecovery() {
        if (pending()) return null;
        RecoveryOperation operation = new RecoveryOperation();
        recovery.add(operation);
        return operation;
    }

    void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }

    private void changed() {
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }

    final class Attempt implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            synchronized (AdbAuthorizationSession.this) {
                if (closed) return;
                closed = true;
                requests--;
            }
            changed();
        }
    }

    @FunctionalInterface interface Write { void run() throws IOException; }

    final class RecoveryOperation implements AutoCloseable {
        private final Set<Closeable> connections = new HashSet<>();
        private volatile boolean cancelled;

        boolean cancelled() { return cancelled; }

        void register(Closeable connection) throws IOException {
            synchronized (AdbAuthorizationSession.this) {
                if (cancelled || pending()) {
                    closeQuietly(connection);
                    throw new IOException("recovery_yielded_to_authorization");
                }
                connections.add(connection);
            }
        }

        void unregister(Closeable connection) {
            synchronized (AdbAuthorizationSession.this) { connections.remove(connection); }
        }

        /** Only short writes/admissions, never a network read or connection wait. */
        void write(Write action) throws IOException {
            synchronized (AdbAuthorizationSession.this) {
                if (cancelled || pending()) {
                    throw new IOException("recovery_yielded_to_authorization");
                }
                action.run();
            }
        }

        private void cancel() {
            cancelled = true;
            for (Closeable connection : connections) closeQuietly(connection);
            connections.clear();
        }

        @Override public void close() {
            synchronized (AdbAuthorizationSession.this) {
                cancel();
                recovery.remove(this);
            }
        }
    }

    private static void closeQuietly(Closeable connection) {
        try { connection.close(); } catch (IOException ignored) { }
    }
}
