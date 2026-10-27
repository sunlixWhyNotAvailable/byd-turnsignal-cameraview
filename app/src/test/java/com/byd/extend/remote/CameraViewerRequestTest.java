package com.byd.extend.remote;

import java.io.EOFException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.*;

/** Real loopback input into the production parser; no substitute Stream or camera/helper results. */
public class CameraViewerRequestTest {
    @Test public void completeRequestPreservesBodyAndLeavesOnlyTheIdleSocketTimeout() throws Exception {
        byte[] body = "{\"password\":\"test value\"}".getBytes(StandardCharsets.UTF_8);
        String headers = "POST /session HTTP/1.1\r\nHost: viewer.local\r\n"
                + "Cookie: viewer=current-session\r\nContent-Length: " + body.length + "\r\n\r\n";
        try (ServerSocket listener = listener();
             Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
             Socket server = listener.accept()) {
            client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(body);
            client.getOutputStream().flush();
            CameraViewerServer.Request request = CameraViewerServer.readRequest(server);
            assertEquals("POST", request.method);
            assertEquals("/session", request.path);
            assertEquals("current-session", request.session);
            assertArrayEquals(body, request.body);
            assertEquals(5_000, server.getSoTimeout());
        }
    }

    @Test public void truncatedAndOversizedInputIsRejectedByTheActualParser() throws Exception {
        assertThrows(EOFException.class, () -> parse("GET / HTTP/1.1\r\nHost: viewer.local"));
        assertThrows(EOFException.class, () -> parse(
                "POST /session HTTP/1.1\r\nContent-Length: 4\r\n\r\nx"));
        CameraViewerServer.HttpFailure body = assertThrows(CameraViewerServer.HttpFailure.class,
                () -> parse("POST /session HTTP/1.1\r\nContent-Length: 4097\r\n\r\n"));
        assertEquals(413, body.status);
        CameraViewerServer.HttpFailure headers = assertThrows(CameraViewerServer.HttpFailure.class,
                () -> parse("GET / HTTP/1.1\r\nX-Padding: " + "x".repeat(16_384)));
        assertEquals(431, headers.status);
    }

    @Test(timeout = 20_000) public void headerAndBodyShareOneDeadlineDespiteContinuousInput() throws Exception {
        ExecutorService sender = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = listener();
             Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
             Socket server = listener.accept()) {
            Future<?> sending = sender.submit(() -> {
                OutputStream out = client.getOutputStream();
                out.write("POST /session HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                TimeUnit.SECONDS.sleep(3);
                out.write("Content-Length: 4096\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                for (int i = 0; i < 80; i++) {
                    out.write('x');
                    out.flush();
                    TimeUnit.MILLISECONDS.sleep(250);
                }
                return null;
            });
            try {
                long started = System.nanoTime();
                CameraViewerServer.HttpFailure failure = assertThrows(CameraViewerServer.HttpFailure.class,
                        () -> CameraViewerServer.readRequest(server));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertEquals(408, failure.status);
                assertEquals("request_timeout", failure.bodyStatus);
                assertTrue(failure.getCause().getMessage().contains("stage=body"));
                assertTrue(failure.getCause().getCause() instanceof SocketTimeoutException);
                assertTrue("deadline elapsed=" + elapsed, elapsed >= 9_000 && elapsed < 12_000);
                // A transport failure must not masquerade as a successful slow-body exercise.
                if (sending.isDone()) sending.get();
            } finally {
                sending.cancel(true);
            }
        } finally {
            sender.shutdownNow();
            assertTrue(sender.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static ServerSocket listener() throws Exception {
        return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    private static CameraViewerServer.Request parse(String wire) throws Exception {
        try (ServerSocket listener = listener();
             Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
             Socket server = listener.accept()) {
            client.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            client.shutdownOutput();
            return CameraViewerServer.readRequest(server);
        }
    }
}
