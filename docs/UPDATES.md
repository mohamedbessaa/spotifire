# Updates

Spotifire is installed from GitHub releases, not from a store.

## In the app

- Once per launch, the app reads the latest release of `mohamedbessaa/spotifire` and compares its tag
  with the installed version (`1.10` is newer than `1.9`; a `-beta` build is older than its release).
- When a newer version exists, a popup shows its release notes with **Update now** and **Later**.
  - **Update now** downloads the APK inside the app, with progress, then opens Android's installer.
    The first time, Android asks to allow installing apps from Spotifire.
  - **Later** closes the popup until the next launch.
- No notifications. Drafts and pre-releases are never offered.
- A downloaded APK is kept only for the session that downloaded it.

## Publishing a release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build the signed APK: `./gradlew :app:assembleRelease` (needs `keystore.properties`, see the README).
3. Create a release tagged `vX.Y.Z` and attach the APK named `spotifire-vX.Y.Z.apk`. The app takes the
   asset whose name starts with `spotifire-`; a universal APK first, else one ending in `_<abi>.apk`.
4. Write the release notes in Markdown; the popup shows them.

Every release must be signed with the same key: Android refuses an update signed with another one.
