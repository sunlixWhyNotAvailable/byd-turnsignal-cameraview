package com.byd.extend;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.system.Os;
import android.util.Base64;
import com.byd.extend.artwork.MusicArtworkPolicy;
import com.byd.extend.artwork.MusicArtworkWorker;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Optional artwork path. It never runs OEM/bitmap/file operations on the shared music handler. */
final class MusicArtworkRuntime {
    private final Context context;
    private final BiConsumer<String, Object[]> events;
    private final Map<String, String> failures = new HashMap<>();
    private volatile Selection selected;
    private boolean running, failed;
    private long revision;
    private volatile Thread launcher;

    MusicArtworkRuntime(Context context, BiConsumer<String, Object[]> events) {
        this.context = context; this.events = events;
    }

    synchronized void reset() { failed = false; failures.clear(); }

    synchronized void metadataChanged(MediaController controller, MediaMetadata metadata) {
        if (selected != null && selected.controller == controller) update(controller, metadata);
    }

    synchronized void removed(MediaController controller) {
        if (selected != null && selected.controller == controller) update(null, null);
    }

    synchronized void update(MediaController controller, MediaMetadata metadata) {
        if (selected == null ? controller != null : selected.controller != controller || selected.metadata != metadata) {
            selected = controller == null ? null : new Selection(controller, metadata);
            revision++;
        }
        if (controller == null || running || failed) return;
        running = true;
        long startRevision = revision;
        Thread broker = new Thread(() -> run(startRevision), "byd-music-artwork");
        broker.setDaemon(true); broker.start();
    }

    private void run(long startRevision) {
        try {
            if (android.os.Process.myUid() != 2000) throw new SecurityException("Artwork broker requires shell UID2000");
            if (launcher != null && launcher.isAlive()) throw new IOException("Previous cellsservice_host launch is still pending");
            relay();
        } catch (Throwable failure) {
            error("artwork_session", failure);
            synchronized (this) { failed = true; }
        } finally {
            synchronized (this) {
                running = false;
                if (!failed && selected != null && revision != startRevision)
                    update(selected.controller, selected.metadata);
            }
        }
    }

