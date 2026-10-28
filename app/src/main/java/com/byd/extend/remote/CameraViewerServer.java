package com.byd.extend.remote;

import com.byd.extend.CameraModeClient;
import com.byd.extend.CameraModeSettings;
import com.byd.extend.DiagnosticLogPolicy;
import com.byd.extend.RemoteCameraView;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

/** Loopback-only HTTP endpoint behind the authenticated native tunnel. */
public final class CameraViewerServer implements AutoCloseable {
    private static final int MAX_HEADERS = 16_384;
    private static final int MAX_BODY = 4_096;
    private static final long REQUEST_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(10);
    private static final int READ_IDLE_TIMEOUT_MS = 5_000;
    private static final int VIDEO_CHUNK = 64 * 1024;

    public interface Stream {
        boolean admitted(String session);
        boolean current(String session);
        void disconnected(String session, String reason);
        void failed(Throwable failure);
        String live(String session, int direction, String request, long selectionRevision);
        int interiorCameras();
        boolean archive(String session, boolean leave, long selectionRevision);
        JSONObject status();
        JSONObject setMode(String session, String mode, boolean enabled) throws Exception;
        JSONObject setSetting(String session, String setting, Object value) throws Exception;
        JSONObject presentation();
        void firstFrame(String session, String mode);
        void viewFrame(String session, long revision, String streamId);
        FragmentedVideoWindow video();
        CameraViewerArchive recordings() throws Exception;
        byte[] resource(String name) throws IOException;
    }

    private final ViewerAccess access;
    private final boolean passwordRequired;
    private final Stream stream;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(2, 4, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> new Thread(r, "camera-view-http"));
    private final Thread listener;
    private final Object playbackLock = new Object();
    private final Map<String, SessionPlayback> playbacks = new HashMap<>();
    private final Map<String, ArchiveSelection> archiveSelections = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong transferIds = new java.util.concurrent.atomic.AtomicLong();
    private final Object sessionSocketLock = new Object();
    private final Map<String, Set<Socket>> sessionSockets = new HashMap<>();
    private final Map<Socket, String> socketSessions = new HashMap<>();
    private volatile boolean closed;
    private volatile String[] sessionRevision = {null, "unknown"};
    private final java.util.concurrent.atomic.AtomicLong lastTransportDiagnostic =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    private final java.util.concurrent.atomic.AtomicLong lastPlaybackSnapshot =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

