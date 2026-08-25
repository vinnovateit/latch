#!/usr/bin/env sh
set -e

# ==============================================================================
# Latch Universal Linux Installer Script
# Usage: curl -fsSL https://latch.vinnovateit.com/install.sh | sh
#
# To install from a local tarball instead of downloading the latest release
# (e.g. a custom or pre-release build), set LATCH_LOCAL_TAR:
#   LATCH_LOCAL_TAR=/path/to/latch-custom-linux-x64.tar.gz sh install.sh
# ==============================================================================

REPO="vinnovateit/latch"
DEFAULT_TAR_URL="https://github.com/${REPO}/releases/latest/download/latch-1.3.7-linux-x64.tar.gz"

echo "==== Installing Latch Desktop by VinnovateIT ===="

CLEANUP_TMP_TAR=0
if [ -n "$LATCH_LOCAL_TAR" ]; then
    if [ ! -f "$LATCH_LOCAL_TAR" ]; then
        echo "ERROR: LATCH_LOCAL_TAR is set but '$LATCH_LOCAL_TAR' does not exist." >&2
        exit 1
    fi
    echo "--> Using local tarball: $LATCH_LOCAL_TAR"
    TMP_TAR="$LATCH_LOCAL_TAR"
else
    TAR_URL=""
    if command -v curl >/dev/null 2>&1; then
        TAR_URL=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" 2>/dev/null | grep "browser_download_url.*tar.gz" | cut -d '"' -f 4 | head -n 1 || true)
    fi
    if [ -z "$TAR_URL" ]; then
        TAR_URL="$DEFAULT_TAR_URL"
    fi

    TMP_TAR=$(mktemp /tmp/latch-XXXXXX.tar.gz)
    CLEANUP_TMP_TAR=1
    echo "--> Downloading latest release from GitHub..."
    curl -fsSL "$TAR_URL" -o "$TMP_TAR"
fi

USE_SUDO=0
if [ $(id -u) -ne 0 ] && command -v sudo >/dev/null 2>&1; then
    if sudo -v 2>/dev/null; then
        USE_SUDO=1
    fi
fi

if [ $(id -u) -eq 0 ] || [ $USE_SUDO -eq 1 ]; then
    echo "--> Installing system-wide to /opt/latch..."
    SUDO_CMD=""
    [ $USE_SUDO -eq 1 ] && SUDO_CMD="sudo"

    $SUDO_CMD mkdir -p /opt/latch /usr/local/bin /usr/share/applications
    $SUDO_CMD tar -xzf "$TMP_TAR" -C /opt/latch --strip-components=1 2>/dev/null || $SUDO_CMD tar -xzf "$TMP_TAR" -C /opt/latch
    $SUDO_CMD ln -sf /opt/latch/bin/Latch /usr/local/bin/latch

    $SUDO_CMD tee /usr/share/applications/latch.desktop >/dev/null <<EOF
[Desktop Entry]
Name=Latch
Comment=VIT Hostel Wi-Fi Auto-Login
Exec=/opt/latch/bin/Latch
Icon=/opt/latch/lib/Latch.png
Terminal=false
Type=Application
Categories=Network;Utility;
EOF
else
    echo "--> No sudo privileges given: installing user-local to $HOME/.local..."
    LOCAL_OPT="$HOME/.local/share/latch"
    LOCAL_BIN="$HOME/.local/bin"
    LOCAL_APPS="$HOME/.local/share/applications"

    mkdir -p "$LOCAL_OPT" "$LOCAL_BIN" "$LOCAL_APPS"
    tar -xzf "$TMP_TAR" -C "$LOCAL_OPT" --strip-components=1 2>/dev/null || tar -xzf "$TMP_TAR" -C "$LOCAL_OPT"
    ln -sf "$LOCAL_OPT/bin/Latch" "$LOCAL_BIN/latch"

    cat <<EOF > "$LOCAL_APPS/latch.desktop"
[Desktop Entry]
Name=Latch
Comment=VIT Hostel Wi-Fi Auto-Login
Exec=$LOCAL_OPT/bin/Latch
Icon=$LOCAL_OPT/lib/Latch.png
Terminal=false
Type=Application
Categories=Network;Utility;
EOF
fi

[ "$CLEANUP_TMP_TAR" -eq 1 ] && rm -f "$TMP_TAR"

echo "==== Latch Desktop successfully installed! ===="
echo "Launch from your application menu or run 'latch' in terminal."
