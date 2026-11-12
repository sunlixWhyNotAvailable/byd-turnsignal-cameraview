package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed IVI -> host ADB switch only. No engineering UI or caller-supplied commands. */
final class OemAdbBridge {
    private static final String DESCRIPTOR = "aidl.cells.ICellsService";
    private static final String WIRELESS = "persist.sys.adb.wiress.enable";
    private static final String TRIGGER = "sys.connect.adb.wiress";
    private enum Command { CONTROL, ENABLE_SETTING, TRIGGER }
    private final Context context;
    private final OemAdbRecovery.Attempt attempt;
    private final IBinder host;
    private final IBinder ivi;

    private static native IBinder findBinder(boolean host) throws IOException;

    OemAdbBridge(Context context, OemAdbRecovery.Attempt attempt) throws Exception {
        this.context = context;
        this.attempt = attempt;
        if (Process.myUid() != context.getApplicationInfo().uid) {
            throw new IOException("OEM recovery must execute under the application's UID");
        }
        attempt.before("load_binder_bridge", false);
        System.loadLibrary("oem_adb");
        attempt.before("lookup_cellsservice_host", false);
        host = findBinder(true);
        attempt.before("lookup_cellsservice_ivi", false);
        ivi = findBinder(false);
        if (host == null || ivi == null) throw new IOException("OEM Binder lookup returned null");
    }

    void enable() throws Exception {
        int control = host(Command.CONTROL);
        if (control != 7) throw new IOException("OEM UID/SELinux control failed: exit=" + control);
        attempt.check();
        String setting = Settings.Global.getString(context.getContentResolver(), "adb_enabled");
        attempt.event("adb_oem_setting", "value", setting, "skipped", "1".equals(setting));
        if (!"1".equals(setting)) {
            int code = host(Command.ENABLE_SETTING);
            attempt.check();
            String actual = Settings.Global.getString(context.getContentResolver(), "adb_enabled");
            if (code != 0 || !"1".equals(actual)) {
                throw new IOException("OEM adb_enabled not confirmed: exit=" + code + ", app_readback=" + actual);
            }
        }
        setIvi(WIRELESS, "true");
        setIvi(TRIGGER, "1");
        int code = host(Command.TRIGGER);
        if (code != 0) throw new IOException("OEM host ADB trigger failed: exit=" + code);
        attempt.check();
    }

    private void setIvi(String key, String value) throws Exception {
        if (!(WIRELESS.equals(key) && "true".equals(value))
                && !(TRIGGER.equals(key) && "1".equals(value))) {
            throw new IllegalArgumentException("Not an allowed ADB property/value");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(key);
            data.writeString(value);
            attempt.before("ivi_write:" + key, true);
            boolean handled = ivi.transact(1, data, reply, 0);
            if (!handled) {
                attempt.replied(true);
                throw new IOException("IVI setter unsupported: " + key);
            }
            reply.readException();
            if (reply.dataAvail() < 4) throw new IOException("IVI setter reply missing: " + key);
            int result = reply.readInt();
            attempt.replied(true);
            attempt.event("adb_oem_setter_result", "key", key, "result", result);
            if (result != 1) throw new IOException("IVI setter rejected " + key + ": boolean=" + result);
        } finally { reply.recycle(); data.recycle(); }
        String actual = getIvi(key);
        attempt.event("adb_oem_readback", "key", key, "value", actual);
        if (!value.equals(actual)) throw new IOException("IVI readback mismatch: " + key + "=" + actual);
    }

