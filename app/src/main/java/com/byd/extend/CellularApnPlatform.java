package com.byd.extend;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.NetworkInterface;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

/** Shell-only adapter for APN2 observations and mobile data. Never puts the display to sleep. */
final class CellularApnPlatform {
    private final long deadline;
    private final String request;
    private final Class<?> powerApi = Class.forName("android.os.IPowerManager");
    private final Class<?> displayApi = Class.forName("android.hardware.display.IDisplayManager");
    private final Class<?> networkApi = Class.forName("android.net.IConnectivityManager");
    private final Method propertyGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class);

    CellularApnPlatform(long deadline, String request) throws Exception {
        if (Process.myUid() != 2000) {
            throw new SecurityException("APN2 recovery requires shell UID 2000");
        }
        this.deadline = deadline;
        this.request = request;
    }

    void checkActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) {
            throw new InterruptedIOException("APN2 recovery check cancelled or deadline expired");
        }
    }

    private IBinder binder(String name) throws Exception {
        checkActive();
        IBinder result = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        checkActive();
        if (result == null) throw new IOException("Service unavailable: " + name);
        return result;
    }

    private Object service(String name, Class<?> api) throws Exception {
        return Class.forName(api.getName() + "$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, binder(name));
    }

    private String property(String key) throws Exception {
        checkActive();
        String result = (String) propertyGet.invoke(null, key);
        checkActive();
        return result;
    }

    String disabledReason() throws Exception {
        checkActive();
        String request = OemWakeDiscovery.request();
        if (request.isEmpty()) return "feature_not_initialized";
        if (!request.equals(this.request)) return "request_superseded";
        String[] parts = request.split(":");
        return parts[1].equals("1") && parts[2].equals("0") ? null : "feature_disabled";
    }

    int vehiclePower() throws Exception {
        checkActive();
        int value = MotionScreenControl.readPowerRaw();
        checkActive();
        return value;
    }

    DisplayState display() throws Exception {
        checkActive();
        boolean interactive = (Boolean) powerApi.getMethod("isInteractive").invoke(service("power", powerApi));
        checkActive();
        Object info = displayApi.getMethod("getDisplayInfo", int.class).invoke(service("display", displayApi), 0);
        checkActive();
        if (info == null) throw new IOException("Main display information unavailable");
        return new DisplayState(interactive, info.getClass().getField("state").getInt(info),
                property("sys.accanim.status"));
    }

    @SuppressWarnings("deprecation")
    boolean wifiConnected() throws Exception {
        Object connectivity = service("connectivity", networkApi);
        // Read the Wi-Fi network itself, even when cellular or a VPN is the default.
        NetworkInfo info = (NetworkInfo) networkApi.getMethod("getNetworkInfo", int.class)
                .invoke(connectivity, ConnectivityManager.TYPE_WIFI);
        checkActive();
        return info != null && info.isConnected();
    }

    ApnState apn2() throws Exception {
        String apnType = property("persist.sys.byd.apn_type");
        if (!"double_apn".equals(apnType)) throw new IOException("APN2 configuration unsupported or unknown: " + apnType);
        String iface = property("net.lte.apn2.ifname");
        boolean present = apnInterfacePresent(iface);
        if (!iface.equals(property("net.lte.apn2.ifname"))) throw new IOException("APN2 identity changed during observation");
        return new ApnState(present, iface, iface.isEmpty() ? "interface_name_absent"
                : present ? "interface_present" : "interface_absent");
    }

    static boolean apnInterfacePresent(String iface) throws IOException {
        if ("".equals(iface)) return false;
        if (iface == null || !iface.matches("[a-zA-Z0-9_.-]{1,15}"))
            throw new IOException("APN2 identity unknown or invalid: net.lte.apn2.ifname=" + iface);
        return NetworkInterface.getByName(iface) != null;
    }

    boolean mobileDataEnabled() throws Exception {
        String stage = "default_subscription";
        try {
            Class<?> subApi = Class.forName("com.android.internal.telephony.ISub");
            int subId = (Integer) subApi.getMethod("getDefaultDataSubId").invoke(service("isub", subApi));
            if (subId < 0 || subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
                throw new IOException("Invalid default data subscription: " + subId);
            stage = "user_data_enabled sub_id=" + subId;
            Class<?> phoneApi = Class.forName("com.android.internal.telephony.ITelephony");
            boolean enabled = (Boolean) phoneApi.getMethod("isDataEnabledForReason", int.class, int.class)
                    .invoke(service("phone", phoneApi), subId, TelephonyManager.DATA_ENABLED_REASON_USER);
            checkActive();
            return enabled;
        } catch (Exception error) { throw new IOException("Mobile data read failed at " + stage, error); }
    }

    void enableMobileData() throws Exception {
        checkActive();
        // Fixed command, inside the already bounded/owned child process group.
        java.lang.Process command = new ProcessBuilder("/system/bin/svc", "data", "enable")
                .redirectErrorStream(true).start();
        String output;
        try (java.io.InputStream input = command.getInputStream()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > 4096) throw new IOException("svc data enable output exceeds 4096 bytes");
                bytes.write(buffer, 0, count);
            }
            output = bytes.toString("UTF-8");
        }
        int exit = command.waitFor();
        checkActive();
        if (exit != 0) throw new IOException("svc data enable exit=" + exit + " output=" + output);
    }

    void wake(int reason) throws Exception {
        Object power = service("power", powerApi);
        Method wake = powerApi.getMethod("wakeUp", long.class, int.class, String.class, String.class);
        checkActive();
        wake.invoke(power, SystemClock.uptimeMillis(), reason, "BYDExtend:APN2Recovery", "com.android.shell");
        checkActive();
    }

    static final class DisplayState {
        final boolean interactive;
        final int state;
        final String blackCover;

        DisplayState(boolean interactive, int state, String blackCover) {
            this.interactive = interactive;
            this.state = state;
            this.blackCover = blackCover;
        }

        boolean asleep() { return !interactive && state == 1; }
        boolean awake() { return interactive && state == 2; }

        @Override public String toString() {
            return "interactive=" + interactive + " display=" + state + " black_cover=" + blackCover;
        }
    }

    static final class ApnState {
        final boolean ready;
        final String iface, reason;

        ApnState(boolean ready, String iface, String reason) {
            this.ready = ready;
            this.iface = iface;
            this.reason = reason;
        }

        @Override public String toString() { return "apn2_ready=" + ready + " interface=" + iface + " reason=" + reason; }
    }
}
