package com.byd.extend;

/** One measured object; continuity changes when its OEM identity disappears/reappears. */
public final class DistanceObject {
    public final Integer id, type;
    public final long continuity;
    private final double[] distances;
    private final double[] geometry, coverage;

    public DistanceObject(Integer id, Integer type, long continuity, double[] distances) {
        this(id, type, continuity, distances, null, null);
    }
    /** Coverage pairs use clockwise eighth-turns, unwrapped around their sector. */
    public DistanceObject(Integer id, Integer type, long continuity, double[] distances,
            double[] geometry, double[] coverage) {
        DistanceFrame.validateDistances(distances);
        if ((id == null) != (type == null)) throw new IllegalArgumentException("Incomplete obstacle identity");
        if (geometry != null) {
            if (geometry.length != 5) throw new IllegalArgumentException("Five OBB coordinates required");
            for (double v : geometry) if (!Double.isFinite(v)) throw new IllegalArgumentException("Invalid OBB coordinate");
            if (geometry[2] <= 0 || geometry[3] <= 0) throw new IllegalArgumentException("Invalid OBB size");
        }
        if (coverage != null) {
            if (coverage.length != 16) throw new IllegalArgumentException("Eight contour intervals required");
            for (int i=0;i<8;i++) {
                double a=coverage[2*i], b=coverage[2*i+1];
                if (Double.isNaN(a) && Double.isNaN(b) && Double.isNaN(distances[i])) continue;
                if (!Double.isFinite(distances[i]) || !Double.isFinite(a) || !Double.isFinite(b)
                        || a>b || a<i-1.5 || b>i+1.5)
                    throw new IllegalArgumentException("Invalid contour interval for sector "+i);
            }
        }
        this.id = id; this.type = type; this.continuity = continuity;
        this.distances = distances.clone();
        this.geometry = geometry == null ? null : geometry.clone();
        this.coverage = coverage == null ? null : coverage.clone();
    }
    public double[] distances() { return distances.clone(); }
    public double distance(int sector) { return distances[sector]; }
    public double[] geometry() { return geometry == null ? null : geometry.clone(); }
    public double[] coverage() { return coverage == null ? null : coverage.clone(); }
    public boolean hasCoverage() { return coverage != null; }
    public double coverageStart(int sector) { return coverage == null ? Double.NaN : coverage[2*sector]; }
    public double coverageEnd(int sector) { return coverage == null ? Double.NaN : coverage[2*sector+1]; }
    public DistanceObject withContinuity(long value) { return new DistanceObject(id, type, value, distances, geometry, coverage); }
    @Override public boolean equals(Object value) {
        if (!(value instanceof DistanceObject)) return false;
        DistanceObject other=(DistanceObject)value;
        return java.util.Objects.equals(id,other.id) && java.util.Objects.equals(type,other.type)
                && continuity==other.continuity && java.util.Arrays.equals(distances,other.distances)
                && java.util.Arrays.equals(geometry,other.geometry) && java.util.Arrays.equals(coverage,other.coverage);
    }
    @Override public int hashCode() {
        return java.util.Objects.hash(id,type,continuity,java.util.Arrays.hashCode(distances),
                java.util.Arrays.hashCode(geometry),java.util.Arrays.hashCode(coverage));
    }
}
