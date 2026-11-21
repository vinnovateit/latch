# Work checklist

## Done — desktop native port
- [x] Native `linuxX64` desktop binary, no JVM, 86 MB → 24 MB
- [x] Native CLI on `feat/native-cli` (2.5 MB, no JVM)
- [x] Single "Latch" launcher; autostart uses `--hidden` (tray-only)
- [x] Material 3 Expressive `WideNavigationRail` (FAB header, inline labels)
- [x] Android: Material 3 Expressive `WideNavigationRail` for Home/Stats/Settings (responsive, FAB header, inline labels)
- [x] History: full 3706-row parse, public-route sync, corrected date filter
- [x] Credential validation via account login endpoint
- [x] Wi-Fi-only portal binding; disconnect/reconnect handling
- [x] Startup 4.2 s → 88 ms
- [x] Stats unresponsive during fetch (offloaded to native IO pool)
- [x] Home chart memoization; bar-chart trackpad horizontal scroll
- [x] Back arrows removed from Stats and Settings
- [x] Architecture: `StatsPresentation` seam, core `SessionSelector`, dead `MpHttpClient` deleted
- [x] Size profiled (24 MB desktop / 2.5 MB CLI; 6 MB Noto bundle opportunity — visual change, deferred)

## Pending
- [ ] Decide on the 6 MB bundled Noto font removal (saves ~6 MB, but changes fallback text rendering where no explicit font family is set).
