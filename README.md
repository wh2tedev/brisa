# Brisa

A minimal Android browser built on `WebView`, with Material 3 and a Chrome-style dark theme.
Google is the default search engine. No analytics, no accounts, no third-party services.

## Features

- Multiple tabs with a bottom-sheet tab switcher
- Find in page
- Desktop site mode
- Downloads screen with progress, open, share and delete; choose the download folder
- Settings by section: search engine, homepage, theme (light, dark, pure black), accent color, address bar at the top or bottom, text size
- Bookmarks and history, stored on the device only
- Address bar suggestions computed locally from bookmarks and history
- Pull to refresh
- Pop-up windows opened by a tap (sign-in flows) open as a tab and return to the page that opened them
- Username and password prompt for HTTP authentication, with a warning on unencrypted connections
- Links such as `mailto:`, `tel:` and `intent:` open in another app after you confirm (only when you tap them)
- Error pages that follow the theme, with a retry button and hints for local servers
- Material 3 motion: spring press feedback, animated menu, tab sheet and omnibox, shared-axis transitions
- Connection indicator in the address bar (lock for HTTPS, warning for HTTP)
- File uploads through the system picker, and fullscreen video
- Tabs are restored on launch (only the selected tab loads until you open the others)
- Optional download confirmation, including `data:` and `blob:` downloads
- Tracker and ad blocking for third-party requests, on by default
- Opens `http` and `https` links from other apps

## Building

Requirements: JDK 17 and Android SDK platform 37.0 (`sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`).
Android 17 is published only as minor-versioned platforms, which needs Android Gradle Plugin 8.13 or newer.

- **Android Studio:** open the project folder and press *Run*.
- **Command line:** `gradle assembleDebug` (Gradle 8.13). The APK is written to `app/build/outputs/apk/debug/`.
- **GitHub Actions:** `.github/workflows/build.yml` builds the APK and uploads it as an artifact.

To generate the Gradle wrapper: `gradle wrapper --gradle-version 8.13`.

## HTTP, HTTPS and local development

Cleartext traffic is allowed so that `localhost`, LAN addresses and dev servers work.

- Typing `localhost:3000`, `192.168.1.10:8080` or `myhost.local` opens it over HTTP.
- Other addresses typed without a scheme open over HTTPS.
- With **Try HTTPS first** enabled (default), `http://` links to public sites are upgraded to HTTPS,
  falling back to HTTP only if the connection fails. Certificate errors never fall back.
- Local and private addresses (`localhost`, `127.x`, `10.x`, `192.168.x`, `172.16–31.x`,
  `*.local`, single-label hostnames) are never upgraded.
- Mixed content (HTTP resources on HTTPS pages) is always blocked.

## Privacy and security

- Certificate errors cancel the load, with no option to proceed.
- Safe Browsing enabled; local file and content provider access disabled.
- Third-party cookies blocked by default.
- Third-party requests to known ad and tracking domains are blocked (`app/src/main/assets/blocklist.txt`).
- Downloads ask for confirmation by default. `blob:` downloads use a one-time token and are capped at 25 MB.
- `DNT` and `Sec-GPC` headers on top-level navigation (configurable).
- Web permissions (camera, microphone, location) are always denied.
- Non-web schemes (`intent:`, `file:`, `javascript:`, …) are blocked during navigation.
- History stays on the device, can be turned off in Settings, and is erased by *Clear browsing data*. Address bar suggestions are computed locally; nothing is sent to Google until you submit a search. Backups are disabled.
- Clear data on exit, or on demand.
- The only permission requested is `INTERNET`.

## Release builds

Copy `keystore.properties.example` to `keystore.properties`, fill in your keystore details and run
`gradle assembleRelease`. Without that file the release build is left unsigned.
Create a keystore with `keytool -genkey -v -keystore brisa.jks -keyalg RSA -keysize 2048 -validity 10000 -alias brisa`.
Never commit the keystore or `keystore.properties`.

## File System Access API

`showOpenFilePicker()`, `showSaveFilePicker()` and `showDirectoryPicker()` reach an app through the WebView
file chooser callback. Brisa maps open, save and folder requests, plus the read/write hint, to the system
document pickers. Google keeps the API disabled for apps by default, because older apps would not understand
the new modes. The folder mode and read/write hint were added in Android 17 (API 37), so Brisa targets API 37.
Where the installed Android or WebView does not enable the API, pages simply see it as unavailable and
ordinary `<input type="file">` uploads keep working. Android file providers do not support every operation
(for example atomic writes), so some web apps behave differently than on desktop.
The Origin Private File System is a separate feature and does not need any of this.

## Not yet implemented

Private tabs are not possible with a plain `WebView`, which shares a single cookie store across the app.
Bookmark folders and per-site permissions are not implemented.

## Updating

Android only installs an update when the new APK has the same package name and is signed with the same key.
Release APKs are always signed with the maintainer's key, so they update each other. Debug builds use the fixed key
in `app/debug.keystore` (public, not secret) and the package `app.brisa.browser.debug`, so they install next to the
release app and update each other too. A release and a debug build never replace one another.

## Tests and releases

`gradle testDebugUnitTest` runs the unit tests (address-bar rules, tracker blocklist).
`.github/workflows/build.yml` runs tests and lint and builds the debug and release APKs on every push.

To publish, add these repository secrets: `KEYSTORE_BASE64` (`base64 -w0 brisa.jks`), `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`. Pushing a tag such as `v0.9.0` runs `release.yml`, which builds a signed APK
and attaches it to a GitHub Release with its SHA-256 and the signing certificate fingerprint.

## Credits and licenses

Brisa is MIT licensed. It uses AndroidX, Material Components, the Kotlin standard library and Material Design icons
(all Apache-2.0); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the in-app list under
Settings > About > Open-source licenses. Web pages are rendered by the system's Android System WebView (Chromium),
which Brisa does not bundle.

Android, Google, Chrome, Google Search and Chromium are trademarks of Google LLC. Other search engines named in
Settings belong to their owners. Brisa is an independent project and is not affiliated with or endorsed by any of them.

## License

MIT
