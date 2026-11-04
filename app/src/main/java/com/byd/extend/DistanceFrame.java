package com.byd.extend;

/** Immutable functional data, independent of the diagnostic logging switch. */
public final class DistanceFrame {
    public static final long STALE_MS=500;
    public final long generation, receivedAt;
    public final boolean valid;
    public final String status;
    private final double[] distances;
    public final java.util.List<DistanceObject> objects;
    public DistanceFrame(long generation,long receivedAt,boolean valid,String status,double[] distances) {
        this(generation, receivedAt, valid, status, distances, java.util.Collections.emptyList());
    }
    public DistanceFrame(long generation,long receivedAt,boolean valid,String status,double[] distances,
            java.util.List<DistanceObject> objects) {
        validateDistances(distances);
        this.generation=generation; this.receivedAt=receivedAt; this.valid=valid; this.status=status;
        this.distances=distances.clone();
        this.objects=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(objects));
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
    public boolean fresh(long now) { return valid && now>=receivedAt && now-receivedAt<STALE_MS; }
    public double[] distances() { return distances.clone(); }
}
