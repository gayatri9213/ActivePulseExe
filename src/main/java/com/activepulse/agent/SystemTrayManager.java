package com.activepulse.agent;

import com.activepulse.agent.monitor.UserStatus;
import com.activepulse.agent.monitor.UserStatusTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SystemTrayManager {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayManager.class);

    private TrayIcon   trayIcon;
    private SystemTray tray;

    private static volatile SystemTrayManager instance;
    private SystemTrayManager() {}

    public static SystemTrayManager getInstance() {
        if (instance == null) {
            synchronized (SystemTrayManager.class) {
                if (instance == null) instance = new SystemTrayManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Install — called from main thread
    // ─────────────────────────────────────────────────────────────────

    public void install() {

        // ── Guard 1: headless ─────────────────────────────────────────
        String headless = System.getProperty("java.awt.headless", "false");
        log.info("java.awt.headless = {}", headless);
        if ("true".equalsIgnoreCase(headless)) {
            log.error("Headless mode is ON — tray cannot start. " +
                    "Add -Djava.awt.headless=false to JVM args.");
            return;
        }

        // ── Guard 2: tray supported ───────────────────────────────────
        if (!SystemTray.isSupported()) {
            log.error("SystemTray.isSupported() = false on this platform.");
            return;
        }
        log.info("SystemTray is supported — proceeding.");

        // ── Run on EDT and WAIT for it to complete ────────────────────
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                createTrayIcon();
            } finally {
                latch.countDown();
            }
        });

        // Wait up to 5 seconds for EDT to finish
        try {
            boolean done = latch.await(5, TimeUnit.SECONDS);
            if (!done) log.error("Tray init timed out after 5s — EDT may be blocked.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Create icon — runs on EDT
    // ─────────────────────────────────────────────────────────────────

    private void createTrayIcon() {
        try {
            log.info("EDT: creating tray icon...");

            tray     = SystemTray.getSystemTray();
            Image img = buildIcon(UserStatus.IDLE);

            trayIcon = new TrayIcon(img, "ActivePulse | Idle");
            trayIcon.setImageAutoSize(true);
            trayIcon.setPopupMenu(buildMenu());

            tray.add(trayIcon);
            log.info("EDT: tray icon added successfully.");

            // Balloon notification so user sees it appeared
            trayIcon.displayMessage(
                    "ActivePulse Started",
                    "Monitoring is active. Right-click this icon for options.",
                    TrayIcon.MessageType.INFO
            );

            // Register status change callback
            UserStatusTracker.getInstance().setOnStatusChanged(this::refresh);

        } catch (AWTException e) {
            log.error("AWTException while adding tray icon: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error creating tray icon: {}", e.getMessage(), e);
        }
    }

    public void remove() {
        if (tray != null && trayIcon != null) {
            SwingUtilities.invokeLater(() -> {
                tray.remove(trayIcon);
                log.info("Tray icon removed.");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Refresh on status change
    // ─────────────────────────────────────────────────────────────────

    public void refresh() {
        if (trayIcon == null) return;
        SwingUtilities.invokeLater(() -> {
            UserStatus s = UserStatusTracker.getInstance().getStatus();
            trayIcon.setImage(buildIcon(s));
            trayIcon.setToolTip(buildTooltip(s));
            trayIcon.setPopupMenu(buildMenu());
        });
    }

    // ─────────────────────────────────────────────────────────────────
    //  Icon
    //  1. Tries to load tray-icon.png from classpath (your custom logo)
    //  2. Falls back to a programmatic coloured dot
    // ─────────────────────────────────────────────────────────────────

    private Image buildIcon(UserStatus status) {
        BufferedImage base = loadResourceImage("tray-icon.png");
        if (base != null) {
            log.debug("Using custom tray-icon.png");
            return overlayDot(base, status);
        }
        log.debug("No tray-icon found — using coloured dot.");
        return buildDot(status);
    }

    private Image overlayDot(BufferedImage base, UserStatus status) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, size, size, null);

        int d = 18, x = size - d - 2, y = size - d - 2;
        g.setColor(Color.WHITE);
        g.fillOval(x - 2, y - 2, d + 4, d + 4);
        g.setColor(dotColor(status));
        g.fillOval(x, y, d, d);
        g.dispose();
        return img;
    }

    private Image buildDot(UserStatus status) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(4, 6, size - 6, size - 6);
        g.setColor(dotColor(status));
        g.fillOval(2, 2, size - 6, size - 6);
        g.setColor(new Color(255, 255, 255, 80));
        g.fillOval(10, 8, size / 3, size / 3);
        g.dispose();
        return img;
    }

    private Color dotColor(UserStatus status) {
        return switch (status) {
            case WORKING -> new Color(34, 197, 94);    // green (clean success)
            case NEUTRAL -> new Color(229, 231, 235);   // slate gray (true neutral)
            case IDLE    -> new Color(75, 85, 99);  // soft gray
            case STOPPED -> new Color(239, 68, 68);    // red
            case AWAY    -> new Color(245, 158, 11);   // amber/orange
        };
    }

    // ─────────────────────────────────────────────────────────────────
    //  Tooltip
    // ─────────────────────────────────────────────────────────────────

    private String buildTooltip(UserStatus status) {
        return "ActivePulse  |  "
                + status.getLabel()
                + "  |  "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Right-click menu
    // ─────────────────────────────────────────────────────────────────

    private PopupMenu buildMenu() {
        UserStatus status = UserStatusTracker.getInstance().getStatus();
        PopupMenu  menu   = new PopupMenu();

        MenuItem s1 = new MenuItem("Status: " + status.getLabel());
        s1.setEnabled(false);
        menu.add(s1);

        MenuItem s2 = new MenuItem(
                System.getProperty("user.name") + "  @  " + System.getProperty("os.name"));
        s2.setEnabled(false);
        menu.add(s2);

        menu.addSeparator();

        MenuItem logs = new MenuItem("Open Logs Folder");
        logs.addActionListener(e -> openFolder(
                Paths.get(System.getProperty("user.home"), ".activepulse", "logs")));
        menu.add(logs);

        MenuItem data = new MenuItem("Open Data Folder");
        data.addActionListener(e -> openFolder(
                Paths.get(System.getProperty("user.home"), ".activepulse")));
        menu.add(data);

        menu.addSeparator();

        MenuItem exit = new MenuItem("Exit ActivePulse");
        exit.addActionListener(e -> {
            log.info("Exit from tray.");
            UserStatusTracker.getInstance().setStopped();
            remove();
            System.exit(0);
        });
        menu.add(exit);

        return menu;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private BufferedImage loadResourceImage(String name) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) return null;
            return ImageIO.read(is);
        } catch (Exception e) {
            log.debug("Could not load '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private void openFolder(Path path) {
        try { Desktop.getDesktop().open(path.toFile()); }
        catch (Exception e) { log.warn("Cannot open folder: {}", e.getMessage()); }
    }
}