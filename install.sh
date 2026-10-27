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

# Read stable release metadata without requiring jq or Python on the installing machine.
linux_tar_url() {
    awk -v prefix="https://github.com/${REPO}/releases/download/" '
        function finish_release( wanted, j) {
            if (draft != "false" || prerelease != "false" ||
                tag !~ /^v[0-9]+\.[0-9]+\.[0-9]+$/) return
            wanted = prefix tag "/latch-" substr(tag, 2) "-linux-x64.tar.gz"
            for (j = 1; j <= asset_count; j++) {
                if (assets[j] == wanted) { print wanted; exit }
            }
        }
        { json = json $0 "\n" }
        END {
            # Tokenize strings so braces and metadata quoted in release notes stay inert.
            for (i = 1; i <= length(json); i++) {
                c = substr(json, i, 1)
                if (c ~ /[ \t\r\n]/) continue
                if (c == "\"") {
                    token = ""
                    while (++i <= length(json)) {
                        c = substr(json, i, 1)
                        if (c == "\"") break
                        if (c == "\\") c = substr(json, ++i, 1)
                        token = token c
                    }
                    if (!value) key = token
                    else {
                        if (depth == release_depth && key == "tag_name") tag = token
                        if (depth == release_depth + 2 && key == "browser_download_url")
                            assets[++asset_count] = token
                        value = 0
                    }
                } else if (c == "{" || c == "[") {
                    if (!depth) root_array = (c == "[")
                    depth++
                    if (c == "{" && depth == 1 + root_array) {
                        release_depth = depth
                        tag = draft = prerelease = ""
                        asset_count = 0
                    }
                    value = 0
                } else if (c == "}" || c == "]") {
                    if (c == "}" && depth == release_depth) finish_release()
                    depth--
                    value = 0
                } else if (c == ":") value = 1
                else if (c == ",") value = 0
                else {
                    token = c
                    while (i < length(json) && substr(json, i + 1, 1) !~ /[ \t\r\n,}\]]/)
                        token = token substr(json, ++i, 1)
                    if (value && depth == release_depth) {
                        if (key == "draft") draft = token
                        if (key == "prerelease") prerelease = token
                    }
                    value = 0
                }
            }
        }
    '
}

# Keep shared latest discovery for Windows while selecting an actual Linux desktop asset here.
resolve_tar_url() {
    url=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" 2>/dev/null \
        | linux_tar_url) || true
    if [ -n "$url" ]; then
        echo "$url"
        return 0
    fi

    page=1
    while [ "$page" -le 10 ]; do
        releases=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases?per_page=100&page=$page" 2>/dev/null) || break
        url=$(printf '%s\n' "$releases" | linux_tar_url)
        if [ -n "$url" ]; then
            echo "$url"
            return 0
        fi
        [ "$(printf '%s' "$releases" | tr -d '[:space:]')" = '[]' ] && break
        page=$((page + 1))
    done

    latest=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/${REPO}/releases/latest" 2>/dev/null) || true
    tag=${latest##*/}
    case "$tag" in
        v[0-9]*)
            url="https://github.com/${REPO}/releases/download/${tag}/latch-${tag#v}-linux-x64.tar.gz"
            if curl -fsSLI "$url" >/dev/null 2>&1; then
                echo "$url"
                return 0
            fi ;;
        *) return 1 ;;
    esac
    return 1
}

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
    if ! command -v curl >/dev/null 2>&1; then
        echo "ERROR: curl is required to download Latch." >&2
        exit 1
    fi
    if ! TAR_URL=$(resolve_tar_url); then
        echo "ERROR: Could not find a compatible Linux desktop release on GitHub. Check your connection, or download the tarball from https://github.com/${REPO}/releases and install it with LATCH_LOCAL_TAR." >&2
        exit 1
    fi

    TMP_TAR=$(mktemp /tmp/latch-XXXXXX.tar.gz)
    CLEANUP_TMP_TAR=1
    echo "--> Downloading latest release from GitHub..."
    curl -fsSL "$TAR_URL" -o "$TMP_TAR"
fi

USE_SUDO=0
if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
    if sudo -v 2>/dev/null; then
        USE_SUDO=1
    fi
fi

if [ "$(id -u)" -eq 0 ] || [ $USE_SUDO -eq 1 ]; then
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
