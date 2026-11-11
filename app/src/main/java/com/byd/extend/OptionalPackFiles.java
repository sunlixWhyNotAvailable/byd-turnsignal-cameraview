package com.byd.extend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Same verified, atomic installation in app-private storage and shell-owned AVAS storage. */
public final class OptionalPackFiles {
    private OptionalPackFiles() {}
    public static File directory(File root, OptionalPackCatalog.Pack pack) {
        return new File(root, pack.key());
    }
    public static File archive(File root, OptionalPackCatalog.Pack pack) {
        return new File(directory(root, pack), "package.zip");
    }
    public static File content(File root, OptionalPackCatalog.Pack pack) {
        return new File(directory(root, pack), "content");
    }
    public static boolean ready(File root, OptionalPackCatalog.Pack pack) {
        File dir = directory(root, pack);
        File marker = new File(dir, "verified");
        if (!marker.isFile()) return false;
        try {
            if (marker.length() != 64 || !pack.sha256.equals(new String(Files.readAllBytes(marker.toPath()),
                    StandardCharsets.US_ASCII))) return false;
            if (archive(root, pack).length() != pack.bytes) return false;
            File content = content(root, pack);
            for (int i = 0; i < pack.files.length; i++) {
                File file = new File(content, pack.files[i]);
                if (!file.isFile() || file.length() != pack.lengths[i]) return false;
            }
            return true;
        } catch (IOException failure) { throw new UncheckedIOException("Cannot read pack receipt: " + pack.key(), failure); }
    }

    /** Caller owns/serializes installs; input ownership stays with the caller. */
    public static void install(File root, OptionalPackCatalog.Pack pack, InputStream input,
            BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(root.toPath());
        cleanup(root, pack);
        Path stage = Files.createTempDirectory(root.toPath(), "." + pack.key() + "-");
        Throwable primary = null;
        try {
            File zip = stage.resolve("package.zip").toFile();
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
            long total = 0;
            byte[] buffer = new byte[32768];
            try (FileOutputStream output = new FileOutputStream(zip)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    total += count;
                    if (total > pack.bytes) throw new IOException("Pack exceeds pinned byte size: " + pack.key());
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (total != pack.bytes || !pack.sha256.contentEquals(hash))
                throw new IOException("Pack size/SHA-256 mismatch: " + pack.key());
            File content = stage.resolve("content").toFile();
            Files.createDirectory(content.toPath());
            Set<String> seen = new HashSet<>();
            long unpacked = 0;
            try (ZipFile archive = new ZipFile(zip)) {
                Enumeration<? extends ZipEntry> entries = archive.entries();
                while (entries.hasMoreElements()) {
                    checkCancelled(cancelled);
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    int index = Arrays.asList(pack.files).indexOf(name);
                    if (entry.isDirectory() || index < 0 || !seen.add(name)
                            || name.contains("/") || name.contains("\\") || name.contains(".."))
                        throw new IOException("Unexpected pack entry: " + name);
                    long length = 0;
                    try (InputStream source = archive.getInputStream(entry);
                         FileOutputStream output = new FileOutputStream(new File(content, name))) {
                        int count;
                        while ((count = source.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            length += count;
                            if (length > pack.lengths[index]) throw new IOException("Oversized entry: " + name);
                            output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                    if (length != pack.lengths[index]) throw new IOException("Truncated entry: " + name);
                    unpacked += length;
                }
            }
            if (seen.size() != pack.files.length || unpacked != pack.unpackedBytes)
                throw new IOException("Incomplete pack: " + pack.key());
            try (FileOutputStream output = new FileOutputStream(stage.resolve("verified").toFile())) {
                output.write(pack.sha256.getBytes(StandardCharsets.US_ASCII));
                output.getFD().sync();
            }
            checkCancelled(cancelled);
            // Never replace a good revision; readers can keep its files open throughout a retry.
            if (!ready(root, pack)) {
                remove(directory(root, pack).toPath());
                Files.move(stage, directory(root, pack).toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException | RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            try { remove(stage); }
            catch (IOException cleanup) {
                if (primary != null) primary.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Pack installation cancelled");
    }
    /** Only call on the install owner thread, never while another install is using this root. */
    static void cleanup(File root, OptionalPackCatalog.Pack pack) throws IOException {
        if (!root.isDirectory()) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.toPath(), "." + pack.key() + "-*")) {
            for (Path entry : entries) remove(entry);
        }
    }
    private static void remove(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) remove(child);
            }
        }
        Files.delete(path);
    }
}
