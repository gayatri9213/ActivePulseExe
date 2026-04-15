package com.activepulse.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * WindowsUserDetector - Detects the currently logged-in AD user on Windows systems.
 * 
 * This class uses native Windows APIs to correctly identify the active user session,
 * even when the Java process runs in admin context. It handles:
 * - User switching scenarios
 * - AD domain user detection
 * - Multiple user sessions
 * - Remote desktop sessions
 */
public class WindowsUserDetector {
    
    private static final Logger log = LoggerFactory.getLogger(WindowsUserDetector.class);
    
    // Pure Java implementation - no native methods
    
    // No native library loading - using pure Java implementation
    
    /**
     * Gets the currently logged-in Windows user name.
     * This method tries multiple approaches to get the correct user:
     * 1. WMI query (most accurate for detecting logged-in user)
     * 2. Query user sessions via command line
     * 3. Environment variables (fallback)
     * 
     * @return The current Windows user name, or null if detection fails
     */
    public static String getCurrentUser() {
        // Try WMI query first - most reliable for detecting active console user
        try {
            String wmiUser = getCurrentUserViaWMI();
            if (wmiUser != null && !wmiUser.trim().isEmpty()) {
                log.debug("Detected user via WMI: {}", wmiUser);
                return wmiUser;
            }
        } catch (Exception e) {
            log.debug("WMI query failed: {}", e.getMessage());
        }
        
        // Try query user sessions
        try {
            String sessionUser = getCurrentUserViaQueryUser();
            if (sessionUser != null && !sessionUser.trim().isEmpty()) {
                log.debug("Detected user via query user: {}", sessionUser);
                return sessionUser;
            }
        } catch (Exception e) {
            log.debug("Query user failed: {}", e.getMessage());
        }
        
        // Try environment variables as fallback
        try {
            String envUser = getCurrentUserViaEnvironment();
            if (envUser != null && !envUser.trim().isEmpty()) {
                log.debug("Detected user via environment: {}", envUser);
                return envUser;
            }
        } catch (Exception e) {
            log.debug("Environment variables failed: {}", e.getMessage());
        }
        
        log.warn("Failed to detect current Windows user");
        return null;
    }
    
    /**
     * Gets the current Windows domain/AD domain for the user.
     * 
     * @return The domain name, or null if detection fails
     */
    public static String getCurrentDomain() {
        // Try WMI query first
        try {
            String wmiDomain = getCurrentDomainViaWMI();
            if (wmiDomain != null && !wmiDomain.trim().isEmpty()) {
                log.debug("Detected domain via WMI: {}", wmiDomain);
                return wmiDomain;
            }
        } catch (Exception e) {
            log.debug("WMI domain query failed: {}", e.getMessage());
        }
        
        // Try environment variables as fallback
        try {
            String envDomain = System.getenv("USERDOMAIN");
            if (envDomain != null && !envDomain.trim().isEmpty()) {
                log.debug("Detected domain via environment: {}", envDomain);
                return envDomain;
            }
        } catch (Exception e) {
            log.debug("Environment domain failed: {}", e.getMessage());
        }
        
        log.debug("Failed to detect current Windows domain");
        return null;
    }
    
    /**
     * Gets the fully qualified user name in DOMAIN\\username format.
     * 
     * @return The fully qualified user name, or just the username if domain is not available
     */
    public static String getFullyQualifiedUser() {
        String username = getCurrentUser();
        String domain = getCurrentDomain();
        
        if (username == null) {
            return null;
        }
        
        if (domain != null && !domain.trim().isEmpty() && !username.contains("\\")) {
            return domain + "\\" + username;
        }
        
        return username;
    }
    
