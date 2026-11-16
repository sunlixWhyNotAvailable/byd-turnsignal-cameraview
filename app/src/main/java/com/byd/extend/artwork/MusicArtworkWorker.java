package com.byd.extend.artwork;

import android.content.AttributionSource;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import java.nio.file.Files;
import android.system.Os;
import android.system.StructStat;

/** Fixed artwork-only entry point executed in cellsservice_host's Android namespace. */
public final class MusicArtworkWorker {
    private static final String AUTH = "com.byd.mediacenter.provider";
    private static final Uri URI = Uri.parse("content://" + AUTH + "/info");

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0 || args.length != 5
                || !args[1].matches("[a-f0-9]{32}") || !args[4].matches("[a-f0-9]{32}")
                || args[1].equals(args[4])) throw new SecurityException("Invalid artwork executor identity");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        int port = Integer.parseInt(args[0]), user = Integer.parseInt(args[2]);
        if (port < 1 || port > 65535 || user < 0) throw new IllegalArgumentException("Invalid artwork endpoint/user");
        if (System.currentTimeMillis() > Long.parseLong(args[3])) throw new IOException("Artwork launch expired");
        String token = args[1];
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println("WORKER " + token);
            run(in, out, user, token, args[4]);
        }
        System.exit(0);
    }

    private static void run(BufferedReader in, PrintWriter out, int user, String token, String imageId) throws Exception {
        String prefix = imagePrefix(user, imageId);
        Provider provider = null;
        JSONObject original = null;
        String originalTrack = null;
        boolean touched = false, cleaned = true;
        try (RandomAccessFile lockFile = new RandomAccessFile("/data/local/tmp/byd-extend-artwork.lock", "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Previous artwork writer still owns the provider");
            try {
                provider = new Provider(user);
                event(out, "connected", "uid", android.os.Process.myUid(), "user", user,
                        "provider_uid", provider.uid);
                try { reapReleased(in, out, user, token, nullable(provider.query(), "cover")); }
                catch (Throwable secondary) { error(out, "orphan_cleanup_secondary", secondary); }
                while (true) {
                    JSONObject state = state(in, out);
                    if (!state.optBoolean("active")) break;
                    if (!state.has("track")) { Thread.sleep(500); continue; } // Superseded preparation is not missing artwork.
                    JSONObject row = provider.query();
                    String cover = nullable(state, "cover"), track = nullable(state, "track");
                    if (original != null && (row.getInt("device") != original.getInt("device")
                            || row.getInt("source") != 26)) break;
                    if (cover != null && !MusicArtworkPolicy.owns(prefix, cover))
                        throw new SecurityException("Artwork path outside this writer's image namespace");
                    if (cover == null && MusicArtworkPolicy.owns(prefix, nullable(row, "cover"))) {
                        JSONObject checked = provider.query();
                        if (checked.toString().equals(row.toString())) {
                            provider.update(row, "default");
                            verify(provider, row, "default");
                            event(out, "cleared", "reason", "no_current_image");
                        }
                    } else if (cover != null && !cover.equals(nullable(row, "cover")) && row.getInt("source") == 26) {
                        JSONObject confirmed = state(in, out), checked = provider.query();
                        if (confirmed.optBoolean("active") && row.toString().equals(checked.toString())
                                && MusicArtworkPolicy.mayPublish(row.getInt("device"), checked.getInt("device"),
                                checked.getInt("source"), track, nullable(confirmed, "track"), cover, nullable(confirmed, "cover"))) {
                            if (original == null) { original = row; originalTrack = track; }
                            touched = true;
                            int result = provider.update(checked, cover);
                            JSONObject readback = provider.query();
                            boolean sameOwner = readback.getInt("device") == checked.getInt("device")
                                    && readback.getInt("source") == checked.getInt("source");
                            event(out, "published", "cover", cover, "provider_result", result,
                                    "readback_matches", sameOwner && cover.equals(nullable(readback, "cover")),
                                    "same_owner", sameOwner, "displayed", readback.opt("cover"));
                        }
                    }
                    Thread.sleep(500); // OEM resets are not MediaSession callbacks; unchanged rows are never written.
                }
            } catch (Throwable failure) {
                error(out, "provider_relay", failure);
            } finally {
                if (touched && original != null && provider != null) {
                    try {
                        String track = null;
                        try { track = nullable(state(in, out), "track"); }
                        catch (Throwable unavailable) { error(out, "restore_track_identity", unavailable); }
                        JSONObject current = provider.query();
                        MusicArtworkPolicy.Restore action = MusicArtworkPolicy.restore(
                                original.getInt("device"), original.getInt("source"), originalTrack,
                                current.getInt("device"), current.getInt("source"), nullable(current, "cover"), prefix, track);
                        if (action != MusicArtworkPolicy.Restore.LEAVE) {
                            if (!provider.query().toString().equals(current.toString()))
                                throw new IOException("Provider changed during artwork release");
                            String restore = action == MusicArtworkPolicy.Restore.ORIGINAL ? nullable(original, "cover") : "default";
                            provider.update(current, restore);
                            verify(provider, current, restore);
                        } else if (current.getInt("device") != original.getInt("device")) {
                            throw new IOException("Original speaker row is not visible; retaining its images");
                        }
                    } catch (Throwable failure) { cleaned = false; error(out, "restore_secondary", failure); }
                }
                if (provider != null) try { provider.close(); }
                catch (Throwable failure) { error(out, "provider_release_secondary", failure); }
            }
        } catch (Throwable failure) { error(out, "worker", failure); }
        if (cleaned) try {
            Properties release = new Properties();
            release.setProperty("user", Integer.toString(user));
            release.setProperty("image_id", imageId);
            release.setProperty("pid", Integer.toString(android.os.Process.myPid()));
            try (FileOutputStream file = new FileOutputStream("/data/local/tmp/byd-extend-artwork-" + token + "/released.properties")) {
                release.store(file, "provider release confirmed"); file.getFD().sync();
            }
        } catch (Throwable secondary) { error(out, "release_receipt_secondary", secondary); }
        event(out, "finished", "cleaned", cleaned);
        try {
            if ("ACK".equals(in.readLine()) && cleaned) {
                try (FileOutputStream complete = new FileOutputStream("/data/local/tmp/byd-extend-artwork-" + token + "/complete")) {
                    complete.write(1);
                }
            }
        } catch (IOException failure) { failure.printStackTrace(); }
    }

    public static String imagePrefix(int user, String token) {
        return "/storage/emulated/" + user + "/Download/.byd-extend-artwork/" + token + "/";
    }

    private static void reapReleased(BufferedReader in, PrintWriter out, int user, String token, String currentCover) throws Exception {
        File[] sessions = new File("/data/local/tmp").listFiles();
        if (sessions == null) throw new IOException("Cannot enumerate artwork executors");
        int removed = 0, retained = 0;
        for (File session : sessions) {
            if (!session.getName().matches("byd-extend-artwork-[a-f0-9]{32}") || session.getName().endsWith(token)) continue;
            retained++;
            try {
                StructStat owner = Os.lstat(session.getPath());
                if (owner.st_uid != 0 || !android.system.OsConstants.S_ISDIR(owner.st_mode) || (owner.st_mode & 0022) != 0) continue;
                File receipt = new File(session, "released.properties");
                if (!receipt.exists()) continue; // Older or uncertain sessions are deliberately retained.
                ArtworkFiles.regular(receipt);
                if (Files.size(receipt.toPath()) > 2048) throw new IOException("Oversized artwork release receipt");
                Properties release = new Properties();
                try (FileInputStream file = new FileInputStream(receipt)) { release.load(file); }
                String imageId = release.getProperty("image_id", "");
                int ownerUser = Integer.parseInt(release.getProperty("user"));
                int pid = Integer.parseInt(release.getProperty("pid"));
                if (ownerUser != user || pid <= 0 || !imageId.matches("[a-f0-9]{32}")) continue;
                if (processExists(pid) || MusicArtworkPolicy.owns(imagePrefix(user, imageId), currentCover)) continue;
                event(out, "cleanup_request", "user", user, "image_id", imageId);
                String line = in.readLine();
                if (line == null) throw new EOFException("Artwork broker disconnected during orphan cleanup");
                JSONObject reply = new JSONObject(line);
                if (!imageId.equals(reply.optString("image_id"))) throw new IOException("Artwork cleanup acknowledgement identity mismatch");
                if (!reply.optBoolean("cleaned")) continue;
                ArtworkFiles.deleteReleasedWorker(session);
                removed++; retained--;
            } catch (Throwable secondary) { error(out, "orphan_session_secondary", secondary); }
        }
        if (removed != 0 || retained != 0) event(out, "cleanup_result", "removed", removed, "retained", retained);
    }

    private static boolean processExists(int pid) throws android.system.ErrnoException {
        try { Os.stat("/proc/" + pid); return true; }
        catch (android.system.ErrnoException failure) {
            if (failure.errno == android.system.OsConstants.ENOENT) return false;
            throw failure;
        }
    }

    private static JSONObject state(BufferedReader in, PrintWriter out) throws Exception {
        event(out, "state_request");
        String reply = in.readLine();
        if (reply == null) throw new EOFException("Artwork owner disconnected");
        JSONObject state = new JSONObject(reply);
        if (state.has("error")) throw new IOException("Artwork owner: " + state.getString("error"));
        return state;
    }

    private static void verify(Provider provider, JSONObject expected, String cover) throws Exception {
        JSONObject readback = provider.query();
        // OEM returns zero even for successful writes. Verify the actual row.
        if (readback.getInt("device") != expected.getInt("device")
                || readback.getInt("source") != expected.getInt("source")
                || !Objects.equals(cover, nullable(readback, "cover")))
            throw new IOException("Artwork readback changed: device=" + readback.optInt("device")
                    + ", source=" + readback.optInt("source") + ", cover_matches=" + Objects.equals(cover, nullable(readback, "cover")));
    }

    private static String nullable(JSONObject object, String key) { return object.isNull(key) ? null : object.optString(key, null); }
    private static void event(PrintWriter out, String kind, Object... fields) throws Exception {
        JSONObject value = new JSONObject().put("event", kind);
        for (int i = 0; i < fields.length; i += 2) value.put(fields[i].toString(), fields[i + 1]);
        out.println(value);
    }
    private static void error(PrintWriter out, String operation, Throwable failure) throws Exception {
        StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
        System.err.println(operation + ": " + trace);
        event(out, "error", "operation", operation, "stack", trace.toString());
    }
    private static Object invoke(Object target, Class<?> type, String method, Class<?>[] types, Object... args) throws Exception {
        try { return type.getMethod(method, types).invoke(target, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }

    private static final class Provider implements Closeable {
        final Object am, provider;
        final Class<?> ami = Class.forName("android.app.IActivityManager"), pi = Class.forName("android.content.IContentProvider");
        final IBinder token = new Binder();
        final AttributionSource attribution = new AttributionSource.Builder(android.os.Process.myUid()).build();
        final int user, uid;
        Provider(int user) throws Exception {
            this.user = user;
            am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Object holder = invoke(am, ami, "getContentProviderExternal", new Class<?>[]{String.class, int.class, IBinder.class, String.class},
                    AUTH, user, token, "byd-extend-artwork");
            if (holder == null) throw new IOException("Media provider absent in executor Android/user=" + user);
            try {
                provider = holder.getClass().getField("provider").get(holder);
                if (provider == null) throw new IOException("Media provider holder has no Binder");
                android.content.pm.ProviderInfo info = (android.content.pm.ProviderInfo) holder.getClass().getField("info").get(holder);
                uid = info.applicationInfo.uid;
            } catch (Exception failure) {
                try { close(); } catch (IOException secondary) { failure.addSuppressed(secondary); }
                throw failure;
            }
        }
        JSONObject query() throws Exception {
            try (Cursor cursor = (Cursor) invoke(provider, pi, "query",
                    new Class<?>[]{AttributionSource.class, Uri.class, String[].class, Bundle.class, Class.forName("android.os.ICancellationSignal")},
                    attribution, URI, new String[]{"device", "source", "cover"}, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("No active-speaker row");
                if (cursor.isNull(cursor.getColumnIndexOrThrow("device")) || cursor.isNull(cursor.getColumnIndexOrThrow("source")))
                    throw new IOException("Active-speaker row lacks device/source");
                JSONObject row = new JSONObject().put("device", cursor.getInt(cursor.getColumnIndexOrThrow("device")))
                        .put("source", cursor.getInt(cursor.getColumnIndexOrThrow("source")))
                        .put("cover", cursor.isNull(cursor.getColumnIndexOrThrow("cover")) ? JSONObject.NULL : cursor.getString(cursor.getColumnIndexOrThrow("cover")));
                if (cursor.moveToNext()) throw new IOException("Ambiguous media provider rows");
                return row;
            }
        }
        int update(JSONObject row, String cover) throws Exception {
            ContentValues values = new ContentValues();
            values.put("device", row.getInt("device")); values.put("source", row.getInt("source"));
            if (cover == null) values.putNull("cover"); else values.put("cover", cover);
            return (Integer) invoke(provider, pi, "update", new Class<?>[]{AttributionSource.class, Uri.class, ContentValues.class, Bundle.class}, attribution, URI, values, null);
        }
        @Override public void close() throws IOException {
            try { invoke(am, ami, "removeContentProviderExternalAsUser", new Class<?>[]{String.class, IBinder.class, int.class}, AUTH, token, user); }
            catch (Exception failure) { throw new IOException("Release media provider", failure); }
        }
    }
}
