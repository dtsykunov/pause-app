# Pause

A minimal Android app that adds a short, timed pause before you open chosen apps. When you launch a
selected app, the screen is covered by a panel with a countdown; only after it finishes can you choose
**Open anyway** or **Cancel**. The pause screen also shows how many times you've opened that app in the
last 24 hours and when you last opened it.

It uses an Accessibility Service to detect app launches and an accessibility overlay to draw the pause
screen — no "draw over other apps" permission, and the app has no network access.

> **Check your phone first.** Recent Android versions include this natively: Digital Wellbeing's app
> timers and Focus mode, and Android 17's Pause Point (a built-in delay before selected apps, on Pixel
> and Samsung). If your phone has those, prefer them. Pause is for older devices, custom ROMs, and
> phones that ship without Google's Digital Wellbeing.

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
3. Back in the app, set the **pause length** and choose the apps you want to gate.

## How it works

- `AppMonitorService` — accessibility service; reacts to foreground-app changes and triggers the
  overlay for paused apps. A configurable per-app allow-window (default 5 min) after "Open anyway"
  avoids re-prompting that app during its session.
- `InterventionOverlay` — the full-screen cover: it covers the screen immediately, eases in a pulsing
  circle and countdown, then reveals the choice buttons; it slides back down on dismiss.
- `Prefs` — stores the paused-app set, pause length, the rolling 24h list of open attempts, and the
  per-app stats.
- `MainActivity` / `AppsActivity` / `StatsActivity` — settings (accessibility status, pause length,
  message, allow-window), the searchable app picker, and per-app stats.

Note: an accessibility-based gate is best-effort, not a hard block — it's designed to make impulsive
opens deliberate, not to be tamper-proof.
</content>
</invoke>
