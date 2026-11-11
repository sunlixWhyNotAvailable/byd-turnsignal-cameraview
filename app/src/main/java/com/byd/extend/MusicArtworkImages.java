package com.byd.extend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.net.Uri;
import com.byd.extend.artwork.MusicArtworkPolicy;
import java.io.InputStream;
import java.util.function.BiConsumer;

/** Image work stays on the artwork broker, never the metadata/lighting handler. */
final class MusicArtworkImages {
    static final int SIDE = 480;
    private final Context context;
    private final BiConsumer<String, Throwable> errors;
    private boolean fontsReady;
    private String iconPackage;
    private Bitmap icon;
    boolean iconFallback;

    MusicArtworkImages(Context context, BiConsumer<String, Throwable> errors) {
        this.context = context; this.errors = errors;
    }

    Bitmap image(String packageName, MediaMetadata metadata) {
        Bitmap cover = artwork(metadata);
        iconFallback = cover == null;
        try {
            if (!fontsReady) { CameraShellMain.initializeSystemFontsForShell(); fontsReady = true; }
            if (!packageName.equals(iconPackage)) {
                icon = null;
                android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
                if (info.icon != 0) {
                    Drawable drawable = context.getPackageManager().getApplicationIcon(info);
                    Bitmap rendered = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
                    drawable.setBounds(0, 0, SIDE, SIDE);
                    drawable.draw(new Canvas(rendered));
                    icon = rendered;
                }
                iconPackage = packageName;
            }
        } catch (Throwable failure) {
            errors.accept("player_icon", failure);
        }
        if (!fontsReady || icon == null) return cover;
        try { return compose(cover, icon); }
        catch (Throwable failure) { errors.accept("compose_cover", failure); return cover; }
    }

    static Bitmap compose(Bitmap cover, Bitmap icon) {
        if (cover == null) return icon; // The player icon IS the cover; no duplicate badge.
        if (icon == null) return cover;
        Bitmap result = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // Match OEM Stretch once, before adding a square badge (also for rectangular artwork).
        canvas.drawBitmap(cover, null, new Rect(0, 0, SIDE, SIDE), paint);
        int edge = SIDE - MusicArtworkPolicy.badgeInset(SIDE);
        int size = MusicArtworkPolicy.badgeSize(SIDE);
        canvas.drawBitmap(icon, null, new Rect(edge - size, edge - size, edge, edge), paint);
        return result;
    }

    private Bitmap artwork(MediaMetadata metadata) {
        if (metadata == null) return null;
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            try { Bitmap bitmap = metadata.getBitmap(key); if (bitmap != null) return bitmap; }
            catch (Throwable failure) { errors.accept("artwork_" + key, failure); }
        }
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            try {
                String value = metadata.getString(key);
                if (value == null || value.isEmpty()) continue;
                Uri uri = Uri.parse(value);
                if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) continue;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(input, null, options);
                }
                options.inJustDecodeBounds = false;
                while (Math.max(options.outWidth, options.outHeight) / Math.max(1, options.inSampleSize) > SIDE * 2)
                    options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
                    if (decoded != null) return decoded;
                    throw new java.io.IOException("Local artwork decode returned null: " + key);
                }
            } catch (Throwable failure) { errors.accept("artwork_uri_" + key, failure); }
        }
        return null;
    }
}
