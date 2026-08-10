package com.byd.turnsignalguard.capture;

import java.util.LinkedHashMap;

/** One retained logical open per owner while BIND_AUTO_CREATE establishes a worker epoch. */
final class CameraWorkerPendingOpen {
    interface Operation {
        void replay(Token token);
        void release();
    }

    static final class Token {
        private static final int QUEUED = 0;
        private static final int CLAIMED = 1;
        private static final int SESSION = 2;
        private static final int CANCELLED = 3;

        private final String owner;
        private final int requestId;
        private final Operation operation;
        private volatile int state = QUEUED;
        private boolean released;

        private Token(String owner, int requestId, Operation operation) {
            this.owner = owner;
            this.requestId = requestId;
            this.operation = operation;
        }

        String owner() {
            return owner;
        }

        int requestId() {
            return requestId;
        }

        void replay() {
            if (state == CANCELLED) return;
            operation.replay(this);
        }

        synchronized void release() {
            if (released) return;
            released = true;
            try {
                operation.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private final LinkedHashMap<String, Token> tokens = new LinkedHashMap<>();

    synchronized Token replace(String owner, int requestId, Operation operation) {
        if (owner == null || operation == null || requestId <= 0) {
            throw new IllegalArgumentException("open token required");
        }
        Token next = new Token(owner, requestId, operation);
        Token previous = tokens.put(owner, next);
        if (previous != null) cancel(previous);
        return previous;
    }

    synchronized Token[] claimAll() {
        Token[] values = tokens.values().toArray(new Token[0]);
        for (Token value : values) {
            if (value.state == Token.QUEUED) value.state = Token.CLAIMED;
        }
        return values;
    }

    synchronized boolean promote(Token expected) {
        if (expected == null || tokens.get(expected.owner) != expected
                || expected.state != Token.CLAIMED) return false;
        tokens.remove(expected.owner);
        expected.state = Token.SESSION;
        return true;
    }

    synchronized boolean cancel(String owner, int requestId) {
        Token current = tokens.get(owner);
        if (current == null || current.requestId != requestId) return false;
        tokens.remove(owner);
        cancel(current);
        return true;
    }

    synchronized Token cancelOwner(String owner) {
        Token current = tokens.remove(owner);
        if (current != null) cancel(current);
        return current;
    }

    synchronized boolean cancelAll() {
        if (tokens.isEmpty()) return false;
        for (Token value : tokens.values()) cancel(value);
        tokens.clear();
        return true;
    }

    synchronized Token[] tokens() {
        return tokens.values().toArray(new Token[0]);
    }

    synchronized boolean isCurrent(Token expected) {
        return expected != null && tokens.get(expected.owner) == expected
                && expected.state != Token.CANCELLED;
    }

    private static void cancel(Token value) {
        value.state = Token.CANCELLED;
        value.release();
    }
}
