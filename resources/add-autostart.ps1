# Add ActivePulse to HKLM Run key for machine-wide auto-start
# This script is called during installation

$ErrorActionPreference = "Stop"

try {
    $installDir = $PSScriptRoot
    $exePath = Join-Path $installDir "ServiceProcess.exe"
    
    Write-Host "Adding ActivePulse to HKLM Run key..."
    Write-Host "Install Directory: $installDir"
    Write-Host "Executable Path: $exePath"
    
    # Check if executable exists
    if (-not (Test-Path $exePath)) {
        Write-Host "Warning: Executable not found at $exePath"
        exit 0
    }
    
    # Add to HKLM Run key
    $regPath = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run"
    $regName = "ActivePulseAgent"
    $regValue = "`"$exePath`""
    
    Set-ItemProperty -Path $regPath -Name $regName -Value $regValue -Type String -Force
    
    Write-Host "Successfully added ActivePulse to auto-start"
    
} catch {
    Write-Host "Error: $_"
    exit 1
}
