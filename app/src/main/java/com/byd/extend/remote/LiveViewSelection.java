package com.byd.extend.remote;

import java.util.UUID;

/** Per-viewer ordering and stable identity for one live output interval. */
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
        streamId = null;
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
