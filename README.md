# Pause

A minimal Android app that adds friction before you open chosen apps. When you launch a
selected app, the screen is covered by a calming panel that slides up, runs a short breathing
countdown, and shows how many times you've opened that app in the last 24 hours. Only after
the countdown can you choose **Open anyway** or **Not now**.

It uses an Accessibility Service to detect app launches and an accessibility overlay to draw
the pause screen — no extra "draw over other apps" permission needed.

## Build

```sh
nix develop        # provides JDK 21 + Android SDK + Gradle
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

With the phone connected over USB (USB debugging enabled):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Set up on the phone

1. Open **Pause**.
2. Tap **Enable accessibility access** and turn on "Pause monitor" in the system settings.
   (On some phones this lives under *Settings → Accessibility → Installed apps*.)
3. Back in the app, set the **pause duration** and toggle on the apps you want to gate.

## How it works

- `AppMonitorService` — accessibility service; reacts to foreground-app changes and triggers
  the overlay for paused apps. A 20s grace window after "Open anyway" avoids re-prompting.
- `InterventionOverlay` — the full-screen cover: slide-up panel, breathing circle, countdown,
  then the choice buttons; slides back down on dismiss.
- `Prefs` — stores the paused-app set, pause duration, and a rolling 24h list of open attempts.
- `MainActivity` — settings: accessibility status, duration slider, and the app picker.

Note: an accessibility-based gate is best-effort, not a hard block — it's designed to make
impulsive opens deliberate, not to be tamper-proof.
