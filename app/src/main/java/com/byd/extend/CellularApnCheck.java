package com.byd.extend;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** One killable check. Its process group also owns any settings CLI children. */
public final class CellularApnCheck {
    private volatile String stage = "startup";

    public static void main(String[] args) {
        CellularApnCheck check = new CellularApnCheck();
        int result = 1;
        try {
            if (args.length != 4) throw new IllegalArgumentException("Expected deadline, reason, source, request");
            long deadline = Long.parseLong(args[0]);
            ownLifetime(deadline, () -> check.stage);
            int reason = Integer.parseInt(args[1]);
            if (reason < 0 || !(args[2].equals("detected") || args[2].equals("default")))
                throw new IllegalArgumentException("Invalid wake selection");
            check.run(deadline, reason, args[2], args[3]);
            result = 0;
        } catch (Throwable error) {
            event("check_error", CellularRecovery.diagnosticDetail(error, "stage=apn2_" + check.stage));
        }
        System.exit(result);
    }

    static void ownLifetime(long deadline, java.util.function.Supplier<String> stage) throws Exception {
        if (Process.myUid() != 2000 || !ownsGroup(Process.myPid()))
            throw new SecurityException("Child requires its own shell process group");
        // Start the deadline before the parent handshake so an abandoned launcher also expires.
        Thread timer = new Thread(() -> {
            SystemClock.sleep(Math.max(0L, deadline - SystemClock.elapsedRealtime()));
            event("deadline", "stage=" + stage.get());
            killGroup();
        }, "byd-child-deadline");
        timer.setDaemon(true); timer.start();
        BufferedReader parent = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (!"start".equals(parent.readLine())) throw new java.io.IOException("Child owner cancelled before start");
        Thread watch = new Thread(() -> {
            try { while (parent.read() != -1) { } }
            catch (Exception e) { event("owner_pipe_error", CellularRecovery.diagnosticDetail(e, "stage=owner_watch")); }
            killGroup();
        }, "byd-child-owner");
        watch.setDaemon(true); watch.start();
    }

    private static void killGroup() {
        try { Os.kill(-Process.myPid(), OsConstants.SIGKILL); }
        catch (Exception error) {
            event("group_stop_error", CellularRecovery.diagnosticDetail(error, "stage=group_stop"));
            Process.killProcess(Process.myPid());
        }
    }

    static boolean ownsGroup(int pid) throws Exception {
        if (pid <= 0) return false;
        java.nio.file.Path path = java.nio.file.Paths.get("/proc/" + pid + "/stat");
        if (Os.stat(path.toString()).st_uid != 2000) return false;
        String stat = new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8);
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
        return fields.length > 3 && Integer.parseInt(fields[2]) == pid && Integer.parseInt(fields[3]) == pid;
    }

    private void run(long deadline, int reason, String source, String request) throws Exception {
        CellularApnPlatform platform = new CellularApnPlatform(deadline, request);
        stage = "vehicle_power";
        int power = platform.vehiclePower();
        event("power", Integer.toString(power));
        if (power != 0) { event("check_skipped", "reason=vehicle_on"); return; }
        stage = "settings";
        String disabled = platform.disabledReason();
        if (disabled != null) { event("check_skipped", "reason=" + disabled); return; }
        stage = "wifi";
        boolean wifiConnected = platform.wifiConnected();
        event("wifi", "connected=" + wifiConnected);
        if (wifiConnected) { event("wake_skipped", "reason=wifi_connected"); return; }
        stage = "network";
        CellularApnPlatform.ApnState apn = platform.apn2();
        if (apn.ready) { event("ready", apn.toString()); return; }
        stage = "display";
        CellularApnPlatform.DisplayState display = platform.display();
        event("observation", apn + " " + display);
        if (!display.blackAsleep()) { event("wake_skipped", "reason=display_not_asleep_under_black_cover"); return; }
        stage = "network_recheck";
        apn = platform.apn2();
        if (apn.ready) { event("ready", "before_dispatch=true " + apn); return; }
        stage = "guards_recheck";
        disabled = platform.disabledReason();
        if (disabled != null) { event("wake_skipped", "reason=" + disabled); return; }
        power = platform.vehiclePower();
        event("power", Integer.toString(power));
        display = platform.display();
        if (power != 0 || !display.blackAsleep()) { event("wake_skipped", "reason=guards_changed " + display); return; }
        disabled = platform.disabledReason();
        if (disabled != null) { event("wake_skipped", "reason=" + disabled); return; }
        stage = "wifi_recheck";
        wifiConnected = platform.wifiConnected();
        event("wifi", "connected=" + wifiConnected + " before_dispatch=true");
        if (wifiConnected) { event("wake_skipped", "reason=wifi_connected before_dispatch=true"); return; }
        platform.checkActive();
        stage = "wake_dispatch";
        event("wake_attempt", "power=0 wifi_connected=false reason=" + reason + " reason_source=" + source + " " + apn + " " + display);
        platform.wake(reason);
        event("wake_returned", "display_and_apn2_not_yet_verified=true");
        stage = "display_verify";
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 1500L);
        do {
            power = platform.vehiclePower();
            event("power", Integer.toString(power));
            display = platform.display();
            if (power != 0) { event("display_verification_stopped", "reason=vehicle_on"); return; }
            if (display.blackAwake()) {
                event("display_confirmed", "power=0 " + display + " apn2_verify_on_next_cycle=true");
                return;
            }
            if (!"1".equals(display.blackCover)) {
                event("display_unconfirmed", "reason=black_cover_changed " + display); return;
            }
            Thread.sleep(50L);
        } while (SystemClock.elapsedRealtime() < until);
        event("display_unconfirmed", "reason=transition_timeout " + display);
    }

    private static void event(String name, String detail) {
        System.out.println("APN2 " + name + " " + detail);
        System.out.flush();
    }
}
