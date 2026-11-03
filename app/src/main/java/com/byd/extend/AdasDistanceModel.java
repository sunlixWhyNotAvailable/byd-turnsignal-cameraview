package com.byd.extend;

import java.util.Arrays;
import java.util.List;

/** Reconstructed SL07 full-contour contract (2026-08-30), units centimetres. */
public final class AdasDistanceModel {
    public static final double LENGTH = 483, WIDTH = 192.5, CENTER_X = 156.25, CENTER_Y = -.75;
    public static final int DIRECTIONS = 8;
    // Clockwise: front, front-right, right, rear-right, rear, rear-left, left, front-left.
    private static final double[][][] PATCHES = createPatches();
    private static final double EPSILON = 1e-7;

    public static final class Obstacle {
        public final double x, y, length, width, heading;
        public Obstacle(double x, double y, double length, double width, double heading) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(length)
                    || !Double.isFinite(width) || !Double.isFinite(heading) || length <= 0 || width <= 0)
                throw new IllegalArgumentException("Invalid ADAS oriented bounding box");
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
        double[] result = new double[DIRECTIONS];
        Arrays.fill(result, Double.NaN);
        for (Obstacle object : objects) {
            double[][] box = object.corners();
            double[] distance = new double[DIRECTIONS];
            double nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < DIRECTIONS; i++) {
                distance[i] = Double.POSITIVE_INFINITY;
                for (double[] segment : PATCHES[i]) distance[i] = Math.min(distance[i], clearance(box, segment));
                nearest = Math.min(nearest, distance[i]);
            }
            for (int i = 0; i < DIRECTIONS; i++) if (distance[i] <= nearest + EPSILON)
                result[i] = Double.isNaN(result[i]) ? distance[i] : Math.min(result[i], distance[i]);
        }
        return result;
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
        for (int i=0;i<4;i++) {
            double[] c=box[i], d=box[(i+1)%4];
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
        for(int i=0;i<4;i++) if(cross(box[i],box[(i+1)%4],p)<-EPSILON) return false;
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
