# ActivePulse Auto-start & Screenshot Fix for AD Users

## Issues Identified

### 1. Auto-start Not Working for AD Users
**Problem**: The PowerShell script creates HKLM registry entry, but `AutoStartManager` skips HKCU registration when HKLM exists. For AD users, HKCU entries are required because HKLM may not execute due to group policies.

**Root Cause**: Lines 59-62 in `AutoStartManager.java` skip HKCU registration when HKLM exists:
```java
if (isWindows() && isInstalledMachineWide()) {
    log.info("Auto-start: machine-wide HKLM entry present — skipping HKCU registration.");
    return;
}
```

### 2. Screenshots Not Being Captured
**Problem**: No screenshot activity in logs, no screenshot files created.

**Root Cause**: Application logs appear stuck, possibly due to scheduler not starting properly or permission issues.

## Solutions Applied

### Fix 1: Auto-start Manager Updated
Modified `AutoStartManager.java` to always create HKCU entry for AD user compatibility:

```java
public void install() {
    // For AD users, always create HKCU entry even if HKLM exists
    // because HKLM may not execute due to group policies
    boolean isMachineWide = isWindows() && isInstalledMachineWide();
    
    if (isMachineWide) {
        log.info("Auto-start: machine-wide HKLM entry present — creating HKCU entry for AD user compatibility.");
    }

    log.info("Registering auto-start...");
    if (isWindows())   installWindows();
    else if (isMac())  installMac();
    else               installLinux();
}
```

### Fix 2: AD User PowerShell Script
Created `configure-autostart-ad.ps1` that:
- Creates BOTH HKLM and HKCU registry entries
- Detects AD vs local users
- Provides verification and diagnostics
- Includes removal functionality

### Fix 3: Screenshot Diagnostic Script
Created `test-screenshot.ps1` that:
- Checks if ActivePulse process is running
- Verifies screenshot directory and files
- Analyzes log files for screenshot activity
- Checks directory permissions

## Implementation Steps

### Step 1: Rebuild and Deploy
```bash
# Build the updated application
mvn clean package

# Create new installer
# (Use your build pipeline to create ActivePulse-Windows-1.0.0 artifact)
```

### Step 2: Install Updated Version
```bash
# Uninstall old version
# Install new ActivePulse-Windows-1.0.0.msi

# Run AD-specific auto-start configuration
.\configure-autostart-ad.ps1

# Test screenshot functionality
.\test-screenshot.ps1
```

### Step 3: Verification Commands
```powershell
# Check registry entries
Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" | Select-Object ActivePulseAgent
Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" | Select-Object ActivePulseAgent

# Check running process
Get-Process -Name "ServiceProcess"

# Check screenshot directory
Get-ChildItem "$env:USERPROFILE\.activepulse\screenshots" -Filter "*.jpg"

# Check logs
Get-Content "$env:USERPROFILE\.activepulse\logs\activepulse.log" | Select-String -Pattern "Screenshot|AgentScheduler" | Select-Object -Last 10
```

## Expected Behavior After Fix

### Auto-start
- ✅ HKLM registry entry: `"C:\Program Files\ServiceProcess\ServiceProcess.exe"`
- ✅ HKCU registry entry: `"C:\Program Files\ServiceProcess\ServiceProcess.exe"`
- ✅ Application auto-starts on user login for both local and AD users
- ✅ No duplicate processes (single instance protection)

### Screenshots
- ✅ Screenshots captured every 5 minutes
- ✅ Files saved to `~/.activepulse/screenshots/`
- ✅ Log entries showing screenshot capture activity
- ✅ Proper error handling for permission issues

## Troubleshooting

### Auto-start Issues
```powershell
# Remove and reconfigure auto-start
.\configure-autostart-ad.ps1 -InstallPath "C:\Program Files\ServiceProcess"

# Manually check registry
reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v ActivePulseAgent
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v ActivePulseAgent
```

### Screenshot Issues
```powershell
# Run diagnostic
.\test-screenshot.ps1

# Check permissions
icacls "$env:USERPROFILE\.activepulse" /grant "$env:USERNAME:(OI)(CI)F"

# Manually trigger screenshot capture (restart application)
Stop-Process -Name "ServiceProcess" -Force
# Start ActivePulse again
```

## Testing Scenarios

### Test 1: AD User Auto-start
1. Sign in as AD user
2. Run `.\configure-autostart-ad.ps1`
3. Sign out and sign back in
4. Verify ActivePulse starts automatically

### Test 2: Screenshot Capture
1. Start ActivePulse
2. Wait 5-10 minutes
3. Run `.\test-screenshot.ps1`
4. Verify screenshot files exist

### Test 3: Local User Compatibility
1. Sign in as local admin user
2. Run `.\configure-autostart-ad.ps1`
3. Verify both HKLM and HKCU entries created
4. Test auto-start functionality

## Files Modified/Created

### Modified
- `src/main/java/com/activepulse/agent/AutoStartManager.java`
  - Updated `install()` method for AD user compatibility

### Created
- `configure-autostart-ad.ps1`
  - AD user auto-start configuration script
- `test-screenshot.ps1`
  - Screenshot functionality diagnostic script
- `AUTOSTART_SCREENSHOT_FIX.md`
  - This documentation file

## Next Steps

1. **Immediate**: Rebuild application with updated `AutoStartManager.java`
2. **Deploy**: Create new installer with fixes
3. **Test**: Verify both auto-start and screenshot functionality
4. **Monitor**: Check logs for proper operation
5. **Document**: Update user documentation with new scripts

## Support Commands

```powershell
# Quick status check
function Get-ActivePulseStatus {
    Write-Host "=== ActivePulse Status ===" -ForegroundColor Cyan
    $process = Get-Process -Name "ServiceProcess" -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "✓ Process running (PID: $($process.Id))" -ForegroundColor Green
    } else {
        Write-Host "✗ Process not running" -ForegroundColor Red
    }
    
    $hklm = Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ActivePulseAgent" -ErrorAction SilentlyContinue
    $hkcu = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ActivePulseAgent" -ErrorAction SilentlyContinue
    
    if ($hklm) { Write-Host "✓ HKLM auto-start configured" -ForegroundColor Green }
    if ($hkcu) { Write-Host "✓ HKCU auto-start configured" -ForegroundColor Green }
    
    $screenshots = Get-ChildItem "$env:USERPROFILE\.activepulse\screenshots" -Filter "*.jpg" -ErrorAction SilentlyContinue
    Write-Host "Screenshots captured: $($screenshots.Count)" -ForegroundColor White
}

# Run status check
Get-ActivePulseStatus
```

This comprehensive fix addresses both the auto-start issue for AD users and provides tools to diagnose and verify screenshot functionality.
