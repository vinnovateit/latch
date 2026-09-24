#!/usr/bin/env bash
# Smoke-tests the final Linux release tarballs as users receive them: each
# archive is extracted into a fresh directory and what came out is executed.
#
# v1.4.0 shipped tarballs whose launchers had lost their execute bit. The
# release job only ran the Gradle build image, which was fine, so nothing
# noticed. Every check here runs against the extracted files instead.
#
# Everything runs with throwaway HOME/XDG directories under one temp root, and
# a session bus address that points nowhere. So there are no credentials, no
# keyring, no portal login, and any startup entry the desktop app writes
# lands under the temp root, which is deleted on exit.
#
# The desktop launch needs a display; in CI run this under `xvfb-run -a`.
#
# Usage: packaging/smoke-linux-tarballs.sh <version> <desktop.tar.gz> <cli.tar.gz>
set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <version> <desktop.tar.gz> <cli.tar.gz>" >&2
    exit 64
fi
version=$1
desktop_archive=$(realpath "$2")
cli_archive=$(realpath "$3")
build_root=$(realpath "${LATCH_BUILD_ROOT:-$PWD}")

if [ -z "${DISPLAY:-}" ]; then
    echo "The desktop launch needs a display; run this under xvfb-run -a." >&2
    exit 1
fi

