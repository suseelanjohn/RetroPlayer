# Retro Player

A retro CRT-green music player for Android. A spinning vinyl turntable sits at the centre of the app: drag the tonearm onto the record and the music starts, lift it off and it stops.

Built natively in Kotlin with no third-party libraries, so it stays small and runs on old phones. Works on **Android 5.1.1 (API 22) and newer**.

<!-- Add screenshots here, for example:
![Player](screenshots/player.png) ![Library](screenshots/library.png) ![Settings](screenshots/settings.png)
-->

## Features

- **Vinyl turntable.** Drag the tonearm onto the record to play, lift it to pause. The record spins at 33 or 45 RPM.
- **Background playback.** Music keeps playing when you leave the app or turn the screen off.
- **Notification and lock screen controls.** Previous, play/pause and next.
  - Android 5 and 6: a custom black and green CRT notification.
  - Android 7 and newer: the system media notification with green pixel buttons.
- **Likes and categories.** Tap the heart (or long-press a track) to like it. Browse liked songs and your most played songs on their own pages.
- **Pixel icon controls.** Shuffle, repeat (off / all / one), previous, play/pause, next.
- **Multi-page layout.** The first tab shows only the player. A bottom bar opens Library, Liked, Top and Setup.
- **Settings.** Shuffle, repeat, CRT scanlines, vinyl speed, keep screen on, reset play counts, clear likes.
- **CRT look everywhere.** Phosphor glow text, LCD-style display panel, scanline overlay, LED equalizer bars and a custom launcher icon.

## Install

1. Download `app-debug.apk` from the [Releases](../../releases) page.
2. Copy it to your phone and open it.
3. Allow installs from unknown sources if asked (Android 5.1.1: Settings, Security, Unknown sources).
4. Open the app and allow the storage permission. On Android 13+ also allow notifications so the playback controls appear.

## Build the APK with GitHub Actions

No Android Studio is needed.

1. Push this project to a GitHub repository. The `app` folder and `.github` folder must be at the repo root.
2. Open the **Actions** tab, choose **Build APK**, and click **Run workflow**.
3. When the run finishes (about 3 to 5 minutes), download **RetroPlayer-debug-apk** from the run's **Artifacts** section and unzip it.

The workflow only runs when you start it manually. To build on every push instead, change `on: workflow_dispatch` in `.github/workflows/build.yml` to `on: [push, workflow_dispatch]`.

## Build locally

Requirements: JDK 17 and Gradle 8.5 (or open the project in Android Studio).

```bash
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Project structure

```
.github/workflows/build.yml        GitHub Actions workflow
app/src/main/
  AndroidManifest.xml
  java/com/retro/player/
    MainActivity.kt                Pages, library, likes, settings
    PlayerService.kt               Background playback, media session, notification
    VinylView.kt                   Custom turntable view (record, tonearm, equalizer)
    ScanlineView.kt                CRT scanline overlay
  res/
    layout/                        activity_main.xml, notif.xml
    drawable/                      Pixel icons and backgrounds
    values/styles.xml              Green CRT theme and text styles
```

## Technical notes

| Item | Value |
|---|---|
| Language | Kotlin 1.9.22 |
| Min SDK | 22 (Android 5.1.1) |
| Target SDK | 33 |
| Build | Android Gradle Plugin 8.2.2, Gradle 8.5, JDK 17 |
| Playback | `MediaPlayer` inside a foreground service |
| Controls | `MediaSession` plus notification actions |
| Storage | `SharedPreferences` for likes, play counts and settings |
| Dependencies | None |

## Permissions

| Permission | Why |
|---|---|
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO` | Find music on the device |
| `POST_NOTIFICATIONS` | Show playback controls (Android 13+) |
| `FOREGROUND_SERVICE` | Keep music playing in the background |
| `WAKE_LOCK` | Keep playback running while the screen is off |

## Known limitations

- Debug build for personal use, not a Play Store release.
- The LED equalizer bars are decorative and do not react to the music.
- Local files only, with no streaming.
- Lock screen controls depend on your phone's notification settings.
- The fully custom black CRT notification appears on Android 5 and 6. Newer Android versions draw media notifications themselves.

## Roadmap

- Custom playlists (for example "Gym" or "Chill")
- Equalizer bars that react to the real audio
- Pixel font
- Signed release builds

## Contributing

Issues and pull requests are welcome. When reporting a bug, include your Android version and phone model.

## License

Add a license of your choice (for example MIT) as a `LICENSE` file in the repo root.
