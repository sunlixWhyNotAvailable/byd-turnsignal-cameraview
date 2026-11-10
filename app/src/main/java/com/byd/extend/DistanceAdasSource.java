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
    private final LatestValueDispatcher<Packet> packets;
    private static final class Packet {
        final long connection, received, wallReceived;
        final Long oemTimestamp;
        final byte[] payload;
        Packet(long connection, long received, long wallReceived, Long oemTimestamp, byte[] payload) {
            this.connection=connection; this.received=received; this.wallReceived=wallReceived; this.oemTimestamp=oemTimestamp; this.payload=payload;
        }
    }
    private final Set<Long> subscribed=new LinkedHashSet<>();
    private volatile Object proxy;
    private Object callback;
    private Class<?> api, callbackClass;
    private Method getTopic, getPayload, getTimestamp;
    private volatile boolean timestampFailureReported;
    private AdasObstacleDecoder decoder;
    private final DistanceObjectContinuity continuity = new DistanceObjectContinuity();
    private ClassLoader loader;
    private volatile boolean needed, failed;
    private boolean started;
    private volatile long epoch;
    private long generation, count, startedAt, expired;
    private final DiagnosticTimingWindow timing = new DiagnosticTimingWindow(
            "work_us", "queue_ms", "decode_us", "decode_cpu_us", "model_us", "model_cpu_us",
            "publish_us", "publish_cpu_us", "age_ms");
    private DistanceFrame current=DistanceFrame.unavailable(0,0,"inactive");
    private final Runnable stale=()-> {
        if(needed && current.valid && !current.fresh(SystemClock.elapsedRealtime())) {
            if (DiagnosticLogPolicy.extended()) emit("distance_stale_detail", "generation", generation,
                    "frame_received_ms", current.receivedAt,
                    "expiry_late_ms", SystemClock.elapsedRealtime()-current.receivedAt-DistanceFrame.STALE_MS);
            invalidate("stale");
        }
    };

    DistanceAdasSource(Context identity,Handler handler,Consumer<DistanceFrame> frames,BiConsumer<String,Object[]> events) {
        this.identity=identity; this.handler=handler; this.frames=frames; this.events=events;
        packets=new LatestValueDispatcher<>(handler::post, this::receive);
    }

    void setNeeded(boolean next) {
        if(needed==next) return;
        needed=next;
        if(!next) { close(); failed=false; return; }
        if(failed) return;
        startedAt=SystemClock.elapsedRealtime(); count=0;
        final long connection=++epoch;
        ++generation;
        timing.clear();
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
            try { getTimestamp=data.getMethod("getTimeStamp"); }
            catch (NoSuchMethodException absent) { getTimestamp=null; logError("optional_oem_timestamp", absent); }
            timestampFailureReported=false;
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
                    if(active(connection)) packets.control(()-> { if(active(connection)) status(ready); });
                } else if(method.getName().equals("onSomeIpEvent") && args!=null && args.length==1) {
                    final long received=SystemClock.elapsedRealtime();
                    final long receivedWall=DiagnosticLogPolicy.extended()?System.currentTimeMillis():0;
                    try {
                        long topic=((Number)call(getTopic,args[0])).longValue();
                        if(topic!=DATA && topic!=AVAILABLE && topic!=UNAVAILABLE) return null;
                        if (!active(connection)) return null;
                        if(topic==DATA) {
                            Long timestamp=null;
                            if(DiagnosticLogPolicy.extended() && getTimestamp!=null) try {
                                timestamp=((Number)call(getTimestamp,args[0])).longValue();
                            } catch(Throwable failure) { if(!timestampFailureReported) { timestampFailureReported=true; logError("optional_oem_timestamp",failure); } }
                            packets.offer(new Packet(connection,received,receivedWall,timestamp,((byte[])call(getPayload,args[0])).clone()));
                        }
                        else packets.control(()-> { if(active(connection)) availability(topic); });
                    } catch(Throwable error) { packets.control(()-> { if(active(connection)) error("callback",error); }); }
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
    void control(Runnable action) { packets.control(action); }
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
    private void availability(long topic) {
        if(topic==UNAVAILABLE) { subscribed.remove(DATA); generationState("service_unavailable"); return; }
        if(topic==AVAILABLE) { try { subscribe(DATA); } catch(Throwable error) { error("resubscribe",error); } return; }
    }
    private void receive(Packet packet, long token) {
        CriticalOperationTrace trace = CriticalOperationTrace.begin("distance_receive", events,
                "generation", packet.connection, "received_ms", packet.received, "oem_timestamp_raw", packet.oemTimestamp);
        try { processPacket(packet, token); } finally { CriticalOperationTrace.end(trace); }
    }
    private void processPacket(Packet packet, long token) {
        if(!active(packet.connection) || !packets.current(token) || !started || !subscribed.contains(DATA)) return;
        long received=packet.received, now=SystemClock.elapsedRealtime();
        if(now<received || now-received>=DistanceFrame.STALE_MS) { expired++; invalidate("stale_delivery"); return; }
        boolean measure=timing.enabled();
        long policyRevision=DiagnosticLogPolicy.revision();
        long start=measure?SystemClock.elapsedRealtimeNanos():0;
        long cpuStart=measure?android.os.Debug.threadCpuTimeNanos():0;
        String operation="decode_model";
        try {
            CriticalOperationTrace.phase("distance_decode");
            List<AdasDistanceModel.Obstacle> objects=decoder.decode(packet.payload);
            Exception identityError=decoder.takeIdentityError();
            if(identityError!=null) logError("optional_obstacle_identity",identityError);
            long decoded=measure?SystemClock.elapsedRealtimeNanos():0;
            long decodedCpu=measure?android.os.Debug.threadCpuTimeNanos():0;
            CriticalOperationTrace.phase("distance_model");
            AdasDistanceModel.Result measured=AdasDistanceModel.evaluate(objects);
            long modeled=measure?SystemClock.elapsedRealtimeNanos():0;
            long modeledCpu=measure?android.os.Debug.threadCpuTimeNanos():0;
            if(!active(packet.connection) || !packets.current(token)) return;
            DistanceFrame next=new DistanceFrame(generation,received,true,"ready",measured.distances,
                    continuity.accept(measured.objects));
            if(!next.fresh(SystemClock.elapsedRealtime())) { expired++; invalidate("stale_delivery"); return; }
            boolean recovering=!current.valid;
            current=next; count++;
            if(recovering) emit("distance_source_ready","generation",generation);
            operation="publish_frame";
            CriticalOperationTrace.phase("distance_publish");
            frames.accept(current);
            long published=measure?SystemClock.elapsedRealtimeNanos():0;
            long publishedCpu=measure?android.os.Debug.threadCpuTimeNanos():0;
            handler.removeCallbacks(stale);
            handler.postDelayed(stale,Math.max(0,received+DistanceFrame.STALE_MS-SystemClock.elapsedRealtime()));
            if(measure && policyRevision==DiagnosticLogPolicy.revision() && DiagnosticLogPolicy.extended()) {
                operation="frame_diagnostic";
                Object[] report=timing.recordContext(SystemClock.elapsedRealtime(), new Object[]{
                        "oem_timestamp_raw",packet.oemTimestamp,"oem_clock","unverified",
                        "received_ms",received,"received_wall_ms",packet.wallReceived,"processed_ms",now}, (published-start)/1000,
                        now-received, (decoded-start)/1000, (decodedCpu-cpuStart)/1000,
                        (modeled-decoded)/1000, (modeledCpu-decodedCpu)/1000,
                        (published-modeled)/1000, (publishedCpu-modeledCpu)/1000,
                        SystemClock.elapsedRealtime()-received);
                if(report!=null) {
                    Object[] fields=java.util.Arrays.copyOf(report,report.length+8);
                    Object[] state={"generation",generation,"replaced",packets.replaced(),"expired",expired,"frames",count};
                    System.arraycopy(state,0,fields,report.length,state.length);
                    emit("distance_source_metrics",fields);
                }
            }
        } catch(Throwable error) { error(operation,error); }
    }
    private void generationState(String reason) {
        ++generation;
        invalidate(reason);
    }
    private void invalidate(String reason) {
        continuity.clear();
        timing.clear();
        boolean changed=current.valid || !reason.equals(current.status);
        current=DistanceFrame.unavailable(generation,SystemClock.elapsedRealtime(),reason);
        handler.removeCallbacks(stale);
        if(changed) frames.accept(current);
        if(changed) emit("distance_source_unavailable","reason",reason,"generation",generation);
    }
    private void command(String method,long value) throws Throwable {
        int result=((Number)call(api.getMethod(method,long.class),proxy,value)).intValue();
        if(result!=0 && result!=13) throw new IllegalStateException(method+"(0x"+Long.toHexString(value)+") OEM result="+result);
    }
    void close() {
        ++epoch; ++generation; packets.clear(); handler.removeCallbacks(stale);
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
