# RainLock

Touch-blocking overlay so you can keep navigation visible on your motorcycle in the rain without raindrops triggering taps. Unlock with a volume-button combo.

## How it works

- Foreground service draws a transparent (or slightly dimmed) full-screen overlay using `TYPE_APPLICATION_OVERLAY`. The overlay swallows all touches, so taps on the screen — from fingers or raindrops — do nothing.
- Your navigation app keeps running underneath; you can still see it.
- Volume buttons are intercepted via a `MediaSessionCompat` with a `VolumeProviderCompat`. The session is kept "playing" so it owns the media route and receives volume key events even while another app is in front.
- The screen is held on (`FLAG_KEEP_SCREEN_ON` + partial wake lock) for up to 12 hours.

### Unlock combo

Configurable in the app:
- **Key:** Vol Up only / Vol Down only / Either (must be same key repeated)
- **Press count:** 2–6
- **Window:** 500ms–3500ms

Default: 3 presses of either volume key within 2 seconds.

## What does NOT work (and why)

- **Power button is not interceptable.** Android consumes Power at the kernel/system level before any app — even an Accessibility Service — sees it. Power cannot be part of the unlock combo. Workaround: the app keeps the screen forced on while locked, so you won't need to wake it.
- **Volume buttons during voice navigation.** If your nav app briefly takes audio focus to speak a turn instruction, the system may route volume keys to it for those few seconds. Press again after the prompt finishes.
- **The system gesture nav bar / status bar** may still respond to swipes in some Android versions because the OS reserves edge gestures. The interior of the screen is fully blocked.

## Usage

1. Install. Open the app.
2. Choose your unlock combo. Tap **Start lock**.
3. The app will ask for **Display over other apps** permission the first time (and notification permission on Android 13+).
4. A dialog says "Lock in 3 seconds". Tap **Go**. The home screen appears.
5. Open your nav app (Google Maps, Waze, etc.) within 3 seconds. The overlay will drop on top.
6. To unlock: press your configured key the configured number of times within the window. The overlay disappears and you can tap normally again.
7. You can also unlock by tapping the **Unlock** action in the ongoing notification (pull down the notification shade — but that requires touching the screen, so it's a fallback).

## Building

Open the project in **Android Studio** (any version with AGP 8.5+). Let it sync; it will download the Gradle wrapper, dependencies, and SDK pieces.

Run on a device (USB debugging on). The first launch will route you to system settings to grant the overlay permission.

Min SDK 26 (Android 8). Target 34.

## Files

- `app/src/main/java/com/rainlock/app/MainActivity.kt` — settings UI + start flow
- `app/src/main/java/com/rainlock/app/LockOverlayService.kt` — foreground service, overlay, media session, unlock detection
- `app/src/main/java/com/rainlock/app/Settings.kt` — SharedPreferences config
- `app/src/main/AndroidManifest.xml` — permissions and service declaration
