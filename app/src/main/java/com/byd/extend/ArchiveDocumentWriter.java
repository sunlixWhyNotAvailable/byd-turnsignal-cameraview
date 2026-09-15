package com.byd.extend;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/** Streams a completed ZIP unchanged, without loading the archive into memory. */
final class ArchiveDocumentWriter {
    private ArchiveDocumentWriter() {}

    static File restore(File cacheDirectory, String name) {
        if (name == null || name.isEmpty() || !name.equals(new File(name).getName())
                || !name.endsWith(".zip")) return null;
        File archive = new File(new File(cacheDirectory, "shared_logs"), name);
        return archive.isFile() ? archive : null;
    }

    static long copy(File archive, OutputStream output) throws IOException {
        return copy(archive, output, null);
    }

    static long copy(File archive, OutputStream output,
            CompatibilityBundleExporter.ExportControl control) throws IOException {
        if (output == null) throw new IOException("Document provider did not open an output stream");
        long expected = archive.length();
        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        CompatibilityBundleExporter.ProgressReporter progress =
                new CompatibilityBundleExporter.ProgressReporter(control);
        progress.report(CompatibilityBundleExporter.Progress.Phase.REMOTE,
                archive.getName(), 0, 0, 0, expected, true);
        try (FileInputStream input = new FileInputStream(archive)) {
            while (true) {
                CompatibilityBundleExporter.checkCancelled(control);
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Archive save interrupted");
                }
                int count = input.read(buffer);
                if (count < 0) break;
                output.write(buffer, 0, count);
                written += count;
                progress.report(CompatibilityBundleExporter.Progress.Phase.REMOTE,
                        archive.getName(), 0, 0, written, expected, false);
            }
        }
        if (written != expected) throw new IOException("Source archive changed during save");
        output.flush();
        CompatibilityBundleExporter.checkCancelled(control);
        progress.report(CompatibilityBundleExporter.Progress.Phase.ZIP,
                archive.getName(), 0, 0, written, expected, true);
        return written;
    }
}
