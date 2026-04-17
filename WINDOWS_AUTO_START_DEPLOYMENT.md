# ActivePulse Windows Auto-Start Deployment Guide

## Overview

This guide explains how to deploy ActivePulse with machine-wide auto-start in Active Directory (AD) environments. The solution uses WiX-based installer configuration, which sets up auto-start during installation (not at runtime), ensuring it works for all users without requiring admin privileges at runtime.

## Why This Approach Works in AD Environments

### Problem with Runtime Registry Writes
- **HKCU (HKEY_CURRENT_USER)**: Only applies to the installing user (Admin), not normal AD users
- **HKLM (HKEY_LOCAL_MACHINE)**: Requires admin privileges; normal users cannot write to it at runtime
- **Java Runtime Issues**: Writing to registry from Java code fails for normal users due to UAC and permission restrictions

### Solution: Installer-Level Configuration
- **Installer Runs as Admin**: The MSI installer is installed by an admin, so it has full system access
- **WiX Executes at Install Time**: Registry entries and Task Scheduler tasks are created during installation when admin privileges are available
- **Machine-Wide Scope**: HKLM registry entries and Task Scheduler tasks apply to ALL users on the machine
- **No Runtime Privileges Needed**: Once installed, the application auto-starts for any user without requiring additional permissions

## Two Approaches

### Approach 1: Registry-Based (HKLM) - RECOMMENDED for jpackage
**File**: `wix-overrides.wxs`

**How it Works**:
- Creates registry entry: `HKLM\Software\Microsoft\Windows\CurrentVersion\Run`
- Key Name: `ActivePulseAgent`
- Value: `"C:\Program Files\ServiceProcess\ServiceProcess.exe"`

**Pros**:
- Simple and straightforward
- Easy to verify and debug
- Widely understood by IT administrators
- Minimal overhead
- **Works with jpackage** (no external dependencies)
- Includes common startup folder shortcut for redundancy

**Cons**:
- Can be disabled by registry cleaners
- Less flexible for complex scenarios
- No execution context control

**Best For**:
- **Most deployments** (RECOMMENDED)
- Simple deployments
- Environments without strict Group Policy restrictions
- When using jpackage for MSI creation
- When simplicity is preferred over flexibility

### Approach 2: Task Scheduler (Enterprise Alternative)
**File**: `wix-overrides-task-scheduler.wxs`

**How it Works**:
- Creates Windows Task Scheduler task named `ActivePulseAgent`
- Trigger: Runs when any user logs on
- Principal: Runs as the logged-in user (not SYSTEM)
- Settings: Configured for reliability in enterprise environments

**Pros**:
- **Enterprise-Standard**: Task Scheduler is the preferred method for enterprise software
- **Group Policy Friendly**: Can be managed, enabled/disabled via Group Policy
- **Better Security**: Runs as logged-in user, not SYSTEM
- **More Robust**: Survives registry cleanups and system optimizations
- **Conditional Execution**: Can set conditions (network available, power source, etc.)
- **Audit Trail**: Task execution is logged in Windows Event Viewer
- **Multiple Instance Control**: Prevents duplicate instances from running

**Cons**:
- **Requires WixUtilExtension** - jpackage doesn't support this extension
- Requires manual WiX compilation (candle/light) outside of jpackage
- More complex build process
- Slightly more complex configuration

**Best For**:
- Enterprise AD environments with strict Group Policy requirements
- When Task Scheduler is mandated by IT policy
- When you can use direct WiX Toolset compilation instead of jpackage

**IMPORTANT**: The Task Scheduler approach requires manual WiX compilation because jpackage doesn't support the WixUtilExtension. To use this approach:
1. Build base MSI with jpackage (without auto-start)
2. Extract MSI contents
3. Compile with WiX Toolset: `candle -ext WixUtilExtension wix-overrides-task-scheduler.wxs`
4. Link with: `light -ext WixUtilExtension -out final.msi`

