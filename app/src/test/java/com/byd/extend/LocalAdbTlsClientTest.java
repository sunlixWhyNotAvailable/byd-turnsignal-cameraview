package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class LocalAdbTlsClientTest {
    @Test public void normalWriteAndCloseReturnsOrderlyResponse() throws Exception {
        byte[] input = packets(
                packet(AdbPacket.A_OKAY, 7, 1, new byte[0]),
                packet(AdbPacket.A_WRTE, 7, 1, new byte[]{1, 2, 3}),
                packet(AdbPacket.A_CLSE, 7, 1, new byte[0]));

        assertEquals(LocalAdbTlsClient.TcpipResult.NORMAL_CLOSE,
                request(new ByteArrayInputStream(input)));
    }

    @Test public void boundaryEofAfterOpenIsOnlyPossibleRestart() throws Exception {
        assertEquals(LocalAdbTlsClient.TcpipResult.POSSIBLE_RESTART_EOF,
                request(new ByteArrayInputStream(new byte[0])));
        assertEquals(LocalAdbTlsClient.TcpipResult.POSSIBLE_RESTART_EOF,
                request(eofAfter(new byte[0])));
    }

    @Test public void socketResetAfterOpenIsOnlyPossibleRestart() throws Exception {
        InputStream reset = new InputStream() {
            @Override public int read() throws IOException { throw new SocketException("reset"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new SocketException("reset");
            }
        };
        assertEquals(LocalAdbTlsClient.TcpipResult.POSSIBLE_RESTART_DISCONNECT,
                request(reset));
    }

    @Test public void socketResetInsidePacketRemainsTruncationFailure() throws Exception {
        InputStream partialHeader = disconnectAfter(new byte[]{1, 2, 3});
        ProtocolException header = assertThrows(ProtocolException.class,
                () -> request(partialHeader));
        assertEquals("truncated_header", header.getMessage());

        byte[] headerOnly = copyOf(packet(AdbPacket.A_WRTE, 7, 1, new byte[]{1}), 24);
        InputStream partialPayload = disconnectAfter(headerOnly);
        ProtocolException payload = assertThrows(ProtocolException.class,
                () -> request(partialPayload));
        assertEquals("truncated_payload", payload.getMessage());
    }

    @Test public void thrownEofInsidePacketRemainsTruncationFailure() throws Exception {
        ProtocolException header = assertThrows(ProtocolException.class,
                () -> request(eofAfter(new byte[]{1, 2, 3})));
        assertEquals("truncated_header", header.getMessage());

        byte[] headerOnly = copyOf(packet(AdbPacket.A_WRTE, 7, 1, new byte[]{1}), 24);
        ProtocolException payload = assertThrows(ProtocolException.class,
                () -> request(eofAfter(headerOnly)));
        assertEquals("truncated_payload", payload.getMessage());
    }

    @Test public void socketResetWhileSendingOpenRemainsFailure() {
        OutputStream reset = new OutputStream() {
            @Override public void write(int value) throws IOException {
                throw new SocketException("reset");
            }
        };
        assertThrows(SocketException.class, () -> LocalAdbTlsClient.requestTcpip5555(
                new ByteArrayInputStream(new byte[0]), reset));
    }

    @Test public void timeoutRemainsFailure() {
        InputStream timeout = new InputStream() {
            @Override public int read() throws IOException { throw new SocketTimeoutException(); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new SocketTimeoutException();
            }
        };
        assertThrows(SocketTimeoutException.class, () -> request(timeout));
    }

    @Test public void corruptMagicBeforeBoundaryEofRemainsProtocolFailure() throws Exception {
        byte[] reply = packet(AdbPacket.A_OKAY, 7, 1, new byte[0]);
        reply[20] ^= 1;
        ProtocolException failure = assertThrows(ProtocolException.class,
                () -> request(new ByteArrayInputStream(reply)));
        assertEquals("invalid_magic", failure.getMessage());
        assertEquals("invalid_magic", LocalAdbTlsClient.failureReason(failure));
    }

    @Test public void truncatedHeaderAndPayloadRemainProtocolFailures() throws Exception {
        ProtocolException header = assertThrows(ProtocolException.class,
                () -> request(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        assertEquals("truncated_header", header.getMessage());

        byte[] complete = packet(AdbPacket.A_WRTE, 7, 1, new byte[]{1, 2, 3});
        ProtocolException payload = assertThrows(ProtocolException.class,
                () -> request(new ByteArrayInputStream(copyOf(complete, complete.length - 1))));
        assertEquals("truncated_payload", payload.getMessage());
    }

    @Test public void invalidLengthAndResponseLimitRemainProtocolFailures() throws Exception {
        ByteBuffer oversizedLength = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        oversizedLength.putInt(AdbPacket.A_WRTE).putInt(7).putInt(1)
                .putInt(AdbPacket.MAX_PAYLOAD + 1).putInt(0).putInt(0);
        ProtocolException invalid = assertThrows(ProtocolException.class,
                () -> request(new ByteArrayInputStream(oversizedLength.array())));
        assertEquals("invalid_payload_length", invalid.getMessage());

        ProtocolException limited = assertThrows(ProtocolException.class,
                () -> request(new ByteArrayInputStream(packet(
                        AdbPacket.A_WRTE, 7, 1, new byte[64 * 1024 + 1]))));
        assertEquals("response_limit_exceeded", limited.getMessage());
    }

    @Test public void wrongStreamAndUnexpectedCommandRemainProtocolFailures() throws Exception {
        ProtocolException stream = assertThrows(ProtocolException.class,
                () -> request(new ByteArrayInputStream(packet(
                        AdbPacket.A_OKAY, 7, 2, new byte[0]))));
        assertEquals("wrong_stream_id", stream.getMessage());

        ProtocolException command = assertThrows(ProtocolException.class,
                () -> request(new ByteArrayInputStream(packet(
                        AdbPacket.A_CNXN, 7, 1, new byte[0]))));
        assertEquals("unexpected_command", command.getMessage());
    }

    @Test public void failureLoggingUsesBoundedCategoriesAndReasons() {
        ProtocolException known = new ProtocolException("wrong_stream_id");
        assertEquals("protocol", LocalAdbTlsClient.failureCategory(known));
        assertEquals("wrong_stream_id", LocalAdbTlsClient.failureReason(known));
        assertEquals("protocol_failure", LocalAdbTlsClient.failureReason(
                new ProtocolException("untrusted remote detail")));
        assertEquals("timeout", LocalAdbTlsClient.failureCategory(
                new SocketTimeoutException()));
        assertEquals("read_timeout", LocalAdbTlsClient.failureReason(
                new SocketTimeoutException()));
    }

    private static LocalAdbTlsClient.TcpipResult request(InputStream input) throws IOException {
        return LocalAdbTlsClient.requestTcpip5555(input, new ByteArrayOutputStream());
    }

    private static byte[] packet(int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AdbPacket.write(output, command, arg0, arg1, payload);
        return output.toByteArray();
    }

    private static byte[] packets(byte[]... packets) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] packet : packets) output.write(packet);
        return output.toByteArray();
    }

    private static byte[] copyOf(byte[] bytes, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(bytes, 0, copy, 0, length);
        return copy;
    }

    private static InputStream disconnectAfter(byte[] prefix) {
        return throwingAfter(prefix, new SocketException("reset"));
    }

    private static InputStream eofAfter(byte[] prefix) {
        return throwingAfter(prefix, new EOFException("ended"));
    }

    private static InputStream throwingAfter(byte[] prefix, IOException failure) {
        return new InputStream() {
            int offset;
            @Override public int read() throws IOException {
                if (offset >= prefix.length) throw failure;
                return prefix[offset++] & 0xff;
            }
            @Override public int read(byte[] buffer, int target, int length) throws IOException {
                if (offset >= prefix.length) throw failure;
                int count = Math.min(length, prefix.length - offset);
                System.arraycopy(prefix, offset, buffer, target, count);
                offset += count;
                return count;
            }
        };
    }
}
