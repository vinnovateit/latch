# macOS: release status and real-Mac qualification

macOS support is in **preview**. The `macos` job in `desktop.yml` builds and
checks an unsigned disk image on every PR; nothing for macOS is published by
`release.yml`.

## Release blocker: signing and notarization

A disk image downloaded from GitHub is quarantined, and Gatekeeper refuses an
app that is not signed with a Developer ID and notarized by Apple. Shipping
the unsigned image would leave users only the option of overriding Gatekeeper,
which Latch will not ask them to do. Until the items below exist, macOS stays
out of the release.

Needed from a VinnovateIT Apple Developer Program membership, stored as
repository secrets:

| Secret | What it is |
| --- | --- |
| `MACOS_CERTIFICATE_P12` | The "Developer ID Application" certificate with its private key, exported as .p12, base64-encoded |
| `MACOS_CERTIFICATE_PASSWORD` | The password of that .p12 |
| `MACOS_SIGNING_IDENTITY` | The identity name, e.g. `Developer ID Application: VinnovateIT (TEAMID)` |
| `APPLE_TEAM_ID` | The 10-character team ID |
| `APPLE_ID` and `APPLE_APP_SPECIFIC_PASSWORD` | The account notarytool submits as (an App Store Connect API key works instead: key ID, issuer ID and the .p8) |

Wiring, once they exist:

1. In `release.yml`, a `build-macos` job on `macos-latest` imports the
   certificate into a temporary keychain, then runs
   `:desktop:packageReleaseDmg` and `:desktop:notarizeReleaseDmg` with
   `compose.desktop.application.nativeDistributions.macOS.signing` and
   `.notarization` taken from those secrets, and staples the ticket.
2. Signing turns on the hardened runtime, which a JVM app needs entitlements
   for: `com.apple.security.cs.allow-jit`,
   `com.apple.security.cs.allow-unsigned-executable-memory`,
   `com.apple.security.cs.disable-library-validation` (JNA and SQLite load
   native libraries they unpack at run time) and
   `com.apple.security.personal-information.location` (without it the
   hardened runtime blocks Location, and Latch never learns the network
   name, so it never signs in).
3. The job runs `packaging/qualify-macos-app.sh` on the notarized image,
   plus `spctl --assess --type open --context context:primary-signature`
   and `xcrun stapler validate`, before the release job may publish it.
4. Only then does the README's macOS section stop saying "preview".

## Real-Mac qualification checklist

CI cannot show Gatekeeper, the Location prompt, a menu-bar icon or a real
network. Run this on a real Mac with the build that is to be released, and
record the macOS version and Mac model. Anything marked **on campus** needs
VIT hostel Wi-Fi and cannot be checked elsewhere.

"No credential transmission" means Latch's log in
`~/Library/Application Support/Latch/logs/` shows the login refused ("refusing
login") and no "Attempting Portal Login", and no request to
`phc.prontonetworks.com` appears in an HTTP proxy or `nettop` meanwhile.

| # | Step | Expected |
| --- | --- | --- |
| 1 | Clean install: open the downloaded .dmg, drag Latch to Applications | Copies; no other files appear |
| 2 | First launch from Applications | Gatekeeper opens it without an override (signed and notarized build only) |
| 3 | First run | Onboarding, then the credentials screen |
| 4 | Location prompt | macOS asks, naming Latch, with the Wi-Fi explanation; allow it |
| 5 | Enter credentials | Saved; Keychain Access shows a `com.vinnovateit.latch` item, account `portal-credentials`; no `credentials.bin` in the data directory |
| 6 | Quit and relaunch | Credentials are still there; no Keychain prompt for the same build |
| 7 | Home or café Wi-Fi | Status says it is not the VIT network; **no credential transmission** |
| 8 | Wi-Fi off | Status says Wi-Fi is off; **no credential transmission** |
| 9 | Location access turned off (System Settings) on any network | No sign-in attempt; log says the network name is hidden; **no credential transmission** |
| 10 | **On campus:** VIT Wi-Fi | Signs in through the portal; status Latched |
| 11 | **On campus:** Disconnect from the app | Portal logout succeeds |
| 12 | **On campus:** latched, then join a non-VIT network | Session ends within a few seconds; no sign-in on the new network |
| 13 | **On campus:** rejoin VIT Wi-Fi | Signs in again without user action |
| 14 | Menu-bar icon | Present; Open, Connect/Disconnect and Exit work; Exit quits cleanly |
| 15 | Start at login | Enabled by default; System Settings > General > Login Items lists Latch; after logging out and in, Latch is running hidden |
| 16 | Turn start at login off in Latch's settings | The Login Items entry disappears; it no longer starts at login |
| 17 | Restart the Mac | Same behavior as 15/16 |
| 18 | Update discovery | With a newer release published, Latch offers it and "Open download page" opens the GitHub release; nothing is downloaded or installed by the app |
| 19 | Install the newer .dmg over the old app | Settings, history and credentials carry over (a new signature may ask once for Keychain access) |
| 20 | Uninstall: quit Latch, delete Latch.app | App gone |
| 21 | After uninstall | Per product policy, `~/Library/Application Support/Latch`, the Keychain item and `~/Library/LaunchAgents/com.vinnovateit.latch.login.plist` remain until removed by hand; removing the LaunchAgent stops a login-time launch attempt of the deleted app. Document these for users or remove them on uninstall |