    private String getIvi(String key) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(key);
            data.writeString("__UNSET__");
            attempt.before("ivi_read:" + key, false);
            if (!ivi.transact(2, data, reply, 0)) throw new IOException("IVI getter unsupported: " + key);
            reply.readException();
            if (reply.dataAvail() < 4) throw new IOException("IVI getter reply missing: " + key);
            String value = reply.readString();
            if (value == null) throw new IOException("IVI getter returned null: " + key);
            attempt.check();
            return value;
        } finally { reply.recycle(); data.recycle(); }
    }

    private int host(Command operation) throws Exception {
        attempt.check();
        try (Output output = new Output(attempt.ticket.remaining(SystemClock.elapsedRealtime()))) {
            attempt.attachOutput(output);
            String command = command(operation, output);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            Throwable primary = null;
            int exit = -1;
            boolean mutation = operation != Command.CONTROL;
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(command);
                attempt.before("host:" + operation, mutation);
                boolean handled = host.transact(3, data, reply, 0);
                if (!handled) {
                    attempt.replied(mutation);
                    throw new IOException("OEM host exec unsupported: " + operation);
                }
                reply.readException();
                if (reply.dataAvail() < 4) throw new IOException("OEM host wait status missing");
                int wait = reply.readInt();
                if (wait < 0 || wait > 65280 || (wait & 255) != 0) {
                    throw new IOException("OEM abnormal system wait_status=" + wait);
                }
                exit = wait >> 8;
                // timeout/kill can leave an already accepted property operation unresolved.
                if (exit != 124 && exit != 137 && exit != 143) attempt.replied(mutation);
                attempt.event("adb_oem_host_result", "exit", exit, "wait_status", wait);
                if (exit != (operation == Command.CONTROL ? 7 : 0)) {
                    throw new IOException("OEM " + operation + " failed: exit=" + exit);
                }
            } catch (Exception | LinkageError error) { primary = error; }
            finally { reply.recycle(); data.recycle(); }
            try {
                String text = output.await(attempt.ticket.remaining(SystemClock.elapsedRealtime()));
                attempt.event("adb_oem_host_output", "output", AdbRecoveryDiagnostics.safeText(text));
            } catch (Exception error) {
                if (primary == null) primary = error; else primary.addSuppressed(error);
            } finally { attempt.closeOutput(); }
            if (primary instanceof LinkageError) throw (LinkageError) primary;
            if (primary != null) throw (Exception) primary;
            attempt.check();
            return exit;
        }
    }

    private String command(Command operation, Output output) {
        String expiry = "[ \"$(date +%s)\" -le " + attempt.expiresWallSeconds + " ] || exit 48\n";
        String body = "run_step() { step=\"$1\"; shift; value=$(\"$@\" 2>&1); code=$?; "
                + "printf \"stage=%s exit=%s output=<%s>\\n\" \"$step\" \"$code\" \"$value\"; return \"$code\"; }\n"
                + "value=$(id -u 2>&1); code=$?; printf \"stage=uid exit=%s value=<%s>\\n\" \"$code\" \"$value\"\n"
                + "[ \"$code\" = 0 ] && [ \"$value\" = 0 ] || exit 41\n"
                + "value=$(getenforce 2>&1); code=$?; printf \"stage=selinux exit=%s value=<%s>\\n\" \"$code\" \"$value\"\n"
                + "[ \"$code\" = 0 ] && [ \"$value\" = Enforcing ] || exit 40\n";
        switch (operation) {
            case CONTROL: body += "exit 7\n"; break;
            case ENABLE_SETTING:
                body += expiry + "run_step adb_enabled_write settings put global adb_enabled 1 || exit $?\n"
                        + "value=$(settings get global adb_enabled 2>&1); code=$?\n"
                        + "printf \"stage=adb_enabled_read exit=%s value=<%s>\\n\" \"$code\" \"$value\"\n"
                        + "[ \"$code\" = 0 ] && [ \"$value\" = 1 ] || exit 61\n";
                break;
            case TRIGGER:
                body += expiry + "run_step trigger_write setprop sys.connect.adb.wiress 1 || exit $?\n"
                        + "value=$(getprop sys.connect.adb.wiress 2>&1); code=$?\n"
                        + "printf \"stage=trigger_read exit=%s value=<%s>\\n\" \"$code\" \"$value\"\n"
                        + "[ \"$code\" = 0 ] && [ \"$value\" = 1 ] || exit 63\n";
                break;
        }
        // Fixed payload only; the random token is never included in diagnostics.
        return "output=$(toybox timeout 10 /system/bin/sh -c '" + body + "' 2>&1)\n"
                + "rc=$?\nresult=\"attempt=" + attempt.ticket.id + " operation=" + operation + " exit=$rc\n$output\"\n"
                + "log -p i -t BydOemAdb \"$result\"\n"
                + "printf \"%s\\n%s\\n%s\\n\" " + output.token + " \"$result\" " + output.token
                + " | toybox timeout 3 toybox nc -w 2 127.0.0.1 " + output.listener.getLocalPort() + "\n"
                + "callback=$?\n[ \"$callback\" = 0 ] || log -p e -t BydOemAdb \"attempt="
                + attempt.ticket.id + " output_callback_exit=$callback\"\nexit \"$rc\"\n";
    }

    private static final class Output implements Closeable {
        private static final int MAX_BYTES = 64 * 1024;
        final String token = UUID.randomUUID().toString().replace("-", "");
        final ServerSocket listener;
        final FutureTask<String> result;
        private volatile Socket peer;
        private volatile boolean closed;

        Output(long remainingMs) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout((int) Math.max(1, remainingMs));
            result = new FutureTask<>(this::receive);
            Thread reader = new Thread(result, "oem-adb-output");
            reader.setDaemon(true);
            reader.start();
        }

        private String receive() throws IOException {
            try (Socket socket = listener.accept()) {
                peer = socket;
                if (closed) throw new IOException("OEM output receiver closed");
                socket.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                byte[] expected = (token + "\n").getBytes(StandardCharsets.US_ASCII);
                byte[] actual = new byte[expected.length];
                input.readFully(actual);
                if (!MessageDigest.isEqual(expected, actual)) throw new IOException("OEM output token mismatch");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                try {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > MAX_BYTES) throw new IOException("OEM output exceeds 64 KiB");
                        bytes.write(buffer, 0, count);
                    }
                    String text = bytes.toString(StandardCharsets.UTF_8.name());
                    String footer = "\n" + token + "\n";
                    if (!text.endsWith(footer)) throw new IOException("OEM output footer missing");
                    return text.substring(0, text.length() - footer.length());
                } catch (IOException error) {
                    String partial = bytes.toString(StandardCharsets.UTF_8.name()).replace(token, "[redacted]");
                    throw new IOException("OEM output incomplete; partial=<" + partial + ">", error);
                }
            }
        }

        String await(long remainingMs) throws IOException {
            try { return result.get(Math.max(1, Math.min(2000, remainingMs)), TimeUnit.MILLISECONDS); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("OEM output wait interrupted", error);
            } catch (ExecutionException error) {
                throw new IOException("OEM output read failed", error.getCause());
            } catch (TimeoutException | CancellationException error) {
                throw new IOException("OEM output unavailable", error);
            }
        }

        @Override public void close() throws IOException {
            closed = true;
            try { listener.close(); }
            finally {
                try { if (peer != null) peer.close(); }
                finally { result.cancel(true); }
            }
        }
    }
}
