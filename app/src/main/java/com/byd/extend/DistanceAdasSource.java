package com.byd.extend;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemClock;
import dalvik.system.PathClassLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Read-only installed SOME/IP SDK path. Owns no video or vehicle-control resource. */
final class DistanceAdasSource {
    private static final long SERVICE=0x000B000C000C0000L, DATA=0x0004000C000C8001L,
            AVAILABLE=0x0001000C000C0001L, UNAVAILABLE=0x0001000C000C0002L;
    private final Context identity;
    private final Handler handler;
    private final Consumer<DistanceFrame> frames;
    private final BiConsumer<String,Object[]> events;
    private final Set<Long> subscribed=new LinkedHashSet<>();
    private Object proxy, callback;
    private Class<?> api, callbackClass;
    private Method getTopic, getPayload;
    private AdasObstacleDecoder decoder;
    private ClassLoader loader;
    private boolean needed, failed, started;
    private long epoch, generation, count, startedAt;
    private DistanceFrame current=DistanceFrame.unavailable(0,0,"inactive");
    private final Runnable stale=()-> {
        if(needed && current.valid && !current.fresh(SystemClock.elapsedRealtime())) invalidate("stale");
    };

    DistanceAdasSource(Context identity,Handler handler,Consumer<DistanceFrame> frames,BiConsumer<String,Object[]> events) {
        this.identity=identity; this.handler=handler; this.frames=frames; this.events=events;
    }

