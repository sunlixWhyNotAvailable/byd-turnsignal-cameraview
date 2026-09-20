package com.byd.extend;

import java.util.function.BooleanSupplier;

/** Small, JVM-testable policy for preserving the shared OEM NAV stream. */
final class AvasNavVolumePolicy {
    interface IntRead { int get() throws Exception; }
    interface BoolRead { boolean get() throws Exception; }
    interface IntWrite { void set(int value) throws Exception; }
    interface BoolWrite { void set(boolean value) throws Exception; }
    interface Action { void run() throws Exception; }

    static final class Snapshot {
        final int volume;
        final boolean muted;

        Snapshot(int volume, boolean muted) {
            this.volume = volume;
            this.muted = muted;
        }
    }

    private AvasNavVolumePolicy() {}

    static void journal(Snapshot snapshot, IntWrite saveMute, IntWrite saveVolume)
            throws Exception {
        // Volume restoration can unmute: its original mute must already be recoverable.
        saveMute.set(snapshot.muted ? 1 : 0);
        saveVolume.set(snapshot.volume);
    }

    static Snapshot capture(BoolRead mute, IntRead lastAudible, IntRead current)
            throws Exception {
        boolean muted = mute.get();
        int volume;
        try {
            volume = lastAudible.get();
            if (volume < 0) throw new IllegalStateException("Invalid last-audible NAV volume");
        } catch (Exception unavailable) {
            if (muted) {
                throw new IllegalStateException(
                        "Cannot preserve muted NAV volume without last-audible index", unavailable);
            }
            volume = current.get();
            if (volume < 0) throw new IllegalStateException("Invalid current NAV volume");
        }
        return new Snapshot(volume, muted);
    }

    static boolean capAndPlay(IntWrite volume, IntRead readback,
            BooleanSupplier cancelled, Action play) throws Exception {
        if (cancelled.getAsBoolean()) return false;
        volume.set(1);
        if (readback.get() != 1) {
            throw new IllegalStateException("Exterior NAV cap was not applied");
        }
        if (cancelled.getAsBoolean()) return false;
        play.run();
        return true;
    }

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
