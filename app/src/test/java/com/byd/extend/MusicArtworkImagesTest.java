package com.byd.extend;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MusicArtworkImagesTest {
    @Test public void disabledOutputsDoNotReadImagesAndArtworkOnlyDoesNotLookUpPlayerIcon() {
        Bitmap cover = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        android.media.MediaMetadata metadata = new android.media.MediaMetadata.Builder()
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, cover).build();
        MusicArtworkImages images = new MusicArtworkImages(org.robolectric.RuntimeEnvironment.getApplication(),
                (operation, error) -> fail(operation + ": " + error));
        assertSame(cover, images.image("nonexistent.player", metadata, -1, true, false));
        assertNull(images.image("nonexistent.player", new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_ART_URI, "file:///private/cover.png")
                .build(), -1, false, false));
    }
    @Test public void EmbeddedBitmapDoesNotRequirePrivilegedUriAccess() {
        Bitmap cover = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        android.media.MediaMetadata metadata = new android.media.MediaMetadata.Builder()
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, cover).build();
        MusicArtworkImages images = new MusicArtworkImages(org.robolectric.RuntimeEnvironment.getApplication(),
                (operation, error) -> fail(operation + ": " + error));
        assertSame(cover, images.artwork("player", metadata, -1));
    }
    @Test public void FileAndCrossUserUrisAreRejectedBeforeResolverAccess() {
        for (String uri : new String[]{"file:///private/cover.png", "content://10@media/cover", "content:/missing"})
            assertThrows(SecurityException.class, () -> MusicArtworkImages.validateUri(android.net.Uri.parse(uri)));
        MusicArtworkImages.validateUri(android.net.Uri.parse("content://media/cover"));
        java.util.List<Throwable> errors = new java.util.ArrayList<>();
        MusicArtworkImages images = new MusicArtworkImages(org.robolectric.RuntimeEnvironment.getApplication(),
                (operation, error) -> errors.add(error));
        assertNull(images.artwork("player", new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_ART_URI, "file:///private/cover.png").build(), -1));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains("SecurityException"));
        assertFalse(errors.get(0).getMessage().contains("/private/cover.png"));
    }
    @Test public void rectangularCoverIsSquaredBeforeAddingUndistortedBadge() {
        Bitmap cover = Bitmap.createBitmap(480, 269, Bitmap.Config.ARGB_8888);
        cover.eraseColor(Color.BLUE);
        Bitmap icon = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888);
        icon.eraseColor(Color.RED);
        Bitmap composed = MusicArtworkImages.compose(cover, icon);
        assertEquals(480, composed.getWidth());
        assertEquals(480, composed.getHeight());
        assertEquals(Color.BLUE, composed.getPixel(0, 479));
        assertEquals(Color.BLUE, composed.getPixel(332, 390));
        assertEquals(Color.RED, composed.getPixel(334, 334));
        assertEquals(Color.RED, composed.getPixel(451, 451));
        assertEquals(Color.BLUE, composed.getPixel(454, 390));
        assertEquals(Color.BLUE, composed.getPixel(390, 454));
    }

    @Test public void iconFallbackHasNoBadgeAndIconFailureKeepsPlainCover() {
        Bitmap cover = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        Bitmap icon = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        assertSame(icon, MusicArtworkImages.compose(null, icon));
        assertSame(cover, MusicArtworkImages.compose(cover, null));
        assertNull(MusicArtworkImages.compose(null, null));
    }

    @Test public void cleanArtworkReadsCurrentMetadataWithoutReusingTheComposedBadge() {
        MusicArtworkImages images = new MusicArtworkImages(org.robolectric.RuntimeEnvironment.getApplication(),
                (operation, error) -> fail(operation + ": " + error));
        Bitmap first = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        first.eraseColor(Color.BLUE);
        Bitmap current = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        current.eraseColor(Color.GREEN);
        Bitmap icon = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        icon.eraseColor(Color.RED);
        MusicArtworkImages.compose(first, icon);
        Bitmap restored = images.artwork("player", new android.media.MediaMetadata.Builder()
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, current).build(), -1);
        assertSame(current, restored);
        assertEquals(Color.GREEN, restored.getPixel(3, 3));
        assertEquals(Color.BLUE, first.getPixel(3, 3));
    }
}