## Deployment Instructions

### Prerequisites

1. **WiX Toolset v3.11** must be installed on the build machine
   - Download from: https://wixtoolset.org/releases/
   - Or install via Chocolatey: `choco install wixtoolset`

2. **Java JDK 21** for building the application

3. **Maven** for building the JAR

### Building the Installer

#### Option A: Using the Build Script (Recommended)
```batch
build-windows-installer.bat
```
This script:
- Checks for WiX Toolset
- Builds the JAR with Maven
- Prompts for auto-start method choice
- Creates the MSI installer

#### Option B: Manual Build
```batch
# Build JAR
mvn clean package

# Prepare JAR
copy target\active-pulse-0.0.1-SNAPSHOT.jar target\app.jar

# Build MSI with Registry approach
jpackage --name ServiceProcess --input target --main-jar app.jar --type msi --dest dist --icon src\main\resources\tray-icon.ico --win-console --win-shortcut --win-menu --win-menu-group "ServiceProcess" --win-dir-chooser --resource-dir . --wx-filename wix-overrides.wxs

# OR build MSI with Task Scheduler approach
jpackage --name ServiceProcess --input target --main-jar app.jar --type msi --dest dist --icon src\main\resources\tray-icon.ico --win-console --win-shortcut --win-menu --win-menu-group "ServiceProcess" --win-dir-chooser --resource-dir . --wx-filename wix-overrides-task-scheduler.wxs
```

### Deployment in AD Environment

#### Method 1: Manual Installation
1. Log in as Administrator
2. Run the MSI installer: `ServiceProcess-*.msi`
3. Install to: `C:\Program Files\ServiceProcess`
4. Complete the installation wizard
5. Log out and log in as a normal AD user to verify auto-start

#### Method 2: Group Policy Deployment (Recommended for Enterprise)
1. Copy the MSI to a network share accessible by all computers
2. Open Group Policy Management Console (GPMC)
3. Create or edit a Group Policy Object (GPO)
4. Navigate to: `Computer Configuration > Policies > Software Settings > Software Installation`
5. Right-click > New > Package
6. Select the MSI file from the network share
7. Choose "Assigned" deployment method
8. Link the GPO to the appropriate OU (Organizational Unit)
9. The application will be installed on next reboot or Group Policy refresh

#### Method 3: SCCM/Intune Deployment
1. Upload the MSI to SCCM/Intune
2. Create an application deployment
3. Target the appropriate device/user collections
4. Deploy with administrative credentials
5. The installer will run with admin privileges and configure auto-start

## Verification Steps

### After Installation

#### For Registry-Based Approach
```powershell
# Check if registry entry exists
Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" | Select-Object ActivePulseAgent

# Expected output:
# ActivePulseAgent : "C:\Program Files\ServiceProcess\ServiceProcess.exe"
```

#### For Task Scheduler Approach
```powershell
# Check if task exists
Get-ScheduledTask -TaskName "ActivePulseAgent"

# Expected output: Task details with Logon trigger

# Verify task is enabled
(Get-ScheduledTask -TaskName "ActivePulseAgent").State
# Expected: Ready

# Check task trigger
(Get-ScheduledTask -TaskName "ActivePulseAgent").Triggers
# Expected: LogonTrigger
```

### Testing Auto-Start
1. Log out of the current session
2. Log in as a different AD user (or the same user)
3. Wait 10-30 seconds
4. Check if ActivePulse is running:
   ```powershell
   Get-Process | Where-Object {$_.ProcessName -like "*ServiceProcess*"}
   ```
5. Check system tray for the ActivePulse icon

## Troubleshooting

### Issue: Auto-Start Not Working

#### Registry-Based
```powershell
# Check if registry entry exists
Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run"

# If missing, reinstall the MSI as administrator
```

