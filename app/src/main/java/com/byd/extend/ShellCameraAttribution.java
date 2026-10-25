package com.byd.extend;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Initializes the unbound viewing app_process with its own existing shell UID/package. */
final class ShellCameraAttribution {
    private static final String PACKAGE = "com.android.shell";

    private ShellCameraAttribution() {}

    static synchronized Context initialize() throws Exception {
        // Camera1 and NormalCamera read ActivityThread rather than the camera caller's Context.
        // This must never relabel an application or acquire another UID's permissions.
        if (Process.myUid() != 2000) throw new SecurityException("camera attribution requires actual shell UID");
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) throw new IllegalStateException("viewing ActivityThread is not initialized");
        Application application = (Application) type.getMethod("currentApplication").invoke(null);
        if (application != null) {
            verify(type, application);
            return application;
        }
        Field initial = field(type, "mInitialApplication");
        Field bound = field(type, "mBoundApplication");
        if (initial.get(thread) != null || bound.get(thread) != null)
            throw new SecurityException("refusing to replace an existing Android application binding");
        Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
        Context packageContext = system.createPackageContext(PACKAGE, 0);
        if (packageContext.getApplicationInfo().uid != Process.myUid())
            throw new SecurityException("shell package does not belong to this process UID");
        Class<?> contextType = Class.forName("android.app.ContextImpl");
        Object loaded = field(contextType, "mPackageInfo").get(packageContext);
        Method create = contextType.getDeclaredMethod("createAppContext", type, Class.forName("android.app.LoadedApk"));
        create.setAccessible(true);
        Context shell = (Context) create.invoke(null, thread, loaded);
        if (!PACKAGE.equals(shell.getPackageName()) || !PACKAGE.equals(shell.getOpPackageName())
                || shell.getApplicationInfo().uid != Process.myUid())
            throw new SecurityException("created camera context is not this process's shell identity");
        Class<?> bindType = Class.forName("android.app.ActivityThread$AppBindData");
        Constructor<?> constructor = bindType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object binding = constructor.newInstance();
        field(bindType, "appInfo").set(binding, shell.getApplicationInfo());
        field(bindType, "processName").set(binding, "bydextend_viewing");
        field(bindType, "info").set(binding, loaded);
        Application ownApplication = new ShellApplication(shell);
        try {
            bound.set(thread, binding);
            initial.set(thread, ownApplication);
            verify(type, ownApplication);
        } catch (Exception failure) {
            try { initial.set(thread, null); bound.set(thread, null); }
            catch (Exception restore) { failure.addSuppressed(restore); }
            throw failure;
        }
        return shell;
    }

    private static void verify(Class<?> type, Application application) throws Exception {
        if (application.getApplicationInfo().uid != Process.myUid()
                || !PACKAGE.equals(application.getPackageName())
                || !PACKAGE.equals(type.getMethod("currentPackageName").invoke(null))
                || !PACKAGE.equals(type.getMethod("currentOpPackageName").invoke(null)))
            throw new SecurityException("existing camera application attribution is not shell");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static final class ShellApplication extends Application {
        ShellApplication(Context context) { attachBaseContext(context); }
    }
}
