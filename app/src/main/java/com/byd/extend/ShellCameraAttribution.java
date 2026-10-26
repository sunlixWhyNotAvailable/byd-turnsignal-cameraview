package com.byd.extend;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Initializes the viewing app_process with its own existing shell UID/package. */
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
        Field initial = field(type, "mInitialApplication");
        Field bound = field(type, "mBoundApplication");
        Object previousInitial = initial.get(thread);
        Object previousBound = bound.get(thread);
        Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
        Class<?> contextType = Class.forName("android.app.ContextImpl");
        Field packageInfo = field(contextType, "mPackageInfo");
        // systemMain installs an android Application before our shell binding is created.
        boolean systemBootstrap = application != null && previousInitial == application
                && previousBound == null && field(type, "mSystemThread").getBoolean(thread)
                && "android".equals(application.getPackageName())
                && application.getApplicationInfo().uid == Process.SYSTEM_UID
                && contextType.isInstance(application.getBaseContext())
                && packageInfo.get(application.getBaseContext()) == packageInfo.get(system);
        if (application != null && !systemBootstrap) {
            verify(type, application);
            return application;
        }
        if (previousBound != null || previousInitial != null && !systemBootstrap)
            throw new SecurityException("refusing to replace an existing Android application binding");
        Context packageContext = system.createPackageContext(PACKAGE, 0);
        if (packageContext.getApplicationInfo().uid != Process.myUid())
            throw new SecurityException("shell package does not belong to this process UID");
        Object loaded = packageInfo.get(packageContext);
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
            try { initial.set(thread, previousInitial); }
            catch (Exception restore) { failure.addSuppressed(restore); }
            try { bound.set(thread, previousBound); }
            catch (Exception restore) { failure.addSuppressed(restore); }
            throw failure;
        }
        return shell;
    }

    private static void verify(Class<?> type, Application application) throws Exception {
        int uid = application.getApplicationInfo().uid;
        String packageName = application.getPackageName();
        Object currentPackage = type.getMethod("currentPackageName").invoke(null);
        Object opPackage = type.getMethod("currentOpPackageName").invoke(null);
        if (uid != Process.myUid() || !PACKAGE.equals(packageName)
                || !PACKAGE.equals(currentPackage) || !PACKAGE.equals(opPackage))
            throw new SecurityException("existing camera application attribution is not shell: uid="
                    + uid + " processUid=" + Process.myUid() + " package=" + packageName
                    + " currentPackage=" + currentPackage + " opPackage=" + opPackage);
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
