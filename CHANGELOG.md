# Changelog

## v1.1.2

### Changed

- The update popup is BitChord's: a frosted "Software Update" alert with the release notes rendered as
  Markdown, Download Now / Remind Me Later, progress in place, then Install Now.

### Fixed

- A Jam invitation is announced once, not again each time the app restarts.

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
