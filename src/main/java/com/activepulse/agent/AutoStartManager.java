package com.activepulse.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;

/**
 * AutoStartManager — registers the agent to start on OS login.
 *
 *  Windows → HKCU registry Run key   (per-user, no admin required)
 *  macOS   → LaunchAgent plist
 *  Linux   → systemd user service
 *
 * Key design: install() is called on every startup so the registry
 * entry is always current (correct exe path for upgrades/reinstalls).
 */
public class AutoStartManager {

    private static final Logger log = LoggerFactory.getLogger(AutoStartManager.class);

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final String JAR_PATH  = resolveJarPath();
    private static final String JAVA_BIN  = resolveJavaBin();

    private static final String TASK_NAME    = "ActivePulseAgent";
    private static final String PLIST_ID     = "com.activepulse.agent";
    private static final String SERVICE_NAME = "activepulse";

    // HKCU (per-user) — no admin rights needed; HKLM is set by the installer script instead
    private static final String WIN_REG_KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

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
        try {
            log.info("Registering auto-start...");
            
            if (isWindows()) {
                // Attempt both HKLM and HKCU for redundancy
                boolean hkmlSuccess = installWindowsHKLM();
                boolean hkcuSuccess = installWindows();
                
                if (hkmlSuccess || hkcuSuccess) {
                    log.info("Auto-start registration successful (HKLM: {}, HKCU: {})", hkmlSuccess, hkcuSuccess);
                } else {
                    log.warn("Auto-start registration failed for both HKLM and HKCU");
                }
            }
            else if (isMac())  installMac();
            else               installLinux();
        } catch (Exception e) {
            log.error("Auto-start installation failed (non-critical): {}", e.getMessage());
            log.debug("Auto-start failure details", e);
        }
    }

    public void uninstall() {
        log.info("Removing auto-start...");
        if (isWindows()) {
            uninstallWindowsHKLM();
            uninstallWindows();
        }
        else if (isMac()) uninstallMac();
        else              uninstallLinux();
    }

    public boolean isInstalled() {
        if (isWindows()) return isInstalledWindows() || isInstalledMachineWide();
        if (isMac())     return isInstalledMac();
        return isInstalledLinux();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Windows
    // ─────────────────────────────────────────────────────────────────

    private boolean installWindows() {
        String exePath = resolveNativeExePath();
        String value;

        if (exePath != null) {
            value = "\"" + exePath + "\"";
            log.info("Auto-start: using native exe: {}", exePath);
        } else {
            value = String.format("\"%s\" -jar \"%s\"", JAVA_BIN, JAR_PATH);
            log.info("Auto-start: using JAR launch: {}", value);
        }

        int exit = exec("reg", "add", WIN_REG_KEY,
                "/v", TASK_NAME,
                "/t", "REG_SZ",
                "/d", value,
                "/f");

        if (exit == 0) {
            log.info("Auto-start registered: {}\\{} = {}", WIN_REG_KEY, TASK_NAME, value);
            return true;
        } else {
            log.error("Registry write failed (exit {}). Key: {}", exit, WIN_REG_KEY);
            return false;
        }
    }

    /**
     * Install machine-wide HKLM registry entry (requires admin privileges).
     * This is called in addition to HKCU for redundancy.
     * If HKLM write fails (no admin), we log but don't fail the app.
     */
    private boolean installWindowsHKLM() {
        String exePath = resolveNativeExePath();
        String value;

        if (exePath != null) {
            value = "\"" + exePath + "\"";
            log.info("Auto-start: using native exe for HKLM: {}", exePath);
        } else {
            value = String.format("\"%s\" -jar \"%s\"", JAVA_BIN, JAR_PATH);
            log.info("Auto-start: using JAR launch for HKLM: {}", value);
        }

        String hklmKey = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        int exit = exec("reg", "add", hklmKey,
                "/v", TASK_NAME,
                "/t", "REG_SZ",
                "/d", value,
                "/f");

        if (exit == 0) {
            log.info("Auto-start registered in HKLM: {}\\{} = {}", hklmKey, TASK_NAME, value);
            return true;
        } else {
            log.warn("HKLM registry write failed (exit {}). This is expected for non-admin users. HKCU will be used instead.", exit);
            return false;
        }
    }

    /**
     * Walks up from the loaded JAR to find the installed .exe.
     * jpackage layout:  <InstallDir>/<AppName>.exe
     *                   <InstallDir>/app/<main>.jar   ← JAR lives here
     *                   <InstallDir>/runtime/
     *
     * Uses dynamic exe-name discovery so it works regardless of --name.
     */
    private String resolveNativeExePath() {
        try {
            Path self = Paths.get(
                    AutoStartManager.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).toAbsolutePath();

            Path appDir     = self.getParent();       // …/<InstallDir>/app/
            if (appDir == null) return null;
            Path installDir = appDir.getParent();     // …/<InstallDir>/
            if (installDir == null) return null;

            // Option A: exe name matches install-dir name (most common)
            String dirName  = installDir.getFileName().toString();
            Path candidate = installDir.resolve(dirName + ".exe");
            if (Files.exists(candidate)) return candidate.toString();

            // Option B: scan the install dir for any .exe
            try (Stream<Path> stream = Files.list(installDir)) {
                return stream
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".exe") && !n.contains("unins");
                        })
                        .map(Path::toString)
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            log.debug("resolveNativeExePath failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check whether the installer has already set a machine-wide HKLM Run entry.
     * If so, we skip HKCU registration to avoid double-launch.
     */
    private boolean isInstalledMachineWide() {
        return exec("reg", "query",
                "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", TASK_NAME) == 0;
    }

    private void uninstallWindowsHKLM() {
        String hklmKey = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        int exit = exec("reg", "delete", hklmKey, "/v", TASK_NAME, "/f");
        if (exit == 0) log.info("Auto-start HKLM entry removed.");
        else           log.warn("HKLM reg delete returned {} — entry may not exist or no admin rights.", exit);
    }

    private void uninstallWindows() {
        int exit = exec("reg", "delete", WIN_REG_KEY, "/v", TASK_NAME, "/f");
        if (exit == 0) log.info("Auto-start HKCU entry removed.");
        else           log.warn("HKCU reg delete returned {} — entry may not exist.", exit);
    }

    private boolean isInstalledWindows() {
        return exec("reg", "query", WIN_REG_KEY, "/v", TASK_NAME) == 0;
    }

    // ─────────────────────────────────────────────────────────────────
    //  macOS
    // ─────────────────────────────────────────────────────────────────

    private void installMac() {
        Path plistDir  = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents");
        Path plistFile = plistDir.resolve(PLIST_ID + ".plist");

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
                <key>StandardOutPath</key><string>%s</string>
                <key>StandardErrorPath</key><string>%s</string>
                <key>ProcessType</key><string>Background</string>
            </dict>
            </plist>
            """,
                PLIST_ID, JAVA_BIN, JAR_PATH,
                logPath("activepulse-stdout.log"),
                logPath("activepulse-stderr.log")
        );

        try {
            Files.createDirectories(plistDir);
            Files.writeString(plistFile, plist);
            exec("launchctl", "unload", "-w", plistFile.toString());
            exec("launchctl", "load",   "-w", plistFile.toString());
            log.info("LaunchAgent installed: {}", plistFile);
        } catch (IOException e) {
            log.error("Failed to write LaunchAgent plist: {}", e.getMessage());
        }
    }

    private void uninstallMac() {
        Path plistFile = Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", PLIST_ID + ".plist");
        exec("launchctl", "unload", "-w", plistFile.toString());
        try { Files.deleteIfExists(plistFile); }
        catch (IOException e) { log.warn("Could not delete plist: {}", e.getMessage()); }
        log.info("LaunchAgent removed.");
    }

    private boolean isInstalledMac() {
        return Files.exists(Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", PLIST_ID + ".plist"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Linux
    // ─────────────────────────────────────────────────────────────────

    private void installLinux() {
        Path serviceDir  = Paths.get(System.getProperty("user.home"),
                ".config", "systemd", "user");
        Path serviceFile = serviceDir.resolve(SERVICE_NAME + ".service");

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
            Environment=XAUTHORITY=%s/.Xauthority

            [Install]
            WantedBy=default.target
            """,
                JAVA_BIN, JAR_PATH,
                logPath("activepulse-stdout.log"),
                logPath("activepulse-stderr.log"),
                System.getProperty("user.home")
        );

        try {
            Files.createDirectories(serviceDir);
            Files.writeString(serviceFile, unit);
            try {
                Files.setPosixFilePermissions(serviceFile,
                        Set.of(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException ignored) {}

            exec("systemctl", "--user", "daemon-reload");
            exec("systemctl", "--user", "enable", SERVICE_NAME);
            log.info("systemd service installed: {}", serviceFile);
        } catch (IOException e) {
            log.error("Failed to write systemd unit: {}", e.getMessage());
        }
    }

    private void uninstallLinux() {
        exec("systemctl", "--user", "stop",    SERVICE_NAME);
        exec("systemctl", "--user", "disable", SERVICE_NAME);
        Path serviceFile = Paths.get(System.getProperty("user.home"),
                ".config", "systemd", "user", SERVICE_NAME + ".service");
        try {
            Files.deleteIfExists(serviceFile);
            exec("systemctl", "--user", "daemon-reload");
        } catch (IOException e) {
            log.warn("Could not delete service file: {}", e.getMessage());
        }
        log.info("systemd service removed.");
    }

    private boolean isInstalledLinux() {
        return Files.exists(Paths.get(System.getProperty("user.home"),
                ".config", "systemd", "user", SERVICE_NAME + ".service"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Path resolution
    // ─────────────────────────────────────────────────────────────────

    private static String resolveJarPath() {
        try {
            return Paths.get(
                    AutoStartManager.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).toAbsolutePath().toString();
        } catch (Exception e) {
            return System.getProperty("user.home") + "/.activepulse/active-pulse-0.0.1-SNAPSHOT.jar";
        }
    }

    private static String resolveJavaBin() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path javaw = Paths.get(javaHome, "bin", "javaw.exe");
            if (Files.exists(javaw)) return javaw.toAbsolutePath().toString();

            String exe = isWindows() ? "java.exe" : "java";
            Path java  = Paths.get(javaHome, "bin", exe);
            if (Files.exists(java)) return java.toAbsolutePath().toString();
        }
        return "java";
    }

    // ─────────────────────────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────────────────────────

    private int exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit != 0 && !output.isEmpty()) {
                log.debug("exec({}) exit={} output={}", cmd[0], exit, output);
            }
            return exit;
        } catch (Exception e) {
            log.debug("exec({}) error: {}", cmd[0], e.getMessage());
            return -1;
        }
    }

    private String logPath(String fileName) {
        return Paths.get(System.getProperty("user.home"),
                ".activepulse", "logs", fileName).toString();
    }

    private static boolean isWindows() { return OS.contains("win"); }
    private static boolean isMac()     { return OS.contains("mac"); }
}
// package com.activepulse.agent;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import java.io.IOException;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.Paths;
// import java.nio.file.attribute.PosixFilePermission;
// import java.util.Set;

