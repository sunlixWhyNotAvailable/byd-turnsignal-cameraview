package com.byd.extend;

import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public final class WeatherLocationSourcesTest {
    private static final long NOW = 200_000;
    private static final long UTC = Instant.parse("2026-11-10T10:12:00Z").toEpochMilli();

    private static WeatherLocationSources.Fix fix(String channel, boolean mock, float accuracy, long age, long utc) {
        return new WeatherLocationSources.Fix(channel, "gps", mock, true, 50, 30, accuracy, NOW - age, utc);
    }

    @Test public void switchesImmediatelyInBothDirectionsWithoutWaitingForMockExpiry() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("gps", true, Float.NaN, 0, UTC), NOW);
        WeatherLocationSources.Selection mock = sources.select(NOW, UTC);
        assertEquals("mock", mock.source);
        sources.accept(fix("flp", false, 5, 1000, UTC - 1000), NOW);
        WeatherLocationSources.Selection gps = sources.select(NOW, UTC);
        assertEquals("gps", gps.source);
        assertTrue(gps.revision > mock.revision);
        sources.accept(fix("flp", false, 500, 0, UTC), NOW);
        assertEquals("mock", sources.select(NOW, UTC).source);
        sources.accept(fix("flp", false, 3, -1, UTC + 1), NOW + 1);
        assertEquals("gps", sources.select(NOW + 1, UTC + 1).source);
    }

    @Test public void qualityBoundariesAreInclusiveAndUnknownTimeDoesNotVetoGps() {
        WeatherLocationSources sources = new WeatherLocationSources();
        assertNull(sources.gpsRejection(fix("gps", false, 300, 10_000, UTC - 10_000), NOW, UTC));
        assertEquals("gps_age_or_coordinates", sources.gpsRejection(fix("gps", false, 1, 10_001, UTC), NOW, UTC));
        assertEquals("gps_accuracy", sources.gpsRejection(fix("gps", false, 300.01f, 0, UTC), NOW, UTC));
        assertEquals("gps_accuracy", sources.gpsRejection(fix("gps", false, Float.NaN, 0, UTC), NOW, UTC));
        assertEquals("gps_accuracy", sources.gpsRejection(fix("gps", false, 0, 0, UTC), NOW, UTC));
        assertNull(sources.gpsRejection(fix("gps", false, 5, 1000, UTC + 59_000), NOW, UTC));
        assertEquals("gps_utc", sources.gpsRejection(fix("gps", false, 5, 1000, UTC + 59_001), NOW, UTC));
        assertNull(sources.gpsRejection(fix("gps", false, 5, 0, UTC + 60_001), NOW, null));
    }

    @Test public void mockExpiryTriggersFallbackButKeepsExisting120SecondCacheLimit() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("gps", true, Float.NaN, 0, UTC), NOW);
        sources.accept(fix("flp", false, Float.NaN, 0, UTC), NOW);
        assertEquals("mock", sources.select(NOW + 30_000, null).source);
        assertEquals(NOW + 30_001, sources.select(NOW + 30_000, null).nextCheckMs);
        assertEquals("gps", sources.select(NOW + 30_001, null).source);
        assertEquals("gps", sources.select(NOW + 120_000, null).source);
        assertEquals("none", sources.select(NOW + 120_001, null).source);
    }

    @Test public void restoredProviderRemovesMockImmediatelyEvenForPoorGps() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("gps", true, 1, 0, UTC), NOW);
        assertEquals("mock", sources.select(NOW, UTC).source);
        sources.accept(fix("gps", false, Float.NaN, -1, UTC + 1), NOW + 1);
        assertEquals("gps", sources.select(NOW + 1, UTC + 1).source);
    }

    @Test public void mockDoesNotEraseFreshRealGpsAndLateCachedMockCannotUndoRestoration() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("gps", false, 5, 1000, UTC - 1000), NOW);
        sources.accept(fix("gps", true, 5, 0, UTC), NOW);
        assertEquals(2, sources.fixes().length);
        assertEquals("gps", sources.select(NOW, UTC).source);
        assertEquals("mock", sources.select(NOW + 9001, UTC + 9001).source);
        sources.accept(fix("gps", false, Float.NaN, -9002, UTC + 9002), NOW + 9002);
        assertEquals("gps", sources.select(NOW + 9002, UTC + 9002).source);
        sources.accept(fix("gps", true, 5, 0, UTC), NOW + 9003);
        assertEquals(1, sources.fixes().length);
        assertEquals("gps", sources.select(NOW + 9003, UTC + 9003).source);
    }

    @Test public void noFlpStillUsesMockThenActualStandardGps() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("gps", true, Float.NaN, 0, UTC), NOW);
        assertEquals("mock", sources.select(NOW, UTC).source);
        assertEquals("none", sources.select(NOW + 30_001, UTC + 30_001).source);
        sources.accept(fix("gps", false, 6, -31_000, UTC + 31_000), NOW + 31_000);
        assertEquals("gps", sources.select(NOW + 31_000, UTC + 31_000).source);
    }

    @Test public void duplicateMockDoesNotRenewAgeButStationaryNewMeasurementDoes() {
        WeatherLocationSources sources = new WeatherLocationSources();
        WeatherLocationSources.Fix mock = fix("gps", true, 1, 0, UTC);
        sources.accept(mock, NOW);
        sources.accept(mock, NOW + 30_001);
        assertNull(sources.select(NOW + 30_001, UTC + 30_001).fix);
        sources.accept(fix("gps", true, 1, -30_002, UTC + 30_002), NOW + 30_002);
        assertEquals("mock", sources.select(NOW + 30_002, UTC + 30_002).source);
    }

    @Test public void flpDeliveryTimestampCannotRenewSameEpoch() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("flp", false, 1, 0, UTC), NOW);
        sources.accept(fix("gps", true, 1, -10_001, UTC + 10_001), NOW + 10_001);
        sources.accept(fix("flp", false, 1, -10_001, UTC), NOW + 10_001);
        assertEquals("mock", sources.select(NOW + 10_001, null).source);
        sources.accept(fix("flp", false, 1, -11_000, UTC + 11_000), NOW + 11_000);
        assertEquals("gps", sources.select(NOW + 11_000, null).source);
        sources.accept(fix("flp", false, 1, -12_000, UTC), NOW + 12_000);
        assertEquals(UTC + 11_000, sources.select(NOW + 12_000, null).fix.utcMs);
        sources.accept(fix("flp", false, 1, -140_000, UTC + 11_000), NOW + 140_000);
        assertEquals("none", sources.select(NOW + 140_000, null).source);
    }

    @Test public void sourceRevisionOnlyChangesOnSourceTransitionsAndClearDropsHistory() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("gps", true, 1, 0, UTC), NOW);
        long mockRevision = sources.select(NOW, UTC).revision;
        sources.accept(fix("gps", true, 1, -1, UTC + 1), NOW + 1);
        assertEquals(mockRevision, sources.select(NOW + 1, UTC + 1).revision);
        sources.accept(fix("flp", false, 1, -1, UTC + 1), NOW + 1);
        assertTrue(sources.select(NOW + 1, UTC + 1).revision > mockRevision);
        sources.clear();
        assertEquals("none", sources.select(NOW + 1, UTC + 1).source);
        assertEquals(0, sources.fixes().length);
    }

    @Test public void coordinateAndTimestampSanityApplyEvenWithoutMock() {
        WeatherLocationSources sources = new WeatherLocationSources();
        for (double latitude : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 90.1}) {
            sources.accept(new WeatherLocationSources.Fix("gps", "gps", false, true,
                    latitude, 30, 1, NOW, UTC), NOW);
            assertEquals("none", sources.select(NOW, UTC).source);
        }
        sources.accept(fix("gps", false, 1, -1, UTC), NOW);
        assertEquals("none", sources.select(NOW, UTC).source);
    }

    @Test public void networkRemainsDistinctAndDoesNotBeatGpsOrMock() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(new WeatherLocationSources.Fix("network", "network", false, false,
                50, 30, 1000, NOW, UTC), NOW);
        assertEquals("network", sources.select(NOW, UTC).source);
        sources.accept(fix("gps", true, 1, 1000, UTC - 1000), NOW);
        assertEquals("mock", sources.select(NOW, UTC).source);
        sources.accept(fix("flp", false, 1, 1000, UTC - 1000), NOW);
        assertEquals("gps", sources.select(NOW, UTC).source);
    }

    static String nmea(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++) checksum ^= body.charAt(i);
        return "$" + body + "*" + String.format(java.util.Locale.US, "%02X", checksum);
    }

    @Test public void optionalNmeaRejectsOnlyMatchingEpochAndNeverWaitsForIt() {
        WeatherLocationSources sources = new WeatherLocationSources();
        sources.accept(fix("gps", true, 1, 0, UTC), NOW);
        sources.accept(fix("flp", false, 1, 0, UTC), NOW);
        assertEquals("gps", sources.select(NOW, UTC).source);
        sources.acceptNmea(WeatherNmeaFix.parse(nmea("GPRMC,101200.00,V,,,,,,,101126,,,N,V"), NOW));
        assertEquals("mock", sources.select(NOW, UTC).source);
        assertEquals("mock", sources.select(NOW + 3000, UTC + 3000).source);
        assertEquals("gps", sources.select(NOW + 3001, UTC + 3001).source);
        sources.acceptNmea(WeatherNmeaFix.parse(nmea("GPRMC,101201.00,V,,,,,,,101126,,,N,V"), NOW + 3001));
        assertEquals("gps", sources.select(NOW + 3001, UTC + 3001).source);
        sources.acceptNmea(WeatherNmeaFix.parse(nmea("GPRMC,101200.00,A,,,,,,,101126,,,A,V"), NOW + 3001));
        assertEquals("gps", sources.select(NOW + 3001, UTC + 3001).source);
        sources.acceptNmea(WeatherNmeaFix.parse(nmea("GPGGA,101200.00,,,,,0,00,99.9,,,,,,"), NOW + 3001));
        assertEquals("mock", sources.select(NOW + 3001, UTC + 3001).source);
    }

    @Test public void corruptNmeaAndDifferentDateDoNotRejectPosition() {
        assertNull(WeatherNmeaFix.parse("$GPRMC,101200.00,V,,,,,,,101126*00", NOW));
        assertNull(WeatherNmeaFix.parse(nmea("GPRMC,256100.00,V,,,,,,,101126,,,N,V"), NOW));
        WeatherNmeaFix otherDay = WeatherNmeaFix.parse(nmea("GPRMC,101200.00,V,,,,,,,091126,,,N,V"), NOW);
        assertNotNull(otherDay);
        assertFalse(otherDay.matches(UTC, NOW));
    }
}
