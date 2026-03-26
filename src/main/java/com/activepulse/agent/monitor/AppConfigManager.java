package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AppConfigManager — manages the app_config table.
 * One row per agent session (login → logout).
 *
 * Tracks:
 *   logintime / logouttime
 *   totaltime / activetime / idletime / awaytime
 *   totalkeyboardcount / totalkeymousecount
 */
public class AppConfigManager {

    private static final Logger log = LoggerFactory.getLogger(AppConfigManager.class);

    private final AtomicInteger activeTime  = new AtomicInteger(0);
    private final AtomicInteger idleTime    = new AtomicInteger(0);
    private final AtomicInteger awayTime    = new AtomicInteger(0);
    private final AtomicInteger keyCount    = new AtomicInteger(0);
    private final AtomicInteger mouseCount  = new AtomicInteger(0);
    private final AtomicLong    sessionId   = new AtomicLong(-1);

    private String username;
    private String deviceid;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile AppConfigManager instance;
    private AppConfigManager() {}

    public static AppConfigManager getInstance() {
        if (instance == null) {
            synchronized (AppConfigManager.class) {
                if (instance == null) instance = new AppConfigManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        username = readConfig("userName",  System.getProperty("user.name"));
        deviceid = readConfig("deviceId",  "DEV-UNKNOWN");

        String logintime = TimeUtil.nowIST();
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app_config (username, deviceid, logintime, status)
                    VALUES (?, ?, ?, 'running')
                    """, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                ps.setString(3, logintime);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) sessionId.set(keys.getLong(1));
            }
            log.info("AppConfig session started — id={} user={} device={} logintime={}",
                    sessionId.get(), username, deviceid, logintime);
        } catch (SQLException e) {
            log.error("Failed to start app_config session: {}", e.getMessage());
        }
    }

    /**
     * Called every 60s by InputActivityRecorder after flush.
     * Accumulates time + input counts and updates the live DB row.
     */
    public void accumulate(int activeSecs, int idleSecs, int awaySecs,
                           int keys, int mouse) {
        activeTime.addAndGet(activeSecs);
        idleTime.addAndGet(idleSecs);
        awayTime.addAndGet(awaySecs);
        keyCount.addAndGet(keys);
        mouseCount.addAndGet(mouse);
        updateRow(null);
    }

    public void stop() {
        String logouttime = TimeUtil.nowIST();
        updateRow(logouttime);
        printSummary();
        log.info("AppConfig session ended — id={}", sessionId.get());
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB update
    // ─────────────────────────────────────────────────────────────────

    private void updateRow(String logouttime) {
        long id = sessionId.get();
        if (id < 0) return;

        int active = activeTime.get();
        int idle   = idleTime.get();
        int away   = awayTime.get();
        int total  = active + idle + away;

        // Raw seconds + formatted strings
        String totalFmt  = TimeUtil.formatDuration(total);
        String activeFmt = TimeUtil.formatDuration(active);
        String idleFmt   = TimeUtil.formatDuration(idle);
        String awayFmt   = TimeUtil.formatDuration(away);

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE app_config SET
                        logouttime         = COALESCE(?, logouttime),
                        totaltime          = ?,
                        activetime         = ?,
                        idletime           = ?,
                        awaytime           = ?,
                        totalkeyboardcount = ?,
                        totalkeymousecount = ?,
                        totaltime_fmt      = ?,
                        activetime_fmt     = ?,
                        idletime_fmt       = ?,
                        awaytime_fmt       = ?,
                        status             = ?
                    WHERE id = ?
                    """)) {
                ps.setString(1,  logouttime);
                ps.setInt(2,     total);
                ps.setInt(3,     active);
                ps.setInt(4,     idle);
                ps.setInt(5,     away);
                ps.setInt(6,     keyCount.get());
                ps.setInt(7,     mouseCount.get());
                ps.setString(8,  totalFmt);
                ps.setString(9,  activeFmt);
                ps.setString(10, idleFmt);
                ps.setString(11, awayFmt);
                ps.setString(12, logouttime != null ? "stopped" : "running");
                ps.setLong(13,   id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to update app_config: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Summary
    // ─────────────────────────────────────────────────────────────────

    private void printSummary() {
        int total = activeTime.get() + idleTime.get() + awayTime.get();
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("  Session Summary — {}", username);
        log.info("  ┌──────────────┬──────────┬────────┬────────┐");
        log.info("  │              │  Seconds │    Min │    Hrs │");
        log.info("  ├──────────────┼──────────┼────────┼────────┤");
        log.info("  │ Total time   │ {:>8} │ {:>6} │ {:>6} │",
                total,
                total / 60,
                String.format("%.2f", total / 3600.0));
        log.info("  │ Active time  │ {:>8} │ {:>6} │ {:>6} │",
                activeTime.get(),
                activeTime.get() / 60,
                String.format("%.2f", activeTime.get() / 3600.0));
        log.info("  │ Idle time    │ {:>8} │ {:>6} │ {:>6} │",
                idleTime.get(),
                idleTime.get() / 60,
                String.format("%.2f", idleTime.get() / 3600.0));
        log.info("  │ Away time    │ {:>8} │ {:>6} │ {:>6} │",
                awayTime.get(),
                awayTime.get() / 60,
                String.format("%.2f", awayTime.get() / 3600.0));
        log.info("  └──────────────┴──────────┴────────┴────────┘");
        log.info("  Keystrokes : {}   Mouse clicks : {}",
                keyCount.get(), mouseCount.get());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Getters (for sync payload)
    // ─────────────────────────────────────────────────────────────────

    public String getUsername() { return username; }
    public String getDeviceid() { return deviceid; }

    // ─────────────────────────────────────────────────────────────────
    //  Helper
    // ─────────────────────────────────────────────────────────────────

    private String readConfig(String key, String fallback) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM agent_config WHERE key = ?")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException ignored) {}
        return fallback;
    }
}