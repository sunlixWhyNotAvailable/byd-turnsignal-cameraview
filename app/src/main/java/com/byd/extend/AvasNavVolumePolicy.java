package com.byd.extend;

/** Small, JVM-testable policy for preserving the shared OEM NAV stream. */
final class AvasNavVolumePolicy {
    interface BoolRead { boolean get() throws Exception; }
    interface IntWrite { void set(int value) throws Exception; }
    interface BoolWrite { void set(boolean value) throws Exception; }
    interface Action { void run() throws Exception; }

    private AvasNavVolumePolicy() {}

    static void restoreVolume(int saved, IntWrite write, Action clear) throws Exception {
        if (saved < 0) return;
        write.set(saved);
        clear.run();
    }

    static void restoreMute(int saved, BoolRead current, BoolWrite write, Action clear)
            throws Exception {
        if (saved < 0) return;
        boolean expected = saved == 1;
        if (current.get() != expected) write.set(expected);
        clear.run();
    }

    static void restore(int savedVolume, int savedMute,
            IntWrite volumeWrite, Action clearVolume,
            BoolRead currentMute, BoolWrite muteWrite, Action clearMute) throws Exception {
        // setStreamVolume may unmute, so never consume the mute marker first.
        restoreVolume(savedVolume, volumeWrite, clearVolume);
        restoreMute(savedMute, currentMute, muteWrite, clearMute);
    }
}
