package com.byd.extend;

import com.byd.extend.artwork.ArtworkFiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ArtworkFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File image(File directory, char digit) throws IOException {
        return Files.write(new File(directory, String.valueOf(digit).repeat(64) + ".png").toPath(), new byte[]{1}).toFile();
    }
    @Test public void RetainedImagesSurvivePruningAndReleasedDirectoryIsRemoved() throws Exception {
        File session = temporary.newFolder("images");
        File current = image(session, 'a'), previous = image(session, 'b'), old = image(session, 'c');
        ArtworkFiles.pruneImages(session, false, current.getPath(), previous.getPath());
        assertTrue(current.exists()); assertTrue(previous.exists()); assertFalse(old.exists());
        ArtworkFiles.pruneImages(session, true);
        assertFalse(session.exists());
        ArtworkFiles.pruneImages(session, true); // Interrupted cleanup is repeatable.
    }
    @Test public void UnknownFilesAndSubdirectoriesPreventDeletion() throws Exception {
        File session = temporary.newFolder("uncertain");
        File cover = image(session, 'a');
        File unknown = Files.write(new File(session, "unknown").toPath(), new byte[]{2}).toFile();
        assertThrows(IOException.class, () -> ArtworkFiles.pruneImages(session, true));
        assertTrue(cover.exists()); assertTrue(unknown.exists());
        Files.delete(unknown.toPath());
        Files.createDirectory(new File(session, "b".repeat(64) + ".png").toPath());
        assertThrows(IOException.class, () -> ArtworkFiles.pruneImages(session, true));
        assertTrue(cover.exists());
    }
    @Test public void ExecutorCleanupRemovesOnlyKnownFlatFiles() throws Exception {
        File session = temporary.newFolder("executor");
        for (String name : new String[]{"worker.jar", "bootstrap.log", "released.properties", "complete"})
            Files.write(new File(session, name).toPath(), new byte[]{1});
        ArtworkFiles.deleteReleasedWorker(session);
        assertFalse(session.exists());
    }
    @Test public void SymbolicImageDoesNotDeleteItsTarget() throws Exception {
        File session = temporary.newFolder("linked");
        File target = temporary.newFile("keep.png");
        File link = new File(session, "d".repeat(64) + ".png");
        try { Files.createSymbolicLink(link.toPath(), target.toPath()); }
        catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.Assume.assumeNoException("Host does not permit symbolic links", unavailable);
        }
        assertThrows(IOException.class, () -> ArtworkFiles.pruneImages(session, true));
        assertTrue(target.exists());
        assertTrue(Files.isSymbolicLink(link.toPath()));
    }
}
