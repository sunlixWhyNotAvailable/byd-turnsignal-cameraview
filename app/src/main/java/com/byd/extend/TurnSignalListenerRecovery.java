package com.byd.extend;

final class TurnSignalListenerRecovery {
    static final int STALK = 1;
    static final int BLINK = 2;

    private int invalidSignals;
    private boolean validPollRequired;
    private boolean neutralRequired;

    static boolean validSample(int signal, int raw) {
        if (signal == STALK) return raw >= 1 && raw <= 5;
        if (signal == BLINK) return raw >= 1 && raw <= 9;
        return false;
    }

    boolean invalidate(int signal) {
        boolean newlyInvalid = (invalidSignals & signal) == 0;
        invalidSignals |= signal;
        validPollRequired = true;
        neutralRequired = true;
        return newlyInvalid;
    }

    void validCallback(int signal) {
        invalidSignals &= ~signal;
    }

    boolean validPoll(boolean fresh, int stalk) {
        if (!fresh) return false;
        boolean wasReady = ready();
        if (invalidSignals == 0) validPollRequired = false;
        if (ready() && stalk == 1) neutralRequired = false;
        return !wasReady && ready();
    }

    boolean ready() {
        return invalidSignals == 0 && !validPollRequired;
    }

    boolean acceptStalkObservation() {
        return !neutralRequired;
    }

    void reset() {
        invalidSignals = 0;
        validPollRequired = false;
        neutralRequired = false;
    }
}
