package com.byd.extend;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Reconstructed SL07 full-contour contract (2026-08-30), units centimetres. */
public final class AdasDistanceModel {
    public static final double LENGTH = 483, WIDTH = 192.5, CENTER_X = 156.25, CENTER_Y = -.75;
    public static final int DIRECTIONS = 8;
    // Clockwise: front, front-right, right, rear-right, rear, rear-left, left, front-left.
    private static final double[][][] PATCHES = createPatches();
    private static final double EPSILON = 1e-7;
    private static final double[][] EMPTY = new double[0][];

    public static final class Obstacle {
        public final Integer id, type;
        public final double x, y, length, width, heading;
        public Obstacle(double x, double y, double length, double width, double heading) {
            this(null, null, x, y, length, width, heading);
        }
        public Obstacle(Integer id, Integer type, double x, double y, double length, double width, double heading) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(length)
                    || !Double.isFinite(width) || !Double.isFinite(heading) || length <= 0 || width <= 0)
                throw new IllegalArgumentException("Invalid ADAS oriented bounding box");
            this.id = id; this.type = type;
            this.x = x; this.y = y; this.length = length; this.width = width; this.heading = heading;
        }
        double[][] corners() {
            double c = Math.cos(Math.toRadians(heading)), s = Math.sin(Math.toRadians(heading));
            double[][] p = {{-length/2,-width/2}, {length/2,-width/2}, {length/2,width/2}, {-length/2,width/2}};
            for (double[] v : p) { double a = v[0], b = v[1]; v[0] = x+a*c-b*s; v[1] = y+a*s+b*c; }
            return p;
        }
    }

    public static double[] distances(List<Obstacle> objects) {
        return evaluate(objects).distances;
    }

    public static final class Result {
        public final double[] distances;
        public final List<DistanceObject> objects;
        Result(double[] distances, List<DistanceObject> objects) {
            this.distances = distances; this.objects = java.util.Collections.unmodifiableList(objects);
        }
    }

    public static Result evaluate(List<Obstacle> objects) {
        double[] result = new double[DIRECTIONS];
        Arrays.fill(result, Double.NaN);
        List<DistanceObject> measured = new ArrayList<>(objects.size());
        for (Obstacle object : objects) {
            double[][] box = object.corners();
            double[] distance = new double[DIRECTIONS];
            double[] coverage = new double[16];
            Arrays.fill(coverage, Double.NaN);
            for (int i = 0; i < DIRECTIONS; i++) {
                distance[i] = Double.POSITIVE_INFINITY;
                for (double[] segment : PATCHES[i]) {
                    double[][] covered;
                    if (segment[0] == segment[2]) {
                        boolean front = segment[0] > CENTER_X;
                        covered = clip(box, 0, segment[0], front);
                        covered = clip(covered, 1, Math.min(segment[1], segment[3]), true);
                        covered = clip(covered, 1, Math.max(segment[1], segment[3]), false);
                    } else {
                        boolean left = segment[1] > CENTER_Y;
                        covered = clip(box, 1, segment[1], left);
                        covered = clip(covered, 0, Math.min(segment[0], segment[2]), true);
                        covered = clip(covered, 0, Math.max(segment[0], segment[2]), false);
                    }
                    distance[i] = Math.min(distance[i], clearance(covered, segment));
                    for (double[] p : covered) {
                        // Project only the covered tangent range onto this physical body segment.
                        double x=segment[0]==segment[2] ? segment[0] : p[0];
                        double y=segment[1]==segment[3] ? segment[1] : p[1];
                        include(coverage,i,x,y);
                    }
                }
                // Corner sectors also cover the quadrant outside both sides of the car.
                if ((i & 1) != 0) {
                    boolean front = i == 1 || i == 7, left = i == 5 || i == 7;
                    double x = CENTER_X + (front ? LENGTH / 2 : -LENGTH / 2);
                    double y = CENTER_Y + (left ? WIDTH / 2 : -WIDTH / 2);
                    double[][] covered = clip(clip(box, 0, x, front), 1, y, left);
                    distance[i] = Math.min(distance[i], clearance(covered, new double[]{x,y,x,y}));
                    if (covered.length != 0) include(coverage,i,x,y);
                }
            }
            for (int i = 0; i < DIRECTIONS; i++) {
                if (Double.isFinite(distance[i]))
                    result[i] = Double.isNaN(result[i]) ? distance[i] : Math.min(result[i], distance[i]);
                else distance[i] = Double.NaN;
            }
            measured.add(new DistanceObject(object.id, object.type, 0, distance,
                    new double[]{object.x,object.y,object.length,object.width,object.heading},coverage));
        }
        return new Result(result, measured);
    }

    private static void include(double[] coverage,int sector,double x,double y) {
        double position=Math.atan2(CENTER_Y-y,x-CENTER_X)*4/Math.PI;
        while (position-sector < -4) position+=8;
        while (position-sector > 4) position-=8;
        int index=sector*2;
        coverage[index]=Double.isNaN(coverage[index]) ? position : Math.min(coverage[index],position);
        coverage[index+1]=Double.isNaN(coverage[index+1]) ? position : Math.max(coverage[index+1],position);
    }

    private static double[][] clip(double[][] polygon, int axis, double edge, boolean greater) {
        int inside = 0;
        for (double[] p : polygon) if (greater ? p[axis] >= edge : p[axis] <= edge) inside++;
        if (inside == 0) return EMPTY;
        if (inside == polygon.length) return polygon;
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i], b = polygon[(i + 1) % polygon.length];
            boolean inA = greater ? a[axis] >= edge : a[axis] <= edge;
            boolean inB = greater ? b[axis] >= edge : b[axis] <= edge;
            if (inA) result.add(a);
            if (inA != inB) {
                double t = (edge - a[axis]) / (b[axis] - a[axis]);
                double[] p = {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
                p[axis] = edge;
                result.add(p);
            }
        }
        return result.toArray(new double[0][]);
    }

    private static double[][][] createPatches() {
        double f=CENTER_X+LENGTH/2, b=CENTER_X-LENGTH/2, l=CENTER_Y+WIDTH/2, r=CENTER_Y-WIDTH/2;
        double fq=CENTER_X+LENGTH/4, bq=CENTER_X-LENGTH/4, lq=CENTER_Y+WIDTH/4, rq=CENTER_Y-WIDTH/4;
        return new double[][][] {
            {{f,rq,f,lq}}, {{f,r,f,rq},{fq,r,f,r}}, {{bq,r,fq,r}},
            {{b,r,b,rq},{b,r,bq,r}}, {{b,rq,b,lq}}, {{b,lq,b,l},{b,l,bq,l}},
            {{bq,l,fq,l}}, {{f,lq,f,l},{fq,l,f,l}}
        };
    }

    private static double clearance(double[][] box, double[] segment) {
        double[] a={segment[0],segment[1]}, b={segment[2],segment[3]};
        if (inside(box,a) || inside(box,b)) return 0;
        double minimum=Double.POSITIVE_INFINITY;
        for (int i=0;i<box.length;i++) {
            double[] c=box[i], d=box[(i+1)%box.length];
            if (intersects(a,b,c,d)) return 0;
            minimum=Math.min(minimum, Math.min(Math.min(pointSegment(a,c,d),pointSegment(b,c,d)),
                    Math.min(pointSegment(c,a,b),pointSegment(d,a,b))));
        }
        return minimum;
    }
    private static double cross(double[] a,double[] b,double[] c) {
        return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
    }
    private static boolean inside(double[][] box,double[] p) {
        double area = 0;
        for(int i=0;i<box.length;i++) {
            double[] a = box[i], b = box[(i+1)%box.length];
            if(cross(a,b,p)<-EPSILON) return false;
            area += a[0]*b[1]-b[0]*a[1];
        }
        if (Math.abs(area) < EPSILON) return false;
        return true;
    }
    private static boolean intersects(double[] a,double[] b,double[] c,double[] d) {
        double ac=cross(a,b,c), ad=cross(a,b,d), ca=cross(c,d,a), cb=cross(c,d,b);
        if (((ac>EPSILON && ad< -EPSILON)||(ac< -EPSILON && ad>EPSILON))
                && ((ca>EPSILON && cb< -EPSILON)||(ca< -EPSILON && cb>EPSILON))) return true;
        return pointSegment(a,c,d)<=EPSILON || pointSegment(b,c,d)<=EPSILON
                || pointSegment(c,a,b)<=EPSILON || pointSegment(d,a,b)<=EPSILON;
    }
    private static double pointSegment(double[] p,double[] a,double[] b) {
        double x=b[0]-a[0], y=b[1]-a[1], norm=x*x+y*y;
        double t=norm==0?0:Math.max(0,Math.min(1,((p[0]-a[0])*x+(p[1]-a[1])*y)/norm));
        return Math.hypot(p[0]-a[0]-t*x,p[1]-a[1]-t*y);
    }
    private AdasDistanceModel() {}
}
