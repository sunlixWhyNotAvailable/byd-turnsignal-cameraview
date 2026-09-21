package com.byd.extend;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One worker queue for exterior playback and short in-cabin NAV auditions. */
final class AvasPlaybackQueue {
    enum Kind { AUTOMATIC_EXTERIOR, MANUAL_EXTERIOR, AUDITION_NAV }

    static final class Request {
        final long id;
        final Kind kind;
        final String profile;
        final String asset;
        final String session;
        final boolean manual;
        final AvasAudioDiagnostics.Context diagnostics;
        final AvasAudioDiagnostics.Context supersededAutomatic;
        final AtomicBoolean cancelled = new AtomicBoolean();

        private Request(long id, Kind kind, String profile, String asset, String session,
                AvasAudioDiagnostics.Context diagnostics,
                AvasAudioDiagnostics.Context supersededAutomatic) {
            this.id = id;
            this.kind = kind;
            this.profile = profile;
            this.asset = asset;
            this.session = session;
            this.manual = kind == Kind.MANUAL_EXTERIOR;
            this.diagnostics = diagnostics;
            this.supersededAutomatic = supersededAutomatic;
        }

        boolean audition() { return kind == Kind.AUDITION_NAV; }
        boolean automatic() { return kind == Kind.AUTOMATIC_EXTERIOR; }
    }

    private final Deque<Request> requests = new ArrayDeque<>();
    private Request active;
    private long nextId;
    private boolean closed;
    private final LongSupplier clock;
    private final int helperPid;

    AvasPlaybackQueue() { this(() -> System.nanoTime() / 1_000_000L, 0); }

    AvasPlaybackQueue(LongSupplier clock, int helperPid) {
        this.clock = clock;
        this.helperPid = helperPid;
    }

    synchronized Request enqueue(String profile, boolean manual) {
        return enqueueExterior(profile, manual);
    }

    synchronized Request enqueueExterior(String profile, boolean manual) {
        if (closed || manual && hasManual(profile)) return null;
        stopAllAuditionsLocked();
        Kind kind = manual ? Kind.MANUAL_EXTERIOR : Kind.AUTOMATIC_EXTERIOR;
        AvasAudioDiagnostics.Context superseded = manual
                ? null : removeReplaceableAutomaticLocked();
        long acceptedMs = clock.getAsLong();
        long id = ++nextId;
        Request request = new Request(id, kind, profile, "", "",
                new AvasAudioDiagnostics.Context(id, profile, manual ? "manual" : "automatic",
                        helperPid, acceptedMs, clock.getAsLong()), superseded);
        requests.addLast(request);
        notifyAll();
        return request;
    }

    /** Replaces an earlier note, but is rejected while any exterior work is busy. */
    synchronized Request enqueueAudition(String profile, String asset, String session) {
        if (closed || exteriorBusy()) return null;
        stopAllAuditionsLocked();
        long acceptedMs = clock.getAsLong();
        long id = ++nextId;
        Request request = new Request(id, Kind.AUDITION_NAV, profile, asset, session,
                new AvasAudioDiagnostics.Context(id, profile, "audition", helperPid,
                        acceptedMs, clock.getAsLong()), null);
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
        requests.removeIf(request -> request.kind == Kind.MANUAL_EXTERIOR
                && request.profile.equals(profile));
        if (active != null && active.kind == Kind.MANUAL_EXTERIOR
                && active.profile.equals(profile)) active.cancelled.set(true);
    }

    synchronized void stopAudition(String session) {
        requests.removeIf(request -> request.audition() && request.session.equals(session));
        if (active != null && active.audition() && active.session.equals(session)) {
            active.cancelled.set(true);
        }
    }

    synchronized void stopAllAuditions() { stopAllAuditionsLocked(); }

    synchronized void removeAuditionsForAssets(Set<String> deleted) {
        requests.removeIf(request -> request.audition() && deleted.contains(request.asset));
        if (active != null && active.audition() && deleted.contains(active.asset)) {
            active.cancelled.set(true);
        }
    }

    synchronized void retainAutomaticProfiles(Set<String> enabled) {
        requests.removeIf(request -> request.automatic() && !enabled.contains(request.profile));
        if (active != null && active.automatic() && !enabled.contains(active.profile)) {
            active.cancelled.set(true);
        }
    }

    synchronized String state(String profile) {
        if (active != null && !active.cancelled.get() && active.profile.equals(profile)
                && active.kind == Kind.MANUAL_EXTERIOR) return "manual_playing";
        // The UI state also drives Stop. A queued manual audition must remain
        // cancellable even while the same profile's automatic sound is playing.
        for (Request request : requests) {
            if (request.kind == Kind.MANUAL_EXTERIOR && request.profile.equals(profile)) {
                return "manual_queued";
            }
        }
        if (active != null && !active.cancelled.get() && active.profile.equals(profile)
                && active.automatic()) return "automatic_playing";
        for (Request request : requests) {
            if (request.automatic() && request.profile.equals(profile)) return "automatic_queued";
        }
        return "idle";
    }

    synchronized String auditionState(String session) {
        if (active != null && active.audition() && !active.cancelled.get()
                && active.session.equals(session)) return "playing";
        for (Request request : requests) {
            if (request.audition() && request.session.equals(session)) return "queued";
        }
        return "idle";
    }

    synchronized int pendingCount() { return requests.size(); }

    synchronized void close() {
        closed = true;
        requests.clear();
        if (active != null) active.cancelled.set(true);
        notifyAll();
    }

    private boolean exteriorBusy() {
        if (active != null && !active.audition()) return true;
        for (Request request : requests) if (!request.audition()) return true;
        return false;
    }

    private void stopAllAuditionsLocked() {
        requests.removeIf(Request::audition);
        if (active != null && active.audition()) active.cancelled.set(true);
    }

    private AvasAudioDiagnostics.Context removeReplaceableAutomaticLocked() {
        // Before the worker takes an idle queue head, protect it as if it were active. This keeps
        // two back-to-back events while still bounding every later automatic slot to the latest.
        Request protectedHead = active == null ? requests.peekFirst() : null;
        for (java.util.Iterator<Request> iterator = requests.iterator(); iterator.hasNext();) {
            Request pending = iterator.next();
            if (pending.automatic() && pending != protectedHead) {
                iterator.remove();
                pending.cancelled.set(true);
                return pending.diagnostics;
            }
        }
        return null;
    }

    private boolean hasManual(String profile) {
        if (active != null && active.kind == Kind.MANUAL_EXTERIOR
                && active.profile.equals(profile) && !active.cancelled.get()) return true;
        for (Request request : requests) {
            if (request.kind == Kind.MANUAL_EXTERIOR && request.profile.equals(profile)) return true;
        }
        return false;
    }
}