// /**
//  * AutoStartManager — registers the agent to start on OS login.
//  *
//  * Windows → HKCU registry Run key   (no admin required, uses javaw.exe)
//  * macOS   → LaunchAgent plist
//  * Linux   → systemd user service
//  *
//  * Key design: install() is called on EVERY startup to ensure the
//  * registry entry is always current (correct JAR path, correct Java path).
//  */
// public class AutoStartManager {

//     private static final Logger log = LoggerFactory.getLogger(AutoStartManager.class);

//     private static final String OS       = System.getProperty("os.name", "").toLowerCase();
//     private static final String JAR_PATH = resolveJarPath();

//     // ── Use javaw.exe on Windows (no console window on auto-start) ───
//     private static final String JAVA_BIN = resolveJavaBin();

//     private static final String TASK_NAME    = "ActivePulseAgent";
//     private static final String PLIST_ID     = "com.activepulse.agent";
//     private static final String SERVICE_NAME = "activepulse";

//     private static final String WIN_REG_KEY =
//     "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

//     // ── Singleton ────────────────────────────────────────────────────
//     private static volatile AutoStartManager instance;
//     private AutoStartManager() {}

//     public static AutoStartManager getInstance() {
//         if (instance == null) {
//             synchronized (AutoStartManager.class) {
//                 if (instance == null) instance = new AutoStartManager();
//             }
//         }
//         return instance;
//     }

