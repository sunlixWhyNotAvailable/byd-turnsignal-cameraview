package com.byd.extend;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Coordinate inputs exercise the production model, without an OEM-response substitute. */
public final class AdasDistanceModelTest {
    private static final double F=397.75, B=-85.25, L=95.5, R=-97, X=156.25, Y=-.75;
    private static AdasDistanceModel.Obstacle box(double x,double y) {
        return new AdasDistanceModel.Obstacle(x,y,10,10,0);
    }
    @Test public void allEightDirectionsPreserveExactClearancesIncludingBeyondDefaultRange() {
        for(double cm:new double[]{0,30,150,151,250}) {
            double diagonal=cm/Math.sqrt(2);
            AdasDistanceModel.Obstacle[] boxes={box(F+cm+5,Y),box(F+diagonal+5,R-diagonal-5),
                box(X,R-cm-5),box(B-diagonal-5,R-diagonal-5),box(B-cm-5,Y),
                box(B-diagonal-5,L+diagonal+5),box(X,L+cm+5),box(F+diagonal+5,L+diagonal+5)};
            for(int sector=0;sector<8;sector++) {
                double[] values=AdasDistanceModel.distances(Collections.singletonList(boxes[sector]));
                for(int i=0;i<8;i++) {
                    if(i==sector) assertEquals("sector="+sector+", cm="+cm,cm,values[i],1e-6);
                    else assertTrue("unexpected sector="+i,Double.isNaN(values[i]));
                }
            }
        }
    }
    @Test public void rotatedBoxAndSharedPatchBoundaryUseTheObjectOutline() {
        double[] rotated=AdasDistanceModel.distances(Collections.singletonList(
                new AdasDistanceModel.Obstacle(F+30+Math.sqrt(50),Y,10,10,45)));
        assertEquals(30,rotated[0],1e-6);
        double[] seam=AdasDistanceModel.distances(Collections.singletonList(box(F+35,Y-192.5/4)));
        assertEquals(30,seam[0],1e-6); assertEquals(30,seam[1],1e-6);
    }
    @Test public void multipleObjectsKeepNearestPerSectorAndEmptyStaysMissing() {
        double[] values=AdasDistanceModel.distances(Arrays.asList(box(F+155,Y),box(F+35,Y),box(X,L+45)));
        assertEquals(30,values[0],1e-6); assertEquals(40,values[6],1e-6);
        for(double value:AdasDistanceModel.distances(Collections.emptyList())) assertTrue(Double.isNaN(value));
        assertThrows(IllegalArgumentException.class,()->box(Double.NaN,0));
        assertThrows(IllegalArgumentException.class,()->new AdasDistanceModel.Obstacle(0,0,0,1,0));
    }
    @Test public void framesAreImmutableAndExpireAtExactlyFiveHundredMilliseconds() {
        double[] values=new double[8];
        DistanceFrame frame=new DistanceFrame(3,1000,true,"ready",values);
        values[0]=17; frame.distances()[1]=18;
        assertEquals(0,frame.distances()[0],0); assertEquals(0,frame.distances()[1],0);
        assertFalse(frame.fresh(999)); assertTrue(frame.fresh(1000)); assertTrue(frame.fresh(1499));
        assertFalse(frame.fresh(1500)); assertFalse(DistanceFrame.unavailable(4,1501,"lost").fresh(1501));
        values[2]=-1;
        assertThrows(IllegalArgumentException.class,()->new DistanceFrame(3,1000,true,"ready",values));
    }

    @Test public void capturedWideRearObjectOccupiesRearAndBothRearCorners() {
        double[] values = AdasDistanceModel.distances(Collections.singletonList(
                new AdasDistanceModel.Obstacle(-393.277893,9.144036,453.480164,186.170914,-.03772996)));
        for (int i = 0; i < 8; i++) {
            if (i >= 3 && i <= 5) assertEquals("sector="+i,81.3,values[i],.15);
            else assertTrue("opposite side="+i,Double.isNaN(values[i]));
        }
    }

    @Test public void broadAndIntersectingObjectsUseOnlyTheirExteriorCoverage() {
        double[] rear = AdasDistanceModel.distances(Collections.singletonList(
                new AdasDistanceModel.Obstacle(B-15,Y,30,400,0)));
        assertEquals(0,rear[3],0); assertEquals(0,rear[4],0); assertEquals(0,rear[5],0);
        for (int i : new int[]{0,1,2,6,7}) assertTrue(Double.isNaN(rear[i]));
        double[] inside = AdasDistanceModel.distances(Collections.singletonList(box(X,Y)));
        for (double value : inside) assertTrue(Double.isNaN(value));
        double[] offset = AdasDistanceModel.distances(Collections.singletonList(box(F+35,L-10)));
        assertEquals(30,offset[7],1e-6); assertTrue(Double.isNaN(offset[0]));
    }
}
