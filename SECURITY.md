# Security Policy

Spotifire signs in to Spotify with your account and stores a session on your device, so security reports are taken seriously.

## What to report

- Credential or token leakage (logs, files, network requests to unexpected hosts).
- Credentials sent to a CDN or a third-party host.
- Weaknesses in how the session or offline data is encrypted on the device.
- Unexpected network requests.
- Build integrity problems, for example the runtime hash check being bypassed.

## What not to report

Spotify account problems, catalog availability or general Spotify service issues that are unrelated to Spotifire.

## How to report

Do not open a public issue with sensitive details. Use GitHub's private vulnerability reporting on this repository (Security → Report a vulnerability). If that is not available, open a minimal issue asking for a private channel.

## Trust model

- Spotifire talks to Spotify's own services. When Spotify has no lyrics for a track, it asks [LRCLIB](https://lrclib.net) with the title, artist, album and duration only (Settings → LRCLIB fallback, on by default). There is no Spotifire server, analytics or tracking.
- Your password is never stored. The email-code sign-in does not use a password at all.
- The session credential is encrypted with an Android Keystore key and never leaves the device except to Spotify's login service.
- You can build the APK yourself from this repository and your own copy of the official Spotify APKM.
- Song copies, Friends (Nostr relays, encrypted photo servers) and the update check reach other services; [docs/PRIVACY.md](docs/PRIVACY.md) lists each one and what it sees. Friends messages are end-to-end encrypted.
