# Changelog

## v1.1.3

### Added

- Ask to join what a friend is playing, or invite them to what you play, from their card in Friends.
- Everyone in a Jam can play, pause, skip and seek (in the app, the notification or the headphones);
  the Jam shows who did what last.
- Canvas for YouTube Music and local songs, borrowed from the same recording on Spotify.
- Settings: Automatic updates (off: no check at launch) and Check for updates.

### Changed

- Sound is processed in 32-bit float from the decoder to the output and ends in a true-peak limiter
  (−0.3 dBTP): equalizer and headphone-profile boosts no longer clip.
- Listen together stays in sync with inaudible corrections (at most 0.1%) instead of speed steps,
  changes songs gaplessly, and holds a skip 0.5 s instead of 1.5 s.

### Fixed

- Cuts in a Jam: a guest no longer seeks again and again after starting a song.
- Lyrics: the line being sung keeps its spaces, and lyrics follow the music frame by frame.
- The Canvas pauses with the song.
- Xiaomi phones (HyperOS) refused to install the APK ("package appears to be invalid"); releases are
  now signed with every scheme.

## v1.1.2

### Changed

- The update popup is BitChord's: a frosted "Software Update" alert with the release notes rendered as
  Markdown, Download Now / Remind Me Later, progress in place, then Install Now.

### Fixed

- A Jam invitation is announced once, not again each time the app restarts.
- The Friends connection notification no longer comes back after being dismissed (it was posted again at
  every app launch and every 15-minute check).
- Canvas follows the song: after Next or Back the previous song's clip no longer stays frozen on screen.
- Friend codes are links (`spotifire://friend/…`) that phone cameras and QR apps can open in Spotifire, drawn
  with their quiet zone; Add friends can also read a code from a photo or screenshot.
- The GitHub build runs again (the Gradle wrapper was not executable).

## v1.1.1

### Fixed

- Friends stays connected in the background: a card (and a row in Friends settings) lets Spotifire run without
  battery limits, and the 15-minute check brings the connection back if Android stopped it. Requests and Jam
  invitations no longer wait until the app is opened.
- Jam invitations stay valid for 2 hours, so a phone that was offline still gets them; joining a Jam that already
  ended leaves it after 20 seconds.
- Listen together reads each phone's position from the audio itself and filters reporting noise; clocks come from
  public time servers.

## v1.1.0

### Added

- **Friends** tab: add people by name, `name#CODE` or QR code; requests need a yes.
- Send songs, albums, artists and playlists to friends inside the app, with a note; played and
  saved receipts.
- **Edit together**: shared playlists every member can edit, with who added each song.
- **Blend**: a daily 50-song playlist from two friends' tastes.
- **Listen together**: Jam sessions on a common clock; each friend chooses to join.
- Profiles with an encrypted photo, listening now (off by default) and a recovery phrase.
- Spotify Free plays and downloads each song from its best copy (Monochrome by ISRC, then YouTube Music).
- Sound: loudness levels with a limiter, EQ with automatic headphone profiles, dimensional mode,
  crossfade and Automix.
- Canvas videos, Smart Shuffle, word-by-word lyrics, Made for you mixes, frosted glass bars, app icons.
- Updates from GitHub releases, offered in an in-app popup (Update now / Later).

### Changed

- New notification icon; the app stays in portrait.
- Sharing happens inside the app only; the Android share sheet is gone.
- The app ships in English only.

### Removed

- Version and diagnostic report rows in Settings.

## v1.0.0

- First Spotifire release: Spotifeur's backend with the Spotify-style interface from Spotui.

Spotifeur's own history is in [its changelog](https://github.com/jean-voila/Spotifeur/blob/main/CHANGELOG.md).
