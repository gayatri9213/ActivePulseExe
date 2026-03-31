package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.BrowserUrlTracker.UrlResult;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AppActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(AppActivityRecorder.class);

    private static final int POLL_SECONDS   = 2;
    private static final int MIN_APP_SECS   = 2;
    private static final int URL_FLUSH_SECS = 30;

    private final WindowTracker     windowTracker = new WindowTracker();
    private final BrowserUrlTracker urlTracker    = BrowserUrlTracker.getInstance();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "app-tracker");
                t.setDaemon(true);
                return t;
            });

    // ── App state ─────────────────────────────────────────────────────
    private WindowInfo currentWindow    = WindowInfo.empty();
    private Instant    windowStart      = Instant.now();
    private String     windowStatusType = "ACTIVE";

    // ── URL state ─────────────────────────────────────────────────────
    private String  currentUrl      = null;
    private String  currentDomain   = null;
    private Instant urlStart        = null;
    private String  urlStatusType   = "ACTIVE";

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile AppActivityRecorder instance;
    private AppActivityRecorder() {}

    public static AppActivityRecorder getInstance() {
        if (instance == null) {
            synchronized (AppActivityRecorder.class) {
                if (instance == null) instance = new AppActivityRecorder();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        log.info("AppActivityRecorder starting.");
        scheduler.scheduleAtFixedRate(
                this::pollWindow, 0, POLL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(
                this::flushUrl, URL_FLUSH_SECS, URL_FLUSH_SECS, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
        Instant now = Instant.now();
        if (!currentWindow.isEmpty())
            saveActivity(currentWindow, null, windowStart, now, windowStatusType);
        synchronized (this) {
            if (currentUrl != null && urlStart != null)
                saveActivity(currentWindow, currentUrl, urlStart, now, urlStatusType);
        }
        log.info("AppActivityRecorder stopped.");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Window poll — every 2 seconds
    // ─────────────────────────────────────────────────────────────────

    private void pollWindow() {
        if (SystemLockDetector.getInstance().isLocked()) return;
        try {
            WindowInfo active = windowTracker.getActiveWindow();
            if (active.isEmpty()) return;
            Instant now = Instant.now();

            if (!active.isSameWindow(currentWindow)) {
                if (!currentWindow.isEmpty())
                    saveActivity(currentWindow, null, windowStart, now, windowStatusType);

                if (!active.isSameApp(currentWindow)) {
                    synchronized (this) {
                        if (currentUrl != null && urlStart != null)
                            saveActivity(currentWindow, currentUrl, urlStart, now, urlStatusType);
                        currentUrl = null; currentDomain = null; urlStart = null;
                    }
                }
                log.info("Window → app='{}' title='{}'",
                        active.appName(), truncate(active.windowTitle(), 70));
                currentWindow    = active;
                windowStart      = now;
                windowStatusType = resolveActivityType(); // capture status at START
            }
            pollUrl(active, now);
        } catch (Exception e) {
            log.warn("pollWindow error: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  URL poll — called every 2s while a browser is active
    // ─────────────────────────────────────────────────────────────────

    private synchronized void pollUrl(WindowInfo active, Instant now) {
        UrlResult result = urlTracker.getActiveUrl(active.appName());
        log.debug("URL poll — app='{}' result={}",
                active.appName(), result != null ? result.domain() : "null");

        if (result == null) {
            UserStatusTracker.getInstance().setWatchingVideo(false);
            return;
        }

        boolean isVideo = VideoSiteDetector.getInstance().isVideoSite(result.domain());
        UserStatusTracker.getInstance().setWatchingVideo(isVideo);

        String newUrl = result.url();

        if (currentUrl == null) {
            log.info("URL started → {} | {}", result.domain(), truncate(newUrl, 80));
            currentUrl      = newUrl;
            currentDomain   = result.domain();
            urlStart        = now;
            urlStatusType   = resolveActivityType(); // capture status at URL START
        } else if (!currentUrl.equals(newUrl)) {
            saveActivity(active, currentUrl, urlStart, now, urlStatusType);
            log.info("URL → {} | {}", result.domain(), truncate(newUrl, 80));
            currentUrl      = newUrl;
            currentDomain   = result.domain();
            urlStart        = now;
            urlStatusType   = resolveActivityType();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Periodic URL flush — every 30s
    // ─────────────────────────────────────────────────────────────────

    private synchronized void flushUrl() {
        if (currentUrl == null || urlStart == null) return;
        Instant now = Instant.now();
        log.info("URL flush → {} {}s", currentDomain,
                now.getEpochSecond() - urlStart.getEpochSecond());
        saveActivity(currentWindow, currentUrl, urlStart, now, urlStatusType);
        urlStart      = now;
        urlStatusType = resolveActivityType();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Resolve activityType from current tray status
    // ─────────────────────────────────────────────────────────────────

    private String resolveActivityType() {
        return switch (UserStatusTracker.getInstance().getStatus()) {
            case WORKING, NEUTRAL -> "ACTIVE";
            case IDLE             -> "IDLE";
            case AWAY, STOPPED    -> "AWAY";
        };
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB write — unified for apps and URLs
    // ─────────────────────────────────────────────────────────────────

    private void saveActivity(WindowInfo window, String url,
                              Instant start, Instant end,
                              String activityType) {
        long duration = end.getEpochSecond() - start.getEpochSecond();
        if (url == null && duration < MIN_APP_SECS) return;

        String username = AppConfigManager.getInstance().getUsername();
        String deviceid = AppConfigManager.getInstance().getDeviceid();

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO activity_log
                        (username, deviceid, starttime, endtime,
                         processname, title, url, duration, activity_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                ps.setString(3, TimeUtil.toIST(start));
                ps.setString(4, TimeUtil.toIST(end));
                ps.setString(5, window.appName());
                ps.setString(6, window.windowTitle());
                ps.setString(7, url);
                ps.setLong(8,   duration);
                ps.setString(9, activityType);
                ps.executeUpdate();

                if (url != null)
                    log.info("Saved URL  → {} {}s [{}]", currentDomain, duration, activityType);
                else
                    log.info("Saved app  → '{}' {}s [{}]", window.appName(), duration, activityType);
            }
        } catch (SQLException e) {
            log.error("saveActivity failed: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max) + "...";
    }
}