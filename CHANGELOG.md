# Changelog

Release notes for GitHub Releases are maintained here. The release workflow reads
the `## <version>` section matching `latchVersion` and publishes it verbatim as
that release's body, so a release is only as good as its entry in this file. Add
the section in the same commit that bumps the version; the release job fails
before it builds anything if the section is missing.

## 1.4.2

Hardening release for the desktop app, the CLI and the Linux installer.

### What's fixed

- **Linux installer**: `install.sh` could fall back to a download from 1.3.8 that no longer exists, and could pick up the CLI archive instead of the desktop one. It now always installs the latest desktop release, and stops with a clear message if it cannot find it
- **Linux**: saved credentials are no longer deleted when their encryption salt file is missing or unreadable. They become readable again once the file is restored
- The authentication token that the CLI and desktop app share is now private to your user from the moment it is written, and runtime files are never replaced partially
- Release builds are now checked on Windows against the real credential store, and on Linux by running the final downloadable archives

No action is needed after updating.

Download the compatible version for your machine from the assets below.

## 1.4.1

Reliability and credential-storage hardening release for the desktop app and CLI.

### What's new

- **Desktop**: captive portal session history sync, with stats built from it; history resyncs over any internet connection
- **Desktop**: native window controls and a resizable window

### What's fixed

- **Linux**: credentials are now stored in the system keyring (Secret Service) when one is available. Earlier versions never detected it and always used the encrypted fallback file. Existing credentials keep working and move to the keyring the next time you save them.
- **Linux**: a save the keyring refuses (for example, while it is locked) is now reported as a failure instead of being written somewhere it would be ignored
- **Linux**: the portable desktop and CLI archives shipped their launchers without execute permission
- Credential saves on Linux and Windows now replace the stored file atomically, and a failed save is always reported instead of showing "Credentials saved."
- The CLI no longer starts the background service when first-run credential setup fails
- CLI and desktop coordinate more reliably when both are running, including under bursts of CLI commands
- Desktop: onboarding waits for credentials, the app menu lines up with the window controls, the wide power button is round, and tiling window managers no longer trigger resize loops
- Updated bundled dependencies, including Bouncy Castle 1.86

No action is needed after updating.

Download the compatible version for your machine from the assets below.

## 1.4.0

The CLI's first public release. Install it from the package manager for your
system, or download the binary from the assets below.

### What's new

- **CLI**: animated ASCII intro banner on launch
- **CLI**: published to apt, dnf, and winget for the first time
- **Linux**: signed apt and dnf repositories hosted on GitHub Pages
- **Windows**: automated winget submissions on every release
- **Packaging**: AUR, Chocolatey, and winget manifest generation on every release
- Added MIT license file

Download the compatible version for your machine from the assets below.

## 1.3.9

### What's new

- Credentials screen: fields focus themselves, and Enter moves to the next one.

### What's fixed

- Connection status no longer relies on the scraped Wi-Fi name, which could show you as disconnected while you were online
- Session stats now stop and finalize on every disconnect and failure path
- Linux: home top bar menu froze on first setup after login
- Desktop settings could be read while they were still being written
- Updated bundled dependencies for six security advisories

Download the compatible version for your machine from the assets below.

The command line app is still unreleased and is not part of these downloads.

## 1.3.8

### What's fixed

- App falsely claimed "update available" on every check, once a day
- Linux one-line installer should now work fine!
- App icon missing from the Linux application menu

Download the compatible version for your machine from the assets below.

## 1.3.7

btw, available for Linux now.

What's New

- Interface updates for a more immersive experience
- Fixed: Wi-Fi doesn't connect sometimes
- New: Speed is now max 20Mbps ;)

Download the compatible version for your machine from the assets below.

## 1.3.6

Auto-login for VIT Hostel Wi-Fi, now on Windows

What's New

Desktop interface built with Compose Multiplatform, matching the Android app
Home: Live connection status, one-click connect/disconnect, and start at login option
Statistics: Real-time throughput graphs, session history with peak upload/download rates and data usage visualization.
Settings: Manage credentials, customize accent colors, switch themes including Monochrome and AMOLED, and choose your update channel
System Tray: Live network speed in the tooltip, quick connect/disconnect actions, and startup integration
Compatible with Windows 10 and Windows 11.

Download the MSI installer below.
