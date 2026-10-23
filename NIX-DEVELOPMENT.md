# Latch development on NixOS

From the repository root:

```sh
nix develop
java -version
```

The `x86_64-linux` flake pins nixpkgs and Temurin 21.0.12.1+1. The existing
Gradle wrapper, application dependencies and JVM targets remain unchanged.
Native system libraries for the desktop and CLI are supplied by Nix.

The Gradle project configures Android even for desktop builds. Install the
Android SDK under `~/Android/Sdk`, including API 37, Build Tools 37.0.0 and
NDK 28.2.13676358, using SDK licenses you have reviewed and accepted. Run:

```sh
android-sdk-prepare
```

This patches SDK Linux host executables/libraries for NixOS, leaving Android
target libraries and NDK sysroots alone. It does not install SDK components
or accept licenses. Rerun it after adding host tools or changing Nix libraries.
The shell sets the SDK paths and routes Gradle to the patched SDK aapt2.

```sh
./gradlew assembleDebug testDebugUnitTest :app:lintDebug
./gradlew :desktop:compileKotlinDesktop :core:desktopTest :cli:build
./gradlew :core:desktopTest :cli:test :desktop:smoke
./gradlew :desktop:desktopTest
bash packaging/test-submit-winget.sh
bash packaging/test-install-script.sh
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`; CLI archives are
under `cli/build/distributions`. Gradle caches downloads/build transforms at
`~/.cache/vinnovateit/gradle`. The shell disables daemons, limits workers to two
and scopes the observed Google Maven IPv4/TLS 1.2 workaround to Java. TLS
certificate verification remains enabled.

The smoke task writes credentials, settings and history and probes the portal.
Use disposable XDG data/config/runtime directories when running it for testing;
block DBus and external networking if it must not touch real credential stores,
autostart state or the hostel portal. See the smoke task implementation.

For intended interactive use:

```sh
./gradlew :desktop:run
```

Actual hostel login needs the appropriate NetworkManager/DBus/network services.
The flake starts no persistent services and installs no privileged helper,
authentication rule, firewall change or autostart entry. Keep SDK local paths,
credentials and signing keys out of Git. Apple/Windows targets need their own
hosts and platform setup.
