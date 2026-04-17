# ActivePulse Deployment Guide - Step by Step

## Complete Deployment Workflow

This guide walks you through the entire process from code changes to running the application on your Windows machine with AD user support.

---

## 📋 Prerequisites

- **Git installed** and configured
- **GitHub account** with push access to the repository
- **Windows machine** (Windows 10/11)
- **Administrator access** on the target machine
- **PowerShell** (comes with Windows)

---

## 🚀 Step 1: Push Code to GitHub

### 1.1 Commit Your Changes
```bash
# Navigate to project directory
cd d:\active-pulse\ActivePulseExe

# Check git status
git status

# Add all changes
git add .

# Commit with descriptive message
git commit -m "Fix AD user auto-start and screenshot functionality"
```

### 1.2 Push to GitHub
```bash
# Push to main branch
git push origin main
```

### 1.3 Verify Push
- Go to your GitHub repository
- Check that your commit appears in the commit history
- Verify the "Actions" tab shows the workflow running

---

## 🔨 Step 2: GitHub Actions Build

### 2.1 Monitor Build Progress
1. Go to your GitHub repository
2. Click on the **"Actions"** tab
3. You'll see the **"Build Installers"** workflow running
4. Click on the workflow to view details

### 2.2 Build Process (Takes 5-10 minutes)
The workflow will:
- **Build Windows MSI**: Creates `ServiceProcess-1.0.0.msi`
- **Build Linux DEB**: Creates Linux installer
- **Build Mac ARM64**: Creates Mac installer for Apple Silicon
- **Build Mac Intel**: Creates Mac installer for Intel Macs
- **Upload Artifacts**: Makes installers available for download

### 2.3 Wait for Completion
- Look for ✅ green checkmark next to the workflow
- The workflow must complete successfully before downloading

---

## 📥 Step 3: Download Windows Installer

### 3.1 Access Artifacts
1. In GitHub Actions, click on the completed workflow run
2. Scroll down to the **"Artifacts"** section
3. Find **"ActivePulse-Windows-1.0.0"**
4. Click the download button (⬇️)

### 3.2 Extract Downloaded File
```bash
# The downloaded file is a ZIP containing:
# - ServiceProcess-1.0.0.msi (installer)
# - configure-autostart.ps1 (auto-start script)

# Extract to a folder (e.g., Downloads\ActivePulse)
# You can extract using Windows Explorer or PowerShell:
Expand-Archive -Path "ActivePulse-Windows-1.0.0.zip" -DestinationPath "C:\Downloads\ActivePulse"
```

### 3.3 Verify Files
Ensure you have:
- `ServiceProcess-1.0.0.msi` (~50-100 MB)
- `configure-autostart.ps1` (auto-start configuration script)

---

## 📦 Step 4: Install ActivePulse

### 4.1 Run MSI Installer
```bash
# Navigate to extracted folder
cd C:\Downloads\ActivePulse

# Run installer as Administrator
# Right-click on ServiceProcess-1.0.0.msi → "Run as Administrator"
# OR use PowerShell:
Start-Process msiexec.exe -ArgumentList "/i ServiceProcess-1.0.0.msi /qn ALLUSERS=1" -Verb RunAs
```

### 4.2 Installation Wizard
1. Click **"Next"** through the wizard
2. Accept default installation path: `C:\Program Files\ServiceProcess`
3. Allow UAC prompt when prompted
4. Wait for installation to complete
5. Click **"Finish"**

### 4.3 Verify Installation
```bash
# Check if files exist
Test-Path "C:\Program Files\ServiceProcess\ServiceProcess.exe"
Test-Path "C:\Program Files\ServiceProcess\app"
Test-Path "C:\Program Files\ServiceProcess\runtime"

# Expected output: True
```

---

## ⚙️ Step 5: Configure Auto-start (CRITICAL for AD Users)

### 5.1 Use New AD-Specific Script
```bash
# Navigate to installation folder
cd C:\Program Files\ServiceProcess

# OR navigate to where you extracted the artifacts
cd C:\Downloads\ActivePulse

# Run the AD-specific auto-start configuration script
# Right-click → "Run as Administrator"
.\configure-autostart-ad.ps1
```

