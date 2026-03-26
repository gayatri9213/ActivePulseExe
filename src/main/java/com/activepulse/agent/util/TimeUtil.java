package com.activepulse.agent.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * TimeUtil — all timestamps stored in IST (Asia/Kolkata, UTC+5:30).
 *
 * Use TimeUtil.nowIST() everywhere instead of Instant.now().toString()
 * so every DB row shows local Indian time.
 */
public class TimeUtil {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter DB_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(IST);

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss").withZone(IST);

    private TimeUtil() {}

    /** Current time as IST string — use for all DB writes. */
    public static String nowIST() {
        return DB_FORMAT.format(Instant.now());
    }

    /** Convert any Instant to IST string. */
    public static String toIST(Instant instant) {
        return DB_FORMAT.format(instant);
    }

    /** Human-readable IST string for logs. */
    public static String nowDisplay() {
        return DISPLAY_FORMAT.format(Instant.now());
    }

    /**
     * Formats raw seconds into "Xh Xm Xs" string.
     * e.g. 3725 → "1h 2m 5s"
     *      90   → "0h 1m 30s"
     */
    public static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format("%dh %dm %ds", h, m, s);
    }

    /**
     * Formats seconds into compact string for logs.
     * e.g. 3725 → "1h 2m"
     */
    public static String formatDurationShort(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return totalSeconds + "s";
    }
}
