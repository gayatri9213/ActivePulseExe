# ActivePulse Troubleshooting Guide

## 🔧 Linux Installation Issues

### Common Installation Errors & Solutions

#### 1. **jpackage not found**
**Error**: `jpackage not found. Need JDK 17+.`

**Solution**:
```bash
# Install JDK 17+ (Ubuntu/Debian)
sudo apt update
sudo apt install openjdk-17-jdk

# OR (RHEL/Fedora)
sudo dnf install java-17-openjdk-devel

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Verify installation
jpackage --version
```

#### 2. **Neither dpkg nor rpm found**
**Error**: `Neither dpkg nor rpm found. Cannot build installer.`

**Solution**:
- For Ubuntu/Debian: `sudo apt install dpkg-dev`
- For RHEL/Fedora: `sudo dnf install rpm-build`

#### 3. **JAR file not found**
**Error**: `JAR not found. Run: mvn clean package -q`

**Solution**:
```bash
# Build the project first
mvn clean package -q

# Verify JAR exists
ls -la target/active-pulse-*.jar
```

#### 4. **Permission denied during installation**
**Error**: Permission denied when running installer

**Solution**:
```bash
# Make script executable
chmod +x package-linux.sh

# Run with proper permissions
./package-linux.sh

# Install package (for .deb)
sudo dpkg -i target/setup/*.deb
sudo apt-get install -f  # Fix dependencies if needed

# Install package (for .rpm)
sudo rpm -i target/setup/*.rpm
```

#### 5. **Missing dependencies**
**Error**: Various dependency errors during installation

**Solution**:
```bash
# Ubuntu/Debian dependencies
sudo apt install openjdk-17-jdk dpkg-dev build-essential

# RHEL/Fedora dependencies
sudo dnf install java-17-openjdk-devel rpm-build gcc
```

### Linux Installation Steps

1. **Prerequisites**:
```bash
# Verify Java version
java -version  # Should be 17+

# Verify package manager
which dpkg || which rpm
```

2. **Build Installer**:
```bash
chmod +x package-linux.sh
./package-linux.sh
```

3. **Install Package**:
```bash
# For .deb (Ubuntu/Debian)
sudo dpkg -i target/setup/*.deb
sudo apt-get install -f

# For .rpm (RHEL/Fedora)
sudo rpm -i target/setup/*.rpm
```

---

## 🍎 Mac Data Sync Issues

### Problem: Screenshots Working but Data Not Syncing

From your dashboard, I can see:
- Screenshots are being captured (10 screenshots visible)
- Some screenshots appear black (sync/upload issue)
- User "aressmacmini389" detected correctly
- Data sync is failing

### Root Causes & Solutions

#### 1. **Server Configuration Issue**
**Most Likely Cause**: `API_KEY` is still set to placeholder value

**Check Configuration**:
```bash
# Find ActivePulse installation
find /Applications -name "ActivePulse*" 2>/dev/null
find ~/Library -name "*activepulse*" 2>/dev/null

# Check agent.env file location
cat /Applications/ActivePulse.app/Contents/Resources/agent.env
# OR
cat ~/Library/Application\ Support/ActivePulse/agent.env
```

**Fix Configuration**:
```bash
# Edit agent.env with correct values
SERVER_BASE_URL=https://activepulse.portal-login-access.net/api
API_KEY=your-actual-api-key-here  # Replace with real API key
USER_ID=1
ORGANIZATION_ID=1
```

#### 2. **Network/Connectivity Issues**
**Symptoms**: Black screenshots, sync timeouts

**Diagnosis**:
```bash
# Test server connectivity
curl -I https://activepulse.portal-login-access.net/api

# Check firewall settings
sudo lsof -i :443
sudo lsof -i :80
```

**Solution**:
- Check if Mac can reach the server
- Verify firewall isn't blocking outbound connections
- Check proxy settings if on corporate network

