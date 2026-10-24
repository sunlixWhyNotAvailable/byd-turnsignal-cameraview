package com.byd.extend;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed shell-UID operations used only by the existing recovery singleton. */
final class WifiRetentionShell {
    private static final long COMMAND_TIMEOUT_MS = 5_000L;
    static final long WIFI_STATE_READ_TIMEOUT_MS = 1_500L;
    private static final int MAX_OUTPUT_BYTES = 65_536;
    private static final String[] VEHICLE_POWER_ARGS = {
            "/system/bin/service", "call", "autoservice", "5", "i32", "1001", "i32", "315621418"
    };
    private static final String[] WIFI_ENABLE_ARGS = {"/system/bin/svc", "wifi", "enable"};
    private static final Pattern VEHICLE_POWER_REPLY = Pattern.compile(
            "Result:\\s*Parcel\\(\\s*(?:0x00000000:\\s*)?00000000\\s+([0-9a-fA-F]{8})\\s+'[^'\\r\\n]*'\\s*\\)");
    private static final ThreadPoolExecutor WIFI_STATE_READER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "wifi-retention-state");
                    thread.setDaemon(true);
                    return thread;
                }
            }, new ThreadPoolExecutor.AbortPolicy());

    private WifiRetentionShell() { }

    static boolean shouldEnableWifi(boolean optionEnabled, boolean running,
            boolean wifiDisabled, Boolean vehiclePoweredOff) {
        return optionEnabled && running && wifiDisabled && Boolean.TRUE.equals(vehiclePoweredOff);
    }

    static Boolean parseVehiclePoweredOff(String output) {
        Matcher reply = VEHICLE_POWER_REPLY.matcher(output == null ? "" : output.trim());
        if (!reply.matches()) return null;
        long raw = Long.parseLong(reply.group(1), 16);
        if (raw > Integer.MAX_VALUE) return null;
        return raw == 0L;
    }

    static WifiStateRead readWifiState(Context context) {
        long started = elapsedRealtime();
        Future<Integer> read;
        try {
            read = WIFI_STATE_READER.submit(() -> {
                WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) throw new IllegalStateException(
                        "WifiManager unavailable from shell context");
                return wifi.getWifiState();
            });
        } catch (RejectedExecutionException failure) {
            return new WifiStateRead(null, elapsedRealtime() - started, failure);
        }
        try {
            return new WifiStateRead(read.get(WIFI_STATE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    elapsedRealtime() - started, null);
        } catch (TimeoutException failure) {
            read.cancel(true);
            TimeoutException timeout = new TimeoutException(
                    "Context.getSystemService(WIFI_SERVICE)/WifiManager.getWifiState exceeded "
                            + WIFI_STATE_READ_TIMEOUT_MS + " ms");
            timeout.initCause(failure);
            return new WifiStateRead(null, elapsedRealtime() - started, timeout);
        } catch (InterruptedException failure) {
            read.cancel(true);
            Thread.currentThread().interrupt();
            return new WifiStateRead(null, elapsedRealtime() - started, failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            return new WifiStateRead(null, elapsedRealtime() - started,
                    cause == null ? failure : cause);
        }
    }

    static long elapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    static CommandResult readVehiclePower() {
        long started = elapsedRealtime();
        try {
            java.lang.Process process = new ProcessBuilder(VEHICLE_POWER_ARGS)
                    .redirectErrorStream(true).start();
            return finish("service call autoservice 5", process, started);
        } catch (Throwable failure) {
            return new CommandResult(-1, "", false, elapsedRealtime() - started, failure);
        }
    }

    static java.lang.Process startWifiEnable() throws IOException {
        return new ProcessBuilder(WIFI_ENABLE_ARGS).redirectErrorStream(true).start();
    }

    static CommandResult finishWifiEnable(java.lang.Process process, long started) {
        return finish("svc wifi enable", process, started);
    }

    private static CommandResult finish(String operation, java.lang.Process process, long started) {
        Throwable failure = null;
        int exitCode = -1;
        try {
            if (process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                Throwable cleanupFailure = null;
                boolean terminated = false;
                try {
                    terminated = terminate(process);
                } catch (Throwable cleanup) {
                    if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                    cleanupFailure = cleanup;
                }
                TimeoutException timeout = new TimeoutException(operation + " exceeded "
                        + COMMAND_TIMEOUT_MS + " ms; process_terminated=" + terminated);
                if (cleanupFailure != null) timeout.addSuppressed(cleanupFailure);
                failure = timeout;
            }
        } catch (InterruptedException interrupted) {
            failure = interrupted;
            try {
                process.destroyForcibly();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    interrupted.addSuppressed(new IOException(operation + " process remained alive"));
                }
            } catch (Throwable cleanup) {
                interrupted.addSuppressed(cleanup);
            }
            Thread.currentThread().interrupt();
        } catch (Throwable commandFailure) {
            failure = commandFailure;
            try { process.destroyForcibly(); }
            catch (Throwable cleanup) { commandFailure.addSuppressed(cleanup); }
        }

        String output = "";
        boolean outputTruncated = false;
        try {
            Output outputCapture = readAvailableOutput(process);
            output = outputCapture.text;
            outputTruncated = outputCapture.truncated;
        } catch (Throwable outputFailure) {
            if (failure == null) failure = outputFailure;
            else failure.addSuppressed(outputFailure);
        } finally {
            try { process.getInputStream().close(); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
            try { process.getErrorStream().close(); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
            try { process.getOutputStream().close(); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
        }
        return new CommandResult(exitCode, output, outputTruncated,
                elapsedRealtime() - started, failure);
    }

    private static boolean terminate(java.lang.Process process) throws InterruptedException {
        process.destroyForcibly();
        return process.waitFor(1, TimeUnit.SECONDS) && !process.isAlive();
    }

    private static Output readAvailableOutput(java.lang.Process process) throws IOException {
        // shortcut: only buffered output is captured, upgrade if fixed commands delay their reply.
        try (InputStream input = process.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int remaining = MAX_OUTPUT_BYTES;
            int available;
            while (remaining > 0 && (available = input.available()) > 0) {
                int count = input.read(buffer, 0, Math.min(Math.min(buffer.length, available), remaining));
                if (count <= 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            boolean truncated = input.available() > 0;
            return new Output(new String(output.toByteArray(), StandardCharsets.UTF_8), truncated);
        }
    }

    private static Throwable append(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        primary.addSuppressed(secondary);
        return primary;
    }

    static final class CommandResult {
        final int exitCode;
        final String output;
        final boolean outputTruncated;
        final long elapsedMs;
        final Throwable failure;

        CommandResult(int exitCode, String output, boolean outputTruncated,
                long elapsedMs, Throwable failure) {
            this.exitCode = exitCode;
            this.output = output;
            this.outputTruncated = outputTruncated;
            this.elapsedMs = elapsedMs;
            this.failure = failure;
        }

        String statusKey(String operation) {
            return operation + ":exit=" + exitCode + ":truncated=" + outputTruncated
                    + ":output=" + output + (failure == null ? "" : ":failure=" + describe(failure));
        }

        private static String describe(Throwable failure) {
            StringWriter output = new StringWriter();
            failure.printStackTrace(new PrintWriter(output));
            return output.toString();
        }
    }

    static final class WifiStateRead {
        final Integer state;
        final long elapsedMs;
        final Throwable failure;

        WifiStateRead(Integer state, long elapsedMs, Throwable failure) {
            this.state = state;
            this.elapsedMs = elapsedMs;
            this.failure = failure;
        }
    }

    private static final class Output {
        final String text;
        final boolean truncated;

        Output(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
