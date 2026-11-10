package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Read-only OEM reader, owned by Distance; never enables the retired parking-camera integration. */
public final class ParkingRadarRuntime {
    public static final String DEVICE_CLASS = "android.hardware.bydauto.radar.BYDAutoRadarDevice";
    public static final String ADAS_DEVICE_CLASS = "android.hardware.bydauto.adas.BYDAutoADASDevice";
    public static final String LISTENER_CLASS = "android.hardware.IBYDAutoListener";
    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> events;
    private final Consumer<ParkingRadarState.Snapshot> sink;
    private final ParkingRadarState state = new ParkingRadarState();
    private final Source[] sources;
    private boolean running;

    ParkingRadarRuntime(Context context, Handler handler, BiConsumer<String, Object[]> events,
            Consumer<ParkingRadarState.Snapshot> sink) {
        this.context=context; this.handler=handler; this.events=events; this.sink=sink;
        sources = new Source[]{new Source(0, DEVICE_CLASS, ParkingCameraProfile.coreRadarFids()),
                new Source(1, ADAS_DEVICE_CLASS, ParkingCameraProfile.sideRadarFids())};
    }

    public void start() { dispatch(() -> {
        if (running) return;
        running=true;
        for (Source source:sources) source.register();
    }); }
    public void stop() { dispatch(() -> {
        running=false;
        for (Source source:sources) source.stop("stopped");
    }); }
    private void dispatch(Runnable action) {
        if (Looper.myLooper()==handler.getLooper()) action.run(); else handler.post(action);
    }
    private synchronized void publish() { sink.accept(state.snapshot()); }
    private void error(String operation, Source source, Integer fid, Throwable failure) {
        StringWriter stack=new StringWriter(); failure.printStackTrace(new PrintWriter(stack));
        events.accept("distance_radar_error",new Object[]{"operation",operation,"family",source.family,
                "device_class",source.deviceClass,"fid",fid,"generation",source.generation,
                "error",failure.toString(),"stack",stack.toString()});
    }

    private final class Source {
        final int family;
        final String deviceClass;
        final int[] fids;
        Object device;
        volatile Object listener;
        Method unregister;
        volatile long generation;
        volatile long failedGeneration;

        Source(int family,String deviceClass,int[] fids) { this.family=family;this.deviceClass=deviceClass;this.fids=fids; }

        void register() {
            final long token=++generation;
            failedGeneration=0;
            state.begin(family,token); publish();
            try {
                if (android.os.Process.myUid()!=2000) throw new SecurityException("Parking reader requires shell UID 2000");
                Class<?> listenerType=Class.forName(LISTENER_CLASS);
                Class<?> eventType=Class.forName("android.hardware.IBYDAutoEvent");
                Method getId=eventType.getMethod("getEventType"), getValue=eventType.getMethod("getValue");
                listener=Proxy.newProxyInstance(ParkingRadarRuntime.class.getClassLoader(),new Class<?>[]{listenerType},
                        (proxy,method,args)-> {
                    if (method.getDeclaringClass()==Object.class) {
                        if ("toString".equals(method.getName())) return "DistanceRadar-"+family;
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return args!=null && proxy==args[0];
                    }
                    if (listener!=proxy || generation!=token || failedGeneration==token) return null;
                    try {
                        if ("onDataChanged".equals(method.getName()) && args!=null && args.length>0 && args[0]!=null) {
                            int fid=((Number)getId.invoke(args[0])).intValue();
                            if (!contains(fid)) {
                                if (DiagnosticLogPolicy.extended()) events.accept("parking_radar_event_ignored",
                                        new Object[]{"fid",fid,"reason","unexpected_fid"});
                                return null;
                            }
                            int value=integerValue(getValue.invoke(args[0]));
                            if (state.callback(fid,value,SystemClock.elapsedRealtime(),token)) publish();
                        } else if ("onError".equals(method.getName())) {
                            String code=args!=null && args.length>0 ? String.valueOf(args[0]) : "missing";
                            String message=args!=null && args.length>1 ? String.valueOf(args[1]) : "missing";
                            throw new IllegalStateException("OEM onError code="+code+", message="+message);
                        }
                    } catch (Throwable failure) { fail("callback",failure,token); }
                    return null;
                });
                Class<?> type=Class.forName(deviceClass);
                device=getInstance(type);
                Method read=type.getMethod("get",int[].class,Class.class);
                Method register=findListenerMethod(device,"registerListener",true,listener);
                unregister=findListenerMethod(device,"unregisterListener",false,listener);
                register.invoke(device,listener,fids);
                state.registered(family,token);
                for (int fid:fids) {
                    if (failedGeneration==token || generation!=token) break;
                    long revision=state.revision(fid);
                    try {
                        int value=integerValue(read.invoke(device,new int[]{fid},Integer.TYPE));
                        state.initialRead(fid,value,SystemClock.elapsedRealtime(),token,revision);
                    } catch (Throwable failure) { error("initial_get",this,fid,failure); }
                }
                publish();
                if (failedGeneration!=token) events.accept("distance_radar_listener",new Object[]{"action","registered",
                        "family",family,"device_class",deviceClass,"generation",token,
                        "uid",android.os.Process.myUid(),"pid",android.os.Process.myPid(),
                        "user",android.os.Process.myUid()/100000,"package",context.getPackageName()});
            } catch (Throwable failure) { fail("register",failure,token); }
        }
        boolean contains(int fid) { for (int value:fids) if (value==fid) return true; return false; }
        void fail(String operation,Throwable failure,long token) {
            if (!state.fail(family,token,operation+"_error")) return;
            failedGeneration=token; publish();
            error(operation,this,null,failure);
            handler.post(() -> { if (generation==token) detach(); });
        }
        void stop(String reason) {
            ++generation; failedGeneration=generation;
            state.invalidate(family,generation,reason); publish();
            detach();
            events.accept("distance_radar_listener",new Object[]{"action",reason,"family",family,"generation",generation});
        }
        void detach() {
            try { if (unregister!=null && device!=null && listener!=null) unregister.invoke(device,listener); }
            catch (Throwable failure) { error("unregister",this,null,failure); }
            listener=null; device=null; unregister=null;
        }
    }
    static Method findListenerMethod(Object target,String name,boolean withIds,Object listener) throws NoSuchMethodException {
        for (Method method:target.getClass().getMethods()) {
            Class<?>[] p=method.getParameterTypes();
            if (method.getName().equals(name) && p.length==(withIds?2:1) && p[0].isAssignableFrom(listener.getClass())
                    && (!withIds || p[1]==int[].class)) return method;
        }
        throw new NoSuchMethodException(name);
    }
    static int integerValue(Object value) throws Exception {
        if (value instanceof Number) return ((Number)value).intValue();
        if (value==null) throw new IllegalStateException("null radar value");
        try { Field field=value.getClass().getField("intValue"); return field.getInt(value); }
        catch (NoSuchFieldException ignored) { return Integer.parseInt(String.valueOf(value)); }
    }
    private Object getInstance(Class<?> type) throws Exception {
        try { return type.getMethod("getInstance",Context.class).invoke(null,context); }
        catch (NoSuchMethodException ignored) { return type.getMethod("getInstance").invoke(null); }
    }
}
