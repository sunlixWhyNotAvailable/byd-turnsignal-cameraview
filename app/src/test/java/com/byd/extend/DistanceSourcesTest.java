package com.byd.extend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled telemetry/lifecycle inputs through production state and fusion, not an OEM registration test. */
public class DistanceSourcesTest {
    private static ParkingRadarState radar() {
        ParkingRadarState state=new ParkingRadarState();
        state.begin(0,1); state.registered(0,1);
        state.begin(1,1); state.registered(1,1);
        return state;
    }
    private static DistanceFrame adas(long at,double cm) {
        double[] values=new double[8]; Arrays.fill(values,cm);
        return new DistanceFrame(7,at,true,"ready",values);
    }
    private static void input(ParkingRadarState state,int fid,int value,long at) {
        assertTrue(state.callback(fid,value,at,1));
    }

    @Test public void mapsAllSixteenFieldsIntoClockwiseEightSectors() {
        ParkingRadarState state=radar();
        int[] fids={0x99000061,0x99000062,0x99000063,0x99000064,0x99000065,0x99000066,0x99000067,0x99000068,
                0x36500008,0x36500018,0x36500028,0x36500038,0x36500010,0x36500020,0x36500030,0x36500040};
        int[] values={17,41,35,21,55,64,65,33,104,103,101,102,114,113,112,111};
        for(int i=0;i<fids.length;i++) input(state,fids[i],values[i],100+i);
        assertArrayEquals(new double[]{35,21,111,33,64,55,101,17},state.snapshot().distances(),0);
        DistanceSources sources=new DistanceSources(); sources.adas(adas(100,10)); sources.radar(state.snapshot());
        DistanceFrame frame=sources.frame(200);
        assertEquals(255,frame.radarMask);
        assertArrayEquals(state.snapshot().distances(),frame.distances(),0);
    }

    @Test public void priorityIsNotMinimumBetweenSourcesAndFarIsNotADistance() {
        ParkingRadarState state=radar(); DistanceSources sources=new DistanceSources();
        input(state,0x99000062,65,100); input(state,0x99000063,150,100);
        sources.radar(state.snapshot()); sources.adas(adas(100,81));
        assertEquals(65,sources.frame(101).distances()[0],0);
        sources.adas(adas(102,50));
        assertEquals(65,sources.frame(102).distances()[0],0);
        for(int value:new int[]{150,151,154,155,-1,999}) {
            input(state,0x99000062,value,103); sources.radar(state.snapshot());
            assertEquals(50,sources.frame(104).distances()[0],0);
        }
        input(state,0x99000062,0,105); sources.radar(state.snapshot());
        assertEquals(0,sources.frame(106).distances()[0],0);
        input(state,0x36500008,200,107); sources.radar(state.snapshot());
        assertEquals(200,sources.frame(108).distances()[6],0);
        for(int value:new int[]{251,252,254,255,256,-1}) {
            input(state,0x36500008,value,109); sources.radar(state.snapshot());
            assertEquals(50,sources.frame(110).distances()[6],0);
        }
    }

    @Test public void callbacksWinAnOverlappingInitialReadAndOldGenerationsCannotReturn() {
        ParkingRadarState state=radar(); int fid=0x99000062;
        long ticket=state.revision(fid);
        input(state,fid,65,100);
        assertFalse(state.initialRead(fid,90,101,1,ticket));
        assertEquals(65,state.snapshot().distances()[0],0);
        state.invalidate(0,2,"stopped"); state.begin(0,3); state.registered(0,3);
        assertFalse(state.callback(fid,20,102,1));
        assertFalse(state.initialRead(fid,20,102,1,ticket));
        assertFalse(state.fail(0,1,"old_callback_error"));
        assertTrue(state.callback(fid,70,103,3));
        assertEquals(70,state.snapshot().distances()[0],0);
        assertTrue(state.fail(0,3,"callback_error"));
        assertFalse(state.callback(fid,20,104,3));
        assertTrue(Double.isNaN(state.snapshot().distances()[0]));
    }

