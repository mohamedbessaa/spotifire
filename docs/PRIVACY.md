# Privacy

Spotifire has no server, no analytics, no crash reporting and no advertising SDK. Diagnostics are
recorded on the phone and leave it only if you copy them yourself.

## Where the app connects

| Service | Why | When |
| --- | --- | --- |
| Spotify | sign-in, library, metadata, Spotify's engine | with a Spotify account |
| YouTube / Google | YouTube Music search, streams, your playlists | when you use YouTube Music |
| Monochrome | copies of Spotify songs (found by ISRC) | Spotify Free or no account |
| LRCLIB, LyricsPlus | lyrics | when lyrics are shown |
| GitHub | the latest release, to offer updates | once per launch |
| Google Cast | casting local files | when you cast |

## Friends

Nothing below happens until you set up Friends.

- **Identity.** A key made on the phone. It is stored encrypted with the Android Keystore and backed
  up by Google Block Store where available. Your 12-word recovery phrase is shown only after your
  screen lock, in Friends settings.
- **Messages** (requests, shares, invitations, receipts, shared playlist changes) travel through
  public Nostr relays: nos.lol, relay.primal.net, nostr.mom, relay.snort.social, offchain.pub. Each
  message is sealed and gift-wrapped (NIP-17/44/59): relays see a random sender key, the recipient's
  public key and the time, never the content or the real sender. Shared playlists and Listen together
  use a key known only to their members.
- **Directory.** While *Let people find me by name* is on, your name, your public key and, if you set
  one, a small photo are public so people can find you. Turning it off withdraws them.
- **Profile photo.** Uploaded encrypted to Blossom file servers (nostr.download, files.sovbit.host,
  24242.io). Only friends receive the key to open it.
- **Listening now.** Off by default. When on, friends receive the song you play, encrypted; it
  expires after an hour. Files on your phone are never announced.
- **Blend** shares up to 200 of your liked and most played songs with that friend, encrypted.
- **Listen together** asks public time servers (time.google.com, time.cloudflare.com, pool.ntp.org)
  the time, to play at the same moment on every phone.
- **Blocking** someone drops everything they send. Removing or blocking a friend stops sending them
  anything.

Relays and file servers are run by third parties. They keep encrypted data for as long as their own
rules say; Spotifire asks the file servers to delete a photo you replace or remove.