#### 3. **Permissions Issue**
**Symptoms**: App running but can't access files or network

**Fix Permissions**:
```bash
# Reset app permissions
sudo tccutil reset All com.aress.activepulse

# Grant necessary permissions
# System Preferences → Security & Privacy → Privacy
# - Accessibility
# - Screen Recording
# - Full Disk Access
```

#### 4. **Java/Mac Compatibility Issues**
**Symptoms**: App crashes or fails to start

**Check Java Version**:
```bash
# Check what Java the app is using
/Applications/ActivePulse.app/Contents/MacOS/ActivePulse --version

# Check system Java
java -version
/usr/libexec/java_home -V
```

### Mac Debugging Steps

#### 1. **Check Logs**:
```bash
# Application logs
tail -f ~/Library/Logs/ActivePulse/active-pulse.log

# System logs for crashes
tail -f /var/log/system.log | grep ActivePulse
```

#### 2. **Verify App Status**:
```bash
# Check if app is running
ps aux | grep ActivePulse

# Check system tray integration
osascript -e 'tell application "System Events" to get name of every process'
```

#### 3. **Test Sync Manually**:
```bash
# Navigate to app directory
cd /Applications/ActivePulse.app/Contents/MacOS

# Run with debug logging
./ActivePulse --debug --log-level=DEBUG
```

### Black Screenshots Issue

**Causes**:
1. **Screen recording permission denied**
2. **Screenshot failed during capture**
3. **Upload failed (corrupted file)**
4. **Server-side processing error**

**Solutions**:
```bash
# 1. Grant screen recording permission
# System Preferences → Security & Privacy → Privacy → Screen Recording
# Add ActivePulse and check the box

# 2. Test screenshot manually
screencapture test.png
# If this works, the issue is app permissions

# 3. Clear corrupted screenshots
rm -rf ~/Library/Application\ Support/ActivePulse/screenshots/*
```

---

## 📋 General Troubleshooting Checklist

### For Linux Issues:
- [ ] JDK 17+ installed
- [ ] jpackage available
- [ ] Package manager (dpkg/rpm) available
- [ ] Build successful (`mvn clean package`)
- [ ] Proper permissions on installer
- [ ] Dependencies installed

### For Mac Issues:
- [ ] Correct API key in agent.env
- [ ] Server URL reachable
- [ ] Screen recording permission granted
- [ ] Accessibility permission granted
- [ ] Network connectivity working
- [ ] App running without crashes

### For Both Platforms:
- [ ] Correct server configuration
- [ ] Proper user detection working
- [ ] Database accessible
- [ ] Logs not showing errors
- [ ] Sync interval configured properly

---

## 🚨 Emergency Fixes

### Quick Mac Sync Fix:
```bash
# 1. Stop the app
pkill -f ActivePulse

# 2. Backup current config
cp /Applications/ActivePulse.app/Contents/Resources/agent.env ~/agent.env.backup

# 3. Update with correct API key
sudo nano /Applications/ActivePulse.app/Contents/Resources/agent.env

# 4. Restart app
open /Applications/ActivePulse.app
```

### Quick Linux Install Fix:
```bash
# 1. Install dependencies
sudo apt update && sudo apt install openjdk-17-jdk dpkg-dev build-essential

# 2. Set Java path
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# 3. Build and install
./package-linux.sh
sudo dpkg -i target/setup/*.deb
sudo apt-get install -f
```

---

## 📞 Support Information

If issues persist:
1. **Collect Logs**: Application logs, system logs, error messages
2. **System Info**: OS version, Java version, architecture
3. **Config Files**: agent.env contents (remove API key)
4. **Network Info**: Proxy settings, firewall rules

**Log Locations**:
- **Linux**: `/var/log/active-pulse/` or `~/.activepulse/logs/`
- **Mac**: `~/Library/Logs/ActivePulse/`
- **Windows**: `%APPDATA%/ActivePulse/logs/`
