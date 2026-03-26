package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.util.TimeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SyncManager — builds JSON payload from unsynced DB rows
 * and writes to ~/.activepulse/sync/sent/ every 10 minutes.
 *
 * After successful sync:
 *   - Marks all included rows as synced (synced = 1)
 *   - Deletes synced screenshot FILES from disk
 *   - Keeps screenshot DB rows (for audit trail)
 */
public class SyncManager {

    private static final Logger log = LoggerFactory.getLogger(SyncManager.class);

    private static final Path SYNC_DIR = Paths.get(
            System.getProperty("user.home"), ".activepulse", "sync");
    private static final Path SENT_DIR        = SYNC_DIR.resolve("sent");
    private static final Path SYNCED_SS_DIR   = SYNC_DIR.resolve("screenshots"); // moved ss go here
    private static final Path SCREENSHOT_DIR  = Paths.get(
            System.getProperty("user.home"), ".activepulse", "screenshots");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SyncManager instance;
    private SyncManager() { ensureDirs(); }

    public static SyncManager getInstance() {
        if (instance == null) {
            synchronized (SyncManager.class) {
                if (instance == null) instance = new SyncManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Main sync — called by SyncJob every 10 minutes
    // ─────────────────────────────────────────────────────────────────

    public void sync() {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("  Sync cycle started (every 10 min)");

        Connection conn = DatabaseManager.getInstance().getConnection();
        String syncId   = "SYNC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String syncStart = TimeUtil.nowIST();

        // ── Collect unsynced data ──────────────────────────────────────
        List<Long>   activityIds    = new ArrayList<>();
        List<Long>   strokeIds      = new ArrayList<>();
        List<Long>   screenshotIds  = new ArrayList<>();
        List<String> screenshotFiles = new ArrayList<>();

        var payload = buildPayload(conn, syncId, syncStart,
                activityIds, strokeIds, screenshotIds, screenshotFiles);

        if (payload == null) {
            log.info("  Nothing to sync.");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            return;
        }

        // ── Write to sent/ folder ─────────────────────────────────────
        String ts      = TimeUtil.nowIST().replace(" ", "_").replace(":", "-");
        String fileName = syncId + "_" + ts + ".json";
        Path   outFile  = SENT_DIR.resolve(fileName);

        try {
            String json = mapper.writeValueAsString(payload);
            Files.writeString(outFile, json);
            log.info("  Payload written → {}", fileName);
        } catch (Exception e) {
            log.error("Failed to write payload: {}", e.getMessage());
            return;
        }

        // ── Mark rows as synced ───────────────────────────────────────
        markSynced(conn, "activity_log",           activityIds);
        markSynced(conn, "keyboard_mouse_strokes",  strokeIds);
        markSynced(conn, "screenshots",             screenshotIds);

        // ── Delete synced screenshot files from disk ──────────────────
        deleteSyncedScreenshots(screenshotFiles);

        // ── Record sync attempt ───────────────────────────────────────
        recordSyncLog(conn, syncId, syncStart, TimeUtil.nowIST());

        log.info("  Sync complete — syncId={}", syncId);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Build payload from DB
    // ─────────────────────────────────────────────────────────────────

    private Object buildPayload(Connection conn, String syncId, String syncStart,
                                List<Long> activityIds, List<Long> strokeIds,
                                List<Long> screenshotIds, List<String> screenshotFiles) {
        try {
            var activityLogs = readActivityLog(conn, activityIds);
            var strokes      = readStrokes(conn, strokeIds);
            var screenshots  = readScreenshots(conn, screenshotIds, screenshotFiles);

            boolean hasData = !activityLogs.isEmpty()
                    || !strokes.isEmpty()
                    || !screenshots.isEmpty();

            if (!hasData) return null;

            // Build as simple Map for JSON serialization
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("syncId",         syncId);
            map.put("syncStartTime",  syncStart);
            map.put("syncEndTime",    TimeUtil.nowIST());
            map.put("deviceId",       readConfig(conn, "deviceId",  "DEV-UNKNOWN"));
            map.put("userId",         readConfig(conn, "userId",    "USR-UNKNOWN"));
            map.put("orgId",          readConfig(conn, "orgId",     "ORG-UNKNOWN"));
            map.put("agentVersion",   readConfig(conn, "agentVersion", "1.0.0"));
            map.put("osType",         normalizeOs(readConfig(conn, "osName", "")));
            map.put("activityLog",    activityLogs);
            map.put("keyboardMouseStrokes", strokes);
            map.put("screenshots",    screenshots);

            log.info("  ┌─────────────────────────────");
            log.info("  │ syncId       : {}", syncId);
            log.info("  │ activityLog  : {} rows", activityLogs.size());
            log.info("  │ strokes      : {} rows", strokes.size());
            log.info("  │ screenshots  : {} rows", screenshots.size());
            log.info("  └─────────────────────────────");

            return map;
        } catch (Exception e) {
            log.error("buildPayload failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB readers
    // ─────────────────────────────────────────────────────────────────

    private List<java.util.Map<String, Object>> readActivityLog(
            Connection conn, List<Long> ids) throws SQLException {

        var list = new ArrayList<java.util.Map<String, Object>>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT id, username, deviceid, starttime, endtime,
                        processname, title, url, duration
                 FROM activity_log WHERE synced = 0 ORDER BY starttime
                 """)) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
                var row = new java.util.LinkedHashMap<String, Object>();
                row.put("username",    rs.getString("username"));
                row.put("deviceid",    rs.getString("deviceid"));
                row.put("starttime",   rs.getString("starttime"));
                row.put("endtime",     rs.getString("endtime"));
                row.put("processname", rs.getString("processname"));
                row.put("title",       rs.getString("title"));
                row.put("url",         rs.getString("url"));   // null for apps
                row.put("duration",    rs.getLong("duration"));
                list.add(row);
            }
        }
        return list;
    }

    private List<java.util.Map<String, Object>> readStrokes(
            Connection conn, List<Long> ids) throws SQLException {

        var list = new ArrayList<java.util.Map<String, Object>>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT id, username, deviceid, recorded_at,
                        keyboardcount, keymousecount
                 FROM keyboard_mouse_strokes WHERE synced = 0
                 ORDER BY recorded_at
                 """)) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
                var row = new java.util.LinkedHashMap<String, Object>();
                row.put("username",      rs.getString("username"));
                row.put("deviceid",      rs.getString("deviceid"));
                row.put("recordedAt",    rs.getString("recorded_at"));
                row.put("keyboardcount", rs.getInt("keyboardcount"));
                row.put("keymousecount", rs.getInt("keymousecount"));
                list.add(row);
            }
        }
        return list;
    }

    private List<java.util.Map<String, Object>> readScreenshots(
            Connection conn, List<Long> ids, List<String> filePaths) throws SQLException {

        var list = new ArrayList<java.util.Map<String, Object>>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT id, file_name, file_path, file_size_bytes, captured_at
                 FROM screenshots WHERE synced = 0
                 ORDER BY captured_at
                 """)) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
                filePaths.add(rs.getString("file_path"));
                var row = new java.util.LinkedHashMap<String, Object>();
                row.put("fileName",      rs.getString("file_name"));
                row.put("fileSizeBytes", rs.getLong("file_size_bytes"));
                row.put("capturedAt",    rs.getString("captured_at"));
                list.add(row);
            }
        }
        return list;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Mark synced
    // ─────────────────────────────────────────────────────────────────

    private void markSynced(Connection conn, String table, List<Long> ids) {
        if (ids.isEmpty()) return;
        String placeholders = "?,".repeat(ids.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        String sql = "UPDATE " + table + " SET synced = 1 WHERE id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            int updated = ps.executeUpdate();
            log.debug("Marked {} rows synced in {}", updated, table);
        } catch (SQLException e) {
            log.error("markSynced failed for {}: {}", table, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Delete synced screenshot files from disk
    // ─────────────────────────────────────────────────────────────────

    private void deleteSyncedScreenshots(List<String> filePaths) {
        if (filePaths.isEmpty()) return;
        int moved  = 0;
        int failed = 0;
        for (String filePath : filePaths) {
            try {
                Path src = Paths.get(filePath);
                if (!Files.exists(src)) continue;

                // Move to sync/screenshots/ — keeps file name unchanged
                Path dest = SYNCED_SS_DIR.resolve(src.getFileName());

                // If a file with same name already exists, suffix with _1, _2 etc.
                int suffix = 1;
                while (Files.exists(dest)) {
                    String name = src.getFileName().toString();
                    int dot     = name.lastIndexOf('.');
                    dest = SYNCED_SS_DIR.resolve(
                            (dot > 0 ? name.substring(0, dot) : name)
                                    + "_" + suffix++
                                    + (dot > 0 ? name.substring(dot) : ""));
                }

                Files.move(src, dest);
                moved++;
                log.debug("Screenshot moved → sync/screenshots/{}", dest.getFileName());

            } catch (IOException e) {
                failed++;
                log.warn("Could not move screenshot {}: {}", filePath, e.getMessage());
            }
        }
        log.info("Screenshots: {} moved to sync/screenshots/, {} failed.", moved, failed);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Sync log
    // ─────────────────────────────────────────────────────────────────

    private void recordSyncLog(Connection conn, String syncId,
                               String syncStart, String syncEnd) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO sync_log
                    (sync_id, sync_start, sync_end, status, response_code)
                VALUES (?, ?, ?, 'local', 0)
                """)) {
            ps.setString(1, syncId);
            ps.setString(2, syncStart);
            ps.setString(3, syncEnd);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("recordSyncLog failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private String readConfig(Connection conn, String key, String fallback) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM agent_config WHERE key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException ignored) {}
        return fallback;
    }

    private String normalizeOs(String osName) {
        if (osName == null) return "Unknown";
        String l = osName.toLowerCase();
        if (l.contains("win"))   return "Windows";
        if (l.contains("mac"))   return "macOS";
        if (l.contains("linux")) return "Linux";
        return osName;
    }

    private void ensureDirs() {
        try {
            Files.createDirectories(SENT_DIR);
            Files.createDirectories(SYNCED_SS_DIR);
            log.info("Sync directories ready:");
            log.info("  sent/        → {}", SENT_DIR.toAbsolutePath());
            log.info("  screenshots/ → {}", SYNCED_SS_DIR.toAbsolutePath());
        } catch (IOException e) {
            log.error("Cannot create sync dirs: {}", e.getMessage());
        }
    }
}