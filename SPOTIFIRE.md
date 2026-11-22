# Spotifire

Spotifeur's backend (Spotify sign-in, library sync, Spotify's own audio engine, YouTube Music,
Google account, local files, downloads) with a Spotify-style interface ported from Spotui.

Both projects are GPL-3.0, so Spotifire is GPL-3.0 too.

## Where things are

| Path | What |
| --- | --- |
| `app/src/main/java/fr/spotifeur/spotifire/` | **New UI**: theme, components, screens, root navigation |
| `app/src/main/java/fr/spotifeur/data`, `playback`, `SpotifeurModel.kt` | Spotifeur backend, unchanged |
| `app/src/main/java/fr/spotifeur/presentation/` | Spotifeur screens still used for Settings, Equalizer, History, Google sign-in and Convert (Spotify colors) |
| `native-runtime/artifacts/spotify-9.2.0.26.apkm` | Official Spotify APKM (ignored by Git, hash-checked by the build) |

What Spotifire adds on top of Spotify's look:

- Home and Library filter chips for **Spotify / YouTube Music / On this phone / Mixed**
- A source badge on every track and playlist that isn't from Spotify
- Search filters by source, and importing a playlist link (magnifier screen, link icon)
- An **Accounts** page (tap the round avatar) to connect or disconnect Spotify and Google
- Playlist ⋮ menu: add songs, edit, **Convert to Spotify / YouTube Music**, delete

## Build

Requirements: JDK 21, Android SDK (platform 37, build-tools, platform-tools), Git for Windows,
an ARM64 phone with Android 8+.

```powershell
cd $env:USERPROFILE\Desktop\spotifire
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Without the APKM (Spotify tracks stop after a few seconds, everything else works):

```powershell
.\gradlew.bat :app:assembleDebug -PnativeRuntime=false
```

The debug build is non-debuggable on purpose (Spotify's engine is rejected by CheckJNI).
