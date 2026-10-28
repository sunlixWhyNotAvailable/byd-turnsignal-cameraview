package com.byd.extend;

/** One wake reset follows the same Motion session and producer through recorder detach. */
final class MotionWakeRecovery {
    private long sequence;
    private long request;
    private String session;
    private int producer;
    private long deadline = -1;
    private int recordingGeneration;

    long begin(String session, int producer) {
        if (session == null || producer <= 0) throw new IllegalArgumentException("wake source missing");
        if (request != 0 && this.session.equals(session) && this.producer == producer) return 0;
        this.session = session;
        this.producer = producer;
        deadline = -1;
        recordingGeneration = 0;
        return request = ++sequence;
    }

    boolean matches(long request, String session, int producer) {
        return request != 0 && this.request == request && this.session.equals(session)
                && this.producer == producer;
    }

    boolean retain(long request, String session, int producer, long deadline, int generation) {
        if (!matches(request, session, producer)) return false;
        if (deadline < 0 || generation <= 0) throw new IllegalArgumentException("recording tail missing");
        if (this.deadline >= 0) return false;
        this.deadline = deadline;
        recordingGeneration = generation;
        return true;
    }

    int takeAfterDetach(String session, int producer) {
        if (request == 0 || !this.session.equals(session) || this.producer != producer) return -1;
        clear();
        return producer;
    }

    void sourceClosed(int producer) {
        if (request != 0 && this.producer == producer) clear();
    }

    long deadline() { return deadline; }
    int recordingGeneration() { return recordingGeneration; }
    void clear() {
        request = 0;
        session = null;
        producer = 0;
        deadline = -1;
        recordingGeneration = 0;
    }
}
