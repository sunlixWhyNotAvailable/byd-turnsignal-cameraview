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
            sector.put("selected_source", source); sector.put("fallback_reason", fallback); sectors.add(sector);
        }
        return new Object[]{"sectors", sectors, "adas_received_ms", adas.receivedAt,
                "adas_current", adas.fresh(frame.receivedAt), "adas_status", adas.status, "adas_generation", adas.generation,
                "radar_fids", ParkingRadarState.FIDS.clone(), "radar_raw", radar.raw(),
                "radar_present", radar.present(), "radar_received_ms", radar.received(),
                "radar_core_status", radar.status(0), "radar_side_status", radar.status(1),
                "radar_core_generation", radar.generation(0), "radar_side_generation", radar.generation(1)};
    }
}
