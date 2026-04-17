# Test screenshot capture functionality for ActivePulse
# This script helps diagnose screenshot capture issues

$ErrorActionPreference = "Stop"

try {
    Write-Host "Testing ActivePulse screenshot functionality..." -ForegroundColor Green
    
    # Check if ActivePulse is running
    $process = Get-Process -Name "ServiceProcess" -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "ActivePulse process (ServiceProcess.exe) is not running!" -ForegroundColor Red
        Write-Host "Please start ActivePulse first." -ForegroundColor Yellow
        exit 1
    }
    
    Write-Host "✓ ActivePulse process is running (PID: $($process.Id))" -ForegroundColor Green
    
    # Check screenshot directory
    $screenshotDir = Join-Path $env:USERPROFILE ".activepulse\screenshots"
    Write-Host "`nChecking screenshot directory: $screenshotDir" -ForegroundColor Cyan
    
    if (Test-Path $screenshotDir) {
        $files = Get-ChildItem -Path $screenshotDir -Filter "*.jpg" | Sort-Object LastWriteTime -Descending
        Write-Host "✓ Screenshot directory exists" -ForegroundColor Green
        
        if ($files.Count -gt 0) {
            Write-Host "Found $($files.Count) screenshot files:" -ForegroundColor White
            foreach ($file in $files | Select-Object -First 5) {
                $sizeKB = [math]::Round($file.Length / 1KB, 2)
                Write-Host "  - $($file.Name) ($sizeKB KB, $($file.LastWriteTime))" -ForegroundColor Gray
            }
        } else {
            Write-Host "No screenshot files found yet." -ForegroundColor Yellow
            Write-Host "Screenshots are taken every 5 minutes. Please wait and check again." -ForegroundColor Yellow
        }
    } else {
        Write-Host "✗ Screenshot directory does not exist" -ForegroundColor Red
        Write-Host "This may indicate a permissions issue or the application hasn't started properly." -ForegroundColor Red
    }
    
    # Check log file for screenshot activity
    $logFile = Join-Path $env:USERPROFILE ".activepulse\logs\activepulse.log"
    Write-Host "`nChecking log file: $logFile" -ForegroundColor Cyan
    
    if (Test-Path $logFile) {
        $screenshotLogs = Select-String -Path $logFile -Pattern "Screenshot" | Select-Object -Last 10
        if ($screenshotLogs.Count -gt 0) {
            Write-Host "Recent screenshot activity in logs:" -ForegroundColor White
            foreach ($log in $screenshotLogs) {
                Write-Host "  $($log.Line)" -ForegroundColor Gray
            }
        } else {
            Write-Host "No screenshot activity found in logs." -ForegroundColor Yellow
        }
        
        # Check for scheduler startup
        $schedulerLogs = Select-String -Path $logFile -Pattern "AgentScheduler started|ScreenshotJob" | Select-Object -Last 5
        if ($schedulerLogs.Count -gt 0) {
            Write-Host "`nScheduler activity:" -ForegroundColor White
            foreach ($log in $schedulerLogs) {
                Write-Host "  $($log.Line)" -ForegroundColor Gray
            }
        }
    } else {
        Write-Host "✗ Log file not found" -ForegroundColor Red
    }
    
    # Check permissions
    Write-Host "`nChecking directory permissions..." -ForegroundColor Cyan
    $baseDir = Join-Path $env:USERPROFILE ".activepulse"
    if (Test-Path $baseDir) {
        $acl = Get-Acl $baseDir
        $user = $env:USERNAME
        $access = $acl.Access | Where-Object { $_.IdentityReference -like "*$user*" -and $_.FileSystemRights -like "*Write*" }
        if ($access) {
            Write-Host "✓ User has write permissions to .activepulse directory" -ForegroundColor Green
        } else {
            Write-Host "✗ User may not have write permissions to .activepulse directory" -ForegroundColor Red
        }
    }
    
    Write-Host "`n✓ Screenshot diagnostic completed" -ForegroundColor Green
    
} catch {
    Write-Host "`nError: $_" -ForegroundColor Red
    exit 1
}
