package com.byd.extend;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ContinuousLogcatRecorderTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void followsAllBuffersAndReplaysHistoryWithoutRotationOrDumpOnly() {
        List<String> command = ContinuousLogcatRecorder.command("");
        assertEquals(java.util.Arrays.asList("/system/bin/logcat", "-b", "all", "-v", "threadtime"), command);
        assertFalse(command.contains("-d"));
        assertFalse(command.contains("-c"));
        assertFalse(command.contains("-r"));
        assertFalse(command.contains("-n"));
        assertFalse(command.contains("--pid"));
    }

    @Test public void fixedSnapshotSupportsVolumesBeyondTwoGiB() {
        assertEquals("head -c 3000000000 '" + ContinuousLogcatRecorder.LOG_PATH + "' 2>/dev/null",
                ContinuousLogcatRecorder.snapshotCommand(3_000_000_000L));
        try {
            ContinuousLogcatRecorder.snapshotCommand(-1);
            fail("Negative bound is not a shell command");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void stopRetainsHistoryAndDoesNotLaunchAnotherReader() throws Exception {
        File directory = temporary.newFolder();
        File log = new File(directory, "logcat.txt");
        java.util.concurrent.BlockingQueue<FakeProcess> children = new java.util.concurrent.LinkedBlockingQueue<>();
        ContinuousLogcatRecorder recorder = new ContinuousLogcatRecorder(directory, "boot", "test", null,
                command -> { FakeProcess child = new FakeProcess(); children.add(child); return child; });
        try {
            recorder.start();
            FakeProcess child = children.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(child);
            child.send("keep_after_off\n");
            awaitText(log, "keep_after_off");
            recorder.close();
            assertFalse(child.isAlive());
            assertTrue(readText(log).contains("keep_after_off"));
            assertTrue(children.isEmpty());
        } finally { recorder.close(); }
    }

    @Test public void loggingContractIsIndependentOfAudioAndAutoStartAndDefaultsOff() throws Exception {
        String service = new String(Files.readAllBytes(new File(
                "src/main/java/com/byd/extend/CameraHelperService.java").toPath()), StandardCharsets.UTF_8);
        String gate = service.substring(service.indexOf("private boolean logcatRecordingRequested("),
                service.indexOf("private void reconcileAvasRecovery("));
        assertTrue(gate.contains("isUserShutdownActive"));
        assertTrue(gate.contains("blocksRuntime"));
        assertTrue(gate.contains("getBoolean(ContinuousLogcatRecorder.PREF_ENABLED, false)"));
        assertFalse(gate.contains("isAutoStartEnabled"));
        assertFalse(gate.contains("hasEnabledProfiles"));
        String daemon = new String(Files.readAllBytes(new File(
                "src/main/java/com/byd/extend/AvasRecoveryShellMain.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(daemon.contains("if (recording) logcat.start();"));
        assertTrue(daemon.contains("else logcat.close();"));
        assertTrue(daemon.indexOf("if (binder.recoveryEnabled)") >= 0
                && daemon.indexOf("new ProcessBuilder(") > daemon.indexOf("if (binder.recoveryEnabled)"));
        assertTrue(AvasRecoveryDaemonController.launchCommand("/apk", 10001, "identity", false, true)
                .contains("'identity' 0 1 </dev/null"));
        assertTrue(AvasRecoveryDaemonController.launchCommand("/apk", 10001, "identity", true, false)
                .contains("'identity' 1 0 </dev/null"));
    }

    @Test public void clearIsScopedAndDoesNotReimportOldBufferAfterSameBootRestart() throws Exception {
        File directory = temporary.newFolder();
        File log = new File(directory, "logcat.txt");
        File unrelated = new File(directory, "unrelated.txt");
        Files.write(log.toPath(), "old history".getBytes(StandardCharsets.UTF_8));
        Files.write(unrelated.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        ContinuousLogcatRecorder recorder = new ContinuousLogcatRecorder(directory, "boot-a", "test", null);
        assertEquals(1, recorder.clearStored());
        assertFalse(log.exists());
        assertTrue(unrelated.exists());
        String since = recorder.clearSince();
        assertFalse(since.isEmpty());
        assertEquals(since, new ContinuousLogcatRecorder(directory, "boot-a", "test", null).clearSince());
        assertTrue(ContinuousLogcatRecorder.command(since).contains("-T"));
        assertEquals("", new ContinuousLogcatRecorder(directory, "boot-b", "test", null).clearSince());
    }

    @Test public void lowSpaceStopsWritingWithoutDeletingExistingEvidence() throws Exception {
        File directory = temporary.newFolder();
        File log = new File(directory, "logcat.txt");
        byte[] original = "retained evidence".getBytes(StandardCharsets.UTF_8);
        Files.write(log.toPath(), original);
        try {
            ContinuousLogcatRecorder.requireSpace(ContinuousLogcatRecorder.MIN_FREE_BYTES - 1);
            fail("Low free space must pause recording");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("existing_logs_retained"));
        }
        assertArrayEquals(original, Files.readAllBytes(log.toPath()));
        ContinuousLogcatRecorder.requireSpace(ContinuousLogcatRecorder.MIN_FREE_BYTES);
    }

    @Test public void restartsDeadReaderAppendsHistoryAndClearJoinsBeforeStartingFresh() throws Exception {
        File directory = temporary.newFolder();
        File log = new File(directory, "logcat.txt");
        java.util.concurrent.BlockingQueue<FakeProcess> children = new java.util.concurrent.LinkedBlockingQueue<>();
        List<List<String>> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        ContinuousLogcatRecorder recorder = new ContinuousLogcatRecorder(directory, "boot-a", "pid=test", null,
                command -> {
                    commands.add(command);
                    FakeProcess child = new FakeProcess();
                    children.add(child);
                    return child;
                });
        try {
            recorder.start();
            FakeProcess first = children.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(first);
            first.send("before_restart\n");
            awaitText(log, "before_restart");
            first.destroy();
            FakeProcess second = children.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(second);
            second.send("after_restart\n");
            awaitText(log, "after_restart");
            assertTrue(readText(log).contains("before_restart"));
            assertEquals(1, recorder.clear());
            assertFalse(second.isAlive());
            FakeProcess third = children.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(third);
            third.send("after_clear\n");
            awaitText(log, "after_clear");
            String fresh = readText(log);
            assertFalse(fresh.contains("before_restart"));
            assertFalse(fresh.contains("after_restart"));
            assertFalse(commands.get(0).contains("-T"));
            assertTrue(commands.get(2).contains("-T"));
            recorder.close();
            assertFalse(third.isAlive());
        } finally { recorder.close(); }
    }

    private static void awaitText(File file, String expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (file.isFile() && readText(file).contains(expected)) return;
            Thread.sleep(10L);
        }
        fail("Recorder did not retain " + expected);
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class FakeProcess extends java.lang.Process {
        final java.io.PipedInputStream input;
        final java.io.PipedOutputStream output;
        volatile boolean alive = true;
        FakeProcess() {
            try {
                input = new java.io.PipedInputStream();
                output = new java.io.PipedOutputStream(input);
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        void send(String value) throws IOException { output.write(value.getBytes(StandardCharsets.UTF_8)); }
        @Override public java.io.InputStream getInputStream() { return input; }
        @Override public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        @Override public boolean isAlive() { return alive; }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public int waitFor() throws InterruptedException { while (alive) Thread.sleep(10L); return 0; }
        @Override public void destroy() {
            alive = false;
            try { output.close(); } catch (IOException ignored) {}
        }
    }
}
