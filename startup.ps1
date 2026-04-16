$WshShell = New-Object -comObject WScript.Shell

$Shortcut = $WshShell.CreateShortcut("$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\ActivePulse.lnk")

$Shortcut.TargetPath = "$env:ProgramFiles\ActivePulse\ActivePulse.exe"

$Shortcut.Save()