### 5.2 What the Script Does
- ✅ Creates **HKLM** registry entry (machine-wide)
- ✅ Creates **HKCU** registry entry (per-user for AD users)
- ✅ Detects if you're an AD user
- ✅ Verifies both entries are created
- ✅ Provides diagnostic output

### 5.3 Expected Output
```
Configuring ActivePulse auto-start for AD user...
Install Path: C:\Program Files\ServiceProcess
Executable found: C:\Program Files\ServiceProcess\ServiceProcess.exe

Creating HKLM registry entry...
✓ HKLM entry created successfully

Creating HKCU registry entry...
✓ HKCU entry created successfully

Verifying registry entries...
✓ HKLM entry verified: "C:\Program Files\ServiceProcess\ServiceProcess.exe"
✓ HKCU entry verified: "C:\Program Files\ServiceProcess\ServiceProcess.exe"

Current user information:
  User: YOUR_USERNAME
  Domain: YOUR_DOMAIN
  Computer: YOUR_COMPUTER
  Status: Active Directory user detected
  → HKCU entry will be used for auto-start

✓ Auto-start configuration completed successfully!
  Sign out and sign back in to test auto-start
```

---

## 🧪 Step 6: Test Auto-start Functionality

### 6.1 Test Immediate Start
```bash
# Start ActivePulse manually first to test
Start-Process "C:\Program Files\ServiceProcess\ServiceProcess.exe"

# Check if process is running
Get-Process -Name "ServiceProcess"
```

### 6.2 Verify Application Running
```bash
# Check system tray for ActivePulse icon
# Should see a tray icon in the system notification area

# Check logs
Get-Content "$env:USERPROFILE\.activepulse\logs\activepulse.log" -Tail 20
```

### 6.3 Test Auto-start
1. **Sign out** of Windows
2. **Sign back in** as the same user
3. ActivePulse should start automatically
4. Check system tray for the icon

### 6.4 Verify Auto-start
```bash
# Check if process is running after sign-in
Get-Process -Name "ServiceProcess"

# Check registry entries
reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v ActivePulseAgent
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v ActivePulseAgent
```

---

## 📸 Step 7: Test Screenshot Functionality

### 7.1 Run Screenshot Diagnostic
```bash
# Navigate to scripts folder (or use the one you created)
cd d:\active-pulse\ActivePulseExe

# Run diagnostic script
.\test-screenshot.ps1
```

### 7.2 Expected Output
```
Testing ActivePulse screenshot functionality...
✓ ActivePulse process is running (PID: 12345)

Checking screenshot directory: C:\Users\YOUR_USERNAME\.activepulse\screenshots
✓ Screenshot directory exists
Found 0 screenshot files yet.
Screenshots are taken every 5 minutes. Please wait and check again.

Checking log file: C:\Users\YOUR_USERNAME\.activepulse\logs\activepulse.log
Recent screenshot activity in logs:
  Screenshot captured → scr_20260417_123456.jpg (512 KB)
```

### 7.3 Wait for Screenshots
- Screenshots are captured every **5 minutes**
- Wait at least 5-10 minutes after starting
- Check screenshot directory again:
```bash
Get-ChildItem "$env:USERPROFILE\.activepulse\screenshots" -Filter "*.jpg"
```

### 7.4 Manual Screenshot Test
If screenshots don't appear after 10 minutes:
```bash
# Restart ActivePulse
Stop-Process -Name "ServiceProcess" -Force
Start-Process "C:\Program Files\ServiceProcess\ServiceProcess.exe"

# Check logs for errors
Get-Content "$env:USERPROFILE\.activepulse\logs\activepulse.log" -Tail 50 | Select-String -Pattern "error|Error|ERROR"
```

---

## 🔍 Step 8: Verify Complete Setup