    public CameraViewerServer(String password, String privateProxyProof, Stream stream) throws IOException {
        this.access = new ViewerAccess(password, privateProxyProof);
        this.passwordRequired = privateProxyProof == null;
        this.stream = Objects.requireNonNull(stream, "stream");
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);
        listener = new Thread(this::listen, "camera-view-listen");
        listener.start();
    }

    public int port() { return server.getLocalPort(); }

    /** Rechecked by the owner when a queued settings mutation actually executes. */
    public boolean canControl(String session) {
        return !closed && access.isCurrent(session);
    }

    /** Called by the runtime when another authenticated viewer takes the shared camera lease. */
    public void supersede(String session) {
        if (session == null) return;
        supersede(session, null);
    }

    /** Commit only the still-pending authenticated candidate after runtime validation. */
    public boolean commitAdmission(String session) {
        return !closed && access.commitAdmission(session, now());
    }

    private static long now() { return System.nanoTime() / 1_000_000; }

    private void listen() {
        try {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    try { requests.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException busy) {
                        sockets.remove(socket);
                        socket.close();
                    }
                } catch (SocketTimeoutException ignored) {
                    // Check abandoned sessions at a bounded interval.
                }
                expireSession();
            }
        } catch (IOException failure) {
            if (!closed) {
                CameraViewingDiagnostics.failure("viewer_listener", "shared", failure);
                stream.failed(failure);
            }
        }
    }

    private void expireSession() {
        String expired = null;
        synchronized (access) {
            String current = access.current();
            if (current != null && access.expire(now())) expired = current;
        }
        if (expired == null) return;
        closePlayback(expired);
        closeSessionSockets(expired, null);
        try { stream.disconnected(expired, "origin=heartbeat_timeout"); }
        catch (RuntimeException failure) {
            CameraViewingDiagnostics.failure("viewer_expired_cleanup", "shared", failure);
        }
    }

    private void serve(Socket socket) {
        Reply reply = new Reply();
        String operation = "request";
        try (socket) {
            try {
                Request request = readRequest(socket);
                reply.head = request.method.equals("HEAD");
                operation = operationName(request.path);
                route(socket, request, reply);
            } catch (Exception failure) {
                if (!isExpectedDisconnect(failure, reply.started, operation)) {
                    if (failure instanceof HttpFailure
                            && "invalid_json".equals(((HttpFailure) failure).bodyStatus)) {
                        CameraViewingDiagnostics.detail("viewer_http_invalid_json", "shared",
                                parserFailureWithoutPayload(failure.getCause()));
                    } else {
                        CameraViewingDiagnostics.failure("viewer_http_" + operation, "shared", failure);
                    }
                }
                if (!reply.started && !socket.isClosed()) {
                    try {
                        if (failure instanceof HttpFailure) {
                            HttpFailure http = (HttpFailure) failure;
                            json(socket, reply, http.status, http.bodyStatus, null, http.extraHeaders);
                        } else if ("request".equals(operation)) {
                            json(socket, reply, 400, "bad_request", null);
                        } else if (failure instanceof IllegalArgumentException) {
                            json(socket, reply, 400, "invalid_request", null);
                        } else {
                            json(socket, reply, 500, "unavailable", null);
                        }
                    } catch (IOException responseFailure) {
                        if (!isExpectedDisconnect(responseFailure, reply.started, operation))
                            CameraViewingDiagnostics.failure("viewer_http_response_" + operation,
                                    "shared", responseFailure);
                    }
                }
            }
        } catch (IOException closeFailure) {
            CameraViewingDiagnostics.failure("viewer_http_close", "shared", closeFailure);
        } finally {
            unregisterSessionSocket(socket);
            sockets.remove(socket);
        }
    }

    static Request readRequest(Socket socket) throws IOException {
        long started = System.nanoTime();
        String stage = "headers";
        try {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4 && headers.size() < MAX_HEADERS) {
                setReadTimeout(socket, started);
                int value = input.read();
                if (value < 0) throw new EOFException("HTTP headers ended before terminator");
                headers.write(value);
                matched = value == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : 0;
            }
            if (matched != 4) throw new HttpFailure(431, "headers_too_large", null);

            String[] lines = headers.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length != 3) throw new IOException("Malformed HTTP request line");
            String method = first[0];
            if (!(method.equals("GET") || method.equals("HEAD") || method.equals("POST")))
                throw new HttpFailure(405, "method_not_allowed", "Allow: GET, HEAD, POST");
            String path = first[1];
            if (!path.startsWith("/") || path.contains("?") || path.contains("%")
                    || path.contains("..") || path.indexOf('\\') >= 0 || path.indexOf('#') >= 0)
                throw new IOException("Invalid viewer path");

            Map<String, String> fields = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) throw new IOException("Malformed HTTP field");
                String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
                if (fields.put(key, lines[i].substring(colon + 1).trim()) != null)
                    throw new IOException("Duplicate HTTP field");
            }
            if (fields.containsKey("transfer-encoding")) throw new IOException("Chunked requests unsupported");
            int length;
            try { length = Integer.parseInt(fields.getOrDefault("content-length", "0")); }
            catch (NumberFormatException malformed) { throw new IOException("Invalid request length", malformed); }
            if (length < 0) throw new IOException("Invalid request length");
            if (length > MAX_BODY) throw new HttpFailure(413, "request_too_large", null);
            stage = "body";
            byte[] body = new byte[length];
            int received = 0;
            while (received < length) {
                setReadTimeout(socket, started);
                int count = input.read(body, received, length - received);
                if (count < 0) throw new EOFException("HTTP body ended before Content-Length");
                received += count;
            }
            if (System.nanoTime() - started >= REQUEST_TIMEOUT_NS)
                throw new SocketTimeoutException("HTTP request deadline exceeded");
            // The intake deadline must not leak into admission or media transfer.
            socket.setSoTimeout(READ_IDLE_TIMEOUT_MS);
            return new Request(method, path, fields, body, cookie(fields.get("cookie")));
        } catch (SocketTimeoutException timeout) {
            throw new HttpFailure(408, "request_timeout", null,
                    new IOException("HTTP request read timed out: stage=" + stage
                            + " elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            timeout));
        }
    }

    private static void setReadTimeout(Socket socket, long started) throws IOException {
        long remaining = REQUEST_TIMEOUT_NS - (System.nanoTime() - started);
        if (remaining <= 0) throw new SocketTimeoutException("HTTP request deadline exceeded");
        int timeout = (int) Math.min(READ_IDLE_TIMEOUT_MS,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        if (socket.getSoTimeout() != timeout) socket.setSoTimeout(timeout);
    }

    private void route(Socket socket, Request request, Reply reply) throws Exception {
        if (request.method.equals("POST") && !Objects.equals(request.fields.get("origin"),
                "https://" + request.fields.get("host"))) {
            json(socket, reply, 403, "forbidden", null);
            return;
        }

        String clientRevision = request.fields.get(ViewerStopReason.REVISION_HEADER.toLowerCase(Locale.ROOT));
        if (clientRevision != null && !clientRevision.equals(Integer.toString(ViewerStopReason.WEB_REVISION))) {
            json(socket, reply, 409, "viewer_update_required", null);
            return;
        }

        if (request.path.equals("/session") && request.method.equals("POST")) {
            admit(socket, request, reply);
            return;
        }
        if (request.path.equals("/") && isRead(request.method)) {
            servePage(socket, request.method.equals("HEAD"), reply);
            return;
        }
        if ((request.path.equals("/viewer.css") || request.path.equals("/viewer.js")
                || request.path.equals("/live-recovery.js")
                || request.path.equals("/favicon.ico"))
                && isRead(request.method)) {
            String name = request.path.substring(1);
            serveBytes(socket, request.method.equals("HEAD"), reply,
                    name.equals("favicon.ico") ? "image/vnd.microsoft.icon"
                            : name.endsWith(".css") ? "text/css; charset=utf-8" : "application/javascript; charset=utf-8",
                    stream.resource(name), null);
            return;
        }

        if (!authorized(request.session, socket, reply)) return;
        if (clientRevision != null) sessionRevision = new String[] {request.session, ViewerStopReason.revision(clientRevision)};
        switch (request.path) {
            case "/stop":
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                JSONObject stopInput = bodyObject(request.body);
                if ("playback_error".equals(stopInput.optString("origin"))) {
                    // Keep the snapshot in the stop request so teardown cannot overtake it.
                    try { logPlaybackSnapshot(stopInput.optJSONObject("snapshot")); }
                    catch (RuntimeException failure) {
                        CameraViewingDiagnostics.failure("browser_snapshot_failed", "viewer", failure);
                    }
                }
                stop(request.session, socket, ViewerStopReason.describe(stopInput));
                json(socket, reply, 200, "ended", expiredCookie());
                return;
            case "/status":
                if (!isRead(request.method)) { methodNotAllowed(socket, reply); return; }
                serveJson(socket, request.method.equals("HEAD"), reply,
                        stream.status().put("webRevision", ViewerStopReason.WEB_REVISION)
                                .put("extendedDiagnostics", DiagnosticLogPolicy.extended())
                                .put("diagnosticsRevision", DiagnosticLogPolicy.revision()));
                return;
            case "/modes": {
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                JSONObject input = bodyObject(request.body);
                Object mode = input.opt("mode");
                Object enabled = input.opt("enabled");
                if (input.length() != 2 || !(enabled instanceof Boolean)
                        || !("tablet".equals(mode) || "recording".equals(mode))) {
                    json(socket, reply, 400, "invalid_mode", null);
                    return;
                }
                try {
                    JSONObject modes = stream.setMode(request.session, (String) mode, (Boolean) enabled);
                    // The owner rechecks the lease on its serialized write path.
                    if (modes == null) {
                        json(socket, reply, 401, "replaced", null);
                        return;
                    }
                    serveJson(socket, false, reply, new JSONObject().put("modes", modes));
                } catch (CameraModeClient.ModeException failure) {
                    CameraViewingDiagnostics.failure("viewer_mode_set", "shared", failure);
                    String code = failure.code();
                    boolean permission = "permission_required".equals(code);
                    json(socket, reply, permission ? 403 : 500,
                            permission ? code : "modes_unavailable", null);
                } catch (Exception failure) {
                    CameraViewingDiagnostics.failure("viewer_mode_set", "shared", failure);
                    json(socket, reply, 500, "modes_unavailable", null);
                }
                return;
            }
            case "/settings": {
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                JSONObject input = bodyObject(request.body);
                Object name = input.opt("setting");
                Object value = input.opt("value");
                try {
                    if (input.length() != 2 || !(name instanceof String))
                        throw new IllegalArgumentException("settings requires one named value");
                    CameraModeSettings.validateSetting((String) name, value);
                } catch (IllegalArgumentException invalid) {
                    json(socket, reply, 400, "invalid_setting", null);
                    return;
                }
                try {
                    JSONObject settings = stream.setSetting(request.session, (String) name, value);
                    if (settings == null) {
                        json(socket, reply, 401, "replaced", null);
                        return;
                    }
                    serveJson(socket, false, reply, new JSONObject().put("settings", settings));
                } catch (Exception failure) {
                    CameraModeClient.ModeException modeFailure = failure instanceof CameraModeClient.ModeException
                            ? (CameraModeClient.ModeException) failure : null;
                    String code = modeFailure == null ? "settings_unavailable" : modeFailure.code();
                    if (!code.matches("[a-z_]{1,64}")) code = "settings_unavailable";
                    String reference = modeFailure == null ? null : modeFailure.errorRef();
                    if (reference == null || !reference.matches("[A-Za-z0-9_-]{1,80}"))
                        reference = java.util.UUID.randomUUID().toString();
                    CameraViewingDiagnostics.failure("viewer_setting_set_" + reference, "shared", failure);
                    json(socket, reply, "permission_required".equals(code) ? 403 : 500,
                            new JSONObject().put("status", code).put("errorRef", reference), null, null);
                }
                return;
            }
            case "/viewer.css":
            case "/viewer.js":
                if (!isRead(request.method)) { methodNotAllowed(socket, reply); return; }
                serveBytes(socket, request.method.equals("HEAD"), reply,
                        request.path.endsWith(".css") ? "text/css; charset=utf-8" : "application/javascript; charset=utf-8",
                        stream.resource(request.path.endsWith(".css") ? "viewer.css" : "viewer.js"), null);
                return;
            case "/hls.js":
                if (!isRead(request.method)) { methodNotAllowed(socket, reply); return; }
                serveBytes(socket, request.method.equals("HEAD"), reply,
                        "application/javascript; charset=utf-8", stream.resource("hls.js"), null);
                return;
            case "/archive":
                if (!isRead(request.method)) { methodNotAllowed(socket, reply); return; }
                serveText(socket, request.method.equals("HEAD"), reply, "application/json; charset=utf-8",
                        stream.recordings().catalog());
                return;
            case "/archive/open":
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                long archiveRevision = selectionRevision(bodyObject(request.body));
                if (!transferCurrent(request.session, socket)) {
                    json(socket, reply, 401, access.wasReplaced(request.session) ? "replaced" : "unauthorized", null);
                    return;
                }
                if (!stream.archive(request.session, false, archiveRevision)) {
                    json(socket, reply, 409, "stale_live_selection", null);
                    return;
                }
                archiveMode(request.session, archiveRevision, true);
                serveJson(socket, false, reply, stream.status());
                return;
            case "/archive/select": {
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                JSONObject selection = bodyObject(request.body);
                String id = selection.optString("id");
                if (archiveId("/" + id, "/") == null) throw new HttpFailure(400, "invalid_archive_id", null);
                long revision = selectionRevision(selection);
                if (!transferCurrent(request.session, socket)) throw new HttpFailure(401, "replaced", null);
                CameraViewerArchive archive = stream.recordings();
                synchronized (playbackLock) {
                    if (closed || !access.isCurrent(request.session)) throw new HttpFailure(401, "replaced", null);
                    ArchiveSelection current = archiveSelections.computeIfAbsent(request.session, ignored -> new ArchiveSelection());
                    if (!current.select(revision, id)) throw new HttpFailure(409, "stale_archive_selection", null);
                    closePlayback(playbacks.remove(request.session), "record_selected");
                    CameraViewerArchive.Playback selected;
                    try { selected = archive.openVideo(id); }
                    catch (java.io.FileNotFoundException missing) {
                        throw new HttpFailure(404, "not_found", null, missing);
                    }
                    playbacks.put(request.session, new SessionPlayback(id, revision, selected));
                }
                serveJson(socket, false, reply, new JSONObject().put("revision", revision));
                return;
            }
            case "/archive/close":
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                JSONObject archiveClose = bodyObject(request.body);
                long playbackRevision = selectionRevision(archiveClose, "playbackRevision");
                boolean leaveArchive = archiveClose.optBoolean("leave");
                long leaveRevision = leaveArchive ? selectionRevision(archiveClose) : 0;
                synchronized (playbackLock) {
                    if (closed || !access.isCurrent(request.session)) throw new HttpFailure(401, "replaced", null);
                    ArchiveSelection selected = archiveSelections.computeIfAbsent(request.session, ignored -> new ArchiveSelection());
                    if (!selected.accepts(playbackRevision, null))
                        throw new HttpFailure(409, "stale_archive_selection", null);
                    if (leaveArchive && !stream.archive(request.session, true, leaveRevision))
                        throw new HttpFailure(409, "stale_live_selection", null);
                    selected.select(playbackRevision, null);
                    if (leaveArchive) selected.mode(leaveRevision, false);
                    closePlayback(playbacks.remove(request.session), "archive_closed");
                }
                serveJson(socket, false, reply, stream.status());
                return;
            case "/live":
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                JSONObject liveInput = bodyObject(request.body);
                int direction = direction(liveInput.optString("view", "composition"));
                long selectionRevision = selectionRevision(liveInput);
                if (!transferCurrent(request.session, socket)) {
                    json(socket, reply, 401, access.wasReplaced(request.session) ? "replaced" : "unauthorized", null);
                    return;
                }
                if (!RemoteCameraView.allowed(direction, stream.interiorCameras())) {
                    json(socket, reply, 403, "view_not_allowed", null);
                    return;
                }
                String liveRequest = java.util.UUID.randomUUID().toString();
                String streamId = stream.live(request.session, direction, liveRequest, selectionRevision);
                if (streamId == null) {
                    json(socket, reply, 409, "stale_live_selection", null);
                    return;
                }
                archiveMode(request.session, selectionRevision, false);
                serveJson(socket, false, reply, new JSONObject().put("status", "preparing")
                        .put("request", liveRequest).put("selectionRevision", selectionRevision)
                        .put("streamId", streamId));
                return;
            case "/diagnostic":
                if (!request.method.equals("POST")) { methodNotAllowed(socket, reply); return; }
                JSONObject diagnostic = bodyObject(request.body);
                String event = diagnostic.optString("event", "");
                String mode = diagnostic.optString("mode", "");
                if ("playback_snapshot".equals(event)) {
                    boolean valid = logPlaybackSnapshot(diagnostic);
                    json(socket, reply, valid ? 200 : 400, valid ? "ok" : "invalid_event", null); return;
                }
                if ("playback_transport".equals(event)) {
                    FragmentedVideoWindow window = stream.video();
                    if (!ViewerStopReason.validTransportReason(diagnostic) || window == null || !window.getStreamId().equals(diagnostic.optString("streamId"))
                            || !"tailscale".equals(stream.presentation().optString("provider"))) {
                        json(socket, reply, 400, "invalid_event", null); return;
                    }
                    if (!DiagnosticLogPolicy.extended()) {
                        json(socket, reply, 200, "ok", null); return;
                    }
                    long time = now(), previous = lastTransportDiagnostic.get();
                    if ((previous == Long.MIN_VALUE || time - previous >= 5_000)
                            && lastTransportDiagnostic.compareAndSet(previous, time))
                        CameraViewingDiagnostics.verbose("browser_transport", "tailscale",
                                () -> "stream=" + window.getStreamId() + " " + ViewerStopReason.transport(diagnostic));
                    json(socket, reply, 200, "ok", null); return;
                }
                if ("view_frame".equals(event)) {
                    long revision = diagnostic.optLong("revision", -1);
                    String displayedStreamId = diagnostic.optString("streamId", "");
                    if (revision < 1 || revision > 9_007_199_254_740_991L
                            || !displayedStreamId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
                        json(socket, reply, 400, "invalid_event", null); return;
                    }
                    stream.viewFrame(request.session, revision, displayedStreamId);
                    json(socket, reply, 200, "ok", null); return;
                }
                if (!"first_frame".equals(event) || !("live".equals(mode) || "archive".equals(mode))) {
                    json(socket, reply, 400, "invalid_event", null);
                    return;
                }
                stream.firstFrame(request.session, mode);
                json(socket, reply, 200, "ok", null);
                return;
            default:
                break;
        }

        if (request.path.startsWith("/archive/thumbnail/")) {
            if (!isRead(request.method)) { methodNotAllowed(socket, reply); return; }
            String id = archiveId(request.path, "/archive/thumbnail/");
            if (id == null) { json(socket, reply, 404, "not_found", null); return; }
            serveThumbnail(socket, request.method.equals("HEAD"), request.session, id, reply);
            return;
        }
        if (request.path.startsWith("/archive/video/")) {
            if (!isRead(request.method)) { methodNotAllowed(socket, reply); return; }
            int split = request.path.indexOf('/', "/archive/video/".length());
            if (split < 0) throw new HttpFailure(409, "stale_archive_selection", null);
            long revision;
            try { revision = Long.parseLong(request.path.substring("/archive/video/".length(), split)); }
            catch (NumberFormatException invalid) { throw new HttpFailure(400, "invalid_archive_revision", null); }
            String id = archiveId(request.path.substring(split), "/");
            if (id == null) { json(socket, reply, 404, "not_found", null); return; }
            serveVideo(socket, request, id, revision, reply);
            return;
        }

        FragmentedVideoWindow window = stream.video();
        String mediaPath = window == null ? null : window.resourcePath(request.path);
        if (mediaPath != null) {
            if ((mediaPath.equals("/live.m3u8") || mediaPath.matches("/live-[0-9]{1,18}\\.m3u8"))
                    && isRead(request.method)) {
                long from = mediaPath.equals("/live.m3u8") ? 0
                        : Long.parseLong(mediaPath.substring(6, mediaPath.length() - 5));
                String playlist = window.playlist(from);
                if (playlist == null) json(socket, reply, 503, "preparing", null);
                else serveText(socket, request.method.equals("HEAD"), reply,
                        "application/vnd.apple.mpegurl", playlist);
                return;
            }
            if (mediaPath.equals("/init.mp4") && isRead(request.method)) {
                byte[] bytes = window.initialization();
                if (bytes == null) json(socket, reply, 503, "preparing", null);
                else serveBytes(socket, request.method.equals("HEAD"), reply, "video/mp4", bytes, null);
                return;
            }
            if (mediaPath.startsWith("/segment/") && mediaPath.endsWith(".m4s")
                    && isRead(request.method)) {
                String number = mediaPath.substring(9, mediaPath.length() - 4);
                try {
                    byte[] bytes = window.segment(Long.parseLong(number));
                    if (bytes == null) mediaNotFound(socket, request, reply, window);
                    else serveBytes(socket, request.method.equals("HEAD"), reply, "video/iso.segment", bytes, null);
                } catch (NumberFormatException malformed) {
                    mediaNotFound(socket, request, reply, window);
                }
                return;
            }
        }
        mediaNotFound(socket, request, reply, window);
    }

    private void mediaNotFound(Socket socket, Request request, Reply reply, FragmentedVideoWindow window)
            throws IOException {
        JSONObject presentation = stream.presentation();
        String provider = presentation.optString("provider");
        String mode = presentation.optString("accessMode");
        String[] revision = sessionRevision;
        String clientRevision = Objects.equals(revision[0], request.session) ? revision[1] : "unknown";
        CameraViewingDiagnostics.detail("viewer_media_404", provider.equals("tailscale") ? "tailscale" : "zrok",
                "accessMode=" + (mode.equals("serve") ? "serve" : mode.equals("funnel") ? "funnel" : "public")
                        + " run=" + presentation.optLong("run") + " "
                        + ViewerStopReason.mediaRequest(request.path, window == null ? null : window.getPlaylistPath(),
                        clientRevision) + (window == null ? "" : " " + window.missingSegmentDiagnostic(request.path)));
        json(socket, reply, 404, "not_found", null);
    }

    private void admit(Socket socket, Request request, Reply reply) throws IOException {
        if (!access.beginAuthentication(request.session, now())) {
            json(socket, reply, 429, "retry", null);
            return;
        }

        JSONObject credentials = bodyObject(request.body);
        String password = credentials.optString("password", "");
        if (!access.authenticate(password, request.fields.get("x-byd-extend-proxy-token"))) {
            json(socket, reply, 401, "unauthorized", null);
            return;
        }

        String previous = access.current();
        String session = access.reserveAdmission(request.session);
        if (session == null) {
            json(socket, reply, 401, "unauthorized", null);
            return;
        }
        // This callback is intentionally outside the access and playback locks. The runtime may
        // fence a previous helper by calling back into server.supersede synchronously.
        boolean accepted = false;
        try {
            accepted = stream.admitted(session) && access.isCurrent(session);
            if (!accepted) {
                json(socket, reply, 409, "busy", null);
                return;
            }
            json(socket, reply, 200, "ready", "viewer=" + session + "; Path=/; Secure; HttpOnly; SameSite=Strict");
        } finally {
            access.cancelAdmission(session);
            // A timeout before commit leaves the old viewer untouched. If commit already ran,
            // release the unacknowledged new owner instead of leaking its live source.
            if (!accepted && !Objects.equals(previous, session)) {
                try { stop(session, socket, "origin=admission_failed"); }
                catch (RuntimeException cleanupFailure) {
                    CameraViewingDiagnostics.failure("viewer_admission_cleanup", "shared", cleanupFailure);
                }
            }
        }
    }

    private void servePage(Socket socket, boolean head, Reply reply) throws IOException {
        String page = new String(stream.resource("index.html"), StandardCharsets.UTF_8);
        JSONObject bootstrap = stream.presentation();
        try {
            bootstrap = new JSONObject(bootstrap.toString());
            bootstrap.put("passwordRequired", passwordRequired);
            bootstrap.put("webRevision", ViewerStopReason.WEB_REVISION);
        } catch (JSONException failure) {
            throw new IOException("Cannot encode camera viewer presentation", failure);
        }
        page = page.replace("<!--VIEWER_BOOTSTRAP-->", jsonForHtml(bootstrap.toString()));
        serveText(socket, head, reply, "text/html; charset=utf-8", page);
    }

    private boolean authorized(String session, Socket socket, Reply reply) throws IOException {
        if (!access.accepts(session, now())) {
            json(socket, reply, 401, access.wasReplaced(session) ? "replaced" : "unauthorized", null);
            return false;
        }
        if (!registerSessionSocket(session, socket)) {
            json(socket, reply, 401, access.wasReplaced(session) ? "replaced" : "unauthorized", null);
            return false;
        }
        final boolean current;
        try { current = stream.current(session); }
        catch (RuntimeException failure) { throw failure; }
        if (!current) {
            supersede(session, socket);
            json(socket, reply, 401, "replaced", null);
            return false;
        }
        return true;
    }

    private boolean logPlaybackSnapshot(JSONObject value) {
        String detail = ViewerStopReason.playbackSnapshot(value);
        if (detail == null) return false;
        FragmentedVideoWindow window = stream.video();
        if (window == null || !window.getStreamId().equals(value.optString("streamId"))) return false;
        long time = now(), previous = lastPlaybackSnapshot.get();
        if ((previous == Long.MIN_VALUE || time - previous >= 10_000)
                && lastPlaybackSnapshot.compareAndSet(previous, time))
            CameraViewingDiagnostics.detail("browser_playback_snapshot", stream.presentation().optString("provider"),
                    "stream=" + window.getStreamId() + " " + detail);
        return true;
    }

    private void stop(String session, Socket responseSocket, String reason) {
        closePlayback(session);
        if (access.release(session)) {
            synchronized (playbackLock) { archiveSelections.remove(session); }
            closeSessionSockets(session, responseSocket);
            stream.disconnected(session, reason);
        }
    }

    private void serveThumbnail(Socket socket, boolean head, String session, String id, Reply reply)
            throws Exception {
        if (!transferCurrent(session, socket)) { json(socket, reply, 401, "replaced", null); return; }
        ParcelFileDescriptor descriptor = stream.recordings().openThumbnail(id);
        if (descriptor == null) { json(socket, reply, 404, "not_found", null); return; }
        try (ParcelFileDescriptor closeable = descriptor) {
            long length = closeable.getStatSize();
            if (length < 0) throw new IOException("Cached Motion thumbnail length is unavailable");
            writeHeaders(socket, reply, 200, "image/png", length, null, null);
            if (head) return;
            byte[] buffer = new byte[16 * 1024];
            long offset = 0;
            while (offset < length) {
                if (!transferCurrent(session)) throw new SocketException("Viewer session is no longer current");
                int count = (int) Math.min(buffer.length, length - offset);
                int read;
                try { read = Os.pread(closeable.getFileDescriptor(), buffer, 0, count, offset); }
                catch (ErrnoException failure) {
                    if (socket.isClosed() || !transferCurrent(session))
                        throw cancelledTransfer(failure);
                    throw new IOException("Cannot read cached Motion thumbnail", failure);
                }
                if (read <= 0) throw new EOFException("Cached Motion thumbnail ended before its indexed size");
                socket.getOutputStream().write(buffer, 0, read);
                offset += read;
            }
            socket.getOutputStream().flush();
        }
    }

    private void serveVideo(Socket socket, Request request, String id, long revision, Reply reply) throws Exception {
        SessionPlayback playback;
        try { playback = playbackFor(request.session, id, revision, socket); }
        catch (java.io.FileNotFoundException missing) { json(socket, reply, 404, "not_found", null); return; }
        catch (HttpFailure expired) { json(socket, reply, expired.status, expired.bodyStatus, null); return; }
        long transfer = transferIds.incrementAndGet(), sent = 0, promised = 0;
        String context = "transfer=" + transfer + " record=" + id + " revision=" + revision;
        String outcome = "opening";
        CameraViewingDiagnostics.detail("archive_transfer_begin", "shared", context);
        try (CameraViewerArchive.RangeFile closeable = playback.playback.openDescriptor()) {
            if (!transferCurrent(request.session, socket) || !registerTransfer(request.session, playback, socket)) {
                outcome = "superseded";
                json(socket, reply, 409, "stale_archive_selection", null);
                return;
            }
            HttpByteRange range;
            try { range = HttpByteRange.parse(request.fields.get("range"), closeable.contentLength); }
            catch (IllegalArgumentException invalid) {
                outcome = "invalid_range";
                writeHeaders(socket, reply, 416, "video/mp4", 0,
                        "Accept-Ranges: bytes\r\nContent-Range: bytes */" + closeable.contentLength + "\r\n", null);
                return;
            }
            int status = range.partial ? 206 : 200;
            promised = request.method.equals("HEAD") ? 0 : range.length();
            CameraViewingDiagnostics.detail("archive_transfer_range", "shared",
                    context + " start=" + range.start + " bytes=" + promised);
            String extra = "Accept-Ranges: bytes\r\n" + (range.partial
                    ? "Content-Range: " + range.contentRange() + "\r\n" : "");
            writeHeaders(socket, reply, status, "video/mp4", range.length(), extra, null);
            if (request.method.equals("HEAD")) { outcome = "complete"; return; }

            byte[] buffer = new byte[VIDEO_CHUNK];
            long offset = range.start;
            long remaining = range.length();
            while (remaining > 0) {
                if (!transferCurrent(request.session) || !isTransferRegistered(request.session, playback, socket))
                    throw new SocketException("Viewer session or recording was superseded");
                int requested = (int) Math.min(buffer.length, remaining);
                int read;
                try {
                    read = Os.pread(closeable.descriptor.getFileDescriptor(), buffer,
                            0, requested, offset);
                } catch (ErrnoException failure) {
                    if (socket.isClosed() || !isTransferRegistered(request.session, playback, socket))
                        throw cancelledTransfer(failure);
                    throw new IOException("Cannot read original Motion recording range", failure);
                }
                if (read <= 0) throw new EOFException("Motion recording ended before the requested range");
                socket.getOutputStream().write(buffer, 0, read);
                sent += read;
                offset += read;
                remaining -= read;
            }
            socket.getOutputStream().flush();
            outcome = "complete";
        } catch (Exception failure) {
            if (!isPlaybackCurrent(request.session, playback)) {
                outcome = "cancelled";
                CameraViewingDiagnostics.detail("archive_transfer_cancelled", "shared",
                        context + " reason=" + playback.closeReason + " cause=" + CameraViewingDiagnostics.describe(failure));
                if (!reply.started && !socket.isClosed()) try {
                    json(socket, reply, 409, "stale_archive_selection", null);
                } catch (IOException responseFailure) {
                    failure.addSuppressed(responseFailure);
                    CameraViewingDiagnostics.failure("archive_cancel_response_" + transfer, "shared", failure);
                }
                return;
            }
            outcome = "failed";
            CameraViewingDiagnostics.failure("archive_transfer_failed_" + transfer, "shared", failure);
            if (failure instanceof java.io.FileNotFoundException && !reply.started) {
                json(socket, reply, 404, "not_found", null);
                return;
            }
            throw failure;
        } finally {
            unregisterTransfer(request.session, playback, socket);
            CameraViewingDiagnostics.detail("archive_transfer_end", "shared",
                    context + " outcome=" + outcome + " promised=" + promised + " sent=" + sent
                            + " closeReason=" + playback.closeReason);
        }
    }

    private SessionPlayback playbackFor(String session, String id, long revision, Socket requestSocket) throws Exception {
        if (!transferCurrent(session, requestSocket))
            throw new HttpFailure(401, "replaced", null);
        synchronized (playbackLock) {
            if (closed || !access.isCurrent(session))
                throw new java.io.FileNotFoundException("Viewer session is no longer current");
            SessionPlayback current = playbacks.get(session);
            ArchiveSelection selected = archiveSelections.get(session);
            if (current != null && current.revision == revision && selected != null && selected.matches(revision, id)) return current;
            throw new HttpFailure(409, "stale_archive_selection", null);
        }
    }

    private boolean isPlaybackCurrent(String session, SessionPlayback playback) {
        synchronized (playbackLock) {
            return !closed && access.isCurrent(session) && playbacks.get(session) == playback;
        }
    }

    private void closePlayback(String session) {
        synchronized (playbackLock) {
            ArchiveSelection selected = archiveSelections.get(session);
            if (selected != null) selected.clear();
            closePlayback(playbacks.remove(session), "mode_or_session_closed");
            if (!access.isCurrent(session)) archiveSelections.remove(session);
        }
    }

    private void archiveMode(String session, long revision, boolean open) throws IOException {
        synchronized (playbackLock) {
            if (closed || !access.isCurrent(session)) throw new HttpFailure(401, "replaced", null);
            ArchiveSelection selected = archiveSelections.computeIfAbsent(session, ignored -> new ArchiveSelection());
            if (!selected.mode(revision, open)) throw new HttpFailure(409, "stale_live_selection", null);
            closePlayback(playbacks.remove(session), open ? "archive_entered" : "live_selected");
        }
    }

    private void supersede(String session, Socket except) {
        access.supersede(session);
        closePlayback(session);
        closeSessionSockets(session, except);
    }

    private boolean registerSessionSocket(String session, Socket socket) {
        synchronized (sessionSocketLock) {
            if (closed || !access.isCurrent(session)) return false;
            sessionSockets.computeIfAbsent(session, ignored -> new HashSet<>()).add(socket);
            socketSessions.put(socket, session);
            return true;
        }
    }

    private void unregisterSessionSocket(Socket socket) {
        synchronized (sessionSocketLock) {
            String session = socketSessions.remove(socket);
            if (session == null) return;
            Set<Socket> active = sessionSockets.get(session);
            if (active == null) return;
            active.remove(socket);
            if (active.isEmpty()) sessionSockets.remove(session);
        }
    }

    private void closeSessionSockets(String session, Socket except) {
        ArrayList<Socket> active;
        synchronized (sessionSocketLock) {
            Set<Socket> current = sessionSockets.get(session);
            if (current == null) return;
            active = new ArrayList<>(current);
        }
        for (Socket socket : active) {
            if (socket == except) continue;
            try { socket.close(); }
            catch (IOException failure) {
                CameraViewingDiagnostics.failure("viewer_session_socket_close", "shared", failure);
            }
        }
    }

    private static void closePlayback(SessionPlayback playback) {
        closePlayback(playback, "server_shutdown");
    }

    private static void closePlayback(SessionPlayback playback, String reason) {
        if (playback == null) return;
        playback.closeReason = reason;
        CameraViewingDiagnostics.detail("archive_playback_closed", "shared",
                "record=" + playback.id + " revision=" + playback.revision + " reason=" + reason);
        for (Socket socket : new ArrayList<>(playback.transfers)) {
            try { socket.close(); }
            catch (IOException failure) {
                CameraViewingDiagnostics.failure("viewer_archive_transfer_close", "shared", failure);
            }
        }
        try { playback.playback.close(); }
        catch (IOException failure) {
            CameraViewingDiagnostics.failure("viewer_archive_lease_close", "shared", failure);
        }
    }

    private boolean registerTransfer(String session, SessionPlayback playback, Socket socket) {
        synchronized (playbackLock) {
            if (closed || playbacks.get(session) != playback || !access.isCurrent(session)) return false;
            playback.transfers.add(socket);
            return true;
        }
    }

    private boolean isTransferRegistered(String session, SessionPlayback playback, Socket socket) {
        synchronized (playbackLock) {
            return !closed && playbacks.get(session) == playback && playback.transfers.contains(socket);
        }
    }

    private void unregisterTransfer(String session, SessionPlayback playback, Socket socket) {
        synchronized (playbackLock) {
            if (playbacks.get(session) == playback) playback.transfers.remove(socket);
        }
    }

    private boolean transferCurrent(String session) {
        return transferCurrent(session, null);
    }

    private boolean transferCurrent(String session, Socket except) {
        if (!access.accepts(session, now())) return false;
        if (stream.current(session)) return true;
        supersede(session, except);
        return false;
    }

    private static JSONObject bodyObject(byte[] body) throws IOException {
        try { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
        catch (JSONException malformed) { throw new HttpFailure(400, "invalid_json", null, malformed); }
    }

    private static int direction(String view) throws IOException {
        try { return RemoteCameraView.parse(view); }
        catch (IllegalArgumentException invalid) { throw new HttpFailure(400, "invalid_view", null, invalid); }
    }

    private static long selectionRevision(JSONObject input) throws IOException {
        return selectionRevision(input, "revision");
    }

    private static long selectionRevision(JSONObject input, String field) throws IOException {
        Object raw = input.opt(field);
        if (!(raw instanceof Number)) throw new HttpFailure(400, "invalid_selection_revision", null);
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < 1 || value > 9_007_199_254_740_991d
                || value != Math.floor(value))
            throw new HttpFailure(400, "invalid_selection_revision", null);
        return (long) value;
    }

    private static String archiveId(String path, String prefix) {
        if (!path.startsWith(prefix)) return null;
        String id = path.substring(prefix.length());
        return id.matches("[A-Za-z0-9_-]{43}") ? id : null;
    }

    private static boolean isRead(String method) { return method.equals("GET") || method.equals("HEAD"); }

    private static String cookie(String header) {
        if (header == null) return null;
        String found = null;
        for (String item : header.split(";")) {
            String value = item.trim();
            if (!value.startsWith("viewer=")) continue;
            if (found != null) return null;
            found = value.substring(7);
        }
        return found;
    }

    private static String jsonForHtml(String value) {
        return value.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e");
    }

    private static String expiredCookie() {
        return "viewer=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    private static String operationName(String path) {
        if (path.equals("/session")) return "session";
        if (path.equals("/live")) return "live";
        if (path.startsWith("/media/")) return "live_media";
        if (path.startsWith("/archive/video/")) return "archive_video";
        if (path.startsWith("/archive/thumbnail/")) return "archive_thumbnail";
        if (path.startsWith("/archive")) return "archive";
        if (path.equals("/status")) return "status";
        if (path.equals("/modes")) return "modes";
        if (path.equals("/settings")) return "settings";
        if (path.equals("/stop")) return "stop";
        if (path.equals("/diagnostic")) return "diagnostic";
        return "resource";
    }

    private static boolean isExpectedDisconnect(Exception failure, boolean replyStarted,
            String operation) {
        return failure instanceof SocketException
                || (failure instanceof EOFException && !replyStarted && "request".equals(operation));
    }

    private static SocketException cancelledTransfer(Throwable cause) {
        SocketException cancelled = new SocketException("Viewer transfer was cancelled");
        cancelled.initCause(cause);
        return cancelled;
    }

    /** Parser errors can include their entire input; keep the cause classes and stacks, not payload. */
    private static String parserFailureWithoutPayload(Throwable failure) {
        StringBuilder detail = new StringBuilder(
                "Malformed JSON request body; parser messages omitted because they may contain payload data\n");
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            detail.append(current.getClass().getName()).append('\n');
            for (StackTraceElement frame : current.getStackTrace())
                detail.append("  at ").append(frame).append('\n');
            current = current.getCause();
        }
        return detail.toString();
    }

    private static void serveJson(Socket socket, boolean head, Reply reply, JSONObject data) throws IOException {
        serveText(socket, head, reply, "application/json; charset=utf-8", data.toString());
    }

    private static void serveText(Socket socket, boolean head, Reply reply, String mime, String value)
            throws IOException {
        serveBytes(socket, head, reply, mime, value.getBytes(StandardCharsets.UTF_8), null);
    }

    private static void serveBytes(Socket socket, boolean head, Reply reply, String mime, byte[] body,
            String extraHeaders) throws IOException {
        writeHeaders(socket, reply, 200, mime, body.length, extraHeaders, null);
        if (!head) {
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        }
    }

    private static void json(Socket socket, Reply reply, int status, String phase, String cookie)
            throws IOException {
        json(socket, reply, status, phase, cookie, null);
    }

    private static void json(Socket socket, Reply reply, int status, String phase, String cookie,
            String extraHeaders)
            throws IOException {
        try { json(socket, reply, status, new JSONObject().put("status", phase), cookie, extraHeaders); }
        catch (JSONException impossible) { throw new IOException("Cannot encode viewer response", impossible); }
    }

    private static void json(Socket socket, Reply reply, int status, JSONObject body, String cookie,
            String extraHeaders) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        writeHeaders(socket, reply, status, "application/json; charset=utf-8", bytes.length, extraHeaders, cookie);
        if (!reply.head) socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static void methodNotAllowed(Socket socket, Reply reply) throws IOException {
        json(socket, reply, 405, "method_not_allowed", null, "Allow: GET, HEAD, POST\r\n");
    }

    private static void writeHeaders(Socket socket, Reply reply, int status, String mime, long length,
            String extraHeaders, String cookie) throws IOException {
        if (length < 0) throw new IOException("Invalid response length");
        String headers = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + ViewerStopReason.REVISION_HEADER + ": " + ViewerStopReason.WEB_REVISION + "\r\n"
                + "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n"
                + "Content-Security-Policy: default-src 'self'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; "
                + "worker-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'\r\n"
                + (extraHeaders == null ? "" : extraHeaders)
                + (cookie == null ? "" : "Set-Cookie: " + cookie + "\r\n") + "\r\n";
        reply.started = true;
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 409: return "Conflict";
            default: return "Error";
        }
    }

    @Override public void close() {
        closed = true;
        String current = access.current();
        boolean released = current != null && access.release(current);
        access.revoke();
        try { server.close(); }
        catch (IOException failure) {
            CameraViewingDiagnostics.failure("viewer_listener_close", "shared", failure);
        }
        for (Socket socket : sockets) {
            try { socket.close(); }
            catch (IOException failure) {
                CameraViewingDiagnostics.failure("viewer_request_socket_close", "shared", failure);
            }
        }
        ArrayList<SessionPlayback> active;
        synchronized (playbackLock) {
            active = new ArrayList<>(playbacks.values());
            playbacks.clear();
            archiveSelections.clear();
        }
        for (SessionPlayback playback : active) closePlayback(playback);
        requests.shutdownNow();
        if (released) {
            try { stream.disconnected(current, "origin=server_shutdown"); }
            catch (RuntimeException failure) {
                CameraViewingDiagnostics.failure("viewer_shutdown_cleanup", "shared", failure);
            }
        }
    }

    static final class Request {
        final String method;
        final String path;
        final Map<String, String> fields;
        final byte[] body;
        final String session;

        Request(String method, String path, Map<String, String> fields, byte[] body, String session) {
            this.method = method;
            this.path = path;
            this.fields = fields;
            this.body = body;
            this.session = session;
        }
    }

    private static final class Reply { boolean started; boolean head; }

    static final class HttpFailure extends IOException {
        final int status;
        final String bodyStatus;
        final String extraHeaders;

        HttpFailure(int status, String bodyStatus, String extraHeaders) {
            this(status, bodyStatus, extraHeaders, null);
        }

        HttpFailure(int status, String bodyStatus, String extraHeaders, Throwable cause) {
            super(bodyStatus, cause);
            this.status = status;
            this.bodyStatus = bodyStatus;
            this.extraHeaders = extraHeaders;
        }
    }

    private static final class SessionPlayback {
        final String id;
        final long revision;
        volatile String closeReason = "none";
        final CameraViewerArchive.Playback playback;
        final Set<Socket> transfers = ConcurrentHashMap.newKeySet();

        SessionPlayback(String id, long revision, CameraViewerArchive.Playback playback) {
            this.id = id;
            this.revision = revision;
            this.playback = playback;
        }
    }

    /** Ordered explicit selection; delayed media GETs never change it. Guarded by playbackLock. */
    static final class ArchiveSelection {
        private long revision;
        private long modeRevision;
        private String id;
        private boolean open;
        boolean mode(long next, boolean open) {
            if (next <= modeRevision) return false;
            modeRevision = next;
            this.open = open;
            id = null;
            return true;
        }
        boolean accepts(long next, String id) {
            return (id == null || open) && next > revision && next <= 9_007_199_254_740_991L;
        }
        boolean select(long next, String id) {
            if (!accepts(next, id)) return false;
            revision = next;
            this.id = id;
            return true;
        }
        boolean matches(long revision, String id) {
            return this.id != null && this.revision == revision && this.id.equals(id);
        }
        void clear() { id = null; open = false; }
    }
}
