package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;

final class LocalAdbClient {
    enum PromptMode { AUTO_ONCE, FORCE, NEVER }

    static final long AUTHORIZED_CACHE_TTL_MS = 30_000L;

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int AUTH_TIMEOUT_MS = 60_000;
    static final int EXPORT_READ_TIMEOUT_MS = 30_000;
    static final long NO_CANCELLATION = Long.MIN_VALUE;
    private static final String PREFS = "local_adb";
    private static final String AUTO_PROMPT_KEY = "auto_prompt_key";
    private static final String AUTHORIZED_KEY = "authorized_key";
    private static final String ACCESS_STATUS_KEY = "access_status";
    private static final String ACCESS_STATUS_FINGERPRINT_KEY = "access_status_fingerprint";
    private static final byte[] SHA1_DIGEST_INFO = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    private static final Object LOCK = new Object();
    private static final Object PENDING_LOCK = new Object();
    private static Socket pendingAuthorization;
    private static long cancellationGeneration;
    private static final AccessCache ACCESS_CACHE = new AccessCache();
    private static volatile AccessState currentAccessState = AccessState.unknown();
    private static volatile long accessStateVersion;
    private static volatile AccessStateListener accessStateListener;

    private LocalAdbClient() {}

    static final class AccessState {
        enum Status { UNKNOWN, OK, ERROR }

        final Status status;
        final String fingerprint;

        AccessState(Status status, String fingerprint) {
            this.status = status == null ? Status.UNKNOWN : status;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }

