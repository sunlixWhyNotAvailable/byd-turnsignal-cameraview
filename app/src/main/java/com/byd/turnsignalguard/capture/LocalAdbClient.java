package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.function.BiConsumer;

final class LocalAdbClient {
    enum PromptMode { AUTO_ONCE, FORCE, NEVER }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int AUTH_TIMEOUT_MS = 60_000;
    static final long NO_CANCELLATION = Long.MIN_VALUE;
    private static final String PREFS = "local_adb";
    private static final String AUTO_PROMPT_KEY = "auto_prompt_key";
    private static final String AUTHORIZED_KEY = "authorized_key";
    private static final byte[] SHA1_DIGEST_INFO = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    private static final Object LOCK = new Object();
    private static final Object PENDING_LOCK = new Object();
    private static Socket pendingAuthorization;
    private static long cancellationGeneration;

    private LocalAdbClient() {}

    static Result authorize(
            Context context, PromptMode mode, BiConsumer<String, Object[]> eventSink) {
        return authorize(context, mode, cancellationToken(), eventSink);
    }

    static Result authorize(
            Context context,
            PromptMode mode,
            long cancellationToken,
            BiConsumer<String, Object[]> eventSink) {
        synchronized (LOCK) {
            return connectOnly(
                    context.getApplicationContext(), mode, cancellationToken, eventSink);
        }
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
        synchronized (LOCK) {
            Connection connection = null;
            try {
                OpenResult open = Connection.open(
                        context.getApplicationContext(), PromptMode.NEVER,
                        cancellationToken, eventSink);
                if (open.connection == null) {
                    return Result.authorizationRequired(
                            open.authorizationError, open.publicKeySent, open.fingerprint);
                }
                connection = open.connection;
                if (!isCancellationTokenCurrent(cancellationToken)) {
                    return Result.superseded();
                }
                ShellResult shell = connection.shell(fixedCommand);
                if (!isCancellationTokenCurrent(cancellationToken)) {
                    return Result.superseded();
                }
                return shell.exitCode == 0
                        ? Result.ok(shell.output, shell.exitCode, open.fingerprint, false)
                        : Result.failed("shell_exit_" + shell.exitCode, shell.output,
                                shell.exitCode, open.fingerprint);
            } catch (AuthorizationSupersededException superseded) {
                return Result.superseded();
            } catch (Throwable error) {
                if (!isCancellationTokenCurrent(cancellationToken)) {
                    return Result.superseded();
                }
                return Result.failed(summary(error), "", -1, "unavailable");
            } finally {
                if (connection != null) connection.close();
            }
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
        try {
            return fingerprint(loadOrCreateKeys(context.getApplicationContext()));
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    static boolean shouldSendPublicKey(
            PromptMode mode, boolean alreadyPrompted, boolean authorizedFingerprintRejected) {
        return mode == PromptMode.FORCE
                || mode == PromptMode.AUTO_ONCE
                && (authorizedFingerprintRejected || !alreadyPrompted);
    }

    private static final class Connection {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        static OpenResult open(
                Context context,
                PromptMode mode,
                BiConsumer<String, Object[]> eventSink) throws Exception {
            return open(context, mode, cancellationToken(), eventSink);
        }

        static OpenResult open(
                Context context,
                PromptMode mode,
                long authGeneration,
                BiConsumer<String, Object[]> eventSink) throws Exception {
            KeyPair keys = loadOrCreateKeys(context);
            String fingerprint = fingerprint(keys);
            Socket socket = new Socket();
            try {
                if (!isCancellationTokenCurrent(authGeneration)) {
                    throw new AuthorizationSupersededException();
                }
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                emitStage(eventSink, "socket_connected", "fingerprint", fingerprint,
                        "endpoint", endpointForTest());
                Connection connection = new Connection(socket);
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
                            connection.close();
                            return OpenResult.authorizationRequired(
                                    "authorization_prompt_timeout", true, fingerprint);
                        }
                        throw timeout;
                    }
                    if (packet.command == AdbPacket.A_CNXN) {
                        if (!isCancellationTokenCurrent(authGeneration)) {
                            throw new AuthorizationSupersededException();
                        }
                        emitStage(eventSink, "cnxn_received", "fingerprint", fingerprint,
                                "public_key_sent", publicKeySent);
                        clearPending(socket);
                        markAuthorized(context, fingerprint);
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
                        connection.close();
                        return OpenResult.authorizationRequired(
                                "authorization_rejected", true, fingerprint);
                    }
                    if (!shouldSendPublicKey(mode, alreadyPrompted(context, fingerprint),
                            authorizedFingerprintRejected)) {
                        emitStage(eventSink, "authorization_required",
                                "fingerprint", fingerprint,
                                "public_key_sent", false);
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
                LocalAdbClient.close(socket);
                if (error instanceof AuthorizationSupersededException
                        || !isCancellationTokenCurrent(authGeneration)) {
                    emitStage(eventSink, "socket_cancelled", "fingerprint", fingerprint);
                    throw new AuthorizationSupersededException();
                }
                throw error;
            }
        }

        ShellResult shell(String command) throws IOException {
            int localId = 1;
            int remoteId = 0;
            String marker = "__BYD_TURN_GUARD_EXIT_" + Long.toHexString(System.nanoTime()) + "__:";
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

        void close() {
            clearPending(socket);
            LocalAdbClient.close(socket);
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

    private static final class AuthorizationSupersededException extends IOException {
        AuthorizationSupersededException() {
            super("ADB authorization superseded");
        }
    }
}
