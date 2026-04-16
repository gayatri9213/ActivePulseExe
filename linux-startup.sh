#!/bin/bash

APP_NAME="ActivePulse"

mkdir -p "$HOME/.config/autostart"

cat > "$HOME/.config/autostart/activepulse.desktop" <<EOF
[Desktop Entry]
Type=Application
Exec=/opt/${APP_NAME}/bin/${APP_NAME}
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
Name=ActivePulse
Comment=Start ActivePulse on login
EOF