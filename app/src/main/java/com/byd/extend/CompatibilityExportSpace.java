package com.byd.extend;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/** Real-disk guard shared by temporary source capture and ZIP publication. */
final class CompatibilityExportSpace {
    static final long RESERVE_BYTES = 512L * 1024L * 1024L;
    static final long CHECK_INTERVAL_BYTES = 1024L * 1024L;

    interface Probe { long usableBytes(File directory); }

    static final class Guard {
        private final File directory;
        private final Probe probe;

        Guard(File directory) { this(directory, File::getUsableSpace); }

        Guard(File directory, Probe probe) {
            this.directory = directory;
            this.probe = probe;
        }

        void check(long additionalBytes) throws IOException {
            long usable = probe.usableBytes(directory);
            long required = additionalBytes > Long.MAX_VALUE - RESERVE_BYTES
                    ? Long.MAX_VALUE : RESERVE_BYTES + Math.max(0L, additionalBytes);
            if (usable < required) {
                throw new LowSpaceException(usable, required);
            }
        }
    }

    static final class CheckedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final Guard guard;
        private long bytesUntilCheck;
        private IOException failure;

        CheckedOutputStream(OutputStream delegate, Guard guard) throws IOException {
            this.delegate = delegate;
            this.guard = guard;
            guard.check(CHECK_INTERVAL_BYTES);
            bytesUntilCheck = CHECK_INTERVAL_BYTES;
        }

        @Override public void write(int value) throws IOException {
            ensureWritableBlock();
            try { delegate.write(value); }
            catch (IOException error) { failure = error; throw error; }
            bytesUntilCheck--;
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > value.length - length) {
                throw new IndexOutOfBoundsException();
            }
            int written = 0;
            while (written < length) {
                ensureWritableBlock();
                int part = (int) Math.min(length - written, bytesUntilCheck);
                try { delegate.write(value, offset + written, part); }
                catch (IOException error) { failure = error; throw error; }
                written += part;
                bytesUntilCheck -= part;
            }
        }

        private void ensureWritableBlock() throws IOException {
            if (bytesUntilCheck == 0L) {
                try { guard.check(CHECK_INTERVAL_BYTES); }
                catch (IOException error) { failure = error; throw error; }
                bytesUntilCheck = CHECK_INTERVAL_BYTES;
            }
        }

        @Override public void flush() throws IOException {
            try { delegate.flush(); }
            catch (IOException error) { failure = error; throw error; }
        }

        @Override public void close() throws IOException {
            try { delegate.close(); }
            catch (IOException error) { failure = error; throw error; }
        }

        void rethrowFailure() throws IOException {
            if (failure != null) throw failure;
        }
    }

    static final class LowSpaceException extends IOException {
        LowSpaceException(long usable, long required) {
            super("insufficient_space usable=" + usable + " required=" + required);
        }
    }

    static boolean isLowSpace(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof LowSpaceException) return true;
        }
        return false;
    }

    private CompatibilityExportSpace() { }
}