    void setNeeded(boolean next) {
        if(needed==next) return;
        needed=next;
        if(!next) { close(); failed=false; return; }
        if(failed) return;
        startedAt=SystemClock.elapsedRealtime(); count=0;
        final long connection=++epoch;
        ++generation;
        try {
            if(android.os.Process.myUid()!=2000) throw new SecurityException("ADAS reader requires executing shell UID 2000");
            if(loader==null) {
            ApplicationInfo app=identity.getPackageManager().getApplicationInfo("com.byd.sr",PackageManager.GET_SHARED_LIBRARY_FILES);
            StringBuilder paths=new StringBuilder(app.sourceDir);
            if(app.splitSourceDirs!=null) for(String path:app.splitSourceDirs) paths.append(':').append(path);
            if(app.sharedLibraryFiles!=null) for(String path:app.sharedLibraryFiles) paths.append(':').append(path);
            loader=new PathClassLoader(paths.toString(),app.nativeLibraryDir,getClass().getClassLoader());
            }
            api=loader.loadClass("ts.car.someip.sdk.SomeIpClientProxy");
            callbackClass=loader.loadClass("ts.car.someip.sdk.SomeIpCallback");
            Class<?> data=loader.loadClass("ts.car.someip.sdk.common.SomeIpData");
            getTopic=data.getMethod("getTopic"); getPayload=data.getMethod("getPayload");
            decoder=new AdasObstacleDecoder(loader);
            proxy=call(api.getMethod("getInstance",Context.class),null,identity);
            if(proxy==null) throw new IllegalStateException("SOME/IP getInstance returned null");
            callback=Proxy.newProxyInstance(loader,new Class<?>[]{callbackClass},(self,method,args)-> {
                if(method.getDeclaringClass()==Object.class) {
                    if(method.getName().equals("hashCode")) return System.identityHashCode(self);
                    if(method.getName().equals("equals")) return self==args[0];
                    return "ExtendDistanceCallback";
                }
                if(method.getName().equals("onRequest")) return args==null||args.length==0?null:args[0];
                if(method.getName().equals("onChangeServiceStatus")) {
                    boolean ready=args!=null && args.length==1 && Boolean.TRUE.equals(args[0]);
                    handler.post(()-> { if(active(connection)) status(ready); });
                } else if(method.getName().equals("onSomeIpEvent") && args!=null && args.length==1) {
                    final long received=SystemClock.elapsedRealtime();
                    try {
                        long topic=((Number)call(getTopic,args[0])).longValue();
                        if(topic!=DATA && topic!=AVAILABLE && topic!=UNAVAILABLE) return null;
                        byte[] payload=topic==DATA?((byte[])call(getPayload,args[0])).clone():null;
                        handler.post(()-> { if(active(connection)) receive(topic,payload,received); });
                    } catch(Throwable error) { handler.post(()-> { if(active(connection)) error("callback",error); }); }
                }
                return null;
            });
            call(api.getMethod("registerCallback",callbackClass),proxy,callback);
            emit("distance_source_started","uid",android.os.Process.myUid(),"pid",android.os.Process.myPid(),
                    "package",identity.getPackageName(),"generation",generation,"backend",proxy.getClass().getName());
            boolean ready=Boolean.TRUE.equals(call(api.getMethod("isServiceReady"),proxy));
            status(ready);
            if(!ready) call(api.getMethod("reBindService"),proxy);
        } catch(Throwable error) { failed=true; error("initialize",error); close(); }
    }
    private boolean active(long connection) { return needed && !failed && proxy!=null && epoch==connection; }
    private void status(boolean ready) {
        if(!ready) { subscribed.clear(); started=false; generationState("service_disconnected"); return; }
        try {
            if(!started) { command("startClient",SERVICE); started=true; }
            subscribe(AVAILABLE); subscribe(UNAVAILABLE); subscribe(DATA);
        } catch(Throwable error) { failed=true; error("subscribe",error); close(); }
    }
    private void subscribe(long topic) throws Throwable {
        if(!subscribed.contains(topic)) { command("subscribe",topic); subscribed.add(topic); }
    }
    private void receive(long topic,byte[] payload,long received) {
        if(topic==UNAVAILABLE) { subscribed.remove(DATA); generationState("service_unavailable"); return; }
        if(topic==AVAILABLE) { try { subscribe(DATA); } catch(Throwable error) { error("resubscribe",error); } return; }
        long start=SystemClock.elapsedRealtimeNanos();
        try {
            List<AdasDistanceModel.Obstacle> objects=decoder.decode(payload);
            double[] distances=AdasDistanceModel.distances(objects);
            boolean recovering=!current.valid;
            current=new DistanceFrame(generation,received,true,"ready",distances); count++;
            if(!current.fresh(SystemClock.elapsedRealtime())) { invalidate("stale_delivery"); return; }
            if(recovering) emit("distance_source_ready","generation",generation);
            frames.accept(current);
            handler.removeCallbacks(stale);
            handler.postDelayed(stale,Math.max(0,received+DistanceFrame.STALE_MS-SystemClock.elapsedRealtime()));
            if(DiagnosticLogPolicy.extended()) {
                org.json.JSONArray values=new org.json.JSONArray(), boxes=new org.json.JSONArray();
                for(double value:distances) values.put(Double.isNaN(value)?org.json.JSONObject.NULL:value);
                for(AdasDistanceModel.Obstacle o:objects) boxes.put(new org.json.JSONArray(new double[]{o.x,o.y,o.length,o.width,o.heading}));
                emit("distance_frame_detail","generation",generation,"distances_cm",values,"objects",boxes,
                        "decode_model_us",(SystemClock.elapsedRealtimeNanos()-start)/1000);
            }
        } catch(Throwable error) { error("decode_model",error); }
    }
    private void generationState(String reason) {
        ++generation;
        invalidate(reason);
    }
    private void invalidate(String reason) {
        boolean changed=current.valid || !reason.equals(current.status);
        current=DistanceFrame.unavailable(generation,SystemClock.elapsedRealtime(),reason);
        handler.removeCallbacks(stale); frames.accept(current);
        if(changed) emit("distance_source_unavailable","reason",reason,"generation",generation);
    }
    private void command(String method,long value) throws Throwable {
        int result=((Number)call(api.getMethod(method,long.class),proxy,value)).intValue();
        if(result!=0 && result!=13) throw new IllegalStateException(method+"(0x"+Long.toHexString(value)+") OEM result="+result);
    }
    void close() {
        ++epoch; ++generation; handler.removeCallbacks(stale);
        if(proxy!=null) {
            for(long topic:new LinkedHashSet<>(subscribed)) cleanup("unsubscribe",()->command("unsubscribe",topic));
            if(started) cleanup("stopClient",()->command("stopClient",SERVICE));
            if(callback!=null) cleanup("unregisterCallback",()->call(api.getMethod("unregisterCallback",callbackClass),proxy,callback));
            cleanup("unBindService",()->call(api.getMethod("unBindService"),proxy));
            emit("distance_source_stopped","frames",count,"elapsed_ms",SystemClock.elapsedRealtime()-startedAt);
        }
        proxy=null; callback=null; decoder=null; started=false; subscribed.clear(); invalidate("inactive");
    }
    private interface Cleanup { void run() throws Throwable; }
    private void cleanup(String operation,Cleanup action) { try {action.run();} catch(Throwable error){ logError("cleanup_"+operation,error); } }
    private void error(String operation,Throwable error) { logError(operation,error); invalidate(operation+"_error"); }
    private void logError(String operation,Throwable error) {
        StringWriter trace=new StringWriter(); error.printStackTrace(new PrintWriter(trace));
        emit("distance_source_error","operation",operation,"error",error.toString(),"stack",trace.toString());
    }
    private void emit(String kind,Object... values) { events.accept(kind,values); }
    private static Object call(Method method,Object owner,Object... args) throws Throwable {
        try { return method.invoke(owner,args); } catch(InvocationTargetException error) { throw error.getCause(); }
    }
}
