package com.activepulse.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * AutoStartManager — registers the agent to start on OS login.
 *
 * Windows → HKCU registry Run key + Startup folder shortcut
 * macOS   → LaunchAgent plist
 * Linux   → systemd user service
 */
public class AutoStartManager {

    private static final Logger log = LoggerFactory.getLogger(AutoStartManager.class);

    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    private static final String TASK_NAME    = "ActivePulseAgent";
    private static final String PLIST_ID     = "com.activepulse.agent";
    private static final String SERVICE_NAME = "activepulse";

    private static final String WIN_REG_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile AutoStartManager instance;
    private AutoStartManager() {}

    public static AutoStartManager getInstance() {
        if (instance == null) {
            synchronized (AutoStartManager.class) {
                if (instance == null) instance = new AutoStartManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    public void install() {
        log.info("Registering auto-start...");
        if (isWindows()) installWindows();
        else if (isMac()) installMac();
        else              installLinux();
    }

    public void uninstall() {
        if (isWindows()) uninstallWindows();
        else if (isMac()) uninstallMac();
        else              uninstallLinux();
    }

    public boolean isInstalled() {
        if (isWindows()) return isInstalledWindows();
        if (isMac())     return isInstalledMac();
        return isInstalledLinux();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Windows
    // ─────────────────────────────────────────────────────────────────

    private void installWindows() {
        // ── Resolve the exact path of the running executable ─────────
        // ProcessHandle.current().info().command() returns the ACTUAL
        // path of the running process — works for both:
        //   - jpackage .exe  → C:\...\ActivePulse\ActivePulse.exe
        //   - java -jar      → C:\...\javaw.exe
        String exePath = getCurrentExePath();
        log.info("Running as: {}", exePath);

        // Build the registry value
        // If running as java.exe/javaw.exe, append -jar <jarpath>
        String regValue;
        if (exePath != null && exePath.toLowerCase().endsWith("activepulse.exe")) {
            // Installed as native exe — just register the exe
            regValue = exePath;
        } else {
            // Running as plain JAR — register javaw + jar
            String jarPath = getJarPath();
            String javaw   = resolveJavaw();
            regValue = javaw + " -jar " + jarPath;
        }

        log.info("Registry value: {}", regValue);

        // ── Write registry — use individual args (no shell escaping) ──
        // ProcessBuilder args are passed directly to the OS — no
        // quoting issues, no cmd.exe interpretation.
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "reg", "add",
                    WIN_REG_KEY,
                    "/v", TASK_NAME,
                    "/t", "REG_SZ",
                    "/d", regValue,   // ← raw value, no extra quotes needed
                    "/f"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();

            if (exit == 0) {
                log.info("Registry auto-start written successfully.");
                log.info("  Key   : {}\\{}", WIN_REG_KEY, TASK_NAME);
                log.info("  Value : {}", regValue);
            } else {
                log.error("Registry write FAILED (exit={}) output: {}", exit, output);
            }
        } catch (Exception e) {
            log.error("Registry write exception: {}", e.getMessage());
        }

        // ── Startup folder shortcut (belt + suspenders) ───────────────
        writeStartupShortcut(regValue, exePath);

        // ── Verify ────────────────────────────────────────────────────
        verifyRegistry();
    }

    /**
     * Gets the file path of the currently running process.
     * Returns null if unavailable.
     */
    private String getCurrentExePath() {
        try {
            return ProcessHandle.current()
                    .info()
                    .command()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("ProcessHandle failed: {}", e.getMessage());
            return null;
        }
    }

    private void writeStartupShortcut(String regValue, String currentExe) {
        try {
            String startupDir = System.getenv("APPDATA")
                    + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
            String lnkPath = startupDir + "\\" + TASK_NAME + ".lnk";

            String target, args, workDir;
            if (currentExe != null && currentExe.toLowerCase().endsWith("activepulse.exe")) {
                target  = currentExe;
                args    = "";
                workDir = Paths.get(currentExe).getParent().toString();
            } else {
                target  = resolveJavaw();
                args    = "-jar " + getJarPath();
                workDir = Paths.get(getJarPath()).getParent().toString();
            }

            String ps = String.format(
                    "$s=(New-Object -COM WScript.Shell).CreateShortcut('%s');" +
                            "$s.TargetPath='%s';" +
                            "$s.Arguments='%s';" +
                            "$s.WorkingDirectory='%s';" +
                            "$s.Description='ActivePulse Activity Monitor';" +
                            "$s.WindowStyle=7;" +
                            "$s.Save()",
                    lnkPath, target, args, workDir
            );

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NonInteractive", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-Command", ps);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exit = p.waitFor();

            if (exit == 0) log.info("Startup shortcut created: {}", lnkPath);
            else           log.warn("Startup shortcut failed (exit={})", exit);

        } catch (Exception e) {
            log.warn("Startup shortcut exception: {}", e.getMessage());
        }
    }

    private void verifyRegistry() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "reg", "query", WIN_REG_KEY, "/v", TASK_NAME);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (out.contains(TASK_NAME)) {
                log.info("Registry verified ✓ — agent will start on next login.");
            } else {
                log.error("Registry NOT found after write — auto-start will not work!");
            }
        } catch (Exception e) {
            log.debug("Registry verify error: {}", e.getMessage());
        }
    }

    private void uninstallWindows() {
        try {
            new ProcessBuilder("reg", "delete", WIN_REG_KEY, "/v", TASK_NAME, "/f")
                    .redirectErrorStream(true).start().waitFor();
            log.info("Registry entry removed.");
        } catch (Exception e) {
            log.warn("Registry delete error: {}", e.getMessage());
        }
        String lnk = System.getenv("APPDATA")
                + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\"
                + TASK_NAME + ".lnk";
        try {
            Files.deleteIfExists(Paths.get(lnk));
            log.info("Startup shortcut removed.");
        } catch (Exception ignored) {}
    }

    private boolean isInstalledWindows() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "reg", "query", WIN_REG_KEY, "/v", TASK_NAME);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  macOS — LaunchAgent
    // ─────────────────────────────────────────────────────────────────

    private void installMac() {
        Path plistDir  = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents");
        Path plistFile = plistDir.resolve(PLIST_ID + ".plist");
        String javaBin = resolveJavaBin();
        String jarPath = getJarPath();

        String plist = String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
              "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key><string>%s</string>
                <key>ProgramArguments</key>
                <array>
                    <string>%s</string>
                    <string>-jar</string>
                    <string>%s</string>
                </array>
                <key>RunAtLoad</key><true/>
                <key>KeepAlive</key><true/>
                <key>StandardOutPath</key>
                <string>%s</string>
                <key>StandardErrorPath</key>
                <string>%s</string>
            </dict>
            </plist>
            """,
                PLIST_ID, javaBin, jarPath,
                logPath("stdout.log"), logPath("stderr.log")
        );
        try {
            Files.createDirectories(plistDir);
            Files.writeString(plistFile, plist);
            exec("launchctl", "unload", "-w", plistFile.toString());
            exec("launchctl", "load",   "-w", plistFile.toString());
            log.info("LaunchAgent installed: {}", plistFile);
        } catch (IOException e) {
            log.error("LaunchAgent failed: {}", e.getMessage());
        }
    }

    private void uninstallMac() {
        Path f = Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", PLIST_ID + ".plist");
        exec("launchctl", "unload", "-w", f.toString());
        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
    }

    private boolean isInstalledMac() {
        return Files.exists(Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", PLIST_ID + ".plist"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Linux — systemd
    // ─────────────────────────────────────────────────────────────────

    private void installLinux() {
        Path dir  = Paths.get(System.getProperty("user.home"), ".config", "systemd", "user");
        Path file = dir.resolve(SERVICE_NAME + ".service");
        String javaBin = resolveJavaBin();
        String jarPath = getJarPath();

        String unit = String.format("""
            [Unit]
            Description=ActivePulse Desktop Activity Agent
            After=graphical-session.target

            [Service]
            Type=simple
            ExecStart=%s -jar %s
            Restart=on-failure
            RestartSec=10
            StandardOutput=append:%s
            StandardError=append:%s
            Environment=DISPLAY=:0

            [Install]
            WantedBy=default.target
            """,
                javaBin, jarPath,
                logPath("stdout.log"), logPath("stderr.log")
        );
        try {
            Files.createDirectories(dir);
            Files.writeString(file, unit);
            try {
                Files.setPosixFilePermissions(file,
                        Set.of(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException ignored) {}
            exec("systemctl", "--user", "daemon-reload");
            exec("systemctl", "--user", "enable", SERVICE_NAME);
            log.info("systemd service installed.");
        } catch (IOException e) {
            log.error("systemd install failed: {}", e.getMessage());
        }
    }

    private void uninstallLinux() {
        exec("systemctl", "--user", "stop",    SERVICE_NAME);
        exec("systemctl", "--user", "disable", SERVICE_NAME);
        try {
            Files.deleteIfExists(Paths.get(System.getProperty("user.home"),
                    ".config", "systemd", "user", SERVICE_NAME + ".service"));
        } catch (IOException ignored) {}
        exec("systemctl", "--user", "daemon-reload");
    }

    private boolean isInstalledLinux() {
        return Files.exists(Paths.get(System.getProperty("user.home"),
                ".config", "systemd", "user", SERVICE_NAME + ".service"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Path helpers
    // ─────────────────────────────────────────────────────────────────

    private String getJarPath() {
        try {
            return Paths.get(
                    AutoStartManager.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).toAbsolutePath().toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** javaw.exe — no console window on Windows */
    private String resolveJavaw() {
        String home = System.getProperty("java.home");
        if (home != null) {
            Path p = Paths.get(home, "bin", "javaw.exe");
            if (Files.exists(p)) return p.toAbsolutePath().toString();
        }
        return "javaw";
    }

    private String resolveJavaBin() {
        String home = System.getProperty("java.home");
        if (home != null) {
            String exe = isWindows() ? "javaw.exe" : "java";
            Path p = Paths.get(home, "bin", exe);
            if (Files.exists(p)) return p.toAbsolutePath().toString();
        }
        return "java";
    }

    private String logPath(String name) {
        return Paths.get(System.getProperty("user.home"),
                ".activepulse", "logs", name).toString();
    }

    private int exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor();
        } catch (Exception e) {
            log.debug("exec({}) error: {}", cmd[0], e.getMessage());
            return -1;
        }
    }

    private static boolean isWindows() { return OS.contains("win"); }
    private static boolean isMac()     { return OS.contains("mac"); }
}