### 8.1 Comprehensive Status Check
```powershell
# Run this complete status check
function Get-ActivePulseCompleteStatus {
    Write-Host "=== ActivePulse Complete Status ===" -ForegroundColor Cyan
    
    # 1. Process Status
    Write-Host "`n[1/6] Process Status" -ForegroundColor Yellow
    $process = Get-Process -Name "ServiceProcess" -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "  ✓ Process running (PID: $($process.Id))" -ForegroundColor Green
        Write-Host "  ✓ Memory: $([math]::Round($process.WorkingSet64 / 1MB, 2)) MB" -ForegroundColor Gray
    } else {
        Write-Host "  ✗ Process not running" -ForegroundColor Red
    }
    
    # 2. Registry Entries
    Write-Host "`n[2/6] Registry Entries" -ForegroundColor Yellow
    $hklm = Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ActivePulseAgent" -ErrorAction SilentlyContinue
    $hkcu = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ActivePulseAgent" -ErrorAction SilentlyContinue
    if ($hklm) { Write-Host "  ✓ HKLM auto-start configured" -ForegroundColor Green }
    else { Write-Host "  ✗ HKLM auto-start NOT configured" -ForegroundColor Red }
    if ($hkcu) { Write-Host "  ✓ HKCU auto-start configured" -ForegroundColor Green }
    else { Write-Host "  ✗ HKCU auto-start NOT configured" -ForegroundColor Red }
    
    # 3. Installation Files
    Write-Host "`n[3/6] Installation Files" -ForegroundColor Yellow
    $exe = Test-Path "C:\Program Files\ServiceProcess\ServiceProcess.exe"
    $app = Test-Path "C:\Program Files\ServiceProcess\app"
    $runtime = Test-Path "C:\Program Files\ServiceProcess\runtime"
    if ($exe) { Write-Host "  ✓ Executable exists" -ForegroundColor Green }
    else { Write-Host "  ✗ Executable missing" -ForegroundColor Red }
    if ($app) { Write-Host "  ✓ App folder exists" -ForegroundColor Green }
    else { Write-Host "  ✗ App folder missing" -ForegroundColor Red }
    if ($runtime) { Write-Host "  ✓ Runtime folder exists" -ForegroundColor Green }
    else { Write-Host "  ✗ Runtime folder missing" -ForegroundColor Red }
    
    # 4. Data Directories
    Write-Host "`n[4/6] Data Directories" -ForegroundColor Yellow
    $logs = Test-Path "$env:USERPROFILE\.activepulse\logs"
    $screenshots = Test-Path "$env:USERPROFILE\.activepulse\screenshots"
    $db = Test-Path "$env:USERPROFILE\.activepulse\activepulse.db"
    if ($logs) { Write-Host "  ✓ Logs directory exists" -ForegroundColor Green }
    else { Write-Host "  ✗ Logs directory missing" -ForegroundColor Red }
    if ($screenshots) { Write-Host "  ✓ Screenshots directory exists" -ForegroundColor Green }
    else { Write-Host "  ✗ Screenshots directory missing" -ForegroundColor Red }
    if ($db) { Write-Host "  ✓ Database exists" -ForegroundColor Green }
    else { Write-Host "  ✗ Database missing" -ForegroundColor Red }
    
    # 5. Screenshots
    Write-Host "`n[5/6] Screenshots" -ForegroundColor Yellow
    $screenshotFiles = Get-ChildItem "$env:USERPROFILE\.activepulse\screenshots" -Filter "*.jpg" -ErrorAction SilentlyContinue
    Write-Host "  Screenshot count: $($screenshotFiles.Count)" -ForegroundColor White
    if ($screenshotFiles.Count -gt 0) {
        Write-Host "  Latest screenshot: $($screenshotFiles[0].LastWriteTime)" -ForegroundColor Gray
    }
    
    # 6. Recent Logs
    Write-Host "`n[6/6] Recent Log Activity" -ForegroundColor Yellow
    $logFile = "$env:USERPROFILE\.activepulse\logs\activepulse.log"
    if (Test-Path $logFile) {
        $recentLogs = Get-Content $logFile -Tail 5
        foreach ($log in $recentLogs) {
            Write-Host "  $log" -ForegroundColor Gray
        }
    } else {
        Write-Host "  ✗ Log file not found" -ForegroundColor Red
    }
    
    Write-Host "`n=== Status Check Complete ===" -ForegroundColor Cyan
}

