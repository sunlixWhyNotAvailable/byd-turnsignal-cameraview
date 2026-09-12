package com.byd.extend;

import static org.junit.Assert.assertEquals;

import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Method;

public final class AvasShellSettingsTest {
    @Test public void providerHandleReleasesTheExternalReferenceExactlyOnce() throws Exception {
        FakeActivityManager manager = new FakeActivityManager();
        Method remove = FakeActivityManager.class.getMethod(
                "removeContentProviderExternal", String.class, IBinder.class);
        AvasShellSettings.ProviderHandle handle =
                new AvasShellSettings.ProviderHandle(manager, remove, null);

        handle.close();
        handle.close();

        assertEquals(1, manager.removals);
        assertEquals("settings", manager.providerName);
    }

    public static final class FakeActivityManager {
        int removals;
        String providerName;

        public void removeContentProviderExternal(String name, IBinder token) {
            removals++;
            providerName = name;
        }
    }
}
