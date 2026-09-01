# Contributing to Latch

Latch spans Android, desktop, CLI, and shared Kotlin modules. Keep cross-platform behavior in `core/` and platform-specific integration in its owning module.

## Development setup

Use JDK 21 through the repository `mise.toml`, the checked-in Gradle wrapper, Android Studio, and Android SDK Platform 37:

```sh
mise current
./gradlew assembleDebug testDebugUnitTest :app:lintDebug
```

Run the desktop application with:

```sh
./gradlew :desktop:run
```

The Gradle project still configures the Android module when building desktop targets, so the Android SDK must be available.

## Change guidelines

- Preserve the JVM targets declared by each module; the JDK used to run Gradle is not permission to raise them.
- Put shared network/authentication behavior in `core/` and add tests at that boundary.
- Keep UI and packaging changes within `app/` or `desktop/` as appropriate.
- Never commit VIT credentials, captured portal sessions, signing files, or local Android SDK paths.

## Pull requests

Include the CI-equivalent Gradle result, identify every affected platform, and attach screenshots for visible UI changes. Document any portal-flow assumption that may require campus-network verification.
