[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android Studio](https://img.shields.io/badge/Android_Studio-3DDC84?style=flat&logo=androidstudio&logoColor=white)](https://developer.android.com/studio)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

<img src="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/redLogoLatch.svg" alt="Project Logo" width="120">


Cross-platform app that automates login to VIT hostel WiFi networks.

[![Latest Release](https://img.shields.io/github/v/release/vinnovateit/latch?style=flat&color=E53935&logoColor=white&label=Release)](https://github.com/vinnovateit/latch/releases/latest)
[![Total Downloads](https://img.shields.io/github/downloads/vinnovateit/latch/total?style=flat&logo=github&color=E53935&logoColor=white&label=Downloads)](https://github.com/vinnovateit/latch/releases)
[![Stars](https://img.shields.io/github/stars/vinnovateit/latch?style=flat&logo=github&color=E53935&logoColor=white&label=Stars)](https://github.com/vinnovateit/latch/stargazers)

[![Website](https://img.shields.io/badge/Website-E53935?style=flat&logo=googlechrome&logoColor=white)](https://latch.vinnovateit.com)
[![Report Bug](https://img.shields.io/badge/Report_Bug-E53935?style=flat&logo=github&logoColor=white)](https://github.com/vinnovateit/latch/issues/new?labels=bug&template=bug-report---.md)
[![Request Feature](https://img.shields.io/badge/Request_Feature-E53935?style=flat&logo=github&logoColor=white)](https://github.com/vinnovateit/latch/issues/new?labels=enhancement&template=feature-request---.md)
## About the project

Latch is a Kotlin application developed by VinnovateIT that automates the login process for VIT hostel WiFi networks. It detects network connections, submits credentials automatically, and optionally logs network statistics. The app reduces repetitive manual logins for students.

## Key features

- Automatic detection of VIT hostel WiFi networks
- Auto-login with securely stored credentials
- Logging and display of network usage statistics

## Prerequisites

Before you start, make sure you have:

- Android Studio
- Kotlin (latest stable version)
- JDK 21
- Android device or emulator running Android 8.0 (API 26) or higher

## Installation

### Windows (desktop)

1. Download `Latch-Setup.msi` from the [latest release](https://github.com/vinnovateit/latch/releases/latest)
2. Run the installer and follow the setup wizard
3. Launch Latch from the Start menu and enter your VIT hostel credentials

### Linux (desktop)

1. Run the install script:

   ```sh
   curl -fsSL https://latch.vinnovateit.com/install.sh | sh
   ```

   This installs Latch to `/opt/latch` (system-wide, if run with `sudo` access) or `~/.local/share/latch` (user-local otherwise), and registers a desktop entry.

2. Launch Latch from your application menu, or run `latch` in a terminal.

   To install manually instead, download `latch-1.3.8-linux-x64.tar.gz` from the [latest release](https://github.com/vinnovateit/latch/releases/latest) and extract it.

### Android

No pre-built APK is currently published for the Android app. To use it today, build it from source. See [Dev setup](#dev-setup) below.

## Dev setup

### Android app

1. Clone the repository:

   ```sh
   git clone https://github.com/vinnovateit/latch.git
   ```

2. Open the project in Android Studio
3. Sync Gradle and build the project.

   The app targets `compileSdk 37`. If Android Studio's SDK Manager doesn't already have Android SDK Platform 37 installed, the first sync will prompt you to download it and accept its license.

4. Run on a connected device or emulator
5. Enter your VIT hostel credentials when prompted

### Desktop app

1. Clone the repository (if not already done above)

2. Run it directly:

   ```sh
   ./gradlew :desktop:run
   ```

3. Or build a native package for your OS:

   ```sh
   ./gradlew :desktop:packageReleaseDeb          # .deb
   ./gradlew :desktop:packageReleaseRpm          # .rpm
   ./gradlew :desktop:packageReleaseAppImage     # AppImage
   ./gradlew :desktop:packageReleaseTarGz        # .tar.gz (Linux)
   ```

   Build artifacts land in `desktop/build/distributions/`.

4. To test a locally-built (or otherwise custom) tarball without publishing a release, point `install.sh` at it directly instead of letting it download from GitHub:

   ```sh
   LATCH_LOCAL_TAR=desktop/build/distributions/latch-1.3.8-linux-x64.tar.gz sh install.sh
   ```

   This module is built as part of the same Gradle project as `:app`, so the Android SDK still needs to be configured (see the Android app prerequisites above) even though the desktop app itself doesn't run on Android.

## Usage

Latch automatically detects VIT hostel WiFi networks and logs in using the credentials you provide.

Optionally, it records network statistics for monitoring purposes.

## Screenshots

| Landing screen | Home screen |
| --- | --- |
| ![Landing screen](https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/onboarding.jpg) | ![Home screen](https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/home_screen.jpg) |

| Stats | Settings |
| --- | --- |
| ![Network stats](https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/stats_screen.jpg) | ![Settings screen](https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/settings_screen.jpg) |

## Roadmap

- Add support for multiple VIT campuses
- Improve UI responsiveness
- CLI client ;)

See the [open issues](https://github.com/vinnovateit/latch/issues) for a full list of proposed features and known issues.

Made with love by [VinnovateIT](https://vinnovateit.com).
