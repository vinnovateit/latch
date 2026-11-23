# Windows development

The desktop app is Kotlin/Native on both hosts: `linuxX64` and `mingwX64` use the same SDL3 + Skia bridge. There is no JVM in either distributable. Build the Windows binary with `:desktop:linkDebugExecutableMingwX64` (or `linkReleaseExecutableMingwX64`). The list below collects the behaviour the Windows build matches against the native Linux app.

## Window

- Use the OS window frame, not a custom title bar. Drag and resize belong to the window manager.
- Closing the window hides it to the tray. Only the tray Quit entry ends the process.
- Minimize keeps the tray icon. Launching a second copy raises the existing window.
- The app icon comes from `desktop/icons/latch.svg`. `desktop/icons/GenerateIcon.java` rasterises the PNG sizes at build time using only the build JDK, so no rsvg-convert or ImageMagick is needed on Windows.
- The LATCH mark and wordmark stay in the wide navigation rail.

## Navigation

- Wide windows use the wide navigation rail.
- Below 900 dp the rail moves into a drawer. The hamburger sits at the top left and opens it.
- The compact top row uses the surface colour. On Home, the connection status pill sits next to the hamburger. Nothing is on the far right.
- Secondary screens use the menu instead of a back button.
- The Home illustration must not be clipped by an extra top bar. The Home stats card needs enough height.

## Wi-Fi and session

- Portal requests go out on the Wi-Fi interface even when Ethernet or USB tethering owns the default route. Never fall back to the default route for portal credentials.
- Account login and history use the public route. The account host refuses Wi-Fi-bound connections.
- Read the Wi-Fi gateway from the Wi-Fi device, not from `ip route show default`.
- Keep the 3-strike debounce on portal health checks. Unlatch before a re-login.
- An OS-level link check must run before the portal probe. A dead carrier or a lost IP ends the session at once. The portal endpoint is not reliable enough to be the only health signal. The Windows equivalent is adapter status up plus a usable IPv4 address.
- Watch for the session ending after about seven hours. The health check detects it through repeated portal failure and re-logs in; the link check covers the case where Wi-Fi itself drops.
- The CLI daemon needs the same tray and the same Wi-Fi binding as the GUI.

## CI

- The JVM desktop tasks are gone. Build Windows with `:desktop:linkDebugExecutableMingwX64` / `:desktop:linkReleaseExecutableMingwX64`; Linux keeps `:desktop:packageNativeReleaseTarGz`.
- Linux CI needs `libcurl4-openssl-dev` for the curl cinterop. Windows uses the OS `curl.exe` and needs no cinterop.
- Native checks are `:core:desktopTest :desktop:linuxX64Test :cli:test` and `:desktop:packageNativeReleaseTarGz`.
- Windows checks run `:core:desktopTest` with the Windows test filters, plus `:desktop:packageNativeMsiMingwX64` (WiX 5, `Scope="perUser"`, `-dcl high`). The `windows-msi` CI job reports the installer size and uploads it as `windows-native-msi`.
- The release workflow builds the same MSI and stages it as `LatchSetup.msi`; `packaging/qualify-windows-native-msi.ps1` verifies identity, silent per-user install, installed files and uninstall.
- Linux keeps a `.tar.gz` but recompresses it at gzip level 9, so the filename and install scripts stay unchanged.
