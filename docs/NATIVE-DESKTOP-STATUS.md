# Native desktop status

The desktop app is a self-contained Kotlin/Native binary for `linuxX64` and `mingwX64` (Windows), built on
[compose-desktop-native](https://github.com/bitsycore/compose-desktop-native)
(SDL3 + Skia). There is **no JVM** in the desktop distributable.

## Artifacts

| Item | Value |
|---|---|
| Release tar | `desktop/build/distributions/latch-native-1.5.1-linux-x64.tar.gz` (~20 MB) |
| Contents | `bin/Latch` (~55 MB uncompressed) + `bin/data.kres` (~277 KB) |
| Launcher | `/usr/share/applications/latch.desktop` → `/opt/latch-new/bin/Latch` |
| Autostart | `~/.config/autostart/latch.desktop` → `.../Latch --hidden` |
| CLI | separate branch `feat/native-cli`, ~2.5 MB, no JVM |

## What works (Linux)

- **Window**: custom title bar, drag-to-move (via SDL window position), compositor resize, minimize, close.
- **Navigation**: Material 3 Expressive wide nav rail (Home / Stats / Settings / How it works / About) with inline labels, FAB-free branded header, responsive collapse/expand. On narrow windows the rail is hidden and a compact overflow menu plus back arrows take over.
- **Back navigation**: back arrow shown only when the rail is hidden; `Backspace` and `Alt+Left` shortcuts work on all secondary screens.
- **Tray**: SDL3/AppIndicator tray with icon, status tooltip/menu entry, Open / Connect / Exit actions. Manual launch opens the window; `--hidden` (autostart) stays tray-only with no taskbar icon.
- **Wi-Fi**: nmcli-based SSID/interface/gateway detection, Available/Lost events, 3-strike health checks, disconnect/reconnect handling, weak-signal logging.
- **Portal security**: captive-portal login/credential traffic is bound to the Wi-Fi NIC; account history uses the public route (the account endpoint is a public IP and refuses Wi-Fi-bound connections). Credentials are validated against the account login endpoint before saving.
- **History**: full HTML table parse (up to ~3700 rows), ~1767 valid sessions persisted, stats recompute to ~485 day-groups / 843 chart points. Resync available in Stats.
- **Stats/UX**: live throughput chart, history bar chart with trackpad/wheel horizontal scroll, bottom sheets capped at 560 dp, confirmation dialogs as centered `AlertDialog`s, Material You theming (full upstream MCU port), Satoshi/MODERNIZ fonts with correct weights.
- **Performance**: startup ~88 ms (blocking nmcli probe removed from init); heavy sync offloaded to a dedicated native IO pool; chart paths/window memoized.
- **Tests**: `core:desktopTest` (349), `desktop:linuxX64Test`, `app:compileDebugKotlin` all green.

## Known limitations / what does not work

- **Windows**: native `mingwX64` build links and runs on Windows using the same SDL3 + Skia port; SDL tray works via its Windows backend. Wi-Fi uses `netsh`, portal traffic is pinned to the Wi-Fi interface through `curl.exe`, credentials are stored in a user-ACL'd JSON file, and session history uses a JSON-backed DAO (Room/SQLite have no mingwX64 klib). The compressed per-user MSI is built with `desktop/packaging/windows/LatchNative.wxs` via `:desktop:packageNativeMsiMingwX64` (~15 MB from ~54 MB of staged files); the release workflow stages it as `LatchSetup.msi`.
- **Credentials at rest**: native fallback is a 0600 JSON file, not the GNOME keyring/Secret Service or Windows DPAPI.
- **Tray on GNOME/Wayland**: needs an AppIndicator extension; SDL sets tooltip as a no-op on Linux, so status is mirrored in a disabled menu entry.
- **Window position persistence**: the window does not remember its position/size across restarts.
- **Notifications**: `notifyTransient` shells out to `notify-send`; silent if no notification daemon is installed.
- **Noto fonts**: removed (~6 MB saved). Any text without an explicit family falls back to the OS sans-serif; all app text styles set Satoshi/MODERNIZ explicitly.
- **Android UI tests**: `:desktop` no longer has a JVM `desktopTest` task; core's 346 JVM tests still run.

## Branch / PRs

- `feat/compose-desktop-native` — main work, pushed at `d8c1182`, no PR.
- `feat/native-cli` — native CLI, pushed at `e6fcb83`, no PR.