    @Test public void latestSnapshotCoalescesWithoutLosingOtherFids() {
        ParkingRadarState state=radar(); ArrayDeque<Runnable> work=new ArrayDeque<>();
        List<ParkingRadarState.Snapshot> delivered=new ArrayList<>();
        LatestValueDispatcher<ParkingRadarState.Snapshot> packets=new LatestValueDispatcher<>(work::add,(value,token)->delivered.add(value));
        input(state,0x99000061,40,100); packets.offer(state.snapshot());
        input(state,0x99000062,80,101); packets.offer(state.snapshot());
        input(state,0x99000061,30,102); packets.offer(state.snapshot());
        while(!work.isEmpty()) work.remove().run();
        assertEquals(1,delivered.size());
        assertEquals(30,delivered.get(0).distances()[7],0);
        assertEquals(80,delivered.get(0).distances()[0],0);
    }

    @Test public void independentFreshnessPartialFailureAndOff() {
        ParkingRadarState state=radar(); DistanceSources sources=new DistanceSources();
        sources.adas(adas(100,81)); input(state,0x99000062,65,100); input(state,0x36500008,75,100);
        sources.radar(state.snapshot());
        DistanceFrame before=sources.frame(599);
        assertTrue(before.adasFresh(599));
        DistanceFrame expired=before.at(600);
        assertTrue(expired.fresh(600)); assertFalse(expired.adasValid);
        assertEquals(65,expired.distances()[0],0);
        assertTrue(Double.isNaN(expired.distances()[1]));
        assertEquals(75,expired.distances()[6],0);
        assertTrue(sources.frame(100_000).fresh(100_000)); // Same healthy state, no invented TTL or callbacks.
        state.fail(0,1,"callback_error"); sources.radar(state.snapshot());
        assertTrue(Double.isNaN(sources.frame(100_001).distances()[0]));
        assertEquals(75,sources.frame(100_001).distances()[6],0);
        sources.adas(adas(100_002,90));
        assertEquals(90,sources.frame(100_002).distances()[0],0);
        state.fail(1,1,"callback_error"); sources.radar(state.snapshot());
        assertArrayEquals(adas(100_002,90).distances(),sources.frame(100_003).distances(),0);
        sources.clear(); assertFalse(sources.frame(100_004).valid);
        assertTrue(Arrays.stream(sources.frame(100_004).distances()).allMatch(Double::isNaN));
    }

    @Test public void radarUpdatesDoNotExtendAdasAndLoggingDoesNotSelectTheSource() {
        ParkingRadarState state=radar(); DistanceSources sources=new DistanceSources(); sources.adas(adas(100,81));
        input(state,0x99000062,65,599); sources.radar(state.snapshot());
        DistanceFrame frame=sources.frame(599);
        assertEquals(100,frame.adasReceivedAt);
        assertTrue(Double.isNaN(frame.at(600).distances()[1]));
        assertTrue(frame.at(600).fresh(10_000));
        boolean previous=DiagnosticLogPolicy.extended();
        try {
            DiagnosticLogPolicy.configure(false); DistanceFrame normal=sources.frame(600);
            DiagnosticLogPolicy.configure(true); sources.diagnostic(sources.frame(600));
            assertArrayEquals(normal.distances(),sources.frame(600).distances(),0);
        } finally { DiagnosticLogPolicy.configure(previous); }
    }

    @Test public void allUnavailableHidesButFarIsAHealthyEmptyReading() {
        ParkingRadarState state=radar(); DistanceSources sources=new DistanceSources();
        input(state,0x99000062,155,100); input(state,0x36500008,255,100);
        sources.radar(state.snapshot()); assertFalse(sources.frame(100).fresh(100));
        input(state,0x99000062,150,101); sources.radar(state.snapshot());
        assertTrue(sources.frame(101).fresh(101));
        assertTrue(Arrays.stream(sources.frame(101).distances()).allMatch(Double::isNaN));
    }
}
