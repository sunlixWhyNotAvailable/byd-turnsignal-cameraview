package com.byd.extend;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One FIFO for all profiles. Manual cancellation never consumes an automatic request. */
final class AvasPlaybackQueue {
    static final class Request {
        final long id;
        final String profile;
        final boolean manual;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Request(long id, String profile, boolean manual) {
            this.id = id; this.profile = profile; this.manual = manual;
        }
    }

    private final Deque<Request> requests = new ArrayDeque<>();
    private Request active;
    private long nextId;
    private boolean closed;

    synchronized Request enqueue(String profile, boolean manual) {
        if (closed || manual && hasManual(profile)) return null;
        Request request = new Request(++nextId, profile, manual);
        requests.addLast(request);
        notifyAll();
        return request;
    }

    synchronized Request take() throws InterruptedException {
        while (!closed && requests.isEmpty()) wait();
        if (closed) return null;
        active = requests.removeFirst();
        return active;
    }

    synchronized void finish(Request request) { if (active == request) active = null; }

    synchronized void stopManual(String profile) {
        requests.removeIf(request -> request.manual && request.profile.equals(profile));
        if (active != null && active.manual && active.profile.equals(profile)) active.cancelled.set(true);
    }

    synchronized void retainAutomaticProfiles(Set<String> enabled) {
        requests.removeIf(request -> !request.manual && !enabled.contains(request.profile));
        if (active != null && !active.manual && !enabled.contains(active.profile)) active.cancelled.set(true);
    }

    synchronized String state(String profile) {
        if (active != null && !active.cancelled.get() && active.profile.equals(profile) && active.manual)
            return "manual_playing";
        // The UI state also drives Stop. A queued manual audition must remain
        // cancellable even while the same profile's automatic sound is playing.
        for (Request request : requests) {
            if (request.manual && request.profile.equals(profile)) return "manual_queued";
        }
        if (active != null && !active.cancelled.get() && active.profile.equals(profile))
            return "automatic_playing";
        return "idle";
    }

    synchronized int pendingCount() { return requests.size(); }

    synchronized void close() {
        closed = true;
        requests.clear();
        if (active != null) active.cancelled.set(true);
        notifyAll();
    }

    private boolean hasManual(String profile) {
        if (active != null && active.manual && active.profile.equals(profile) && !active.cancelled.get()) return true;
        for (Request request : requests) if (request.manual && request.profile.equals(profile)) return true;
        return false;
    }
}
