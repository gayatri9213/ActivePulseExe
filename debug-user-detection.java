import java.io.*;

public class debug-user-detection {
    public static void main(String[] args) {
        System.out.println("=== Windows User Detection Debug ===");
        
        // Test environment variables
        System.out.println("\n1. Environment Variables:");
        String[] envVars = {"USERNAME", "USER", "LOGNAME"};
        for (String envVar : envVars) {
            String value = System.getenv(envVar);
            System.out.println("  " + envVar + " = " + value);
        }
        
        // Test WMI user query
        System.out.println("\n2. WMI User Query:");
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "computersystem", "get", "username", "/value");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            System.out.println("  Output: " + output.toString());
            
            String result = output.toString();
            for (String line : result.split("\n")) {
                if (line.startsWith("Username=")) {
                    String username = line.substring("Username=".length()).trim();
                    System.out.println("  Parsed username: " + username);
                    if (!username.isEmpty() && !username.equals("Username")) {
                        if (username.contains("\\")) {
                            String justUser = username.substring(username.lastIndexOf("\\") + 1);
                            System.out.println("  After removing domain: " + justUser);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }
        
        // Test query user command
        System.out.println("\n3. Query User Command:");
        try {
            ProcessBuilder pb = new ProcessBuilder("query", "user");
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            process.waitFor();
            System.out.println("  Output: " + output.toString());
            
            String result = output.toString();
            for (String line : result.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("USERNAME") && !line.startsWith("----")) {
                    String[] parts = line.split("\\s+");
                    System.out.println("  Line parts: " + java.util.Arrays.toString(parts));
                    if (parts.length >= 2) {
                        String username = parts[0];
                        System.out.println("  Raw username: " + username);
                        
                        if (username.startsWith(">")) {
                            if (parts.length > 1) {
                                username = parts[1];
                                System.out.println("  After removing '>': " + username);
                            }
                        }
                        
                        if (line.toLowerCase().contains("active")) {
                            System.out.println("  Active user found: " + username);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }
        
        // Test Java property
        System.out.println("\n4. Java System Property:");
        System.out.println("  user.name = " + System.getProperty("user.name"));
        
        System.out.println("\n=== End Debug ===");
    }
}
