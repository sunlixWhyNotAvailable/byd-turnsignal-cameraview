package com.byd.extend;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.system.Os;
import android.util.Base64;
import com.byd.extend.artwork.MusicArtworkPolicy;
import com.byd.extend.artwork.MusicArtworkWorker;
import com.byd.extend.artwork.ArtworkFiles;
import com.byd.extend.artwork.ArtworkDirectPolicy;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private boolean showArtwork, showPlayerIcon;
    private volatile long revision, sessionEpoch;
    private long fallbackEpoch = -1;
    private volatile Thread launcher;

    MusicArtworkRuntime(Context context, BiConsumer<String, Object[]> events) {
        this.context = context; this.events = events;
    }

    synchronized void reset() { failed = false; failures.clear(); }

    synchronized void configure(boolean artwork, boolean playerIcon) {
        if (showArtwork == artwork && showPlayerIcon == playerIcon) return;
        showArtwork = artwork;
        showPlayerIcon = playerIcon;
        if (selected != null) update(selected.controller, selected.metadata);
    }

    synchronized void metadataChanged(MediaController controller, MediaMetadata metadata) {
        if (selected != null && selected.controller == controller) update(controller, metadata);
    }

    synchronized void removed(MediaController controller) {
        if (selected != null && selected.controller == controller) update(null, null);
    }

    synchronized void update(MediaController controller, MediaMetadata metadata) {
        if (!showArtwork && !showPlayerIcon) controller = null;
        if (selected == null ? controller != null : selected.controller != controller || selected.metadata != metadata
                || selected.artwork != showArtwork || selected.playerIcon != showPlayerIcon) {
            if (selected == null && controller != null) failed = false;
            if (controller == null) sessionEpoch++;
            selected = controller == null ? null : new Selection(controller, metadata, showArtwork, showPlayerIcon);
            revision++;
            notifyAll();
        }
        if (controller == null || running || failed) return;
        running = true;
        long startRevision = revision, epoch = sessionEpoch;
        Thread broker = new Thread(() -> run(startRevision, epoch), "byd-music-artwork");
        broker.setDaemon(true); broker.start();
    }

    private void run(long startRevision, long epoch) {
        try {
            if (android.os.Process.myUid() != 2000) throw new SecurityException("Artwork broker requires shell UID2000");
            if (launcher != null && launcher.isAlive()) throw new IOException("Previous cellsservice_host launch is still pending");
            try {
                if (fallbackEpoch == epoch) relay(epoch);
                else direct(epoch);
            }
            catch (MusicArtworkDirect.Failure failure) {
                error("direct_transport", failure);
                if (selected != null && sessionEpoch == epoch) {
                    fallbackEpoch = epoch;
                    events.accept("music_artwork_fallback", new Object[]{"from", "shell", "to", "cellsservice_host",
                            "reason", failure.getMessage(), "session", epoch});
                    relay(epoch);
                }
            }
        } catch (Throwable failure) {
            error("artwork_session", failure);
            synchronized (this) { if (sessionEpoch == epoch) failed = true; }
        } finally {
            synchronized (this) {
                running = false;
                if (!failed && selected != null && revision != startRevision)
                    update(selected.controller, selected.metadata);
            }
        }
    }

    private static int currentUser() throws Exception {
        return (Integer) Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
    }

    private synchronized void awaitChange(long observedRevision, long epoch, long delay) throws InterruptedException {
        if (revision == observedRevision && sessionEpoch == epoch && selected != null && delay > 0) wait(delay);
    }

    private void direct(long epoch) throws Exception {
        Context shell = TurnSignalShellMain.shellContext(context);
        int user = currentUser();
        String prefix = MusicArtworkWorker.imagePrefix(user, UUID.randomUUID().toString().replace("-", ""));
        MusicArtworkDirect transport = new MusicArtworkDirect(user, prefix);
        Images images = new Images(shell, new File(prefix), user, epoch);
        events.accept("music_artwork_started", new Object[]{"transport", "shell", "broker_uid", android.os.Process.myUid(),
                "user", user, "vm", transport.vm, "single_os", transport.singleOs, "host_root", transport.hostRoot});
        String sentTrack = null, sentPath = null, previousPath = null, attempted = null, restored = null;
        long lastSend = 0, sends = 0;
        boolean touched = false, handoff = false;
        String restoration = "not_required";
        try {
            while (sessionEpoch == epoch && selected != null) {
                long sampledRevision = revision;
                JSONObject state = images.state();
                if (!state.optBoolean("active")) break;
                if (!state.has("track") || revision != sampledRevision) continue;
                String track = state.getString("track"), path = state.isNull("cover") ? null : state.getString("cover");
                boolean changed = !Objects.equals(track, sentTrack) || !Objects.equals(path, sentPath);
                long delay = ArtworkDirectPolicy.delay(SystemClock.elapsedRealtime(), lastSend, changed, path != null);
                if ((path != null || touched && changed) && delay == 0) {
                    if (currentUser() != user) {
                        synchronized (this) { if (sessionEpoch == epoch) update(null, null); }
                        break;
                    }
                    if (!ArtworkDirectPolicy.current(epoch, sessionEpoch, sampledRevision, revision,
                            images.preparedSelection, selected)) continue;
                    attempted = path;
                    touched = true; // An exception cannot prove native did not receive the path.
                    long begin = SystemClock.elapsedRealtime();
                    transport.send(path);
                    lastSend = SystemClock.elapsedRealtime(); sends++;
                    if (!Objects.equals(sentPath, path)) previousPath = sentPath;
                    sentPath = path; sentTrack = track;
                    if (DiagnosticLogPolicy.shouldProduce("music_artwork_direct_send"))
                        events.accept("music_artwork_direct_send", new Object[]{"reason", changed ? "image_changed" : "repeat",
                                "duration_ms", lastSend - begin, "command_accepted", true, "visual_confirmed", false});
                    if (changed) try { ArtworkFiles.pruneImages(images.directory, false, path, previousPath); }
                    catch (Throwable secondary) { error("direct_image_cleanup_secondary", secondary); }
                }
                awaitChange(sampledRevision, epoch,
                        ArtworkDirectPolicy.delay(SystemClock.elapsedRealtime(), lastSend, false, path != null));
            }
        } catch (MusicArtworkDirect.Failure failure) {
            handoff = true;
            throw failure;
        } finally {
            if (touched && !handoff) {
                try {
                    if (currentUser() == user) {
                        Selection restore = currentArtworkSelection(shell);
                        String restoreTrack = selectionTrack(restore);
                        Bitmap clean = restore == null ? null : images.loader.artwork(restore.controller.getPackageName(),
                                restore.metadata, sessionUid(restore.controller));
                        restored = clean == null ? null : images.save(clean);
                        Selection checked = currentArtworkSelection(shell);
                        if (currentUser() != user || !Objects.equals(restoreTrack, selectionTrack(checked)))
                            throw new IOException("Current player/track/user changed during direct artwork restoration");
                        attempted = restored;
                        transport.send(restored);
                        restoration = "command_accepted_visual_unverified";
                        events.accept("music_artwork_restore", new Object[]{"transport", "shell", "kind",
                                restored == null ? "default" : "current_track_clean", "command_accepted", true,
                                "visual_confirmed", false});
                    } else restoration = "user_changed_files_retained";
                } catch (Throwable secondary) {
                    restoration = "pending";
                    error("direct_restore_secondary", secondary);
                }
            } else if (touched) restoration = "handoff_files_retained";
            // shortcut: native exposes no displayed-path getter; retain final/previous PNGs until a verified release is available.
            try { ArtworkFiles.pruneImages(images.directory, !touched, sentPath, previousPath, attempted, restored); }
            catch (Throwable secondary) { error("direct_image_cleanup_secondary", secondary); }
            if (DiagnosticLogPolicy.shouldProduce("music_artwork_direct_send"))
                events.accept("music_artwork_direct_send", new Object[]{"reason", "session_totals", "sends", sends});
            events.accept("music_artwork_stopped", new Object[]{"transport", "shell",
                    "restoration", restoration, "retention_required", touched});
        }
    }

    private static int sessionUid(MediaController controller) throws Exception {
        return (Integer) controller.getSessionToken().getClass().getMethod("getUid").invoke(controller.getSessionToken());
    }

    private static String selectionTrack(Selection selection) {
        if (selection == null) return null;
        return MusicArtworkPolicy.trackKey(selection.controller.getPackageName() + ":" + selection.controller.getSessionToken(),
                text(selection.metadata, MediaMetadata.METADATA_KEY_MEDIA_ID), text(selection.metadata, MediaMetadata.METADATA_KEY_TITLE),
                text(selection.metadata, MediaMetadata.METADATA_KEY_ARTIST));
    }

    private static Selection currentArtworkSelection(Context shell) throws Exception {
        AudioManager audio = (AudioManager) shell.getSystemService(Context.AUDIO_SERVICE);
        String focus = (String) audio.getClass().getMethod("getCurrentAudioFocusPackage").invoke(audio);
        MediaSessionManager manager = (MediaSessionManager) shell.getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> matches = new ArrayList<>(), playing = new ArrayList<>();
        for (MediaController controller : manager.getActiveSessions(null)) {
            if (!controller.getPackageName().equals(focus)) continue;
            matches.add(controller);
            if (controller.getPlaybackState() != null && controller.getPlaybackState().getState()
                    == android.media.session.PlaybackState.STATE_PLAYING) playing.add(controller);
        }
        if (playing.size() == 1) matches = playing;
        if (matches.size() > 1) throw new IOException("Ambiguous current MediaSession during artwork restoration");
        return matches.isEmpty() ? null : new Selection(matches.get(0), matches.get(0).getMetadata(), true, false);
    }

    private void relay(long epoch) throws Exception {
        Context shell = TurnSignalShellMain.shellContext(context);
        int user = currentUser();
        String token = UUID.randomUUID().toString().replace("-", "");
        String imageId = UUID.randomUUID().toString().replace("-", "");
        String prefix = MusicArtworkWorker.imagePrefix(user, imageId);
        File directory = new File(prefix);
        Images images = new Images(shell, directory, user, epoch);
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
                    events.accept("music_artwork_started", new Object[]{"transport", "cellsservice_host", "broker_uid", android.os.Process.myUid(), "user", user});
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
                            if (!message.optString("operation").startsWith("orphan_")) workerFailed = true;
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
                        } else if ("readback_state".equals(kind)) {
                            events.accept("music_artwork_readback", new Object[]{"detail", message.toString()});
                            if (!"mismatch".equals(message.optString("state"))) {
                                synchronized (this) { failures.remove("publish_readback"); }
                            }
                        } else if ("cleanup_request".equals(kind)) {
                            String orphan = message.getString("image_id");
                            if (message.getInt("user") != user || !orphan.matches("[a-f0-9]{32}") || orphan.equals(imageId))
                                throw new SecurityException("Invalid artwork cleanup identity");
                            boolean cleaned = false;
                            try {
                                ArtworkFiles.pruneImages(new File(MusicArtworkWorker.imagePrefix(user, orphan)), true);
                                cleaned = true;
                            } catch (Throwable secondary) { error("orphan_images_secondary", secondary); }
                            output.println(new JSONObject().put("image_id", orphan).put("cleaned", cleaned));
                        } else if ("cleanup_result".equals(kind)) {
                            events.accept("music_artwork_cleanup", new Object[]{"removed", message.getInt("removed"),
                                    "retained", message.getInt("retained")});
                        } else if ("finished".equals(kind)) {
                            finished = true;
                            boolean imagesCleaned = false;
                            if (message.getBoolean("cleaned")) try { images.prune(null); imagesCleaned = true; }
                            catch (Throwable secondary) { error("image_cleanup_secondary", secondary); }
                            output.println(imagesCleaned ? "ACK" : "RETAIN");
                            events.accept("music_artwork_stopped", new Object[]{"released", message.getBoolean("cleaned"), "error", workerFailed});
                            if (workerFailed) synchronized (this) { if (sessionEpoch == epoch) failed = true; }
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
        final int user;
        final long epoch;
        Selection preparedSelection;
        MediaMetadata previousMetadata;
        MediaController previousController;
        Bitmap previousImage;
        String path, track, lastDisplayed, packageName = "";
        boolean iconFallback;
        boolean previousArtwork, previousPlayerIcon;
        Images(Context shell, File directory, int user, long epoch) {
            this.shell = shell; this.directory = directory;
            this.user = user; this.epoch = epoch;
            loader = new MusicArtworkImages(shell, MusicArtworkRuntime.this::error);
        }
        JSONObject state() throws Exception {
            Selection selection = selected;
            if (selection == null || sessionEpoch != epoch) return new JSONObject().put("active", false);
            if (currentUser() != user) {
                synchronized (MusicArtworkRuntime.this) { if (selected == selection) update(null, null); }
                return new JSONObject().put("active", false);
            }
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
            if (controller != previousController || metadata != previousMetadata || !nextTrack.equals(track)
                    || previousArtwork != selection.artwork || previousPlayerIcon != selection.playerIcon) {
                int sessionUid = -1;
                try { sessionUid = (Integer) controller.getSessionToken().getClass().getMethod("getUid").invoke(controller.getSessionToken()); }
                catch (Throwable failure) { error("session_uid", failure); }
                Bitmap bitmap = loader.image(controller.getPackageName(), metadata, sessionUid,
                        selection.artwork, selection.playerIcon);
                if (bitmap == null) path = null;
                else if (path == null || previousImage == null || !bitmap.sameAs(previousImage)) {
                    path = save(bitmap); previousImage = bitmap;
                }
                previousController = controller; previousMetadata = metadata; track = nextTrack;
                previousArtwork = selection.artwork; previousPlayerIcon = selection.playerIcon;
                packageName = controller.getPackageName();
                iconFallback = loader.iconFallback;
            }
            if (sessionEpoch != epoch) return new JSONObject().put("active", false);
            if (selected != selection) return new JSONObject().put("active", true);
            preparedSelection = selection;
            return new JSONObject().put("active", true).put("track", track).put("cover", path == null ? JSONObject.NULL : path);
        }
        String save(Bitmap bitmap) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("Artwork PNG encoding failed");
            File image = ArtworkFiles.writeImage(directory, bytes.toByteArray());
            Os.chmod(directory.getPath(), 0755);
            Os.chmod(image.getPath(), 0644);
            return image.getPath();
        }
        void prune(String displayed) throws IOException {
            ArtworkFiles.pruneImages(directory, displayed == null, displayed, path, lastDisplayed);
            lastDisplayed = displayed;
        }
    }

    private static final class Selection {
        final MediaController controller;
        final MediaMetadata metadata;
        final boolean artwork, playerIcon;
        Selection(MediaController controller, MediaMetadata metadata, boolean artwork, boolean playerIcon) {
            this.controller = controller; this.metadata = metadata;
            this.artwork = artwork; this.playerIcon = playerIcon;
        }
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
                + "if [ -f " + dir + "/complete ]; then rm -f " + dir + "/complete " + dir + "/worker.jar " + dir + "/bootstrap.log " + dir + "/released.properties; rmdir " + dir + "; fi\nexit \"$rc\"\n";
        return "/system/bin/nohup /system/bin/sh -c '" + body.replace("'", "'\\''") + "' </dev/null >/dev/null 2>&1 &";
    }
}
