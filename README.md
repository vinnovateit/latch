<a id="readme-top"></a>


<!-- Club Logo -->
<br />
<div align="center">
  <a href="https://github.com/vinnovateit/latch">
    <picture>
      <source srcset="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/redLogoLatch.svg">
  	  <img alt="Project Logo" src="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/redLogoLatch.svg">
    </picture>
  </a>

<h3 align="center">Latch</h3>

  <p align="center">
    Android app that automates login to VIT hostel WiFi networks.
    <br /><br />
    <a href="https://github.com/vinnovateit/latch">Visit</a>
    &middot;
    <a href="https://github.com/vinnovateit/latch/issues/new?labels=bug&template=bug-report---.md">Report Bug</a>
    &middot;
    <a href="https://github.com/vinnovateit/latch/issues/new?labels=enhancement&template=feature-request---.md">Request Feature</a>
  </p>
</div>



<!-- TABLE OF CONTENTS -->
<!-- Use if things get too long -->
<!-- <details>
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#built-with">Built With</a></li>
      </ul>
    </li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details> -->



<!-- ABOUT THE PROJECT -->
## About The Project
<picture>
  <source srcset="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/redLogoLatch.svg">
  <img alt="Project Logo" src="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/redLogoLatch.svg" width="200" align="right">
</picture>
Latch is an Android application developed by VinnovateIT that automates the login process for VIT hostel WiFi networks. It detects network connections, submits credentials automatically, and optionally logs network statistics. The app improves convenience and reduces repetitive manual logins for students. A companion desktop client for Windows and Linux, sharing the same auto-login logic, is also available in the `./desktop` directory of this repository.

## **Key features:**
- Automatic detection of VIT hostel WiFi networks
- Auto-login with securely stored credentials
- Logging and display of network usage statistics


<!-- Put appropriate SCREENSHOTS here
Use width modifier to control size
Use wisely: don't overfill & don't use too heavy imgs
-->
<details>

  <summary><b>Screenshots</b></summary>

| Landing Screen | Home Screen |
  | :--------------: | :--------: |
| <img width="60%" alt="Landing Screen" src="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/onboarding.jpg"> | <img width="60%" alt="Home screen" src="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/home_screen.jpg"> |
| Stats | Settings |
| <img width="60%" alt="Network stats" src="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/stats_screen.jpg"> | <img width="60%" alt="Settings screen" src="https://raw.githubusercontent.com/vinnovateit/latch/main/.github/assets/settings_screen.jpg"> |

</details>

### Built With 

[![Kotlin][Kotlin-badge]][Kotlin-url]  [![Android Studio][Android-badge]][Android-url]  [![Jetpack Compose][Compose-badge]][Compose-url]


<!-- ROADMAP -->
## Roadmap

- Add support for multiple VIT campuses
- Improve UI responsiveness
- CLI client ;)

See the [open issues](https://github.com/vinnovateit/latch/issues) for a full list of proposed features and known issues.



<!-- GETTING STARTED -->
## Getting Started

Follow these steps to get a local copy of Latch up and running.

### Prerequisites
- Android Studio
- Kotlin (latest stable version)
- JDK 21 (check Android Studio's own Gradle JDK setting under Build, Execution, Deployment > Build Tools > Gradle if Gradle sync fails)
- Android device or emulator running Android 8.0 (API 26) or higher

## Installation

### **Windows (Desktop)**

1. Download `Latch-Setup.msi` from the [latest release](https://github.com/vinnovateit/latch/releases/latest)
2. Run the installer and follow the setup wizard
3. Launch Latch from the Start menu and enter your VIT hostel credentials

### **Linux (Desktop)**

1. Run the install script:

   ```sh
   curl -fsSL https://latch.vinnovateit.com/install.sh | sh
   ```

   This installs Latch to `/opt/latch` (system-wide, if run with `sudo` access) or `~/.local/share/latch` (user-local otherwise), and registers a desktop entry.
2. Launch Latch from your application menu, or run `latch` in a terminal

   To install manually instead, download `latch-1.3.7-linux-x64.tar.gz` from the [latest release](https://github.com/vinnovateit/latch/releases/latest) and extract it.

### **Android**

No pre-built APK is currently published for the Android app. To use it today, build it from source. See [Dev Setup](#dev-setup) below.

## Dev Setup

### **Android app**

1. Clone the repository

   ```sh
   git clone https://github.com/vinnovateit/latch.git
   ```
2. Open the project in Android Studio
3. Sync Gradle and build the project. 
> The app targets `compileSdk 37`. If Android Studio's SDK Manager doesn't already have Android SDK Platform 37 installed, the first sync will prompt you to download it and accept its license
4. Run on a connected device or emulator
5. Enter your VIT hostel credentials when prompted

### **Desktop app**

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

   Build artifacts land in `desktop/build/distributions/`

4. To test a locally-built (or otherwise custom) tarball without publishing a release, point `install.sh` at it directly instead of letting it download from GitHub:

   ```sh
   LATCH_LOCAL_TAR=desktop/build/distributions/latch-1.3.7-linux-x64.tar.gz sh install.sh
   ```
   This module is built as part of the same Gradle project as `:app`, so the Android SDK still needs to be configured (see the Android app prerequisites above) even though the desktop app itself doesn't run on Android.

<!-- USAGE - REMOVE IF NOT NEEDED -->
## Usage

Latch automatically detects VIT hostel WiFi networks and logs in using the credentials provided by you.  

Optionally, it records network statistics for monitoring purposes.

### Top contributors:

<a href="https://github.com/vinnovateit/latch/graphs/contributors" align="center">
  <img src="https://contrib.rocks/image?repo=vinnovateit/latch" alt="contrib.rocks image" />
</a>





<!-- ACKNOWLEDGMENTS -->
## Acknowledgments

- [VinnovateIT](https://vinnovateit.com) for mentoring and resources
- Android Developers for documentation and libraries
- Jetpack Compose for UI framework

<p align="center">
	Made with :heart: by <a href="https://vinnovateit.com">VinnovateIT</a>
</p>


[Kotlin-badge]: https://img.shields.io/badge/Kotlin-7F52FF?&logo=kotlin&logoColor=white
[Kotlin-url]: https://kotlinlang.org/
[Android-badge]: https://img.shields.io/badge/Android_Studio-3DDC84?&logo=androidstudio&logoColor=white
[Android-url]: https://developer.android.com/studio
[Compose-badge]: https://img.shields.io/badge/Jetpack_Compose-4285F4?&logo=jetpackcompose&logoColor=white
[Compose-url]: https://developer.android.com/jetpack/compose