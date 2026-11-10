package com.byd.extend;

import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Sector priority only: neither object association nor correction of the ADAS model. */
final class DistanceSources {
    private DistanceFrame adas = DistanceFrame.unavailable(0, 0, "inactive");
    private ParkingRadarState.Snapshot radar = new ParkingRadarState().snapshot();

    void adas(DistanceFrame value) { adas = value; }
    void radar(ParkingRadarState.Snapshot value) { radar = value; }
    void clear() {
        adas = DistanceFrame.unavailable(adas.generation, 0, "inactive");
        radar = new ParkingRadarState().snapshot();
    }
    DistanceFrame frame(long now) {
        boolean adasReady = adas.fresh(now), radarReady = radar.available();
        double[] values = adas.at(now).distances(), radarValues = radar.distances();
        int mask = 0;
        for (int i = 0; i < 8; i++) if (Double.isFinite(radarValues[i])) {
            values[i] = radarValues[i]; mask |= 1 << i;
        }
        return new DistanceFrame(adas.generation, now, adasReady || radarReady,
                radarReady ? (adasReady ? "adas_radar" : "radar_only") : adas.status,
                values, adasReady ? adas.objects : Collections.emptyList(),
                adas.receivedAt, adasReady, mask, radarReady, radar.generation(0), radar.generation(1));
    }
    DistanceFrame adas() { return adas; }
    ParkingRadarState.Snapshot radar() { return radar; }
    /** Called only for extended diagnostics; functional data never travels through JSON. */
    Object[] diagnostic(DistanceFrame frame) {
        double[] adasValues=adas.distances(), radarValues=radar.distances(), selected=frame.distances();
        java.util.ArrayList<Object> sectors = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String source = frame.radarSector(i) ? "radar" : Double.isFinite(selected[i]) ? "adas" : "none";
            int family = i == 2 || i == 6 ? 1 : 0;
            String fallback = frame.radarSector(i) ? "radar_priority" :
                    !"ready".equals(radar.status(family)) ? radar.status(family) : "no_radar_measurement";
            java.util.LinkedHashMap<String, Object> sector = new java.util.LinkedHashMap<>();
            sector.put("sector", i);
            sector.put("adas_cm", Double.isFinite(adasValues[i]) ? adasValues[i] : null);
            sector.put("radar_cm", Double.isFinite(radarValues[i]) ? radarValues[i] : null);
            sector.put("selected_cm", Double.isFinite(selected[i]) ? selected[i] : null);
            java.util.ArrayList<Integer> owners=new java.util.ArrayList<>();
            for (int j=0;j<adas.objects.size();j++)
                if (Double.isFinite(adasValues[i]) && adas.objects.get(j).distance(i)==adasValues[i]) owners.add(j);
            sector.put("adas_object_indices", owners);
            sector.put("selected_source", source); sector.put("fallback_reason", fallback); sectors.add(sector);
        }
        java.util.ArrayList<Object> objects=new java.util.ArrayList<>();
        for (DistanceObject value : adas.objects) {
            java.util.LinkedHashMap<String,Object> object=new java.util.LinkedHashMap<>();
            object.put("index",objects.size()); object.put("id",value.id); object.put("type",value.type);
            object.put("continuity",value.continuity);
            double[] geometry=value.geometry();
            java.util.LinkedHashMap<String,Object> box=new java.util.LinkedHashMap<>();
            if (geometry!=null) {
                String[] names={"x_cm","y_cm","length_cm","width_cm","heading_deg"};
                for (int i=0;i<names.length;i++) box.put(names[i],geometry[i]);
            }
            object.put("obb",geometry==null ? null : box);
            object.put("sector_cm",nullableValues(value.distances()));
            object.put("contour_eighth_turns",nullableValues(value.coverage()));
            objects.add(object);
        }
        return new Object[]{"sectors", sectors, "adas_objects_version",1,"adas_objects",objects,"adas_received_ms", adas.receivedAt,
                "adas_current", adas.fresh(frame.receivedAt), "adas_status", adas.status, "adas_generation", adas.generation,
                "radar_fids", ParkingRadarState.FIDS.clone(), "radar_raw", radar.raw(),
                "radar_present", radar.present(), "radar_received_ms", radar.received(),
                "radar_core_status", radar.status(0), "radar_side_status", radar.status(1),
                "radar_core_generation", radar.generation(0), "radar_side_generation", radar.generation(1)};
    }
    private static Object[] nullableValues(double[] values) {
        if (values==null) return null;
        Object[] result=new Object[values.length];
        for (int i=0;i<values.length;i++) result[i]=Double.isFinite(values[i]) ? values[i] : null;
        return result;
    }
}