root=$(mktemp -d)
desktop_pid=""
cleanup() {
    if [ -n "$desktop_pid" ] && kill -0 "$desktop_pid" 2>/dev/null; then
        kill -KILL "$desktop_pid" 2>/dev/null || true
    fi
    rm -rf "$root"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

mkdir -p "$root/home" "$root/config" "$root/data" "$root/cache" "$root/state" "$root/run"
chmod 700 "$root/run"
data_dir="$root/data/Latch"

# The same isolated environment for every launch. The latch.dataDir override is
# not set here, so the XDG location is what isolates the data directory.
isolated() {
    env -i \
        PATH=/usr/bin:/bin \
        LANG=C.UTF-8 \
        HOME="$root/home" \
        XDG_CONFIG_HOME="$root/config" \
        XDG_DATA_HOME="$root/data" \
        XDG_CACHE_HOME="$root/cache" \
        XDG_STATE_HOME="$root/state" \
        XDG_RUNTIME_DIR="$root/run" \
        DBUS_SESSION_BUS_ADDRESS="unix:path=$root/no-bus" \
        DISPLAY="${DISPLAY:-}" \
        XAUTHORITY="${XAUTHORITY:-}" \
        "$@"
}

# Extracts an archive into its own directory and prints the single top-level
# directory it contains.
extract() {
    local archive=$1 into=$2
    mkdir -p "$into"
    tar -xzf "$archive" -C "$into"
    local tops
    tops=$(find "$into" -mindepth 1 -maxdepth 1)
    [ "$(printf '%s\n' "$tops" | wc -l)" -eq 1 ] && [ -d "$tops" ] \
        || fail "$(basename "$archive") must contain exactly one top-level directory"
    printf '%s\n' "$tops"
}

require_executable() {
    local path=$1
    [ -f "$path" ] || fail "missing ${path#"$root"/}"
    [ -x "$path" ] || fail "not executable: ${path#"$root"/} ($(stat -c '%A' "$path"))"
    echo "  executable: ${path#"$root"/} ($(stat -c '%A' "$path"))"
}

# A jpackage image is runnable only if its launcher, launcher library, runtime
# and every classpath jar named in the launcher config are present.
check_image() {
    local image=$1 launcher=$2
    require_executable "$image/bin/$launcher"
    require_executable "$image/lib/runtime/lib/jspawnhelper"
    for required in lib/libapplauncher.so lib/runtime/lib/server/libjvm.so lib/runtime/release "lib/app/$launcher.cfg"; do
        [ -f "$image/$required" ] || fail "missing ${image#"$root"/}/$required"
    done
    local jars=0 jar
    while IFS= read -r jar; do
        [ -f "$image/lib/app/$jar" ] || fail "$launcher.cfg names a missing jar: $jar"
        jars=$((jars + 1))
    done < <(sed -n 's/^app\.classpath=\$APPDIR\///p' "$image/lib/app/$launcher.cfg")
    [ "$jars" -gt 0 ] || fail "$launcher.cfg lists no classpath jars"
    echo "  runtime present, $jars classpath jars present"
    if grep -rlaF "$build_root" "$image" >/dev/null; then
        fail "the image embeds the build path $build_root: $(grep -rlaF "$build_root" "$image" | head -n 3 | tr '\n' ' ')"
    fi
    echo "  no build-machine path ($build_root) embedded"
}

# Runs a command in the isolated environment, requiring an exact exit status.
# Prints stdout for later assertions; stderr is shown only on failure.
run_expect() {
    local expected=$1
    shift
    local status=0
    isolated "$@" >"$root/out" 2>"$root/err" || status=$?
    if [ "$status" -ne "$expected" ]; then
        cat "$root/out" "$root/err" >&2
        fail "$(basename "$1") ${*:2} exited $status, expected $expected"
    fi
    cat "$root/out"
}

echo "== CLI archive: $(basename "$cli_archive")"
cli_image=$(extract "$cli_archive" "$root/cli")
check_image "$cli_image" latch-cli
cli="$cli_image/bin/latch-cli"

actual=$(run_expect 0 "$cli" --version)
[ "$actual" = "latch-cli $version" ] || fail "--version printed '$actual', expected 'latch-cli $version'"
echo "  --version: $actual"
help_output=$(run_expect 0 "$cli" --help)
grep -q -- '--status' <<<"$help_output" || fail "--help does not describe --status"
echo "  --help: exit 0"
status_output=$(run_expect 0 "$cli" --status)
grep -q '^owner: ' <<<"$status_output" || fail "--status did not report an owner"
echo "  --status: exit 0 ($(grep '^owner: ' <<<"$status_output"))"
run_expect 2 "$cli" --no-such-flag >/dev/null
echo "  unknown flag: exit 2"
[ -d "$data_dir" ] || fail "the CLI did not use the isolated data directory"

echo "== Desktop archive: $(basename "$desktop_archive")"
desktop_image=$(extract "$desktop_archive" "$root/desktop")
check_image "$desktop_image" Latch

# Hidden, so it starts as a tray process; with no credentials it never logs in.
isolated "$desktop_image/bin/Latch" --hidden >"$root/desktop.log" 2>&1 &
desktop_pid=$!
for _ in $(seq 1 90); do
    grep -q '"DESKTOP"' "$data_dir/.runtime.json" 2>/dev/null && break
    kill -0 "$desktop_pid" 2>/dev/null || break
    sleep 1
done
if ! kill -0 "$desktop_pid" 2>/dev/null || ! grep -q '"DESKTOP"' "$data_dir/.runtime.json" 2>/dev/null; then
    tail -n 40 "$root/desktop.log" >&2
    fail "the extracted desktop app did not come up as the runtime owner"
fi
echo "  launcher started; runtime owner is the desktop app"

# The extracted CLI reaching the extracted desktop app over the authenticated
# local IPC proves the desktop runtime finished initialising, not just that a
# JVM started.
status_output=$(run_expect 0 "$cli" --status)
grep -qx 'owner: desktop' <<<"$status_output" \
    || fail "--status did not reach the desktop owner: $status_output"
echo "  --status via the desktop owner: owner: desktop"
kill -0 "$desktop_pid" 2>/dev/null || fail "the desktop app exited while answering --status"

kill -TERM "$desktop_pid"
for _ in $(seq 1 15); do
    kill -0 "$desktop_pid" 2>/dev/null || break
    sleep 1
done
kill -KILL "$desktop_pid" 2>/dev/null || true
wait "$desktop_pid" 2>/dev/null || true
desktop_pid=""
if grep -q 'Permission denied' "$root/desktop.log"; then
    fail "the desktop log reports a permission error"
fi

echo "== Extracted Linux tarballs for $version passed"
