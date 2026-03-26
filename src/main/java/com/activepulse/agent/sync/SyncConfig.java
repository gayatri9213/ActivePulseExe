package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SyncConfig — reads server connection settings from agent_config table.
 *
 * Keys expected in agent_config:
 *   serverUrl    — full endpoint URL  e.g. https://api.activepulse.com/v1/sync
 *   apiKey       — Bearer token for Authorization header
 *   userId       — assigned user ID
 *   orgId        — organisation ID
 *
 * Call SyncConfig.seed() once at startup to insert defaults if missing.
 * Operator updates these rows directly in the DB or via an installer wizard.
 */
public class SyncConfig {

    private static final Logger log = LoggerFactory.getLogger(SyncConfig.class);

    // ── Defaults (used when keys are absent from DB) ─────────────────
    public static final String DEFAULT_SERVER_URL = "https://api.activepulse.com/v1/sync";
    public static final int    CONNECT_TIMEOUT_SEC = 10;
    public static final int    REQUEST_TIMEOUT_SEC = 30;
    public static final int    MAX_RETRIES         = 3;
    public static final long   RETRY_BASE_DELAY_MS = 2_000; // doubles each attempt

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SyncConfig instance;
    private SyncConfig() {}

    public static SyncConfig getInstance() {
        if (instance == null) {
            synchronized (SyncConfig.class) {
                if (instance == null) instance = new SyncConfig();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public getters — always read live from DB so changes take effect
    //  without restart
    // ─────────────────────────────────────────────────────────────────

    public String getServerUrl() {
        String url = read("serverUrl");
        return (url == null || url.isBlank()) ? DEFAULT_SERVER_URL : url;
    }

    public String getApiKey() {
        return read("apiKey");   // null = no auth header sent
    }

    public String getUserId() {
        return read("userId");
    }

    public String getOrgId() {
        return read("orgId");
    }

    /** Returns true only when a real server URL and API key are configured. */
     public boolean isConfigured() {
        String url    = getServerUrl();
        String apiKey = getApiKey();
        return url != null && !url.isBlank()
                && !url.equals(DEFAULT_SERVER_URL)   // ← still default = NOT configured
                && apiKey != null && !apiKey.isBlank()
                && !apiKey.equals("YOUR_API_KEY_HERE"); // ← placeholder = NOT configured
    }

    // ─────────────────────────────────────────────────────────────────
    //  Seed — insert placeholder values at first startup so the rows
    //  exist and the operator knows what to fill in
    // ─────────────────────────────────────────────────────────────────

    public void seed() {
        upsertIfAbsent("serverUrl", DEFAULT_SERVER_URL);
        upsertIfAbsent("apiKey",    "YOUR_API_KEY_HERE");
        upsertIfAbsent("userId",    "USR-UNKNOWN");
        upsertIfAbsent("orgId",     "ORG-UNKNOWN");
        log.info("SyncConfig seeded. Update serverUrl + apiKey in agent_config to enable HTTP sync.");
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB helpers
    // ─────────────────────────────────────────────────────────────────

    private String read(String key) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM agent_config WHERE key = ?")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            log.debug("SyncConfig read '{}' failed: {}", key, e.getMessage());
        }
        return null;
    }

    private void upsertIfAbsent(String key, String defaultValue) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO agent_config (key, value) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, defaultValue);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("SyncConfig seed '{}' failed: {}", key, e.getMessage());
        }
    }
}