//     // ─────────────────────────────────────────────────────────────────
//     //  Public API
//     // ─────────────────────────────────────────────────────────────────

//     /**
//      * Always re-installs on every startup — ensures JAR path and
//      * Java path are always up to date in the registry.
//      */
//     public void install() {
//         log.info("Installing auto-start...");
//         if (isWindows())   installWindows();
//         else if (isMac())  installMac();
//         else               installLinux();
//     }

//     public void uninstall() {
//         log.info("Removing auto-start...");
//         if (isWindows()) uninstallWindows();
//         else if (isMac()) uninstallMac();
//         else              uninstallLinux();
//     }

//     public boolean isInstalled() {
//         if (isWindows()) return isInstalledWindows();
//         if (isMac())     return isInstalledMac();
//         return isInstalledLinux();
//     }

//     // ─────────────────────────────────────────────────────────────────
//     //  Windows — HKCU Registry Run key
//     //  Uses javaw.exe so no console window appears on auto-start
//     // ─────────────────────────────────────────────────────────────────

//     /**
//      * Resolves the correct executable to register for auto-start.
//      *
//      * Running as jpackage .exe  → register ActivePulse.exe directly
//      * Running as plain JAR      → register javaw.exe -jar agent.jar
//      */
//     private void installWindows() {
//         String value;
//         String exePath = resolveNativeExePath();


