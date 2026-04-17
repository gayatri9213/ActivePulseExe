# Configure ActivePulse auto-start for Active Directory users
# This script creates BOTH HKLM and HKCU entries to ensure compatibility
# with AD user group policies that may block HKLM execution

param(
    [Parameter(Mandatory=$false)]
    [string]$InstallPath = "C:\Program Files\ServiceProcess"
)

$ErrorActionPreference = "Stop"

try {
    Write-Host "Configuring ActivePulse auto-start for AD user..." -ForegroundColor Green
    Write-Host "Install Path: $InstallPath"
    
    $exePath = Join-Path $InstallPath "ServiceProcess.exe"
    
    # Check if executable exists
    if (-not (Test-Path $exePath)) {
        Write-Host "Error: Executable not found at $exePath" -ForegroundColor Red
        Write-Host "Please ensure ActivePulse is installed to: $InstallPath" -ForegroundColor Yellow
        exit 1
    }
    
    Write-Host "Executable found: $exePath" -ForegroundColor Green
    
    # Create HKLM entry (machine-wide - for local admin users)
    Write-Host "`nCreating HKLM registry entry..." -ForegroundColor Cyan
    $regPathLM = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run"
    $regName = "ActivePulseAgent"
    $regValue = "`"$exePath`""
    
    Set-ItemProperty -Path $regPathLM -Name $regName -Value $regValue -Type String -Force
    Write-Host "✓ HKLM entry created successfully" -ForegroundColor Green
    
    # Create HKCU entry (per-user - for AD users)
    Write-Host "`nCreating HKCU registry entry..." -ForegroundColor Cyan
    $regPathCU = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    
    Set-ItemProperty -Path $regPathCU -Name $regName -Value $regValue -Type String -Force
    Write-Host "✓ HKCU entry created successfully" -ForegroundColor Green
    
    # Verify both entries exist
    Write-Host "`nVerifying registry entries..." -ForegroundColor Cyan
    
    $hklmValue = Get-ItemProperty -Path $regPathLM -Name $regName -ErrorAction SilentlyContinue
    $hkcuValue = Get-ItemProperty -Path $regPathCU -Name $regName -ErrorAction SilentlyContinue
    
    if ($hklmValue) {
        Write-Host "✓ HKLM entry verified: $($hklmValue.$regName)" -ForegroundColor Green
    } else {
        Write-Host "✗ HKLM entry NOT found" -ForegroundColor Red
    }
    
    if ($hkcuValue) {
        Write-Host "✓ HKCU entry verified: $($hkcuValue.$regName)" -ForegroundColor Green
    } else {
        Write-Host "✗ HKCU entry NOT found" -ForegroundColor Red
    }
    
    # Show current user info
    Write-Host "`nCurrent user information:" -ForegroundColor Cyan
    Write-Host "  User: $env:USERNAME" -ForegroundColor White
    Write-Host "  Domain: $env:USERDOMAIN" -ForegroundColor White
    Write-Host "  Computer: $env:COMPUTERNAME" -ForegroundColor White
    
    # Test if this is an AD user
    $isAdUser = $env:USERDOMAIN -ne $env:COMPUTERNAME
    if ($isAdUser) {
        Write-Host "  Status: Active Directory user detected" -ForegroundColor Yellow
        Write-Host "  → HKCU entry will be used for auto-start" -ForegroundColor Green
    } else {
        Write-Host "  Status: Local user detected" -ForegroundColor Green
        Write-Host "  → Either HKLM or HKCU entry will work" -ForegroundColor Green
    }
    
    Write-Host "`n✓ Auto-start configuration completed successfully!" -ForegroundColor Green
    Write-Host "  Sign out and sign back in to test auto-start" -ForegroundColor Yellow
    
} catch {
    Write-Host "`nError: $_" -ForegroundColor Red
    Write-Host "Stack Trace: $($_.ScriptStackTrace)" -ForegroundColor Red
    exit 1
}

# Additional helper function to remove auto-start
function Remove-ActivePulseAutostart {
    param(
        [string]$InstallPath = "C:\Program Files\ServiceProcess"
    )
    
    Write-Host "Removing ActivePulse auto-start entries..." -ForegroundColor Yellow
    
    $regName = "ActivePulseAgent"
    
    # Remove HKLM entry
    try {
        Remove-ItemProperty -Path "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" -Name $regName -ErrorAction SilentlyContinue
        Write-Host "✓ HKLM entry removed" -ForegroundColor Green
    } catch {
        Write-Host "HKLM entry not found or already removed" -ForegroundColor Gray
    }
    
    # Remove HKCU entry
    try {
        Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name $regName -ErrorAction SilentlyContinue
        Write-Host "✓ HKCU entry removed" -ForegroundColor Green
    } catch {
        Write-Host "HKCU entry not found or already removed" -ForegroundColor Gray
    }
    
    Write-Host "Auto-start entries removed successfully" -ForegroundColor Green
}

# Export the function for use in the same session
Export-ModuleMember -Function Remove-ActivePulseAutostart
