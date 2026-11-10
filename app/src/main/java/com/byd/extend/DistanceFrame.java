package com.byd.extend;

/** Immutable functional data, independent of the diagnostic logging switch. */
public final class DistanceFrame {
    public static final long STALE_MS=500;
    public final long generation, receivedAt;
    public final boolean valid;
    public final String status;
    public final long adasReceivedAt, radarCoreGeneration, radarSideGeneration;
    public final boolean adasValid, radarAvailable;
    public final int radarMask;
    private final double[] distances;
    public final java.util.List<DistanceObject> objects;
    public DistanceFrame(long generation,long receivedAt,boolean valid,String status,double[] distances) {
        this(generation, receivedAt, valid, status, distances, java.util.Collections.emptyList());
    }
    public DistanceFrame(long generation,long receivedAt,boolean valid,String status,double[] distances,
            java.util.List<DistanceObject> objects) {
        this(generation, receivedAt, valid, status, distances, objects, receivedAt, valid, 0, false, 0, 0);
    }
    public DistanceFrame(long generation,long receivedAt,boolean valid,String status,double[] distances,
            java.util.List<DistanceObject> objects, long adasReceivedAt, boolean adasValid,
            int radarMask, boolean radarAvailable, long radarCoreGeneration, long radarSideGeneration) {
        validateDistances(distances);
        if ((radarMask & ~255) != 0 || radarMask != 0 && !radarAvailable)
            throw new IllegalArgumentException("Invalid radar sector mask");
        this.generation=generation; this.receivedAt=receivedAt; this.valid=valid; this.status=status;
        this.distances=distances.clone();
        this.objects=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(objects));
        this.adasReceivedAt=adasReceivedAt; this.adasValid=adasValid; this.radarMask=radarMask;
        this.radarAvailable=radarAvailable; this.radarCoreGeneration=radarCoreGeneration;
        this.radarSideGeneration=radarSideGeneration;
    }
    static void validateDistances(double[] distances) {
        if(distances.length!=8) throw new IllegalArgumentException("Eight distance sectors required");
        for(double value:distances) if(!Double.isNaN(value) && (!Double.isFinite(value) || value<0))
            throw new IllegalArgumentException("Distance must be nonnegative or missing");
    }
    public static DistanceFrame unavailable(long generation,long now,String reason) {
        double[] empty=new double[8]; java.util.Arrays.fill(empty,Double.NaN);
        return new DistanceFrame(generation,now,false,reason,empty);
    }
    public boolean adasFresh(long now) { return adasValid && now>=adasReceivedAt && now-adasReceivedAt<STALE_MS; }
    public boolean fresh(long now) { return valid && now>=receivedAt && (radarAvailable || adasFresh(now)); }
    public boolean radarSector(int sector) { return (radarMask & (1 << sector)) != 0; }
    /** Expire only ADAS sectors; an unrelated radar callback cannot renew their deadline. */
    public DistanceFrame at(long now) {
        if (!valid || now < receivedAt) return unavailable(generation, receivedAt, status);
        if (fresh(now) && (!adasValid || adasFresh(now))) return this;
        double[] values=distances();
        for (int i=0;i<8;i++) if (!radarAvailable || !radarSector(i)) values[i]=Double.NaN;
        return new DistanceFrame(generation, receivedAt, valid && radarAvailable,
                radarAvailable ? "radar_only" : "stale", values, java.util.Collections.emptyList(),
                adasReceivedAt, false, radarMask, radarAvailable, radarCoreGeneration, radarSideGeneration);
    }
    public double[] distances() { return distances.clone(); }
}
