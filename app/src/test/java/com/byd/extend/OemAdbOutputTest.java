package com.byd.extend;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback transport and framing, not an OEM command/execution simulation. */
public class OemAdbOutputTest {
    private static void send(OemAdbBridge.Output output, String bytes) throws Exception {
        try (Socket client = new Socket("127.0.0.1", output.listener.getLocalPort())) {
            client.getOutputStream().write(bytes.getBytes(StandardCharsets.UTF_8));
        }
    }
    @Test public void WrongTokenAndEarlyEofDoNotConsumeTheAuthenticatedOutput() throws Exception {
        try (OemAdbBridge.Output output = new OemAdbBridge.Output(3000)) {
            send(output, "x".repeat(output.token.length()) + "\n");
            send(output, "short");
            send(output, output.token + "\npayload\n" + output.token + "\n");
            assertEquals("payload", output.await(2000));
        }
    }
    @Test public void SilentPeerCannotExtendDeadlineAndFirstRejectionIsPreserved() throws Exception {
        long started = System.nanoTime();
        try (OemAdbBridge.Output output = new OemAdbBridge.Output(300)) {
            send(output, "x".repeat(output.token.length()) + "\n");
            try (Socket silent = new Socket("127.0.0.1", output.listener.getLocalPort())) {
                ExecutionException failure = assertThrows(ExecutionException.class, () -> output.result.get(2, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof java.net.SocketTimeoutException);
                assertEquals("OEM output token mismatch", failure.getCause().getSuppressed()[0].getMessage());
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
            }
        }
    }
    @Test public void AuthenticatedTruncationKeepsCauseAndRedactsToken() throws Exception {
        try (OemAdbBridge.Output output = new OemAdbBridge.Output(2000)) {
            send(output, output.token + "\npartial " + output.token);
            IOException failure = assertThrows(IOException.class, () -> output.await(2000));
            assertEquals("OEM output footer missing", failure.getCause().getCause().getMessage());
            assertFalse(failure.getCause().getMessage().contains(output.token));
        }
    }
    @Test public void CloseCancelsReceiver() throws Exception {
        OemAdbBridge.Output output = new OemAdbBridge.Output(10000);
        output.close();
        assertTrue(output.result.isCancelled());
        assertTrue(output.listener.isClosed());
    }
}
