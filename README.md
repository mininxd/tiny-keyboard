# Tiny Keyboard

<img src="images/keyboard.png" width="500"/>

## About

An ultra-minimalist, private Android keyboard with the smallest possible APK size (~34 kB release APK), modern Material 3 styling, and zero external dependencies.

- **Size**: ~34 kB release APK
- **Permissions**: 0 (no internet, no telemetry, no storage access)
- **Dependencies**: 0 external libraries
- **Layouts**: en_US (QWERTY with standard Gboard symbol placement)
- **License**: Apache License 2.0

## Features

- **Material 3 UI**: Clean, rounded key cards with native state feedback and zero legacy text shadows.
- **Theme Selection**: Choose between **Auto** (follows system dark mode), **Light**, or **Dark** themes.
- **Auto & Adjustable Height**: Automatically scales proportionally to the display size (`8%p` per row in portrait, `13%p` in landscape).
- **Haptic Feedback**: Subtle tactile feedback on keypress.
- **Gboard-Style Symbols**: Muscle-memory layout matching standard Gboard positions for `?`, `!`, `"`, `'`, `:`, `;`, `*`, `(`, `)`, etc.
- **Quick Settings Dialog**: Hold the `.` (dot) key and drag upward (or swipe up from `.`) to access the in-keyboard settings menu to toggle haptics, adjust the height slider (70% – 130%), and pick your theme.

## How it's made

Android OS contains default [Keyboard](https://developer.android.com/reference/android/inputmethodservice/Keyboard) and [KeyboardView](https://developer.android.com/reference/android/inputmethodservice/KeyboardView) implementations. Input method developers can use these classes as base for their own keyboard implementations. Tiny Keyboard enhances these with Material 3 styling, dynamic scaling, and custom gesture settings while keeping code and resource footprints strictly minimal.

## Downloads

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
      alt="Get it on Google Play"
      height="80">](https://play.google.com/store/apps/details?id=rkr.tinykeyboard.inputmethod)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
      alt="Get it at IzzyOnDroid"
      height="80">](https://apt.izzysoft.de/packages/rkr.tinykeyboard.inputmethod)

Pre-built `app-debug.apk` and `app-release.apk` are also automatically built and uploaded to GitHub Actions artifacts on every push.

## Credits

Based on https://android.googlesource.com/platform/development/+/master/samples/SoftKeyboard

AOSP Keyboard.java: https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/inputmethodservice/Keyboard.java

AOSP KeyboardView.java: https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/inputmethodservice/KeyboardView.java
