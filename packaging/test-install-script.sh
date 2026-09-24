#!/usr/bin/env bash
# Runs install.sh for real, without the network or root.
#
# curl and sudo are replaced by stubs on PATH, and each case gets its own
# temporary HOME, so install.sh takes its user-local path (~/.local) and
# installs into a directory that is deleted afterwards. The stub curl serves
# a canned releases API response and latest-release redirect, and hands back a
# fixture tarball for any download, recording the URL it was asked for.
#
# With a desktop tarball argument, that tarball is also installed through
# LATCH_LOCAL_TAR, to check install.sh against the real archive layout.
#
# Usage: packaging/test-install-script.sh [desktop.tar.gz]
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
real_tarball=${1:+$(realpath "$1")}

if [ "$(id -u)" -eq 0 ]; then
    echo "Run this as a non-root user: as root, install.sh installs system-wide." >&2
    exit 1
fi

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

mkdir -p "$root/fixture/latch-9.8.7/bin" "$root/fixture/latch-9.8.7/lib" "$root/stubs"
printf '#!/bin/sh\necho fixture-latch\n' >"$root/fixture/latch-9.8.7/bin/Latch"
chmod 755 "$root/fixture/latch-9.8.7/bin/Latch"
touch "$root/fixture/latch-9.8.7/lib/Latch.png"
fixture="$root/latch-9.8.7-linux-x64.tar.gz"
tar -czf "$fixture" -C "$root/fixture" latch-9.8.7

cat >"$root/stubs/sudo" <<'EOF'
#!/bin/sh
exit 1
EOF

# STUB_API: file served as the releases API response; unset makes it fail.
# STUB_LATEST: where the latest-release page redirects; unset makes it fail.
cat >"$root/stubs/curl" <<'EOF'
#!/bin/sh
out="" url="" write_out=0
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out=$2; shift ;;
        -w) write_out=1; shift ;;
        -*) ;;
        *) url=$1 ;;
    esac
    shift
done
case "$url" in
    https://api.github.com/*)
        [ -n "${STUB_API:-}" ] || exit 22
        cat "$STUB_API" ;;
    */releases/latest)
        [ -n "${STUB_LATEST:-}" ] || exit 22
        [ "$write_out" -eq 1 ] && printf '%s' "$STUB_LATEST" ;;
    *)
        echo "$url" >>"$STUB_LOG"
        cp "$STUB_TARBALL" "$out" ;;
esac
EOF
chmod 755 "$root/stubs/sudo" "$root/stubs/curl"

# The API lists assets alphabetically, which today happens to put the desktop
# tarball first; list the CLI tarball first so the test does not depend on it.
cat >"$root/api.json" <<'EOF'
{
  "tag_name": "v9.8.7",
  "assets": [
    {
      "browser_download_url": "https://github.com/vinnovateit/latch/releases/download/v9.8.7/latch-cli-9.8.7-linux-x64.tar.gz"
    },
    {
      "browser_download_url": "https://github.com/vinnovateit/latch/releases/download/v9.8.7/latch-9.8.7-linux-x64.tar.gz"
    }
  ]
}
EOF
expected_url="https://github.com/vinnovateit/latch/releases/download/v9.8.7/latch-9.8.7-linux-x64.tar.gz"

# Runs install.sh with a fresh HOME named after the case; extra arguments are
# environment assignments. Leaves its exit status in $status.
install_case() {
    local name=$1
    shift
    mkdir -p "$root/$name"
    status=0
    env -i PATH="$root/stubs:/usr/bin:/bin" HOME="$root/$name" \
        STUB_LOG="$root/$name.urls" STUB_TARBALL="$fixture" "$@" \
        sh "$repo_root/install.sh" >"$root/$name.out" 2>&1 || status=$?
}

require_installed() {
    local name=$1 home="$root/$1"
    [ "$status" -eq 0 ] || { cat "$root/$name.out" >&2; fail "$name: install.sh exited $status"; }
    [ -x "$home/.local/share/latch/bin/Latch" ] || fail "$name: no executable launcher in ~/.local/share/latch/bin"
    [ -L "$home/.local/bin/latch" ] && [ -x "$home/.local/bin/latch" ] || fail "$name: ~/.local/bin/latch is not an executable link"
    [ -f "$home/.local/share/latch/lib/Latch.png" ] || fail "$name: the icon the desktop entry points at is missing"
    grep -qx "Exec=$home/.local/share/latch/bin/Latch" "$home/.local/share/applications/latch.desktop" \
        || fail "$name: the desktop entry does not launch the installed launcher"
}

require_downloaded() {
    local name=$1
    [ "$(cat "$root/$name.urls" 2>/dev/null)" = "$expected_url" ] \
        || fail "$name: downloaded '$(cat "$root/$name.urls" 2>/dev/null)', expected $expected_url"
}

install_case api STUB_API="$root/api.json"
require_installed api
require_downloaded api
[ "$("$root/api/.local/bin/latch")" = fixture-latch ] || fail "api: the installed link does not run the launcher"
echo "ok: uses the desktop tarball the releases API lists, not the CLI one"

install_case redirect STUB_LATEST="https://github.com/vinnovateit/latch/releases/tag/v9.8.7"
require_installed redirect
require_downloaded redirect
echo "ok: with the API unavailable, the version comes from the latest-release redirect"

install_case offline
[ "$status" -ne 0 ] || fail "offline: install.sh succeeded with no release to download"
[ ! -e "$root/offline.urls" ] || fail "offline: install.sh still downloaded $(cat "$root/offline.urls")"
[ ! -e "$root/offline/.local/share/latch" ] || fail "offline: install.sh installed something"
grep -q "Could not find the latest Latch release" "$root/offline.out" || fail "offline: no explanation printed"
echo "ok: with GitHub unreachable, install.sh stops with an error instead of a guessed URL"

install_case local LATCH_LOCAL_TAR="$fixture"
require_installed local
[ ! -e "$root/local.urls" ] || fail "local: LATCH_LOCAL_TAR still downloaded"
echo "ok: LATCH_LOCAL_TAR installs without downloading"

if grep -n '1\.3\.8' "$repo_root/install.sh"; then
    fail "install.sh still names a fixed release version"
fi

if [ -n "$real_tarball" ]; then
    install_case real LATCH_LOCAL_TAR="$real_tarball"
    require_installed real
    echo "ok: installs the real desktop tarball $(basename "$real_tarball")"
fi

echo "install.sh tests passed"
