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
}