//         if (exePath != null) {
//             // Running inside jpackage app-image — use the .exe directly
//             value = "\"" + exePath + "\"";
//             log.info("Auto-start: using native exe: {}", exePath);
//         } else {
//             // Plain JAR run
//             value = String.format("\"%s\" -jar \"%s\"", JAVA_BIN, JAR_PATH);
//             log.info("Auto-start: using JAR launch: {}", value);
//         }

        
//         log.info("Writing registry key to HKLM...");
// log.info("Command value: {}", value);

//         int exit = exec("reg", "add", WIN_REG_KEY,
//         "/v", TASK_NAME,
//         "/t", "REG_SZ",
//         "/d", value,
//         "/f");

//         if (exit == 0) {
//             log.info("Auto-start registered in Windows Registry.");
//             log.info("  Key   : {}\\{}", WIN_REG_KEY, TASK_NAME);
//             log.info("  Value : {}", value);
//         } else {
//             log.error("Registry write failed (exit {})", exit);
//         }
//     }

//     /**
//      * If running inside a jpackage app-image, the .exe lives one level
//      * above the app/runtime folder.
//      * Structure:  ActivePulse\
//      *               ActivePulse.exe      ← this is what we want
//      *               app\
//      *               runtime\
//      */
//    private String resolveNativeExePath() {
//     try {
//         Path self = Paths.get(
//                 AutoStartManager.class.getProtectionDomain()
//                         .getCodeSource().getLocation().toURI()
//         ).toAbsolutePath();

//         Path appDir     = self.getParent();      // …/<App>/app/
//         Path installDir = appDir.getParent();    // …/<App>/
//         if (installDir == null) return null;

//         // Derive exe name from install dir (works regardless of branding)
//         String exeName = installDir.getFileName().toString() + ".exe";
//         Path exe = installDir.resolve(exeName);
//         if (Files.exists(exe)) return exe.toString();

