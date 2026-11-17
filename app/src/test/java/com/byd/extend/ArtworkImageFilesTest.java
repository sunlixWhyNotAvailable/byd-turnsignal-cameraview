package com.byd.extend;

import com.byd.extend.artwork.ArtworkFiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ArtworkImageFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void imageWriteReopensAndReusesTheSameContentWithoutTemporaryFiles() throws Exception {
        File directory = new File(temporary.newFolder(), "session");
        byte[] content = new byte[]{1, 7, 9};
        File image = ArtworkFiles.writeImage(directory, content);
        long modified = image.lastModified();
        assertArrayEquals(content, Files.readAllBytes(image.toPath()));
        assertEquals(image, ArtworkFiles.writeImage(directory, content));
        assertEquals(modified, image.lastModified());
        assertArrayEquals(new String[]{image.getName()}, directory.list());
    }

    @Test public void cleanupKeepsRestoredAndUnconfirmedImagesUntilExplicitRelease() throws Exception {
        File directory = new File(temporary.newFolder(), "session");
        File stale = ArtworkFiles.writeImage(directory, new byte[]{1});
        File previous = ArtworkFiles.writeImage(directory, new byte[]{2});
        File restored = ArtworkFiles.writeImage(directory, new byte[]{3});
        ArtworkFiles.pruneImages(directory, false, previous.getPath(), restored.getPath());
        assertFalse(stale.exists());
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(previous.toPath()));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(restored.toPath()));
        ArtworkFiles.pruneImages(directory, true);
        assertFalse(directory.exists());
    }

    @Test public void existingImageWithModifiedBytesIsRejected() throws Exception {
        File directory = new File(temporary.newFolder(), "session");
        File image = ArtworkFiles.writeImage(directory, new byte[]{1});
        Files.write(image.toPath(), new byte[]{2});
        assertThrows(IOException.class, () -> ArtworkFiles.writeImage(directory, new byte[]{1}));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(image.toPath()));
    }
}
