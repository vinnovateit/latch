#!/usr/bin/env bash
# Qualifies the macOS disk image as a user receives it: the .dmg is mounted,
# Latch.app is copied out of it, and every check runs against that copy.
#
#   - the image is non-empty, mounts, and holds exactly one Latch.app;
#   - Info.plist: bundle id, version, executable, minimum macOS, icon, and the
#     Location usage text without which macOS never shows the prompt that
#     lets Latch read the Wi-Fi network name;
#   - the launcher, the bundled runtime and every classpath jar exist;
#   - the headless platform smoke test (CoreWLAN, Keychain round trip, Room,
#     OSHI) passes on the app's jars, limited to the bundled runtime's
#     modules. jpackage strips the java command from that runtime, so a JDK
#     of the same version (JAVA_HOME) runs it with --limit-modules;
#   - the app itself starts, hidden, and becomes the runtime owner.
#
# Everything the app writes goes to a temporary data directory, set through
# JAVA_TOOL_OPTIONS: on macOS the JVM takes user.home from the account
# record, not $HOME, so HOME cannot isolate it. Two things cannot be
# redirected and are handled instead:
#   - the launched app registers itself to start at login (an installed
#     build's first run), which is checked and then removed, and any
#     LaunchAgent that was there before is put back;
#   - the smoke test's Keychain round trip uses Latch's real service name, so
#     run this only where that is disposable (the CI job points it at a
#     throwaway Keychain).
#
# The image is not signed or notarized here; that is reported, not checked.
#
# Usage: packaging/qualify-macos-app.sh <version> <Latch.dmg>
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <version> <Latch.dmg>" >&2
    exit 64
fi
version=$1
dmg=$2
build_root=$(cd "${LATCH_BUILD_ROOT:-$PWD}" && pwd -P)
bundle_id=com.vinnovateit.latch
minimum_macos=12.0

[ "$(uname -s)" = Darwin ] || { echo "This qualifies a macOS app; run it on macOS." >&2; exit 1; }

root=$(mktemp -d)
mount_point="$root/mount"
app_pid=""
account_home=$(/usr/bin/dscl . -read "/Users/$(id -un)" NFSHomeDirectory | sed 's/^NFSHomeDirectory: //')
launch_agent="$account_home/Library/LaunchAgents/com.vinnovateit.latch.login.plist"
if [ -e "$launch_agent" ]; then
    cp -p "$launch_agent" "$root/launch-agent.backup"
fi

