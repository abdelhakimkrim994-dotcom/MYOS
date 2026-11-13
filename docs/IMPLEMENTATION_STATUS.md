# MYOS Home — Implementation Status

Last updated: 2026-11-13

## Milestone 0 — Native Android scaffold

**Status: Source committed; build and device verification pending.**

Committed in this milestone:

- Root Gradle settings and Android application module.
- Native Kotlin `MainActivity` registered for Android HOME and launcher intents.
- Minimal static home scene with a white upper area and a stylised, drawn ocean wave below.
- Swipe-up gesture to open the app drawer.
- Original user-provided ocean photograph verified at `app/src/main/assets/myos_ocean.jpg` (Git blob `a69b86e9d4d20178cc01e82c06f1f7efe3d52e59`).
- 560 ms decelerating rising-wave reveal that clips the app drawer into view; swipe down/Back closes it with the reverse reveal.
- App discovery through launcher activities exposed by Android package visibility queries.
- App icon/name rendering and launch-intent handling.
- Swipe-down close behavior and Android Back handling.

## Important limitations

- No APK has been generated or installed.
- No Gradle build has been run in this environment.
- No behavior has been verified on the user's phone.
- The Kotlin home renderer now attempts to load the exact reference from `app/src/main/assets/myos_ocean.jpg`; it falls back to the temporary drawn wave if the asset is absent. The assets directory is prepared, but the JPG still needs to be uploaded there before the photo appears in an APK.
- The app drawer's current icon layout is a first functional arrangement. Spacing, typography, scrolling, accessibility, and visual polish need device testing.
- The Gradle plugin, Kotlin plugin, compile SDK, target SDK, and minimum SDK values are provisional. Confirm compatibility and current Android requirements before release.
- Edge-to-edge and system-bar insets need testing, especially on newer Android versions.

## Next steps

1. Confirm the target phone's Android version and available build environment.
2. Build the project with a compatible JDK and Android SDK; fix all compiler and manifest issues.
3. Generate a debug APK and install it on the phone.
4. Verify HOME selection, app discovery/launching, gestures, Back behavior, system-bar insets, and rotation policy.
5. Test the rising-wave transition on the phone and tune its duration/shape.
6. Refine the floating icon layout based on real-device usability.

## Verification rule

A source file being committed does not mean the app builds or runs. Mark each behavior complete only after the corresponding build or on-device test has passed.