//         // Fallback: scan for any .exe at install root
//         try (var stream = Files.list(installDir)) {
//             return stream
//                 .filter(p -> p.toString().toLowerCase().endsWith(".exe"))
//                 .map(Path::toString)
//                 .findFirst()
//                 .orElse(null);
//         }
//     } catch (Exception ignored) {}
//     return null;
// }

//     private void uninstallWindows() {
//         int exit = exec("reg", "delete", WIN_REG_KEY, "/v", TASK_NAME, "/f");
//         if (exit == 0) log.info("Auto-start registry entry removed.");
//         else           log.warn("reg delete returned {} — entry may not exist.", exit);
//     }

//     private boolean isInstalledWindows() {
//         return exec("reg", "query", WIN_REG_KEY, "/v", TASK_NAME) == 0;
//     }

//     // ─────────────────────────────────────────────────────────────────
//     //  macOS — LaunchAgent plist
//     // ─────────────────────────────────────────────────────────────────

//     private void installMac() {
//         Path plistDir  = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents");
//         Path plistFile = plistDir.resolve(PLIST_ID + ".plist");

//         String plist = String.format("""
//             <?xml version="1.0" encoding="UTF-8"?>
//             <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
//               "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
//             <plist version="1.0">
//             <dict>
//                 <key>Label</key>
//                 <string>%s</string>
//                 <key>ProgramArguments</key>
//                 <array>
//                     <string>%s</string>
//                     <string>-jar</string>
//                     <string>%s</string>
//                 </array>
//                 <key>RunAtLoad</key>
//                 <true/>
//                 <key>KeepAlive</key>
//                 <true/>
//                 <key>StandardOutPath</key>
//                 <string>%s</string>
//                 <key>StandardErrorPath</key>
//                 <string>%s</string>
//                 <key>ProcessType</key>
//                 <string>Background</string>
//             </dict>
//             </plist>
//             """,
//                 PLIST_ID, JAVA_BIN, JAR_PATH,
//                 logPath("activepulse-stdout.log"),
//                 logPath("activepulse-stderr.log")
//         );

//         try {
//             Files.createDirectories(plistDir);
//             Files.writeString(plistFile, plist);
//             exec("launchctl", "unload", "-w", plistFile.toString()); // unload first to refresh
//             exec("launchctl", "load",   "-w", plistFile.toString());
//             log.info("LaunchAgent installed: {}", plistFile);
//         } catch (IOException e) {
//             log.error("Failed to write LaunchAgent plist: {}", e.getMessage());
//         }
//     }

//     private void uninstallMac() {
//         Path plistFile = Paths.get(System.getProperty("user.home"),
//                 "Library", "LaunchAgents", PLIST_ID + ".plist");
//         exec("launchctl", "unload", "-w", plistFile.toString());
//         try { Files.deleteIfExists(plistFile); }
//         catch (IOException e) { log.warn("Could not delete plist: {}", e.getMessage()); }
//         log.info("LaunchAgent removed.");
//     }

//     private boolean isInstalledMac() {
//         return Files.exists(Paths.get(System.getProperty("user.home"),
//                 "Library", "LaunchAgents", PLIST_ID + ".plist"));
//     }

//     // ─────────────────────────────────────────────────────────────────
//     //  Linux — systemd user service
//     // ─────────────────────────────────────────────────────────────────

//     private void installLinux() {
//         Path serviceDir  = Paths.get(System.getProperty("user.home"),
//                 ".config", "systemd", "user");
//         Path serviceFile = serviceDir.resolve(SERVICE_NAME + ".service");

//         String unit = String.format("""
//             [Unit]
//             Description=ActivePulse Desktop Activity Agent
//             After=graphical-session.target

//             [Service]
//             Type=simple
//             ExecStart=%s -jar %s
//             Restart=on-failure
//             RestartSec=10
//             StandardOutput=append:%s
//             StandardError=append:%s
//             Environment=DISPLAY=:0
//             Environment=XAUTHORITY=%s/.Xauthority