cleanup() {
    if [ -n "$app_pid" ] && kill -0 "$app_pid" 2>/dev/null; then
        kill -KILL "$app_pid" 2>/dev/null || true
    fi
    if [ -d "$mount_point" ]; then
        hdiutil detach "$mount_point" -force >/dev/null 2>&1 || true
    fi
    if [ -e "$root/launch-agent.backup" ]; then
        cp -p "$root/launch-agent.backup" "$launch_agent" || echo "warning: could not restore $launch_agent" >&2
    else
        rm -f "$launch_agent"
    fi
    rm -rf "$root"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

plist_value() {
    /usr/libexec/PlistBuddy -c "Print :$1" "$app/Contents/Info.plist" 2>/dev/null
}

expect_plist() {
    local key=$1 expected=$2 actual
    actual=$(plist_value "$key") || fail "Info.plist has no $key"
    [ "$actual" = "$expected" ] || fail "Info.plist $key is '$actual', expected '$expected'"
    echo "  $key: $actual"
}

require_executable() {
    [ -f "$1" ] || fail "missing ${1#"$root"/}"
    [ -x "$1" ] || fail "not executable: ${1#"$root"/}"
    echo "  executable: ${1#"$root"/}"
}

echo "== Disk image: $(basename "$dmg")"
[ -s "$dmg" ] || fail "$dmg is missing or empty"
echo "  size: $(stat -f '%z' "$dmg") bytes"
hdiutil verify "$dmg" >/dev/null || fail "hdiutil verify rejected the image"
echo "  hdiutil verify: OK"

mkdir -p "$mount_point"
hdiutil attach "$dmg" -readonly -nobrowse -noautoopen -mountpoint "$mount_point" >/dev/null \
    || fail "the image does not mount"
apps=$(find "$mount_point" -maxdepth 1 -name '*.app' -type d)
[ "$(printf '%s\n' "$apps" | grep -c .)" -eq 1 ] || fail "expected exactly one .app in the image, found: ${apps:-none}"
[ "$(basename "$apps")" = Latch.app ] || fail "the app is named $(basename "$apps"), expected Latch.app"
ditto "$apps" "$root/Latch.app"
hdiutil detach "$mount_point" >/dev/null
rmdir "$mount_point"
app="$root/Latch.app"
echo "  holds Latch.app; copied out and detached"

echo "== Bundle metadata"
expect_plist CFBundleIdentifier "$bundle_id"
expect_plist CFBundleShortVersionString "$version"
expect_plist CFBundleExecutable Latch
expect_plist LSMinimumSystemVersion "$minimum_macos"
for key in NSLocationUsageDescription NSLocationWhenInUseUsageDescription; do
    text=$(plist_value "$key") || fail "Info.plist has no $key; macOS would never ask for Location access"
    [ -n "$text" ] || fail "Info.plist $key is empty"
    echo "  $key: present"
done
icon=$(plist_value CFBundleIconFile) || fail "Info.plist has no CFBundleIconFile"
[ -s "$app/Contents/Resources/${icon%.icns}.icns" ] || fail "the icon $icon is missing from Resources"
echo "  CFBundleIconFile: $icon (present)"

echo "== Bundle contents"
require_executable "$app/Contents/MacOS/Latch"
java_home="$app/Contents/runtime/Contents/Home"
[ -f "$java_home/lib/server/libjvm.dylib" ] || fail "the bundled runtime has no libjvm.dylib"
[ -f "$java_home/release" ] || fail "the bundled runtime has no release file"
runtime_version=$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' "$java_home/release")
runtime_modules=$(sed -n 's/^MODULES="\(.*\)"$/\1/p' "$java_home/release" | tr ' ' ',')
{ [ -n "$runtime_version" ] && [ -n "$runtime_modules" ]; } || fail "the bundled runtime's release file names no version or modules"
echo "  bundled runtime: Java $runtime_version, $(printf '%s' "$runtime_modules" | tr ',' '\n' | grep -c .) modules"
cfg="$app/Contents/app/Latch.cfg"
[ -f "$cfg" ] || fail "missing Contents/app/Latch.cfg"
jars=0
# $APPDIR is jpackage's literal placeholder in Latch.cfg, not a shell variable.
# shellcheck disable=SC2016
while IFS= read -r jar; do
    [ -f "$app/Contents/app/$jar" ] || fail "Latch.cfg names a missing jar: $jar"
    jars=$((jars + 1))
done < <(sed -n 's/^app\.classpath=\$APPDIR\///p' "$cfg")
[ "$jars" -gt 0 ] || fail "Latch.cfg lists no classpath jars"
echo "  runtime present, $jars classpath jars present"
if grep -rlaF "$build_root" "$app" >/dev/null; then
    fail "the app embeds the build path $build_root: $(grep -rlaF "$build_root" "$app" | head -n 3 | tr '\n' ' ')"
fi
echo "  no build-machine path embedded"
echo "  signature (informational): $(codesign -dv "$app" 2>&1 | grep -E '^(Signature|Authority|TeamIdentifier)=' | tr '\n' ' ' || true)"

data="$root/data"
mkdir -p "$data"

echo "== Headless smoke test on the bundled runtime's modules"
host_java="${JAVA_HOME:?set JAVA_HOME to a JDK matching the bundled runtime}/bin/java"
[ -x "$host_java" ] || fail "JAVA_HOME has no bin/java: $JAVA_HOME"
host_version=$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' "$JAVA_HOME/release")
[ "${host_version%%.*}" = "${runtime_version%%.*}" ] \
    || fail "JAVA_HOME is Java $host_version but the bundled runtime is Java $runtime_version"
smoke_log="$root/smoke.log"
if ! "$host_java" --limit-modules "$runtime_modules" -Djava.awt.headless=true -Dlatch.dataDir="$data/smoke" \
    -cp "$app/Contents/app/*" com.vinnovateit.latch.desktop.SmokeMainKt >"$smoke_log" 2>&1; then
    tail -n 60 "$smoke_log" >&2
    fail "the smoke test failed with the bundled runtime's modules"
fi
grep -q '=== smoke test complete ===' "$smoke_log" || { tail -n 60 "$smoke_log" >&2; fail "the smoke test did not finish"; }
grep -E 'roundtrip|cleared|guard holds|currentSsid|wifiEnabled' "$smoke_log" | sed 's/^/  /' || true
if grep -q 'roundtrip        : FAILED' "$smoke_log"; then fail "the Keychain round trip failed"; fi
if grep -q 'guard holds      : FAILED' "$smoke_log"; then fail "a non-installed run registered itself to start at login"; fi
[ ! -e "$data/smoke/credentials.bin" ] || fail "credentials were written to a file instead of the Keychain"
echo "  completed; no credential file written"

echo "== App launch"
JAVA_TOOL_OPTIONS="-Dlatch.dataDir=$data/app" "$app/Contents/MacOS/Latch" --hidden >"$root/app.log" 2>&1 &
app_pid=$!
for _ in $(seq 1 90); do
    grep -q '"DESKTOP"' "$data/app/.runtime.json" 2>/dev/null && break
    kill -0 "$app_pid" 2>/dev/null || break
    sleep 1
done
if ! kill -0 "$app_pid" 2>/dev/null || ! grep -q '"DESKTOP"' "$data/app/.runtime.json" 2>/dev/null; then
    tail -n 60 "$root/app.log" >&2
    find "$data/app/logs" -type f -exec tail -n 40 {} + >&2 2>/dev/null || true
    fail "Latch.app did not come up as the runtime owner"
fi
echo "  started hidden; runtime owner is the desktop app"
[ ! -e "$data/app/credentials.bin" ] || fail "the app wrote a credential file"
if grep -rqs 'AppIndicator\|libgtk\|secret-tool' "$data/app/logs"; then
    fail "the app touched a Linux-only integration on macOS"
fi
echo "  no Linux tray/keyring integration touched"
[ -f "$launch_agent" ] || fail "the installed app did not register itself to start at login"
grep -qF "<string>$app/Contents/MacOS/Latch</string>" "$launch_agent" \
    || fail "the LaunchAgent does not start this Latch.app: $(cat "$launch_agent")"
grep -qF '<string>--hidden</string>' "$launch_agent" || fail "the LaunchAgent does not start Latch hidden"
plutil -lint "$launch_agent" >/dev/null || fail "launchd would reject the LaunchAgent plist"
echo "  start at login: LaunchAgent written for this app, valid plist"
kill "$app_pid" 2>/dev/null || true
wait "$app_pid" 2>/dev/null || true
app_pid=""

echo "== macOS app qualified"
