package com.byd.extend;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.UserHandle;
import com.byd.extend.artwork.MusicArtworkPolicy;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.io.InputStream;
import java.util.function.BiConsumer;

/** Image work stays on the artwork broker, never the metadata/lighting handler. */
final class MusicArtworkImages {
    static final int SIDE = 480;
    private final Context context;
    private final BiConsumer<String, Throwable> errors;
    private boolean fontsReady;
    private String iconPackage;
    private int iconUid = -1;
    private Bitmap icon;
    boolean iconFallback;

    MusicArtworkImages(Context context, BiConsumer<String, Throwable> errors) {
        this.context = context; this.errors = errors;
    }

    Bitmap image(String packageName, MediaMetadata metadata, int sessionUid,
            boolean showArtwork, boolean showPlayerIcon) {
        Bitmap cover = showArtwork ? artwork(packageName, metadata, sessionUid) : null;
        iconFallback = cover == null;
        if (!showPlayerIcon) return cover;
        try {
            if (!fontsReady) { CameraShellMain.initializeSystemFontsForShell(); fontsReady = true; }
            if (!packageName.equals(iconPackage) || iconUid != sessionUid) {
                icon = null;
                Context playerContext = userContext(sessionUid);
                ApplicationInfo info = playerContext.getPackageManager().getApplicationInfo(packageName, 0);
                if (info.icon != 0) {
                    // BYD's icon decoration requires an Application context absent in the shell helper.
                    Drawable drawable = playerContext.getPackageManager().getResourcesForApplication(info)
                            .getDrawable(info.icon, null);
                    Bitmap rendered = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
                    drawable.setBounds(0, 0, SIDE, SIDE);
                    drawable.draw(new Canvas(rendered));
                    icon = rendered;
                }
                iconPackage = packageName;
                iconUid = sessionUid;
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

    Bitmap artwork(String packageName, MediaMetadata metadata, int sessionUid) {
        if (metadata == null) return null;
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            try { Bitmap bitmap = metadata.getBitmap(key); if (bitmap != null) return bitmap; }
            catch (Throwable failure) { errors.accept("artwork_" + key, failure); }
        }
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String value = null;
            try {
                value = metadata.getString(key);
                if (value == null || value.isEmpty()) continue;
                Uri uri = Uri.parse(value);
                validateUri(uri);
                Context reader = userContext(sessionUid);
                ApplicationInfo player = reader.getPackageManager().getApplicationInfo(packageName, 0);
                if (player.uid != sessionUid) throw new SecurityException("MediaSession/package UID mismatch");
                ProviderInfo provider = reader.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
                if (provider == null || provider.applicationInfo == null)
                    throw new java.io.IOException("Artwork provider unavailable");
                if (!UserHandle.getUserHandleForUid(provider.applicationInfo.uid).equals(UserHandle.getUserHandleForUid(sessionUid)))
                    throw new SecurityException("Artwork provider belongs to another Android user");
                // This deliberately excludes access based solely on general provider permissions.
                if (provider.applicationInfo.uid != sessionUid && reader.checkUriPermission(uri, -1, sessionUid,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("Player has neither provider ownership nor explicit URI read grant");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream input = reader.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(input, null, options);
                }
                options.inJustDecodeBounds = false;
                while (Math.max(options.outWidth, options.outHeight) / Math.max(1, options.inSampleSize) > SIDE * 2)
                    options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
                try (InputStream input = reader.getContentResolver().openInputStream(uri)) {
                    Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
                    if (decoded != null) return decoded;
                    throw new java.io.IOException("Local artwork decode returned null: " + key);
                }
            } catch (Throwable failure) {
                // Retain the full diagnostic chain, but never persist a private URI path/query.
                errors.accept("artwork_uri_" + key, new java.io.IOException(
                        CameraViewingDiagnostics.describe(failure, value == null ? "" : value)));
            }
        }
        return null;
    }

    static void validateUri(Uri uri) {
        if (!"content".equals(uri.getScheme())) throw new SecurityException("Artwork requires a content URI");
        String authority = uri.getAuthority();
        if (authority == null || authority.isEmpty() || authority.contains("@"))
            throw new SecurityException("Artwork requires an unqualified provider in the session's Android user");
    }

    private Context userContext(int uid) throws Exception {
        if (uid < 0) throw new SecurityException("MediaSession UID unavailable");
        return (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class)
                .invoke(context, UserHandle.getUserHandleForUid(uid), 0);
    }
}