    private void relay() throws Exception {
        Context shell = TurnSignalShellMain.shellContext(context);
        int user = (Integer) Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        String token = UUID.randomUUID().toString().replace("-", "");
        String imageId = UUID.randomUUID().toString().replace("-", "");
        String prefix = MusicArtworkWorker.imagePrefix(user, imageId);
        File directory = new File(prefix);
        Images images = new Images(shell, directory);
        boolean launched = false, finished = false;
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            JSONObject first = images.state();
            if (!first.optBoolean("active")) return;
            Context owner = context.createPackageContext("com.byd.extend", 0);
            byte[] dex;
            try (InputStream asset = owner.getAssets().open("music-artwork/worker.jar")) { dex = readWorker(asset); }
            String command = bootstrap(dex, token, imageId, user, server.getLocalPort(), System.currentTimeMillis() + 30_000);
            AtomicReference<Throwable> launchFailure = new AtomicReference<>();
            launcher = new Thread(() -> {
                try { cells(command); }
                catch (Throwable failure) { launchFailure.set(failure); }
            }, "byd-artwork-oem-launch");
            launcher.setDaemon(true); launcher.start(); launched = true;
            server.setSoTimeout(500);
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (launchFailure.get() != null) throw new IOException("cellsservice_host artwork launch", launchFailure.get());
                Socket connection;
                try { connection = server.accept(); } catch (SocketTimeoutException waiting) { continue; }
                try (Socket socket = connection) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    String hello = input.readLine();
                    if (("BOOT " + token).equals(hello)) {
                        StringBuilder detail = new StringBuilder(); String line;
                        while ((line = input.readLine()) != null && detail.length() < 32768) detail.append(line).append('\n');
                        throw new IOException("Artwork executor bootstrap: " + detail);
                    }
                    if (!("WORKER " + token).equals(hello)) continue;
                    events.accept("music_artwork_started", new Object[]{"broker_uid", android.os.Process.myUid(), "user", user});
                    boolean workerFailed = false;
                    while (true) {
                        String line = input.readLine();
                        if (line == null) throw new EOFException("Artwork executor disconnected without release result");
                        JSONObject message = new JSONObject(line);
                        String kind = message.getString("event");
                        if ("state_request".equals(kind)) {
                            try { output.println(images.state()); }
                            catch (Throwable failure) {
                                error("read_current_artwork", failure);
                                output.println(new JSONObject().put("error", trace(failure)));
                            }
                        } else if ("error".equals(kind)) {
                            workerFailed = true;
                            events.accept("music_artwork_error", new Object[]{"operation", message.optString("operation"), "stack", message.optString("stack")});
                        } else if ("published".equals(kind)) {
                            if (message.getBoolean("same_owner")) {
                                try { images.prune(message.isNull("displayed") ? "default" : message.getString("displayed")); }
                                catch (Throwable secondary) { error("image_cleanup_secondary", secondary); }
                            }
                            events.accept("music_artwork_publish", new Object[]{"package", images.packageName,
                                    "icon_fallback", images.iconFallback, "readback_matches", message.getBoolean("readback_matches"),
                                    "provider_result", message.getInt("provider_result")});
                            if (!message.getBoolean("readback_matches")) error("publish_readback",
                                    new IOException("OEM artwork readback differs; same_owner=" + message.getBoolean("same_owner")
                                            + ", provider_result=" + message.getInt("provider_result")));
                        } else if ("finished".equals(kind)) {
                            finished = true;
                            if (message.getBoolean("cleaned")) try { images.prune(null); }
                            catch (Throwable secondary) { error("image_cleanup_secondary", secondary); }
                            output.println("ACK");
                            events.accept("music_artwork_stopped", new Object[]{"released", message.getBoolean("cleaned"), "error", workerFailed});
                            if (workerFailed) synchronized (this) { failed = true; }
                            return;
                        } else if ("connected".equals(kind)) {
                            events.accept("music_artwork_identity", new Object[]{"worker_uid", message.getInt("uid"), "provider_uid", message.getInt("provider_uid"), "user", user});
                        }
                    }
                }
            }
            throw new IOException("Artwork executor did not connect within 10s; launch expired or denied, cause not established");
        } finally {
            if (!launched) try { images.prune(null); }
            catch (Throwable secondary) { error("image_cleanup_secondary", secondary); }
            if (launched && !finished) events.accept("music_artwork_release_pending", new Object[]{"reason", "executor_result_unavailable"});
        }
    }

    private final class Images {
        final Context shell;
        final File directory;
        final MusicArtworkImages loader;
        MediaMetadata previousMetadata;
        MediaController previousController;
        Bitmap previousImage;
        String path, track, lastDisplayed, packageName = "";
        boolean iconFallback;
        Images(Context shell, File directory) {
            this.shell = shell; this.directory = directory;
            loader = new MusicArtworkImages(shell, MusicArtworkRuntime.this::error);
        }
        JSONObject state() throws Exception {
            Selection selection = selected;
            if (selection == null) return new JSONObject().put("active", false);
            MediaController controller = selection.controller;
            AudioManager audio = (AudioManager) shell.getSystemService(Context.AUDIO_SERVICE);
            String focus = (String) audio.getClass().getMethod("getCurrentAudioFocusPackage").invoke(audio);
            if (!controller.getPackageName().equals(focus)) {
                synchronized (MusicArtworkRuntime.this) {
                    if (selected == selection) update(null, null);
                }
                return new JSONObject().put("active", false);
            }
            MediaMetadata metadata = selection.metadata;
            String nextTrack = MusicArtworkPolicy.trackKey(controller.getPackageName() + ":" + controller.getSessionToken(),
                    text(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID), text(metadata, MediaMetadata.METADATA_KEY_TITLE), text(metadata, MediaMetadata.METADATA_KEY_ARTIST));
            if (controller != previousController || metadata != previousMetadata || !nextTrack.equals(track)) {
                Bitmap bitmap = loader.image(controller.getPackageName(), metadata);
                if (bitmap == null) path = null;
                else if (path == null || previousImage == null || !bitmap.sameAs(previousImage)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("Artwork PNG encoding failed");
                    byte[] png = bytes.toByteArray();
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create artwork image directory");
                    File image = new File(directory, digest(png) + ".png");
                    if (!image.isFile()) { Files.write(image.toPath(), png); Os.chmod(image.getPath(), 0644); }
                    path = image.getPath(); previousImage = bitmap;
                }
                previousController = controller; previousMetadata = metadata; track = nextTrack;
                packageName = controller.getPackageName();
                iconFallback = loader.iconFallback;
            }
            if (selected != selection) return new JSONObject().put("active", true);
            return new JSONObject().put("active", true).put("track", track).put("cover", path == null ? JSONObject.NULL : path);
        }
        void prune(String displayed) throws IOException {
            File[] files = directory.listFiles();
            if (files == null) return;
            for (File file : files) {
                String name = file.getName();
                if (name.matches("[a-f0-9]{64}\\.png") && (displayed == null
                        || !file.getPath().equals(displayed) && !file.getPath().equals(path)
                        && !file.getPath().equals(lastDisplayed))) Files.deleteIfExists(file.toPath());
            }
            lastDisplayed = displayed;
            if (displayed == null) Files.deleteIfExists(directory.toPath());
        }
    }

    private static final class Selection {
        final MediaController controller;
        final MediaMetadata metadata;
        Selection(MediaController controller, MediaMetadata metadata) { this.controller = controller; this.metadata = metadata; }
    }

    private static String text(MediaMetadata metadata, String key) { return metadata == null ? null : metadata.getString(key); }
    static byte[] readWorker(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 450_000) throw new IOException("Packaged artwork DEX exceeds Binder payload limit");
            output.write(buffer, 0, count);
        }
        if (output.size() == 0) throw new IOException("Packaged artwork DEX is empty");
        return output.toByteArray();
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
    private synchronized void error(String operation, Throwable failure) {
        String stack = trace(failure);
        if (stack.equals(failures.put(operation, stack))) return;
        events.accept("music_artwork_error", new Object[]{"operation", operation, "stack", stack});
    }
    private static String trace(Throwable failure) {
        StringWriter text = new StringWriter(); failure.printStackTrace(new PrintWriter(text)); return text.toString();
    }

    private static void cells(String command) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("checkService", String.class).invoke(null, "cellsservice_host");
        if (binder == null) throw new IOException("cellsservice_host absent or access denied; reason not distinguished");
        if (!"aidl.cells.ICellsService".equals(binder.getInterfaceDescriptor())) throw new IOException("Unexpected cellsservice_host descriptor");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("aidl.cells.ICellsService"); input.writeString(command);
            if (!binder.transact(3, input, output, 0)) throw new IOException("Artwork OEM transaction 3 rejected");
            output.readException(); int status = output.readInt();
            if (status != 0) throw new IOException("Artwork OEM bootstrap wait_status=" + status);
        } finally { input.recycle(); output.recycle(); }
    }

    static String bootstrap(byte[] dex, String token, String imageId, int user, int port, long deadline) {
        if (!token.matches("[a-f0-9]{32}") || !imageId.matches("[a-f0-9]{32}") || token.equals(imageId)
                || user < 0 || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid artwork bootstrap");
        String dir = "/data/local/tmp/byd-extend-artwork-" + token;
        String report = "{ printf '%s\\n' 'BOOT " + token + "'; cat " + dir + "/bootstrap.log; } | /system/bin/toybox nc -w 3 127.0.0.1 " + port;
        String body = "umask 077\nmkdir " + dir + " || exit 41\nexec >" + dir + "/bootstrap.log 2>&1\n"
                + "printf %s '" + Base64.encodeToString(dex, Base64.NO_WRAP) + "' | /system/bin/toybox base64 -d > " + dir + "/worker.jar\n"
                + "chmod 444 " + dir + "/worker.jar\n"
                + "CLASSPATH=" + dir + "/worker.jar /system/bin/app_process /system/bin --nice-name=byd_music_artwork "
                + MusicArtworkWorker.class.getName() + " " + port + " " + token + " " + user + " " + deadline + " " + imageId + "\n"
                + "rc=$?\n[ \"$rc\" = 0 ] || { " + report + "; }\n"
                + "if [ -f " + dir + "/complete ]; then rm -f " + dir + "/complete " + dir + "/worker.jar " + dir + "/bootstrap.log; rmdir " + dir + "; fi\nexit \"$rc\"\n";
        return "/system/bin/nohup /system/bin/sh -c '" + body.replace("'", "'\\''") + "' </dev/null >/dev/null 2>&1 &";
    }
}
