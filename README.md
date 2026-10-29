# Play Home

A console-style home screen for Google TV and Android TV, inspired by the PS4 and PS5 home
screens. Kotlin, no runtime dependencies, about 1.3 MB. Android TV 12 (API 31) and newer; it
installs on TVs only.

## Features

- **One row of apps.** Square tiles; the focused one grows and shows its name, and the app's
  banner becomes the backdrop, with a big **Open** button and a **···** menu underneath
  (Open, Refresh, Pin to Home / Move to front, App info). The row ends with the **Library**
  (every app, A to Z).
- **Your order.** By default pinned apps come first, then the ones you used most recently.
  Control Center → **Arrange apps** lets you put them in any order instead (pick up with OK,
  move with the arrows, drop with OK).
- **Control Center** (the gear, or Menu / Info on the remote): clock, weather, volume, one card
  per HDMI input, and shortcuts to Home, Library, Arrange apps, Wallpaper, Appearance, UI sounds
  and settings.
- **Wallpapers.** Six built-in, an animated Live theme, and up to six of your own sent from your
  phone by scanning a QR code (see Privacy). The picker previews as you move.
- **Dark and Light** appearance, soft UI sounds (can be turned off), and a hint bar that always
  shows what OK, Hold OK and Back do on the current screen.
- **Refresh** an app that's misbehaving: ··· → Refresh closes it and opens it fresh.

## Install

Download `app-release.apk` from the Releases page, then from a computer on the same network
as the TV (with [developer options and network debugging](https://developer.android.com/tv/develop/test-debug) on):

```bash
adb connect <tv-ip>:5555
adb install app-release.apk
```

Open Play Home from the TV's app list. To make it what the Home button opens:

```bash
adb shell cmd package set-home-activity io.github.mattdillioncodes.playhome/com.matthew.playhome.HomeActivity
```

Some Google TV builds keep sending Home to their own launcher until it's disabled
(`adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx`, or
`com.google.android.tvlauncher` on older ones). Re-enable it with `pm enable` to undo.
Uninstall Play Home with `adb uninstall io.github.mattdillioncodes.playhome`.

## Privacy

Play Home has no accounts, ads or analytics. It touches the network for two things only:

- **Weather** from [Open-Meteo](https://open-meteo.com) (free, no key). The city comes from the
  TV's time zone (e.g. America/Chicago → Chicago); that name is sent to Open-Meteo's geocoding
  service once each time Play Home starts, then the coordinates are used for forecasts every 15 minutes. °F in the US,
  °C elsewhere. No location permission. Time zones without a city (UTC) show no weather.
- **Adding your own wallpaper.** While the QR code screen is open, a small web server on the TV
  lets a phone on the same network send one photo. It's built to be safe:
  - it runs only while that screen is up, five minutes at most;
  - its only address carries a fresh 128-bit random code; anything else gets "not found";
  - it only answers devices on your local network;
  - one image at a time, 25 MB max, and only files that decode as images;
  - a few connections at most, so a flood from the network can't wear down the TV;
  - the photo is re-encoded as a new 1920x1080 JPEG, so no location or other metadata is kept;
  - nothing becomes a wallpaper until you choose **Keep** on the TV.

  It's plain HTTP: a TV on a home network can't get a trusted certificate for its local address,
  so the random code and the short lifetime are what protect it.

Settings (order, pins, wallpaper, appearance) and your own wallpapers stay on the TV.

## Build

Needs JDK 17+ (Android Studio's bundled one works) and the Android SDK.

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # unit tests: row order, weather place, QR round trip, upload server
./gradlew lintRelease
```

Release builds are signed with the key in `keystore.properties` (git-ignored) when it exists:

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it they're signed with the debug key, which is fine for your own devices only. Create the
key before publishing a release: Android only installs an update signed with the same key as the
installed copy, so moving from a debug-signed release to a real key means uninstalling first.

`./run.sh` builds a debug APK and runs it as the Home app on an emulator called `PlayHome_TV`
on port 5620 (it boots it if needed, and refuses to touch an emulator that's a different AVD).
Create one with:

```bash
avdmanager create avd -n PlayHome_TV -k "system-images;android-34;android-tv;arm64-v8a" -d tv_1080p
```

## Notes for contributors

- **Weak TV GPUs.** With photo wallpapers, the photo, app art and shades are composed into one
  bitmap off the UI thread, so each frame is a single full-screen draw; focus glows are wide
  strokes, not blurred shadows. On a TCL QM5K (PowerVR BXE-4-32) scrolling the row runs at a
  7 ms median frame. Live draws every layer each frame and pauses 12 s after the last key press.
- **App art.** TV app banners top out around 640x360 and the backdrop is 1382x777, so banners are
  enlarged once per app with a bicubic upscaler on a worker thread.
- **Colours** all come from `Look.kt`; views read them when built, so switching Dark / Light
  recreates the activity.
- **"Opened …"** only counts launches made from Play Home.
- **Row order** is `homeRow()` in `Apps.kt`.
- **The QR encoder** (`Qr.kt`) is hand-written to keep the app dependency-free; `QrTest` decodes
  every length it supports back with ZXing (a test-only dependency).
- **Wallpapers** are drawn by `tools/wallpapers.swift` (macOS): `swift tools/wallpapers.swift app/src/main/res/drawable-nodpi`.

## License

MIT, see [LICENSE](LICENSE).

Play Home is an independent project, not affiliated with or endorsed by Sony Interactive
Entertainment. PlayStation, PS4 and PS5 are trademarks of Sony Interactive Entertainment Inc.