        static AccessState unknown() {
            return new AccessState(Status.UNKNOWN, "");
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AccessState)) return false;
            AccessState state = (AccessState) other;
            return status == state.status && fingerprint.equals(state.fingerprint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, fingerprint);
        }
    }

    interface AccessStateListener {
        void onAccessStateChanged(AccessState state);
    }

    static void setAccessStateListener(AccessStateListener listener) {
        accessStateListener = listener;
    }

    static void clearAccessStateListener(AccessStateListener listener) {
        if (accessStateListener == listener) accessStateListener = null;
    }

    static boolean hasAccessStateListenerForTest(AccessStateListener listener) {
        return accessStateListener == listener;
    }

    static AccessState readAccessState(Context context) {
        if (context == null) return AccessState.unknown();
        SharedPreferences preferences = prefs(context.getApplicationContext());
        AccessState state = decodeAccessState(
                preferences.getString(ACCESS_STATUS_KEY, ""),
                preferences.getString(ACCESS_STATUS_FINGERPRINT_KEY, ""));
        currentAccessState = state;
        return state;
    }

    static Result authorize(
            Context context, PromptMode mode, BiConsumer<String, Object[]> eventSink) {
        return authorize(context, mode, cancellationToken(), eventSink);
    }

    static Result authorize(
            Context context,
            PromptMode mode,
            long cancellationToken,
            BiConsumer<String, Object[]> eventSink) {
        Context applicationContext = context.getApplicationContext();
        long stateVersion = accessStateVersion();
        Result result;
        synchronized (LOCK) {
            if (!isCancellationTokenCurrent(cancellationToken)) {
                result = Result.superseded();
            } else {
                String fingerprint = currentFingerprint(applicationContext);
                invalidateCacheForIdentity(applicationContext, fingerprint);
                if (ACCESS_CACHE.isValid(fingerprint, mode)) {
                    result = Result.ok("", 0, fingerprint, false);
                } else {
                    result = connectOnly(applicationContext, mode, cancellationToken, eventSink);
                }
            }
        }
        notifyAccessStateChanged(stateVersion);
        return result;
    }

    static Result executeAuthorized(
            Context context, String fixedCommand, BiConsumer<String, Object[]> eventSink) {
        return executeAuthorized(context, fixedCommand, NO_CANCELLATION, eventSink);
    }

    static Result executeAuthorized(
            Context context,
            String fixedCommand,
            long cancellationToken,
            BiConsumer<String, Object[]> eventSink) {
        Context applicationContext = context.getApplicationContext();
        long stateVersion = accessStateVersion();
        Result result;
        synchronized (LOCK) {
            Connection connection = null;
            try {
                OpenResult open = Connection.open(
                        applicationContext, PromptMode.NEVER,
                        cancellationToken, eventSink);
                if (open.connection == null) {
                    result = Result.authorizationRequired(
                            open.authorizationError, open.publicKeySent, open.fingerprint);
                } else {
                    connection = open.connection;
                    if (!isCancellationTokenCurrent(cancellationToken)) {
                        result = Result.superseded();
                    } else {
                        ShellResult shell = connection.shell(fixedCommand);
                        if (!isCancellationTokenCurrent(cancellationToken)) {
                            result = Result.superseded();
                        } else {
                            result = shell.exitCode == 0
                                    ? Result.ok(shell.output, shell.exitCode, open.fingerprint, false)
                                    : Result.failed("shell_exit_" + shell.exitCode, shell.output,
                                            shell.exitCode, open.fingerprint);
                        }
                    }
                }
            } catch (AuthorizationSupersededException superseded) {
                result = Result.superseded();
            } catch (Throwable error) {
                if (!isCancellationTokenCurrent(cancellationToken)) {
                    result = Result.superseded();
                } else {
                    result = Result.failed(summary(error), "", -1, "unavailable");
                }
            } finally {
                if (connection != null) connection.close();
            }
        }
        notifyAccessStateChanged(stateVersion);
        return result;
    }

    /** Executes a fixed shell command while streaming raw stdout without UTF-8 buffering. */
    static Result executeAuthorizedStreaming(
            Context context,
            String fixedCommand,
            OutputStream output,
            long maxBytes,
            BiConsumer<String, Object[]> eventSink) {
        return executeAuthorizedStreaming(context, fixedCommand, output, maxBytes,
                READ_TIMEOUT_MS, null, eventSink);
    }

    /** Executes a bounded stream with operation-local cancellation and read timeout. */
    static Result executeAuthorizedStreaming(
            Context context,
            String fixedCommand,
            OutputStream output,
            long maxBytes,
            int readTimeoutMs,
            OperationCancellation cancellation,
            BiConsumer<String, Object[]> eventSink) {
        if (output == null) return Result.failed("output_required", "", -1, "unavailable");
        if (maxBytes < 0) return Result.failed("invalid_stream_limit", "", -1, "unavailable");
        if (readTimeoutMs <= 0) {
            return Result.failed("invalid_read_timeout", "", -1, "unavailable");
        }
        Context applicationContext = context.getApplicationContext();
        long stateVersion = accessStateVersion();
        Result result;
        synchronized (LOCK) {
            Connection connection = null;
            String operationFingerprint = "";
            try {
                OpenResult open = Connection.open(
                        applicationContext, PromptMode.NEVER,
                        NO_CANCELLATION, eventSink, cancellation);
                operationFingerprint = open.fingerprint;
                if (open.connection == null) {
                    result = Result.authorizationRequired(
                            open.authorizationError, open.publicKeySent, open.fingerprint);
                } else {
                    connection = open.connection;
                    if (cancellation != null && cancellation.isCancellationRequested()) {
                        result = Result.cancelled();
                    } else {
                        // Connection.open keeps its handshake timeout unchanged. Export-only streams
                        // switch to their longer inactivity timeout after CNXN is received.
                        connection.setReadTimeout(readTimeoutMs);
                        StreamShellResult shell = connection.shellTo(
                                fixedCommand, output, maxBytes, cancellation);
                        result = shell.exitCode == 0
                                ? Result.ok("", shell.exitCode, open.fingerprint, false)
                                : Result.failed("shell_exit_" + shell.exitCode, "",
                                        shell.exitCode, open.fingerprint);
                    }
                }
            } catch (TooLargeException tooLarge) {
                result = Result.failed("too_large", "", -1, "unavailable");
            } catch (OutputSinkException sinkError) {
                result = Result.failed(summary(sinkError), "", -1,
                        operationFingerprint.isEmpty() ? "unavailable" : operationFingerprint);
            } catch (Throwable error) {
                if (cancellation != null && cancellation.isCancellationRequested()) {
                    result = Result.cancelled();
                } else {
                    result = Result.failed(summary(error), "", -1, "unavailable");
                }
            } finally {
                if (connection != null) connection.close();
            }
        }
        notifyAccessStateChanged(stateVersion);
        return result;
    }

    static Result executeAuthorizedText(
            Context context,
            String fixedCommand,
            long maxBytes,
            BiConsumer<String, Object[]> eventSink) {
        return executeAuthorizedText(context, fixedCommand, maxBytes,
                READ_TIMEOUT_MS, null, eventSink);
    }

    static Result executeAuthorizedText(
            Context context,
            String fixedCommand,
            long maxBytes,
            int readTimeoutMs,
            OperationCancellation cancellation,
            BiConsumer<String, Object[]> eventSink) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Result result = executeAuthorizedStreaming(
                context, fixedCommand, output, maxBytes, readTimeoutMs,
                cancellation, eventSink);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (result.ok) {
            return Result.ok(text, result.exitCode, result.fingerprint, result.publicKeySent);
        }
        return result.authorizationRequired
                ? Result.authorizationRequired(result.error, result.publicKeySent, result.fingerprint)
                : Result.failed(result.error, text, result.exitCode, result.fingerprint);
    }

    static Result parseStreamingResponseForTest(
            byte[] response, String marker, OutputStream output, long maxBytes) {
        try {
            StreamShellResult shell = Connection.finishStream(
                    output, maxBytes, 0L, response,
                    marker.getBytes(StandardCharsets.US_ASCII));
            return shell.exitCode == 0
                    ? Result.ok("", shell.exitCode, "test", false)
                    : Result.failed("shell_exit_" + shell.exitCode, "",
                            shell.exitCode, "test");
        } catch (TooLargeException tooLarge) {
            return Result.failed("too_large", "", -1, "test");
        } catch (IOException error) {
            return Result.failed(summary(error), "", -1, "test");
        }
    }

    private static Result connectOnly(
            Context context,
            PromptMode mode,
            long cancellationToken,
            BiConsumer<String, Object[]> eventSink) {
        Connection connection = null;
        try {
            OpenResult open = Connection.open(context, mode, cancellationToken, eventSink);
            connection = open.connection;
            return connection == null
                    ? Result.authorizationRequired(
                            open.authorizationError, open.publicKeySent, open.fingerprint)
                    : Result.ok("", 0, open.fingerprint, open.publicKeySent);
        } catch (AuthorizationSupersededException superseded) {
            return Result.superseded();
        } catch (Throwable error) {
            return Result.failed(summary(error), "", -1, "unavailable");
        } finally {
            if (connection != null) connection.close();
        }
    }

    static boolean cancelPendingAuthorization() {
        Socket socket;
        synchronized (PENDING_LOCK) {
            cancellationGeneration++;
            socket = pendingAuthorization;
            pendingAuthorization = null;
        }
        if (socket == null) return false;
        close(socket);
        return true;
    }

    static long cancellationToken() {
        return cancellationGeneration();
    }

    static boolean isCancellationTokenCurrent(long token) {
        return token == NO_CANCELLATION || cancellationGeneration() == token;
    }

    static byte[] signToken(PrivateKey key, byte[] token) throws Exception {
        byte[] payload = Arrays.copyOf(SHA1_DIGEST_INFO, SHA1_DIGEST_INFO.length + token.length);
        System.arraycopy(token, 0, payload, SHA1_DIGEST_INFO.length, token.length);
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(key);
        signature.update(payload);
        return signature.sign();
    }

    static String endpointForTest() {
        return HOST + ":" + PORT;
    }

    static String keyFingerprint(Context context) {
        String value = currentFingerprint(context.getApplicationContext());
        invalidateCacheForIdentity(context, value);
        return value;
    }

    static boolean shouldSendPublicKey(
            PromptMode mode, boolean alreadyPrompted, boolean authorizedFingerprintRejected) {
        return mode == PromptMode.FORCE
                || mode == PromptMode.AUTO_ONCE
                && (authorizedFingerprintRejected || !alreadyPrompted);
    }

    static AccessState decodeAccessStateForTest(String status, String fingerprint) {
        return decodeAccessState(status, fingerprint);
    }

    static boolean shouldInvalidateAccess(Throwable error, boolean cancelledOrSuperseded) {
        return !cancelledOrSuperseded
                && !(error instanceof OutputSinkException)
                && !(error instanceof TooLargeException)
                && !(error instanceof OperationCancelledException)
                && !(error instanceof AuthorizationSupersededException);
    }

    static final class AccessCache {
        interface Clock {
            long elapsedRealtime();
        }

        private final Clock clock;
        private String fingerprint = "";
        private long successAt = Long.MIN_VALUE;

        AccessCache() {
            this(SystemClock::elapsedRealtime);
        }

        AccessCache(Clock clock) {
            this.clock = clock;
        }

        synchronized boolean isValid(String identity, PromptMode mode) {
            if (mode == PromptMode.FORCE || mode == null || identity == null
                    || identity.isEmpty() || !identity.equals(fingerprint)) {
                if (!identityEquals(identity)) clear();
                return false;
            }
            long age = clock.elapsedRealtime() - successAt;
            if (successAt == Long.MIN_VALUE || age < 0L || age >= AUTHORIZED_CACHE_TTL_MS) {
                clear();
                return false;
            }
            return true;
        }

        synchronized void markSuccess(String identity) {
            if (identity == null || identity.isEmpty()) {
                clear();
                return;
            }
            fingerprint = identity;
            successAt = clock.elapsedRealtime();
        }

        synchronized void invalidate() {
            clear();
        }

        synchronized boolean invalidateIfIdentityChanged(String identity) {
            if (fingerprint.isEmpty() || Objects.equals(fingerprint, identity)) return false;
            clear();
            return true;
        }

        synchronized long successAtForTest() {
            return successAt;
        }

        synchronized String fingerprintForTest() {
            return fingerprint;
        }

        private boolean identityEquals(String identity) {
            return Objects.equals(fingerprint, identity);
        }

        private void clear() {
            fingerprint = "";
            successAt = Long.MIN_VALUE;
        }
    }

    private static final class Connection {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final OperationCancellation cancellation;

        private Connection(Socket socket, OperationCancellation cancellation) throws IOException {
            this.socket = socket;
            in = socket.getInputStream();
            out = socket.getOutputStream();
            this.cancellation = cancellation;
        }

        static OpenResult open(
                Context context,
                PromptMode mode,
                BiConsumer<String, Object[]> eventSink) throws Exception {
            return open(context, mode, cancellationToken(), eventSink, null);
        }

        static OpenResult open(
                Context context,
                PromptMode mode,
                long authGeneration,
                BiConsumer<String, Object[]> eventSink) throws Exception {
            return open(context, mode, authGeneration, eventSink, null);
        }

        static OpenResult open(
                Context context,
                PromptMode mode,
                long authGeneration,
                BiConsumer<String, Object[]> eventSink,
                OperationCancellation cancellation) throws Exception {
            KeyPair keys;
            String fingerprint = "unavailable";
            Socket socket = new Socket();
            if (cancellation != null) cancellation.registerActiveSocket(socket);
            try {
                keys = loadOrCreateKeys(context);
                fingerprint = fingerprint(keys);
                invalidateCacheForIdentity(context, fingerprint);
                if (cancellation != null && cancellation.isCancellationRequested()) {
                    throw new OperationCancelledException();
                }
                if (!isCancellationTokenCurrent(authGeneration)) {
                    throw new AuthorizationSupersededException();
                }
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                emitStage(eventSink, "socket_connected", "fingerprint", fingerprint,
                        "endpoint", endpointForTest());
                Connection connection = new Connection(socket, cancellation);
                AdbPacket.write(connection.out, AdbPacket.A_CNXN, AdbPacket.VERSION,
                        AdbPacket.MAX_DATA, nul("host::"));
                boolean signatureSent = false;
                boolean publicKeySent = false;
                while (true) {
                    AdbPacket packet;
                    try {
                        packet = AdbPacket.read(connection.in);
                    } catch (SocketTimeoutException timeout) {
                        if (publicKeySent) {
                            emitStage(eventSink, "authorization_timeout",
                                    "fingerprint", fingerprint,
                                    "public_key_sent", true);
                            recordAccessError(context, fingerprint);
                            connection.close();
                            return OpenResult.authorizationRequired(
                                    "authorization_prompt_timeout", true, fingerprint);
                        }
                        throw timeout;
                    }
                    if (packet.command == AdbPacket.A_CNXN) {
                        if (cancellation != null && cancellation.isCancellationRequested()) {
                            throw new OperationCancelledException();
                        }
                        if (!isCancellationTokenCurrent(authGeneration)) {
                            throw new AuthorizationSupersededException();
                        }
                        emitStage(eventSink, "cnxn_received", "fingerprint", fingerprint,
                                "public_key_sent", publicKeySent);
                        clearPending(socket);
                        markAuthorized(context, fingerprint);
                        recordAccessSuccess(context, fingerprint);
                        return OpenResult.connected(connection, publicKeySent, fingerprint);
                    }
                    if (packet.command != AdbPacket.A_AUTH
                            || packet.arg0 != AdbPacket.AUTH_TOKEN) {
                        throw new IOException("Unexpected ADB handshake packet");
                    }
                    if (!signatureSent) {
                        emitStage(eventSink, "first_auth_token_received",
                                "fingerprint", fingerprint,
                                "token_bytes", packet.payload.length);
                        AdbPacket.write(connection.out, AdbPacket.A_AUTH,
                                AdbPacket.AUTH_SIGNATURE, 0,
                                signToken(keys.getPrivate(), packet.payload));
                        emitStage(eventSink, "signature_sent", "fingerprint", fingerprint,
                                "token_bytes", packet.payload.length);
                        signatureSent = true;
                        continue;
                    }
                    if (!publicKeySent) {
                        emitStage(eventSink, "second_auth_token_received",
                                "fingerprint", fingerprint,
                                "token_bytes", packet.payload.length);
                    }
                    boolean authorizedFingerprintRejected = isAuthorized(context, fingerprint);
                    if (authorizedFingerprintRejected) clearAuthorized(context);
                    if (publicKeySent) {
                        emitStage(eventSink, "authorization_rejected",
                                "fingerprint", fingerprint,
                                "public_key_sent", true,
                                "token_bytes", packet.payload.length);
                        recordAccessError(context, fingerprint);
                        connection.close();
                        return OpenResult.authorizationRequired(
                                "authorization_rejected", true, fingerprint);
                    }
                    if (!shouldSendPublicKey(mode, alreadyPrompted(context, fingerprint),
                            authorizedFingerprintRejected)) {
                        emitStage(eventSink, "authorization_required",
                                "fingerprint", fingerprint,
                                "public_key_sent", false);
                        recordAccessError(context, fingerprint);
                        connection.close();
                        return OpenResult.authorizationRequired(
                                "authorization_required", false, fingerprint);
                    }
                    String publicKey = AdbKeyFormatter.formatPublicKey(
                            (RSAPublicKey) keys.getPublic());
                    if (!trackPending(socket, authGeneration)) {
                        throw new AuthorizationSupersededException();
                    }
                    AdbPacket.write(connection.out, AdbPacket.A_AUTH,
                            AdbPacket.AUTH_RSAPUBLICKEY, 0,
                            nul(publicKey));
                    markPrompted(context, fingerprint);
                    emitStage(eventSink, "public_key_sent", "fingerprint", fingerprint,
                            "public_key", publicKey,
                            "payload_bytes", publicKey.getBytes(StandardCharsets.UTF_8).length + 1);
                    publicKeySent = true;
                    socket.setSoTimeout(AUTH_TIMEOUT_MS);
                }
            } catch (Throwable error) {
                clearPending(socket);
                if (cancellation != null) cancellation.clearActiveSocket(socket);
                LocalAdbClient.close(socket);
                if ("unavailable".equals(fingerprint)) {
                    invalidateCacheForIdentity(context, fingerprint);
                }
                if (cancellation != null && cancellation.isCancellationRequested()) {
                    throw new OperationCancelledException();
                }
                if (error instanceof AuthorizationSupersededException
                        || !isCancellationTokenCurrent(authGeneration)) {
                    emitStage(eventSink, "socket_cancelled", "fingerprint", fingerprint);
                    throw new AuthorizationSupersededException();
                }
                recordAccessError(context, fingerprint);
                throw error;
            }
        }

        ShellResult shell(String command) throws IOException {
            int localId = 1;
            int remoteId = 0;
            String marker = "__BYD_EXTEND_EXIT_" + Long.toHexString(System.nanoTime()) + "__:";
            AdbPacket.write(out, AdbPacket.A_OPEN, localId, 0,
                    nul("shell:" + command + "\necho " + marker + "$?"));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                AdbPacket packet = AdbPacket.read(in);
                if (packet.arg1 != localId) throw new IOException("Unexpected ADB stream id");
                if (packet.command == AdbPacket.A_OKAY) {
                    if (remoteId != 0 && remoteId != packet.arg0) {
                        throw new IOException("ADB remote id changed");
                    }
                    remoteId = packet.arg0;
                } else if (packet.command == AdbPacket.A_WRTE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    if (remoteId != packet.arg0) throw new IOException("ADB WRTE remote id changed");
                    output.write(packet.payload, 0, packet.payload.length);
                    AdbPacket.write(out, AdbPacket.A_OKAY, localId, remoteId, null);
                } else if (packet.command == AdbPacket.A_CLSE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, null);
                    return ShellResult.parse(output.toString("UTF-8"), marker);
                } else {
                    throw new IOException("Unexpected ADB shell packet");
                }
            }
        }

        StreamShellResult shellTo(
                String command,
                OutputStream output,
                long maxBytes,
                OperationCancellation operationCancellation)
                throws IOException {
            int localId = 1;
            int remoteId = 0;
            String marker = "__BYD_EXTEND_STREAM_EXIT_"
                    + Long.toHexString(System.nanoTime()) + "__:";
            byte[] markerBytes = marker.getBytes(StandardCharsets.US_ASCII);
            int tailLimit = markerBytes.length + 24;
            ByteArrayOutputStream pending = new ByteArrayOutputStream(tailLimit + 4096);
            long written = 0L;
            AdbPacket.write(out, AdbPacket.A_OPEN, localId, 0,
                    nul("shell:" + command + "\necho " + marker + "$?"));
            while (true) {
                if (operationCancellation != null
                        && operationCancellation.isCancellationRequested()) {
                    throw new OperationCancelledException();
                }
                AdbPacket packet = AdbPacket.read(in);
                if (packet.arg1 != localId) throw new IOException("Unexpected ADB stream id");
                if (packet.command == AdbPacket.A_OKAY) {
                    if (remoteId != 0 && remoteId != packet.arg0) {
                        throw new IOException("ADB remote id changed");
                    }
                    remoteId = packet.arg0;
                } else if (packet.command == AdbPacket.A_WRTE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    if (remoteId != packet.arg0) throw new IOException("ADB WRTE remote id changed");
                    pending.write(packet.payload, 0, packet.payload.length);
                    if (pending.size() > tailLimit) {
                        byte[] bytes = pending.toByteArray();
                        int flush = bytes.length - tailLimit;
                        if (written > maxBytes - flush) throw new TooLargeException();
                        writeOutput(output, bytes, 0, flush);
                        written += flush;
                        pending.reset();
                        pending.write(bytes, flush, bytes.length - flush);
                    }
                    AdbPacket.write(out, AdbPacket.A_OKAY, localId, remoteId, null);
                } else if (packet.command == AdbPacket.A_CLSE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    AdbPacket.write(out, AdbPacket.A_CLSE, localId, remoteId, null);
                    return finishStream(
                            output, maxBytes, written, pending.toByteArray(), markerBytes);
                } else {
                    throw new IOException("Unexpected ADB shell packet");
                }
            }
        }

        private static StreamShellResult finishStream(
                OutputStream output,
                long maxBytes,
                long written,
                byte[] tail,
                byte[] markerBytes) throws IOException {
            int markerIndex = lastIndexOf(tail, markerBytes);
            int dataLength = markerIndex >= 0 ? markerIndex : tail.length;
            if (written > maxBytes - dataLength) throw new TooLargeException();
            if (dataLength > 0) writeOutput(output, tail, 0, dataLength);
            written += dataLength;
            int exitCode = -1;
            if (markerIndex >= 0) {
                int start = markerIndex + markerBytes.length;
                int end = start;
                while (end < tail.length && tail[end] >= '0' && tail[end] <= '9') end++;
                try {
                    exitCode = Integer.parseInt(new String(
                            tail, start, end - start, StandardCharsets.US_ASCII));
                } catch (NumberFormatException ignored) {
                    exitCode = -1;
                }
            }
            return new StreamShellResult(exitCode, written);
        }

        private static int lastIndexOf(byte[] value, byte[] needle) {
            outer: for (int i = value.length - needle.length; i >= 0; i--) {
                for (int j = 0; j < needle.length; j++) {
                    if (value[i + j] != needle[j]) continue outer;
                }
                return i;
            }
            return -1;
        }

        void close() {
            clearPending(socket);
            if (cancellation != null) cancellation.clearActiveSocket(socket);
            LocalAdbClient.close(socket);
        }

        void setReadTimeout(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs);
        }
    }

    static final class Result {
        final boolean ok;
        final boolean authorizationRequired;
        final boolean publicKeySent;
        final boolean superseded;
        final String output;
        final int exitCode;
        final String error;
        final String fingerprint;

        private Result(boolean ok, boolean authorizationRequired, boolean publicKeySent,
                boolean superseded,
                String output, int exitCode, String error, String fingerprint) {
            this.ok = ok;
            this.authorizationRequired = authorizationRequired;
            this.publicKeySent = publicKeySent;
            this.superseded = superseded;
            this.output = output;
            this.exitCode = exitCode;
            this.error = error;
            this.fingerprint = fingerprint;
        }

        static Result ok(String output, int exitCode, String fingerprint, boolean publicKeySent) {
            return new Result(true, false, publicKeySent, false,
                    output, exitCode, "", fingerprint);
        }

        static Result authorizationRequired(
                String error, boolean publicKeySent, String fingerprint) {
            return new Result(false, true, publicKeySent, false, "", -1,
                    error, fingerprint);
        }

        static Result superseded() {
            return new Result(false, false, false, true,
                    "", -1, "authorization_superseded", "unavailable");
        }

        static Result cancelled() {
            return new Result(false, false, false, false,
                    "", -1, "cancelled", "unavailable");
        }

        static Result failed(String error, String output, int exitCode, String fingerprint) {
            return new Result(false, false, false, false,
                    output, exitCode, error, fingerprint);
        }
    }

    private static final class OpenResult {
        final Connection connection;
        final boolean publicKeySent;
        final String fingerprint;
        final String authorizationError;

        private OpenResult(Connection connection, boolean publicKeySent, String fingerprint,
                String authorizationError) {
            this.connection = connection;
            this.publicKeySent = publicKeySent;
            this.fingerprint = fingerprint;
            this.authorizationError = authorizationError;
        }

        static OpenResult connected(
                Connection connection, boolean publicKeySent, String fingerprint) {
            return new OpenResult(connection, publicKeySent, fingerprint, "");
        }

        static OpenResult authorizationRequired(
                String error, boolean publicKeySent, String fingerprint) {
            return new OpenResult(null, publicKeySent, fingerprint, error);
        }
    }

    private static final class ShellResult {
        final String output;
        final int exitCode;

        ShellResult(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }

        static ShellResult parse(String raw, String marker) {
            int index = raw.lastIndexOf(marker);
            if (index < 0) return new ShellResult(raw.trim(), -1);
            int start = index + marker.length();
            int end = start;
            while (end < raw.length() && Character.isDigit(raw.charAt(end))) end++;
            try {
                return new ShellResult(raw.substring(0, index).trim(),
                        Integer.parseInt(raw.substring(start, end)));
            } catch (NumberFormatException error) {
                return new ShellResult(raw.substring(0, index).trim(), -1);
            }
        }
    }

    private static final class StreamShellResult {
        final int exitCode;
        final long bytesWritten;

        StreamShellResult(int exitCode, long bytesWritten) {
            this.exitCode = exitCode;
            this.bytesWritten = bytesWritten;
        }
    }

    static final class TooLargeException extends IOException {
        TooLargeException() { super("stream limit exceeded"); }
    }

    static final class OutputSinkException extends IOException {
        OutputSinkException(IOException cause) {
            super("output_write_failed", cause);
        }
    }

    static final class OperationCancelledException extends IOException {
        OperationCancelledException() { super("operation_cancelled"); }
    }

    interface OperationCancellation {
        boolean isCancellationRequested();
        void registerActiveSocket(Socket socket);
        void clearActiveSocket(Socket socket);
    }

    private static KeyPair loadOrCreateKeys(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "adb_keys");
        File privateFile = new File(dir, "adb_key.priv");
        File publicFile = new File(dir, "adb_key.pub");
        if (privateFile.exists()) {
            PrivateKey privateKey;
            try {
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(read(privateFile)));
            } catch (Throwable error) {
                throw new IOException("ADB private key is corrupt; refusing replacement", error);
            }
            if (!(privateKey instanceof RSAPrivateCrtKey)) {
                throw new IOException("ADB private key is not RSA CRT");
            }
            RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) privateKey;
            RSAPublicKey derived = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
            boolean writePublic = true;
            if (publicFile.exists()) {
                try {
                    RSAPublicKey persisted = (RSAPublicKey) KeyFactory.getInstance("RSA")
                            .generatePublic(new X509EncodedKeySpec(read(publicFile)));
                    writePublic = !persisted.getModulus().equals(derived.getModulus());
                } catch (Throwable ignored) {
                    writePublic = true;
                }
            }
            if (writePublic) write(publicFile, derived.getEncoded(), dir);
            return new KeyPair(derived, privateKey);
        }
        if (publicFile.exists()) {
            throw new IOException("ADB private key is missing; refusing key replacement");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair created = generator.generateKeyPair();
        write(privateFile, created.getPrivate().getEncoded(), dir);
        write(publicFile, created.getPublic().getEncoded(), dir);
        return created;
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void write(File file, byte[] value, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create adb_keys");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value);
        }
    }

    private static String fingerprint(KeyPair pair) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded());
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) result.append(':');
            result.append(String.format("%02x", digest[i] & 0xff));
        }
        return result.toString();
    }

    private static boolean alreadyPrompted(Context context, String fingerprint) {
        return promptKey(fingerprint).equals(prefs(context).getString(AUTO_PROMPT_KEY, ""));
    }

    private static void markPrompted(Context context, String fingerprint) {
        prefs(context).edit().putString(AUTO_PROMPT_KEY, promptKey(fingerprint)).apply();
    }

    private static boolean isAuthorized(Context context, String fingerprint) {
        return fingerprint.equals(prefs(context).getString(AUTHORIZED_KEY, ""));
    }

    private static void markAuthorized(Context context, String fingerprint) {
        prefs(context).edit().putString(AUTHORIZED_KEY, fingerprint).apply();
    }

    private static void clearAuthorized(Context context) {
        prefs(context).edit()
                .remove(AUTHORIZED_KEY)
                .remove(AUTO_PROMPT_KEY)
                .apply();
    }

    private static String currentFingerprint(Context context) {
        try {
            return fingerprint(loadOrCreateKeys(context));
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    private static void invalidateCacheForIdentity(Context context, String fingerprint) {
        SharedPreferences preferences = prefs(context);
        String persistedFingerprint = preferences.getString(ACCESS_STATUS_FINGERPRINT_KEY, "");
        boolean cacheChanged = ACCESS_CACHE.invalidateIfIdentityChanged(fingerprint);
        boolean persistedChanged = !persistedFingerprint.isEmpty()
                && !Objects.equals(persistedFingerprint, fingerprint);
        if (!cacheChanged && !persistedChanged) return;
        preferences.edit()
                .remove(ACCESS_STATUS_KEY)
                .remove(ACCESS_STATUS_FINGERPRINT_KEY)
                .apply();
        currentAccessState = AccessState.unknown();
        accessStateVersion++;
    }

    private static void recordAccessSuccess(Context context, String fingerprint) {
        ACCESS_CACHE.markSuccess(fingerprint);
        persistAccessState(context, new AccessState(AccessState.Status.OK, fingerprint));
    }

    private static void recordAccessError(Context context, String fingerprint) {
        ACCESS_CACHE.invalidate();
        persistAccessState(context, new AccessState(AccessState.Status.ERROR, fingerprint));
    }

    private static void persistAccessState(Context context, AccessState state) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (state.status == AccessState.Status.UNKNOWN) {
            editor.remove(ACCESS_STATUS_KEY).remove(ACCESS_STATUS_FINGERPRINT_KEY);
        } else {
            editor.putString(ACCESS_STATUS_KEY, state.status.name())
                    .putString(ACCESS_STATUS_FINGERPRINT_KEY, state.fingerprint);
        }
        editor.apply();
        currentAccessState = state;
        accessStateVersion++;
    }

    private static AccessState decodeAccessState(String status, String fingerprint) {
        if (AccessState.Status.OK.name().equals(status)) {
            return new AccessState(AccessState.Status.OK, fingerprint);
        }
        if (AccessState.Status.ERROR.name().equals(status)) {
            return new AccessState(AccessState.Status.ERROR, fingerprint);
        }
        return AccessState.unknown();
    }

    private static long accessStateVersion() {
        return accessStateVersion;
    }

    private static void notifyAccessStateChanged(long priorVersion) {
        if (accessStateVersion == priorVersion) return;
        AccessStateListener listener = accessStateListener;
        if (listener == null) return;
        try {
            listener.onAccessStateChanged(currentAccessState);
        } catch (Throwable ignored) {
            // UI observers are best-effort and must not change ADB operation results.
        }
    }

    private static String promptKey(String fingerprint) {
        return BuildConfig.VERSION_CODE + ":" + fingerprint;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static byte[] nul(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(bytes, bytes.length + 1);
    }

    private static boolean trackPending(Socket socket, long generation) {
        synchronized (PENDING_LOCK) {
            if (generation != cancellationGeneration) return false;
            pendingAuthorization = socket;
            return true;
        }
    }

    private static long cancellationGeneration() {
        synchronized (PENDING_LOCK) {
            return cancellationGeneration;
        }
    }

    private static void clearPending(Socket socket) {
        synchronized (PENDING_LOCK) {
            if (pendingAuthorization == socket) pendingAuthorization = null;
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void writeOutput(
            OutputStream output, byte[] bytes, int offset, int length) throws OutputSinkException {
        try {
            output.write(bytes, offset, length);
        } catch (IOException error) {
            throw new OutputSinkException(error);
        }
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void emitStage(
            BiConsumer<String, Object[]> eventSink, String stage, Object... fields) {
        if (eventSink == null) return;
        Object[] payload = new Object[fields.length + 2];
        payload[0] = "stage";
        payload[1] = stage;
        System.arraycopy(fields, 0, payload, 2, fields.length);
        eventSink.accept("local_adb_stage", payload);
    }

    static final class AuthorizationSupersededException extends IOException {
        AuthorizationSupersededException() {
            super("ADB authorization superseded");
        }
    }
}