#### Task Scheduler-Based
```powershell
# Check task status
Get-ScheduledTask -TaskName "ActivePulseAgent"

# Check task history (Event Viewer)
# Navigate to: Event Viewer > Applications and Services Logs > Microsoft > Windows > TaskScheduler > Operational
```

### Issue: Permission Denied
- Ensure the MSI was installed with admin privileges
- Reinstall using "Run as Administrator"

### Issue: Application Not Starting
- Check if the executable path is correct: `C:\Program Files\ServiceProcess\ServiceProcess.exe`
- Check Windows Event Viewer for application errors
- Verify Java runtime is available on the system

### Issue: Multiple Instances Running
- Task Scheduler approach should prevent this with `MultipleInstances=IgnoreNew`
- If using registry approach, add mutex logic to the application

## Best Practices for Enterprise Deployment

### 1. Use Task Scheduler Approach
- Recommended for all enterprise AD deployments
- Better integration with Group Policy
- More robust and maintainable

### 2. Test in Non-Production First
- Deploy to a test OU or pilot group
- Verify auto-start works for different user types
- Test with various AD user permission levels

### 3. Monitor Deployment
- Use Group Policy results (GPResult) to verify deployment
- Check Event Viewer for installation errors
- Monitor application startup logs

### 4. Version Management
- Include version number in MSI filename
- Use upgrade codes in WiX for seamless upgrades
- Test upgrade scenarios before deployment

### 5. Uninstallation
- The MSI will automatically remove the registry entry or Task Scheduler task
- Test uninstallation to ensure clean removal
- Verify no orphaned entries remain

### 6. Documentation
- Document the chosen auto-start method
- Include deployment instructions in IT knowledge base
- Provide troubleshooting guide for support teams

### 7. Security Considerations
- The application runs as the logged-in user, not SYSTEM (Task Scheduler approach)
- Ensure the application has appropriate file permissions
- Consider signing the MSI with a code signing certificate

### 8. Network Requirements
- Ensure the application can reach required servers
- Configure firewall rules if needed
- Test with both wired and wireless connections

## Group Policy Management

### Enable/Disable Auto-Start via GPO

#### For Registry-Based Approach
```powershell
# Create a GPO to remove the registry entry
# Path: Computer Configuration > Preferences > Windows Settings > Registry
# Action: Delete
# Hive: HKEY_LOCAL_MACHINE
# Key Path: Software\Microsoft\Windows\CurrentVersion\Run
# Value Name: ActivePulseAgent
```

#### For Task Scheduler-Based Approach
```powershell
# Create a GPO to disable the task
# Path: Computer Configuration > Preferences > Control Panel Settings > Scheduled Tasks
# Action: Disable
# Task Name: ActivePulseAgent
```

### Force Application Removal via GPO
```powershell
# Navigate to: Computer Configuration > Policies > Software Settings > Software Installation
# Right-click the package > All Tasks > Remove
# Choose "Immediately uninstall the software from users and computers"
```

## File Reference

- **Registry Approach**: `wix-overrides.wxs`
- **Task Scheduler Approach**: `wix-overrides-task-scheduler.wxs`
- **Build Script**: `build-windows-installer.bat`
- **GitHub Workflow**: `.github/workflows/build-installers.yml`

## Summary

| Aspect | Registry (HKLM) | Task Scheduler |
|--------|----------------|----------------|
| **Complexity** | Simple | Moderate |
| **Enterprise Ready** | Good | Excellent |
| **Group Policy** | Limited | Full Support |
| **Robustness** | Medium | High |
| **Debugging** | Easy | Moderate |
| **Security** | Standard | Better |
| **Recommendation** | Simple cases | **Enterprise (Recommended)** |

## Support

For issues or questions:
1. Check the troubleshooting section above
2. Review Windows Event Viewer logs
3. Verify WiX Toolset installation
4. Test with a fresh installation on a clean machine

## Version History

- **v1.0** - Initial implementation with both Registry and Task Scheduler approaches
- **v1.1** - Added comprehensive deployment guide and troubleshooting
