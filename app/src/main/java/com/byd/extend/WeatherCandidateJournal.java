package com.byd.extend;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

final class WeatherCandidateJournal {
    private record Candidate(String channel, String provider, boolean mock, double latitude, double longitude,
            float accuracy, long elapsedMs, long utcMs, boolean networkTimeKnown, String rejection, boolean selected) {}

    private Set<Candidate> previous = Collections.emptySet();
    private long loggingRevision;

    void clear() { previous = Collections.emptySet(); }

    void write(WeatherLocationSources.Fix[] candidates, WeatherLocationSources sources,
            WeatherLocationSources.Fix selected, long now, Long utcNow, long revision, BiConsumer<String, Object[]> events) {
        if (loggingRevision != revision) {
            clear();
            loggingRevision = revision;
        }
        Set<Candidate> current = new HashSet<>();
        for (WeatherLocationSources.Fix fix : candidates) {
            String rejection = fix.gps && !fix.mock ? sources.gpsRejection(fix, now, utcNow) : null;
            Candidate sample = new Candidate(fix.channel, fix.provider, fix.mock, fix.latitude, fix.longitude,
                    fix.accuracy, fix.elapsedMs, fix.utcMs, utcNow != null, rejection, selected == fix);
            current.add(sample);
            if (previous.contains(sample)) continue;
            events.accept("weather_location_candidate", new Object[]{"channel", fix.channel, "provider", fix.provider,
                    "mock", fix.mock, "latitude", fix.latitude, "longitude", fix.longitude,
                    "accuracy_m", fix.accuracy, "elapsed_ms", fix.elapsedMs, "utc_ms", fix.utcMs,
                    "age_ms", now - fix.elapsedMs, "network_time_known", utcNow != null,
                    "gps_rejection", rejection, "selected", selected == fix});
        }
        previous = current;
    }
}
