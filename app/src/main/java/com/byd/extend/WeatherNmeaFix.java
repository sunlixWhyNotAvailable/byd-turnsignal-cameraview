package com.byd.extend;

import java.time.LocalDate;

/** Only fix status, never a replacement position provider. */
final class WeatherNmeaFix {
    final String type;
    final boolean valid;
    final long secondOfDay, epochDay, receivedMs;

    private WeatherNmeaFix(String type, boolean valid, long secondOfDay, long epochDay, long receivedMs) {
        this.type = type;
        this.valid = valid;
        this.secondOfDay = secondOfDay;
        this.epochDay = epochDay;
        this.receivedMs = receivedMs;
    }

    static WeatherNmeaFix parse(String sentence, long receivedMs) {
        if (sentence == null || !sentence.startsWith("$")) return null;
        int star = sentence.indexOf('*');
        if (star < 1 || star + 3 > sentence.length()) return null;
        try {
            int checksum = 0;
            for (int i = 1; i < star; i++) checksum ^= sentence.charAt(i);
            if (checksum != Integer.parseInt(sentence.substring(star + 1, star + 3), 16)) return null;
            String[] fields = sentence.substring(1, star).split(",", -1);
            if (fields[0].length() != 5 || fields.length < 7 || fields[1].length() < 6) return null;
            String type = fields[0].substring(2);
            int hour = Integer.parseInt(fields[1].substring(0, 2));
            int minute = Integer.parseInt(fields[1].substring(2, 4));
            double second = Double.parseDouble(fields[1].substring(4));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59
                    || !Double.isFinite(second) || second < 0 || second >= 60) return null;
            long day = -1;
            boolean valid;
            if ("RMC".equals(type)) {
                if (fields.length < 10 || fields[9].length() != 6
                        || !("A".equals(fields[2]) || "V".equals(fields[2]))) return null;
                day = LocalDate.of(2000 + Integer.parseInt(fields[9].substring(4)),
                        Integer.parseInt(fields[9].substring(2, 4)),
                        Integer.parseInt(fields[9].substring(0, 2))).toEpochDay();
                valid = "A".equals(fields[2]);
            } else if ("GGA".equals(type)) {
                int quality = Integer.parseInt(fields[6]);
                if (quality < 0 || quality > 8) return null;
                valid = quality != 0;
            } else return null;
            return new WeatherNmeaFix(type, valid, hour * 3600L + minute * 60L + (long) second, day, receivedMs);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    boolean matches(long fixUtcMs, long now) {
        if (fixUtcMs <= 0 || receivedMs > now || now - receivedMs > WeatherLocationSources.NMEA_AGE_MS) return false;
        return Math.floorMod(fixUtcMs / 1000, 86400) == secondOfDay
                && (epochDay < 0 || Math.floorDiv(fixUtcMs, 86_400_000) == epochDay);
    }
}
