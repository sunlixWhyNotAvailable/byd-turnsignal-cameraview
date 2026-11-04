package com.byd.extend;

/** One measured object; continuity changes when its OEM identity disappears/reappears. */
public final class DistanceObject {
    public final Integer id, type;
    public final long continuity;
    private final double[] distances;

    public DistanceObject(Integer id, Integer type, long continuity, double[] distances) {
        DistanceFrame.validateDistances(distances);
        if ((id == null) != (type == null)) throw new IllegalArgumentException("Incomplete obstacle identity");
        this.id = id; this.type = type; this.continuity = continuity;
        this.distances = distances.clone();
    }
    public double[] distances() { return distances.clone(); }
    public double distance(int sector) { return distances[sector]; }
    public DistanceObject withContinuity(long value) { return new DistanceObject(id, type, value, distances); }
}
