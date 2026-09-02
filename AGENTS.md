# Project Guidelines & Architecture Notes

## 1. Android Compose Navigation & Predictive Back (STRICT ARCHITECTURAL INVARIANT)
- **Status**: Verified and mandated by the user. NEVER remove, simplify, rework, or refactor this implementation.
- **Implementation Design (`LatchNavGraph.kt`)**:
  - `PredictiveSlideBackContainer`: Custom wrapper utilizing Android's `PredictiveBackHandler` and Compose `Animatable(0f)`.
  - **Gesture Tracking**: `progress.snapTo(event.progress)` tracks finger movement continuously during the edge swipe.
  - **Commit on Release**: When the user commits the back gesture, `progress.animateTo(1f)` smoothly finishes the slide to the right edge *before* calling `onBackPressed()`. This eliminates any frame jerking, momentary flashes, or snap-backs.
  - **Parallax Background**: Renders `backgroundContent` (`HomeScreen`) directly behind the sliding child screen (`SETTINGS`, `STATS`, `MEET_THE_TEAM`), translating it with 1/3 parallax `(progress.value - 1f) * (screenWidth / 3f)` and a subtle black depth scrim (`alpha = (1f - progress.value) * 0.25f`).
  - **Cancellation**: If the user cancels the swipe, `progress.animateTo(0f)` smoothly springs back to the origin.
  - **Programmatic Back Clicks**: Top bar back buttons invoke `triggerBack` which plays the same `progress.animateTo(1f)` slide-out.
  - **NavHost Pop Transitions**: `popEnterTransition` and `popExitTransition` are set to `EnterTransition.None` / `ExitTransition.None` so `NavHost` does not attempt to replay a duplicate animation on top of the container.
- **Rule**: Never replace this with standard `NavHost` scale/fade defaults, do not remove `PredictiveSlideBackContainer`, and do not attempt to "simplify" it into single-line calls.

## 2. AGP & Gradle Architecture
- **AGP 9 Modern DSL**: The project uses AGP 9 with `android.builtInKotlin=true` and `android.newDsl=true` in `gradle.properties`.
- **Kotlin Multiplatform Library**: The `:core` module applies `com.android.kotlin.multiplatform.library` configured via `kotlin { android { ... } }`.
- **App Module**: The `:app` module applies `com.android.application` and compiles Kotlin directly through AGP's built-in Kotlin integration.

## 3. Database & Target Separation
- **Room Multiplatform**: Room expect/actual database constructors live in `commonMain` with target compilers assigned via `dependencies { add("kspDesktop", ...) ; add("kspAndroid", ...) }`.
- **Desktop Module**: JVM Compose desktop application configured with jpackage native distribution bundlers.
- **CLI Module**: Lightweight JVM-only application independent of GUI dependencies.