//             [Install]
//             WantedBy=default.target
//             """,
//                 JAVA_BIN, JAR_PATH,
//                 logPath("activepulse-stdout.log"),
//                 logPath("activepulse-stderr.log"),
//                 System.getProperty("user.home")
//         );

//         try {
//             Files.createDirectories(serviceDir);
//             Files.writeString(serviceFile, unit);
//             try {
//                 Files.setPosixFilePermissions(serviceFile,
//                         Set.of(PosixFilePermission.OWNER_READ,
//                                 PosixFilePermission.OWNER_WRITE,
//                                 PosixFilePermission.GROUP_READ,
//                                 PosixFilePermission.OTHERS_READ));
//             } catch (UnsupportedOperationException ignored) {}

//             exec("systemctl", "--user", "daemon-reload");
//             exec("systemctl", "--user", "enable", SERVICE_NAME);
//             log.info("systemd service installed: {}", serviceFile);
//         } catch (IOException e) {
//             log.error("Failed to write systemd unit: {}", e.getMessage());
//         }
//     }

//     private void uninstallLinux() {
//         exec("systemctl", "--user", "stop",    SERVICE_NAME);
//         exec("systemctl", "--user", "disable", SERVICE_NAME);
//         Path serviceFile = Paths.get(System.getProperty("user.home"),
//                 ".config", "systemd", "user", SERVICE_NAME + ".service");
//         try {
//             Files.deleteIfExists(serviceFile);
//             exec("systemctl", "--user", "daemon-reload");
//         } catch (IOException e) {
//             log.warn("Could not delete service file: {}", e.getMessage());
//         }
//         log.info("systemd service removed.");
//     }

//     private boolean isInstalledLinux() {
//         return Files.exists(Paths.get(System.getProperty("user.home"),
//                 ".config", "systemd", "user", SERVICE_NAME + ".service"));
//     }

//     // ─────────────────────────────────────────────────────────────────
//     //  Resolve paths
//     // ─────────────────────────────────────────────────────────────────

//     private static String resolveJarPath() {
//         try {
//             return Paths.get(
//                     AutoStartManager.class
//                             .getProtectionDomain()
//                             .getCodeSource()
//                             .getLocation()
//                             .toURI()
//             ).toAbsolutePath().toString();
//         } catch (Exception e) {
//             return System.getProperty("user.home") + "/.activepulse/active-pulse-0.0.1-SNAPSHOT.jar";
//         }
//     }

//     /**
//      * On Windows: prefer javaw.exe (no console window).
//      * On other OS: use java.
//      */
//     private static String resolveJavaBin() {
//         String javaHome = System.getProperty("java.home");
//         if (javaHome != null) {
//             // Try javaw.exe first (Windows, no console window)
//             Path javaw = Paths.get(javaHome, "bin", "javaw.exe");
//             if (Files.exists(javaw)) return javaw.toAbsolutePath().toString();

//             // Fallback to java / java.exe
//             String exe = isWindows() ? "java.exe" : "java";
//             Path java  = Paths.get(javaHome, "bin", exe);
//             if (Files.exists(java)) return java.toAbsolutePath().toString();
//         }
//         return "java";
//     }

//     // ─────────────────────────────────────────────────────────────────
//     //  Utilities
//     // ─────────────────────────────────────────────────────────────────

//     private int exec(String... cmd) {
//     try {
//         Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
//         String output = new String(p.getInputStream().readAllBytes()).trim();
//         int exit = p.waitFor();
//         if (exit != 0 && !output.isEmpty()) {
//             log.warn("exec({}) exit={} output={}", cmd[0], exit, output);
//         }
//         return exit;
//     } catch (Exception e) {
//         log.debug("exec({}) error: {}", cmd[0], e.getMessage());
//         return -1;
//     }
// }

//     private String logPath(String fileName) {
//         return Paths.get(System.getProperty("user.home"),
//                 ".activepulse", "logs", fileName).toString();
//     }

//     private static boolean isWindows() { return OS.contains("win"); }
//     private static boolean isMac()     { return OS.contains("mac"); }
// }