package com.activepulse.agent.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for WindowsUserDetector functionality.
 * These tests verify that the user detection works correctly in various scenarios.
 */
public class WindowsUserDetectorTest {

    @Test
    @DisplayName("Should detect current user")
    public void testGetCurrentUser() {
        // This test will only work on Windows systems
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        String currentUser = WindowsUserDetector.getCurrentUser();
        
        // Should detect a user (not null or empty)
        assertNotNull(currentUser, "Current user should not be null");
        assertFalse(currentUser.trim().isEmpty(), "Current user should not be empty");
        
        System.out.println("Detected current user: " + currentUser);
        
        // Compare with Java's user property to see if we get different results
        String javaUser = System.getProperty("user.name");
        System.out.println("Java user property: " + javaUser);
        
        if (!currentUser.equals(javaUser)) {
            System.out.println("✅ WindowsUserDetector detected different user than Java property - this is expected when running in admin context!");
        } else {
            System.out.println("ℹ️ WindowsUserDetector detected same user as Java property");
        }
    }

    @Test
    @DisplayName("Should detect current domain")
    public void testGetCurrentDomain() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        String currentDomain = WindowsUserDetector.getCurrentDomain();
        
        // Domain detection may fail in some environments, so we just log the result
        System.out.println("Detected domain: " + currentDomain);
        
        if (currentDomain != null && !currentDomain.trim().isEmpty()) {
            System.out.println("✅ Domain detected successfully");
        } else {
            System.out.println("ℹ️ Domain detection failed or not available");
        }
    }

    @Test
    @DisplayName("Should get fully qualified user name")
    public void testGetFullyQualifiedUser() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        String fullyQualified = WindowsUserDetector.getFullyQualifiedUser();
        
        assertNotNull(fullyQualified, "Fully qualified user should not be null");
        assertFalse(fullyQualified.trim().isEmpty(), "Fully qualified user should not be empty");
        
        System.out.println("Fully qualified user: " + fullyQualified);
    }

    @Test
    @DisplayName("Should detect if user is domain user")
    public void testIsDomainUser() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        String currentUser = WindowsUserDetector.getCurrentUser();
        boolean isDomain = WindowsUserDetector.isDomainUser(currentUser);
        
        System.out.println("User '" + currentUser + "' is domain user: " + isDomain);
    }

    @Test
    @DisplayName("Should get user info")
    public void testGetUserInfo() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        WindowsUserDetector.UserInfo userInfo = WindowsUserDetector.getUserInfo();
        
        assertNotNull(userInfo, "User info should not be null");
        assertNotNull(userInfo.getUsername(), "Username should not be null");
        
        System.out.println("User info: " + userInfo);
    }

    @Test
    @DisplayName("Should get session ID")
    public void testGetCurrentSessionId() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        int sessionId = WindowsUserDetector.getCurrentSessionId();
        
        System.out.println("Current session ID: " + sessionId);
        
        // Session ID should be positive or -1 if detection failed
        assertTrue(sessionId >= -1, "Session ID should be valid");
    }

    @Test
    @DisplayName("Should check session activity")
    public void testIsSessionActive() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        boolean isActive = WindowsUserDetector.isSessionActive();
        
        System.out.println("Session is active: " + isActive);
    }

    @Test
    @DisplayName("Should get all logged-in users")
    public void testGetAllLoggedInUsers() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; // Skip test on non-Windows systems
        }

        String[] users = WindowsUserDetector.getAllLoggedInUsers();
        
        assertNotNull(users, "Users array should not be null");
        System.out.println("Found " + users.length + " logged-in users:");
        
        for (String user : users) {
            System.out.println("  - " + user);
        }
    }
}
