package com.byd.extend;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real decoded-coordinate input and controlled time, with no OEM service substitute. */
public final class DistanceAdasMeasurementsTest {
    private static final AdasDistanceModel.Obstacle OBJECT=
            new AdasDistanceModel.Obstacle(7,2,432.75,-.75,10,10,0);

    @Test public void recalibrationRetainsOriginalDeadlineAndStartsNewContinuity() {
        DistanceAdasMeasurements state=new DistanceAdasMeasurements();
        DistanceFrame initial=state.accept(Collections.singletonList(OBJECT),1,1000);
        assertEquals(30,initial.distances()[0],0);
        assertTrue(state.calibrate(20,0,0,0));
        DistanceFrame updated=state.recalculate(2,1040);
        assertEquals(50,updated.distances()[0],0);
        assertEquals(2,updated.generation); assertEquals(1000,updated.receivedAt);
        assertTrue(updated.fresh(1499)); assertFalse(updated.fresh(1500));
        assertNotEquals(initial.objects.get(0).continuity,updated.objects.get(0).continuity);
        assertFalse(state.calibrate(20,0,0,0));
        DistanceFrame next=state.accept(Collections.singletonList(OBJECT),2,1100);
        assertEquals(updated.objects.get(0).continuity,next.objects.get(0).continuity);
        assertTrue(state.calibrate(0,0,0,0));
        assertEquals(30,state.recalculate(3,1150).distances()[0],0);
    }

    @Test public void staleOrClearedSampleCannotBeRevivedAndInvalidCalibrationKeepsPrevious() {
        DistanceAdasMeasurements state=new DistanceAdasMeasurements();
        state.calibrate(20,0,0,0);
        assertFalse(state.recalculate(1,1000).valid);
        state.accept(Collections.singletonList(OBJECT),1,1000);
        assertThrows(IllegalArgumentException.class,()->state.calibrate(250,250,0,0));
        assertEquals(50,state.recalculate(1,1200).distances()[0],0);
        assertFalse(state.recalculate(2,1500).valid);
        state.accept(Collections.singletonList(OBJECT),2,1600);
        state.clear();
        assertFalse(state.recalculate(3,1601).valid);
        assertEquals(50,state.accept(Collections.singletonList(OBJECT),3,1700).distances()[0],0);
    }
}
