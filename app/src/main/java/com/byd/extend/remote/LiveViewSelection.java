package com.byd.extend.remote;

import java.util.UUID;

/** Per-viewer ordering and stable media identity until the session or output ends. */
public final class LiveViewSelection {
    private long revision;
    private String streamId;

    public synchronized String select(long nextRevision) {
        if (nextRevision <= revision) return null;
        revision = nextRevision;
        return ensureStreamId();
    }

    public synchronized boolean archive(long nextRevision) {
        if (nextRevision <= revision) return false;
        revision = nextRevision;
        return true;
    }

    public synchronized String ensureStreamId() {
        if (streamId == null) streamId = UUID.randomUUID().toString();
        return streamId;
    }

    public synchronized void closeStream() { streamId = null; }
    public synchronized long revision() { return revision; }
    public synchronized String streamId() { return streamId; }
}
