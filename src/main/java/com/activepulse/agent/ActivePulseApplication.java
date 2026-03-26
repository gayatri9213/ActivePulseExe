package com.activepulse.agent;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.monitor.AppActivityRecorder;
import com.activepulse.agent.monitor.InputActivityMonitor;
import com.activepulse.agent.monitor.InputActivityRecorder;
import com.activepulse.agent.monitor.ScreenshotRecorder;
import com.activepulse.agent.monitor.SystemLockDetector;
import com.activepulse.agent.monitor.SystemMetricsRecorder;
import com.activepulse.agent.monitor.UserStatusTracker;
import com.activepulse.agent.scheduler.AgentScheduler;
import com.activepulse.agent.sync.SyncConfig;
import com.activepulse.agent.sync.SyncManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ActivePulseApplication {

    private static final Logger log = LoggerFactory.getLogger(ActivePulseApplication.class);
    private volatile boolean running = true;

    // ─────────────────────────────────────────────────────────────────
    //  Entry point
    //
    //  Supported CLI flags:
    //    --install    install auto-start entry for current OS and exit
    //    --uninstall  remove  auto-start entry for current OS and exit
    //    (no args)    run the agent normally
    // ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // ── MUST be the absolute first lines before any class loads ───
        System.setProperty("java.awt.headless", "false");
        // Boot AWT toolkit immediately on main thread
        Toolkit.getDefaultToolkit();

        ensureDirectories();

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "--install" -> {
                    // DB must be ready before AutoStartManager seeds config
                    DatabaseManager.getInstance().init();
                    AutoStartManager.getInstance().install();
                    log.info("Auto-start installed. Run the agent normally to start monitoring.");
                    System.exit(0);
                }
                case "--uninstall" -> {
                    AutoStartManager.getInstance().uninstall();
                    log.info("Auto-start removed.");
                    System.exit(0);
                }
                default -> log.warn("Unknown argument '{}' — starting agent normally.", args[0]);
            }
        }

        new ActivePulseApplication().start();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Agent startup
    // ─────────────────────────────────────────────────────────────────

    private void start() {
        log.info("╔══════════════════════════════════════╗");
        log.info("║   ActivePulse Desktop Agent v1.0.0   ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("Agent starting at {}",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // ── 1. Database
        DatabaseManager.getInstance().init();
        writeAgentConfig();
        SyncConfig.getInstance().seed();

        // ── 2. Init sync directories + session report
        SyncManager.getInstance();
        AppConfigManager.getInstance().start();

        // ── 2. Init sync directories immediately on startup
        SyncManager.getInstance();

        // ── 2. Auto-start: install silently on first run if not yet registered
        SystemTrayManager.getInstance().install();
        installAutoStartIfNeeded();

        // ── 3. Monitors (continuous / event-driven)
        SystemLockDetector.getInstance().start();   // lock detection first
        AppActivityRecorder.getInstance().start();
        InputActivityMonitor.getInstance().start();
        InputActivityRecorder.getInstance().start();
        ScreenshotRecorder.getInstance().start();
        SystemMetricsRecorder.getInstance().start();

        // ── 4. Quartz — all periodic jobs
        AgentScheduler.getInstance().start();

        registerShutdownHook();
        log.info("Agent is running. Press Ctrl+C to stop.");
        keepAlive();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Auto-start: silent install on first run
    // ─────────────────────────────────────────────────────────────────

    private void installAutoStartIfNeeded() {
        // Always re-register on every startup so the path is always current.
        // Safe to call repeatedly — reg /f overwrites silently.
        AutoStartManager.getInstance().install();
        log.info("Auto-start registration complete.");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Write machine identity to agent_config
    // ─────────────────────────────────────────────────────────────────

    private void writeAgentConfig() {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO agent_config (key, value) VALUES (?, ?)")) {

                String[][] config = {
                        {"deviceId",     "DEV-" + getMachineId()},
                        {"osName",       System.getProperty("os.name")},
                        {"osVersion",    System.getProperty("os.version")},
                        {"osArch",       System.getProperty("os.arch")},
                        {"javaVersion",  System.getProperty("java.version")},
                        {"userName",     System.getProperty("user.name")},
                        {"agentVersion", "1.0.0"},
                        {"startedAt",    Instant.now().toString()},
                };
                for (String[] kv : config) {
                    ps.setString(1, kv[0]);
                    ps.setString(2, kv[1]);
                    ps.executeUpdate();
                }
                log.info("Agent config written — device: DEV-{}", getMachineId());
            }
        } catch (SQLException e) {
            log.error("Failed to write agent config", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Graceful shutdown — reverse startup order
    // ─────────────────────────────────────────────────────────────────

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping agent...");
            running = false;
            AgentScheduler.getInstance().stop();
            AppActivityRecorder.getInstance().stop();
            InputActivityRecorder.getInstance().stop();
            InputActivityMonitor.getInstance().stop();
            ScreenshotRecorder.getInstance().stop();
            SystemMetricsRecorder.getInstance().stop();
            SystemLockDetector.getInstance().stop();
            AppConfigManager.getInstance().stop();
            UserStatusTracker.getInstance().setStopped();
            SystemTrayManager.getInstance().remove();
            DatabaseManager.getInstance().close();
            log.info("ActivePulse Agent stopped cleanly.");
        }, "shutdown-hook"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────────────────────────

    private void keepAlive() {
        while (running) {
            try { Thread.sleep(1_000); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    /**
     * Derives a stable device ID from the MAC address of the
     * primary non-loopback network interface.
     * Format: DEV-AABBCCDDEEFF
     * Falls back to a hostname hash if MAC is unavailable.
     */
    private String getMachineId() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) continue;
                byte[] mac = nic.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : mac) sb.append(String.format("%02X", b));
                return sb.toString();   // e.g. "A4C3F0112233"
            }
        } catch (Exception ignored) {}
        // Fallback: hash of username + hostname
        try {
            String raw = System.getProperty("user.name")
                    + java.net.InetAddress.getLocalHost().getHostName();
            return String.valueOf(Math.abs(raw.hashCode()));
        } catch (Exception e) { return "000000"; }
    }

    private static void ensureDirectories() {
        Path logDir = Paths.get(System.getProperty("user.home"), ".activepulse", "logs");
        if (!Files.exists(logDir)) {
            try {
                Files.createDirectories(logDir);
                System.out.println("[ActivePulse] Created log directory: " + logDir);
            } catch (IOException e) {
                System.err.println("[ActivePulse] WARNING: " + e.getMessage());
            }
        }
    }
}