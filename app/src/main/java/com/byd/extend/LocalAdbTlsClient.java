package com.byd.extend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/** Local-loopback-only ADB STLS bridge. */
final class LocalAdbTlsClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    private LocalAdbTlsClient(Socket socket) throws IOException {
        this.socket = socket;
        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    static LocalAdbTlsClient connect(int port, AdbTlsIdentity identity) throws Exception {
        if (port < 1 || port > 65535) throw new IOException("Invalid local ADB TLS port");
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            plain.setSoTimeout(READ_TIMEOUT_MS);
            plain.setTcpNoDelay(true);
            AdbPacket.write(plain.getOutputStream(), AdbPacket.A_CNXN,
                    AdbPacket.VERSION, AdbPacket.MAX_DATA, nul("host::"));
            AdbPacket first = AdbPacket.read(plain.getInputStream());
            if (first.command != AdbPacket.A_STLS) {
                throw new IOException("Discovered endpoint did not request STLS");
            }
            AdbPacket.write(plain.getOutputStream(), AdbPacket.A_STLS,
                    0x01000000, 0, new byte[0]);
            SSLContext context = tlsContext(identity);
            SSLSocket encrypted = (SSLSocket) context.getSocketFactory()
                    .createSocket(plain, "127.0.0.1", port, true);
            encrypted.setSoTimeout(READ_TIMEOUT_MS);
            encrypted.startHandshake();
            LocalAdbTlsClient client = new LocalAdbTlsClient(encrypted);
            try {
                AdbPacket packet = AdbPacket.read(client.input);
                if (packet.command == AdbPacket.A_AUTH
                        && packet.arg0 == AdbPacket.AUTH_TOKEN) {
                    AdbPacket.write(client.output, AdbPacket.A_AUTH,
                            AdbPacket.AUTH_SIGNATURE, 0,
                            LocalAdbClient.signToken(identity.keyPair.getPrivate(), packet.payload));
                    packet = AdbPacket.read(client.input);
                }
                if (packet.command != AdbPacket.A_CNXN) {
                    throw new IOException("Existing Extend RSA key rejected by ADB TLS");
                }
                return client;
            } catch (Throwable error) {
                client.close();
                throw error;
            }
        } catch (Throwable error) {
            try { plain.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    /** Returns after a normal close or expected restart EOF; only fresh classic proof is success. */
    String requestTcpip5555() throws IOException {
        int local = 1;
        int remote = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AdbPacket.write(output, AdbPacket.A_OPEN, local, 0, nul("tcpip:5555"));
        try {
            while (true) {
                AdbPacket packet = AdbPacket.read(input);
                if (packet.arg1 != local || remote != 0 && packet.arg0 != remote) {
                    throw new IOException("Unexpected ADB tcpip stream id");
                }
                remote = packet.arg0;
                if (packet.command == AdbPacket.A_WRTE) {
                    if (bytes.size() + packet.payload.length > OUTPUT_LIMIT) {
                        throw new IOException("ADB tcpip response too large");
                    }
                    bytes.write(packet.payload);
                    AdbPacket.write(output, AdbPacket.A_OKAY, local, remote, new byte[0]);
                } else if (packet.command == AdbPacket.A_CLSE) {
                    AdbPacket.write(output, AdbPacket.A_CLSE, local, remote, new byte[0]);
                    return bytes.toString(StandardCharsets.UTF_8.name()).trim();
                } else if (packet.command != AdbPacket.A_OKAY) {
                    throw new IOException("Unexpected ADB tcpip response");
                }
            }
        } catch (IOException expectedRestart) {
            return "transport_ended_after_tcpip_request";
        }
    }

    private static SSLContext tlsContext(AdbTlsIdentity identity) throws Exception {
        X509ExtendedKeyManager keys = new X509ExtendedKeyManager() {
            @Override public String chooseClientAlias(String[] types, Principal[] issuers,
                    Socket socket) {
                return types != null && Arrays.asList(types).contains("RSA") ? "adb" : null;
            }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return "adb".equals(alias) ? new X509Certificate[]{identity.certificate} : null;
            }
            @Override public PrivateKey getPrivateKey(String alias) {
                return "adb".equals(alias) ? identity.keyPair.getPrivate() : null;
            }
            @Override public String[] getClientAliases(String type, Principal[] issuers) {
                return "RSA".equals(type) ? new String[]{"adb"} : null;
            }
            @Override public String[] getServerAliases(String type, Principal[] issuers) {
                return null;
            }
            @Override public String chooseServerAlias(String type, Principal[] issuers,
                    Socket socket) { return null; }
        };
        X509TrustManager loopbackTrust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{keys}, new TrustManager[]{loopbackTrust}, new SecureRandom());
        return context;
    }

    private static byte[] nul(String value) {
        return (value + "\0").getBytes(StandardCharsets.UTF_8);
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
