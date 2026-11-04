package com.byd.extend;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Uses the installed OEM protobuf implementation, including its wire validation. */
public final class AdasObstacleDecoder {
    private final Method parse, hasStruct, getStruct, getObjects;
    private final Method[] fields;
    private Method id, type;
    private Exception identityError;
    public AdasObstacleDecoder(ClassLoader loader) throws ReflectiveOperationException {
        String base="someip.obstacle_.lane.line.info.service.SomeipObstacleLaneLineInfoService$";
        Class<?> wrapper=loader.loadClass(base+"ObstacleInfoNotify"), struct=loader.loadClass(base+"ObstacleInfoNotifyStruct"),
                object=loader.loadClass(base+"Object");
        parse=wrapper.getMethod("parseFrom",byte[].class);
        hasStruct=wrapper.getMethod("hasObstacleInfoNotifyStruct");
        getStruct=wrapper.getMethod("getObstacleInfoNotifyStruct");
        getObjects=struct.getMethod("getObjectArrayList");
        String[] names={"getObstacleDistanceXI","getObstacleDistanceYI","getBoundingBoxLengthI","getBoundingBoxWidthI","getObjHeading"};
        fields=new Method[names.length];
        for(int i=0;i<fields.length;i++) fields[i]=object.getMethod(names[i]);
        try { id=object.getMethod("getObstacleIdI"); type=object.getMethod("getObstacleType"); }
        catch(NoSuchMethodException error) { id=null; type=null; identityError=error; }
    }
    public List<AdasDistanceModel.Obstacle> decode(byte[] payload) throws ReflectiveOperationException {
        Object wrapper=parse.invoke(null,(Object)payload);
        if(!Boolean.TRUE.equals(hasStruct.invoke(wrapper))) throw new IllegalArgumentException("ADAS frame missing obstacle struct");
        List<?> objects=(List<?>)getObjects.invoke(getStruct.invoke(wrapper));
        List<AdasDistanceModel.Obstacle> result=new ArrayList<>(objects.size());
        for(Object object:objects) {
            Integer objectId=null, objectType=null;
            if(id!=null && type!=null) {
                try { objectId=((Number)id.invoke(object)).intValue(); objectType=((Number)type.invoke(object)).intValue(); }
                catch(Exception error) { id=null; type=null; identityError=error; objectId=null; objectType=null; }
            }
            result.add(new AdasDistanceModel.Obstacle(objectId, objectType,
                    number(0,object),number(1,object),number(2,object),number(3,object),number(4,object)));
        }
        return result;
    }
    public Exception takeIdentityError() {
        Exception error=identityError; identityError=null; return error;
    }
    private double number(int i,Object object) throws ReflectiveOperationException { return ((Number)fields[i].invoke(object)).doubleValue(); }
}
