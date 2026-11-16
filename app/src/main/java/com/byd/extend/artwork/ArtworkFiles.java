package com.byd.extend.artwork;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Deletes only known files in an owned session; never traverses links or subdirectories. */
public final class ArtworkFiles {
    private ArtworkFiles() {}

    public static void pruneImages(File directory, boolean released, String... retained) throws IOException {
        if (Files.isSymbolicLink(directory.getParentFile().toPath()))
            throw new IOException("Artwork image root is a symbolic link");
        File[] files = entries(directory);
        if (files == null) return;
        Set<String> keep = new HashSet<>(Arrays.asList(retained));
        for (File file : files) {
            if (!file.getName().matches("[a-f0-9]{64}\\.png"))
                throw new IOException("Unexpected file in artwork image directory");
            regular(file);
        }
        for (File file : files) if (released || !keep.contains(file.getPath())) Files.deleteIfExists(file.toPath());
        if (released) Files.deleteIfExists(directory.toPath());
    }

    public static void deleteReleasedWorker(File directory) throws IOException {
        File[] files = entries(directory);
        if (files == null) return;
        Set<String> known = new HashSet<>(Arrays.asList("worker.jar", "bootstrap.log", "complete", "released.properties"));
        for (File file : files) {
            if (!known.contains(file.getName())) throw new IOException("Unexpected file in artwork executor directory");
            regular(file);
        }
        // Keep the receipt until the other files have been removed, so interrupted cleanup can resume.
        for (File file : files) if (!file.getName().equals("released.properties")) Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(new File(directory, "released.properties").toPath());
        Files.deleteIfExists(directory.toPath());
    }

    private static File[] entries(File directory) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return null; }
        if (!attributes.isDirectory())
            throw new IOException("Artwork session is not a directory");
        java.util.List<File> files = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = Files.newDirectoryStream(directory.toPath())) {
            for (java.nio.file.Path file : stream) files.add(file.toFile());
        }
        return files.toArray(new File[0]);
    }

    public static void regular(File file) throws IOException {
        if (!Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
            throw new IOException("Artwork session contains a non-regular file");
    }
}
