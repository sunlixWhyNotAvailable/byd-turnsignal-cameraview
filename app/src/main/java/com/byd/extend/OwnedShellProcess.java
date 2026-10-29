package com.byd.extend;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One shell child group with a deadline, parent-pipe ownership and bounded output. */
final class OwnedShellProcess implements AutoCloseable {
    private Process active;
    private int pid;
    private long epoch;
    synchronized long token() { return epoch; }

    static final class Result {
        final int exitCode; final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    static final class Failure extends IOException {
        final String output;
        Failure(String name, String output, Exception cause) {
            super("Shell child failed: " + name + " output=" + output, cause);
            this.output = output;
        }
    }

    Result run(long token, String className, String niceName, long deadline, String... args) throws Exception {
        Process child;
        synchronized (this) {
            if (epoch != token) throw new InterruptedIOException("Shell child cancelled before start");
            if (active != null && active.isAlive()) throw new IOException("Previous shell child still alive; refusing overlap");
            if (!niceName.matches("bydextend_[a-z0-9_]+")) throw new IOException("Invalid child name");
            if (System.getenv("CLASSPATH") == null) throw new IOException("Shell child CLASSPATH missing");
            List<String> command = new ArrayList<>(Arrays.asList("/system/bin/setsid", "/system/bin/sh", "-c",
                    "echo CHILD_PID=$$; exec app_process /system/bin --nice-name=" + niceName + " \"$@\"",
                    "child", className, Long.toString(deadline)));
            command.addAll(Arrays.asList(args));
            active = child = new ProcessBuilder(command).redirectErrorStream(true).start();
            pid = 0;
        }
        FutureTask<String> output = new FutureTask<>(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null || !header.matches("CHILD_PID=[1-9][0-9]*"))
                    throw new IOException("Shell child identity missing: " + header);
                int childPid = Integer.parseInt(header.substring(10));
                synchronized (this) {
                    if (!CellularApnCheck.ownsGroup(childPid)) throw new IOException("Shell child group mismatch: " + childPid);
                    if (active != child) throw new IOException("Shell child ownership changed");
                    pid = childPid;
                    if (token != epoch) stop();
                    else {
                        child.getOutputStream().write("start\n".getBytes(StandardCharsets.UTF_8));
                        child.getOutputStream().flush();
                    }
                }
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[1024];
                int n; boolean truncated = false;
                while ((n = reader.read(buffer)) >= 0) {
                    int keep = Math.min(n, Math.max(0, 65_536 - text.length()));
                    text.append(buffer, 0, keep); truncated |= keep != n;
                }
                if (truncated) text.append("\n[output truncated]");
                return text.toString();
            }
        });
        Thread drain = new Thread(output, niceName + "-output");
        drain.setDaemon(true); drain.start();
        Exception failure = null;
        try {
            if (!child.waitFor(Math.max(1L, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS))
                failure = new IOException("Shell child deadline exceeded: " + niceName + "; remote completion unknown");
        } catch (Exception e) { failure = e; }
        synchronized (this) { if (active == child) stop(); }
        String captured = "";
        try {
            if (!child.waitFor(1, TimeUnit.SECONDS)) throw new IOException("Shell child did not exit: " + niceName);
            captured = output.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        } finally {
            output.cancel(true);
            try { child.getInputStream().close(); }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            synchronized (this) { if (active == child && !child.isAlive()) { active = null; pid = 0; } }
        }
        synchronized (this) {
            if (token != epoch && failure == null) failure = new InterruptedIOException("Shell child cancelled: " + niceName);
        }
        if (failure != null) throw new Failure(niceName, captured, failure);
        return new Result(child.exitValue(), captured);
    }

    synchronized void cancel() { epoch++; stop(); }

    private void stop() {
        if (active == null) return;
        try { active.getOutputStream().close(); }
        catch (Exception e) { log(e, "child_pipe_close"); }
        try {
            if (pid > 0) Os.kill(-pid, OsConstants.SIGKILL);
            else if (active.isAlive()) active.destroyForcibly();
        } catch (android.system.ErrnoException e) {
            if (e.errno != OsConstants.ESRCH) log(e, "child_group_stop");
        } catch (Exception e) { log(e, "child_stop"); }
    }

    private static void log(Exception e, String stage) {
        CellularRecovery.log("shell_child_error " + CellularRecovery.diagnosticDetail(e, "stage=" + stage));
    }
    @Override public void close() { cancel(); }
}
