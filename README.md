# SecureScreen

SecureScreen is a Kotlin Android app that prevents screenshots and screen recording for selected apps using `FLAG_SECURE`-based enforcement.

## Features

- Select protected apps from installed launchable applications
- Foreground app detection via `UsageStatsManager`
- Foreground service with persistent notification
- Transparent `SecureActivity` enforcement with `FLAG_SECURE`
- Optional watermark overlay with timestamp and session ID
- Settings for watermark toggle, opacity, and aggressive mode flag
- Boot receiver to recover service state after reboot/update

## Build

1. Open this project in Android Studio (Jellyfish or newer recommended).
2. Let Gradle sync complete.
3. If `gradle-wrapper.jar` is missing, run `gradle wrapper` once from the project root.
4. Run on a physical Android device (Android 8.0+).

## Required User Setup

- Grant Usage Access permission in system settings.
- Enable notification permissions on Android 13+ if prompted.
- Grant Overlay permission only if watermark is enabled.

## Play Store Positioning

Use the following plain-language claim:

> "Prevents screenshots and screen recording for selected apps."

Avoid spyware/anti-spyware claims.
