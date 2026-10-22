# 5G NR 1-Second Auto-Switcher ⚡

Android application written in **Kotlin & Jetpack Compose (Material 3 Dynamic Colors, API 29–34)** with automated **1-Second 5G NR Only and Normal Auto Mode switching** powered by an Android `AccessibilityService` and a 1-Tap Quick Settings Tile.

## Features
- **1-Second Automated Switching**: Uses `FiveGAccessibilityService` scoped strictly to `com.android.phone` and `com.android.settings` to find `preferredNetworkType` and select target modes automatically in under 1 second.
- **Start / Stop Controls**:
  - **Start 5G Lock (NR Only)**: Direct lock to pure 5G Standalone.
  - **Stop (Restore Normal NR/LTE)**: Restores hybrid network mode using fallback priority list.
- **1-Tap Quick Settings Tile**: Toggle between 5G NR Locked and Normal Auto Mode from the notification shade without opening the app.
- **Safety Safeguard**: 4-second timeout prevents any infinite loop if RadioInfo is opened manually.
- **Material 3 Dynamic Colors**: Native theming on Android 12+ (API 31+) with backward compatibility down to Android 10 (API 29).

## Download & Install
Download the compiled APK directly from the [Releases](https://github.com/MMohanraj21/5G/releases) page.