# Run the status check
Get-ActivePulseCompleteStatus
```

---

## 🐛 Troubleshooting

### Issue: Auto-start Not Working
```powershell
# Remove and reconfigure auto-start
cd C:\Downloads\ActivePulse
.\configure-autostart-ad.ps1

# Manually check registry
reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v ActivePulseAgent
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v ActivePulseAgent

# Manually add HKCU entry if missing
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v ActivePulseAgent /t REG_SZ /d "\"C:\Program Files\ServiceProcess\ServiceProcess.exe\"" /f
```

### Issue: Screenshots Not Capturing
```powershell
# Run diagnostic
.\test-screenshot.ps1

# Check permissions
icacls "$env:USERPROFILE\.activepulse" /grant "$env:USERNAME:(OI)(CI)F"

# Restart application
Stop-Process -Name "ServiceProcess" -Force
Start-Process "C:\Program Files\ServiceProcess\ServiceProcess.exe"

# Check logs for errors
Get-Content "$env:USERPROFILE\.activepulse\logs\activepulse.log" -Tail 50
```

### Issue: Process Not Starting
```powershell
# Check if executable exists
Test-Path "C:\Program Files\ServiceProcess\ServiceProcess.exe"

# Try running manually
Start-Process "C:\Program Files\ServiceProcess\ServiceProcess.exe" -NoNewWindow -Wait

# Check for error logs
Get-Content "$env:USERPROFILE\.activepulse\logs\activepulse.log" -Tail 100
```

---

## 📊 Expected Timeline

| Step | Time Required |
|------|---------------|
| Push to GitHub | 1-2 minutes |
| GitHub Actions Build | 5-10 minutes |
| Download Artifacts | 2-5 minutes |
| Install MSI | 2-3 minutes |
| Configure Auto-start | 1 minute |
| Test Auto-start | 5 minutes |
| Test Screenshots | 10 minutes |
| **Total** | **~30-40 minutes** |

---

## ✅ Success Criteria

You have successfully deployed ActivePulse when:

- ✅ Application starts automatically on user login
- ✅ Process visible in Task Manager as "ServiceProcess.exe"
- ✅ System tray icon appears
- ✅ Screenshots captured every 5 minutes
- ✅ Log files show activity
- ✅ Database file created and growing
- ✅ Both HKLM and HKCU registry entries exist

---

## 🔄 Quick Reference Commands

```powershell
# Start ActivePulse
Start-Process "C:\Program Files\ServiceProcess\ServiceProcess.exe"

# Stop ActivePulse
Stop-Process -Name "ServiceProcess" -Force

# Check running status
Get-Process -Name "ServiceProcess"

# View logs
Get-Content "$env:USERPROFILE\.activepulse\logs\activepulse.log" -Tail 50

# Check screenshots
Get-ChildItem "$env:USERPROFILE\.activepulse\screenshots" -Filter "*.jpg"

# Configure auto-start
.\configure-autostart-ad.ps1

# Run diagnostics
.\test-screenshot.ps1

# Complete status check
Get-ActivePulseCompleteStatus
```

---

## 📞 Support

If you encounter issues:

1. Check the logs: `$env:USERPROFILE\.activepulse\logs\activepulse.log`
2. Run the diagnostic scripts
3. Verify registry entries
4. Ensure you have Administrator permissions
5. Check that Windows Defender is not blocking the application

---

## 🎯 Next Steps After Deployment

1. **Monitor** the application for 24 hours
2. **Verify** data is syncing to your server
3. **Check** screenshot capture regularly
4. **Review** logs for any errors
5. **Update** to new versions when released

---

**Deployment Complete!** 🎉

Your ActivePulse application is now running with AD user auto-start support and screenshot functionality enabled.