    /**
     * Gets the current session ID for the active user session.
     * 
     * @return The session ID, or -1 if detection fails
     */
    public static int getCurrentSessionId() {
        try {
            // Use query user to get session info
            ProcessBuilder pb = new ProcessBuilder("query", "session");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            
            String result = output.toString();
            for (String line : result.split("\n")) {
                if (line.contains(">") && !line.trim().isEmpty()) {
                    // Parse session ID from lines like "> rdp-tcp#0  Administrator  1  Active"
                    String[] parts = line.trim().split("\\s+");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].matches("\\d+")) {
                            return Integer.parseInt(parts[i]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get current session ID: {}", e.getMessage());
        }
        
        return -1;
    }
    
    /**
     * Checks if the current user session is active (not locked/sleep).
     * 
     * @return true if the session is active, false otherwise
     */
    public static boolean isSessionActive() {
        try {
            // Check if workstation is locked by trying to query user sessions
            ProcessBuilder pb = new ProcessBuilder("query", "user");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            
            String result = output.toString();
            // Look for "Active" status in user sessions
            for (String line : result.split("\n")) {
                if (line.toLowerCase().contains("active") && 
                    (line.toLowerCase().contains("administrator") || line.toLowerCase().contains("user"))) {
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            log.debug("Failed to check session activity: {}", e.getMessage());
            return true; // Assume active if we can't detect
        }
    }
    
    /**
     * Gets a list of all currently logged-in users.
     * 
     * @return Array of user names, or empty array if detection fails
     */
    public static String[] getAllLoggedInUsers() {
        try {
            ProcessBuilder pb = new ProcessBuilder("query", "user");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            
            java.util.List<String> users = new java.util.ArrayList<>();
            String result = output.toString();
            
            for (String line : result.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("USERNAME") && !line.startsWith("----")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String username = parts[0];
                        
                        // Handle lines that start with '>' (current active session indicator)
                        if (username.startsWith(">")) {
                            // Skip the '>' and get the actual username
                            if (parts.length > 1) {
                                username = parts[1];
                            } else {
                                continue; // Skip this line if no username found after '>'
                            }
                        }
                        
                        if (!username.equalsIgnoreCase("USERNAME") && 
                            !users.contains(username)) {
                            users.add(username);
                        }
                    }
                }
            }
            
            if (!users.isEmpty()) {
                log.debug("Found {} logged-in users", users.size());
                return users.toArray(new String[0]);
            }
        } catch (Exception e) {
            log.debug("Failed to get logged-in users: {}", e.getMessage());
        }
        
        return new String[0];
    }
    
    // ─────────────────────────────────────────────────────────────────
    //  Fallback methods using WMI, command line, and environment variables
    // ─────────────────────────────────────────────────────────────────
    
    private static String getCurrentUserViaQueryUser() {
        try {
            ProcessBuilder pb = new ProcessBuilder("query", "user");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            
            String result = output.toString();
            for (String line : result.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("USERNAME") && !line.startsWith("----")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String username = parts[0];
                        
                        // Handle lines that start with '>' (current active session indicator)
                        if (username.startsWith(">")) {
                            // Skip the '>' and get the actual username
                            if (parts.length > 1) {
                                username = parts[1];
                            } else {
                                continue; // Skip this line if no username found after '>'
                            }
                        }
                        
                        // Check if this user is active
                        if (line.toLowerCase().contains("active")) {
                            return username;
                        }
                    }
                }
            }
            
            // If no active user found, return the first user found
            for (String line : result.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("USERNAME") && !line.startsWith("----")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String username = parts[0];
                        
                        // Handle lines that start with '>' (current active session indicator)
                        if (username.startsWith(">")) {
                            // Skip the '>' and get the actual username
                            if (parts.length > 1) {
                                username = parts[1];
                            } else {
                                continue; // Skip this line if no username found after '>'
                            }
                        }
                        
                        return username;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Query user failed: {}", e.getMessage());
        }
        
        return null;
    }
    
    private static String getCurrentUserViaWMI() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "computersystem", "get", "username", "/value");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            
            String result = output.toString();
            for (String line : result.split("\n")) {
                if (line.startsWith("Username=")) {
                    String username = line.substring("Username=".length()).trim();
                    if (!username.isEmpty() && !username.equals("Username")) {
                        // Extract just the username part (remove domain if present)
                        if (username.contains("\\")) {
                            return username.substring(username.lastIndexOf("\\") + 1);
                        }
                        return username;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("WMI user query failed: {}", e.getMessage());
        }
        
        return null;
    }
    
    private static String getCurrentDomainViaWMI() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "computersystem", "get", "domain", "/value");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            
            String result = output.toString();
            for (String line : result.split("\n")) {
                if (line.startsWith("Domain=")) {
                    String domain = line.substring("Domain=".length()).trim();
                    if (!domain.isEmpty() && !domain.equals("Domain")) {
                        return domain;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("WMI domain query failed: {}", e.getMessage());
        }
        
        return null;
    }
    
    private static String getCurrentUserViaEnvironment() {
        // Try multiple environment variables
        String[] envVars = {"USERNAME", "USER", "LOGNAME"};
        
        for (String envVar : envVars) {
            String value = System.getenv(envVar);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        
        return null;
    }
    
    /**
     * Validates if the detected user is an AD domain user.
     * 
     * @param username The username to validate
     * @return true if the user appears to be an AD user, false otherwise
     */
    public static boolean isDomainUser(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        
        // Check if username contains domain separator
        if (username.contains("\\")) {
            return true;
        }
        
        // Check if we can detect a domain
        String domain = getCurrentDomain();
        return domain != null && !domain.trim().isEmpty() && 
               !domain.equalsIgnoreCase("WORKGROUP") && 
               !domain.equalsIgnoreCase(getComputerName());
    }
    
    private static String getComputerName() {
        try {
            return System.getenv("COMPUTERNAME");
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Gets user information in a structured format.
     * 
     * @return UserInfo object containing detected user details
     */
    public static UserInfo getUserInfo() {
        String username = getCurrentUser();
        String domain = getCurrentDomain();
        String fullyQualified = getFullyQualifiedUser();
        boolean isDomain = isDomainUser(username);
        int sessionId = getCurrentSessionId();
        boolean isActive = isSessionActive();
        
        return new UserInfo(username, domain, fullyQualified, isDomain, sessionId, isActive);
    }
    
    /**
     * User information container class.
     */
    public static class UserInfo {
        private final String username;
        private final String domain;
        private final String fullyQualifiedName;
        private final boolean isDomainUser;
        private final int sessionId;
        private final boolean isSessionActive;
        
        public UserInfo(String username, String domain, String fullyQualifiedName, 
                       boolean isDomainUser, int sessionId, boolean isSessionActive) {
            this.username = username;
            this.domain = domain;
            this.fullyQualifiedName = fullyQualifiedName;
            this.isDomainUser = isDomainUser;
            this.sessionId = sessionId;
            this.isSessionActive = isSessionActive;
        }
        
        public String getUsername() { return username; }
        public String getDomain() { return domain; }
        public String getFullyQualifiedName() { return fullyQualifiedName; }
        public boolean isDomainUser() { return isDomainUser; }
        public int getSessionId() { return sessionId; }
        public boolean isSessionActive() { return isSessionActive; }
        
        @Override
        public String toString() {
            return String.format("UserInfo{username='%s', domain='%s', fullyQualified='%s', isDomain=%s, sessionId=%d, active=%s}",
                    username, domain, fullyQualifiedName, isDomainUser, sessionId, isSessionActive);
        }
    }
}
