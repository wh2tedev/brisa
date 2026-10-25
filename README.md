# Brisa

A minimal Android browser built on `WebView`, with Material 3 and a Chrome-style dark theme.
Google is the default search engine. No analytics, no accounts, no third-party services.

## Features

- Multiple tabs with a bottom-sheet tab switcher
- Find in page
- Desktop site mode
- Downloads through the system download manager
- Bookmarks (stored on the device only)
- Material 3 motion: spring press feedback, animated menu, tab sheet and omnibox, shared-axis transitions
- Connection indicator in the address bar (lock for HTTPS, warning for HTTP)
- File uploads through the system picker, and fullscreen video
- Opens `http` and `https` links from other apps

## Building

Requirements: JDK 17 and Android SDK 34.

- **Android Studio:** open the project folder and press *Run*.
- **Command line:** `gradle assembleDebug` (Gradle 8.7). The APK is written to `app/build/outputs/apk/debug/`.
- **GitHub Actions:** `.github/workflows/build.yml` builds the APK and uploads it as an artifact.

To generate the Gradle wrapper: `gradle wrapper --gradle-version 8.7`.

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
- `DNT` and `Sec-GPC` headers on top-level navigation (configurable).
- Web permissions (camera, microphone, location) are always denied.
- Non-web schemes (`intent:`, `file:`, `javascript:`, …) are blocked during navigation.
- No browsing history is stored by the app and backups are disabled.
- Clear data on exit, or on demand.
- The only permission requested is `INTERNET`.

## Release builds

Copy `keystore.properties.example` to `keystore.properties`, fill in your keystore details and run
`gradle assembleRelease`. Without that file the release build is left unsigned.
Create a keystore with `keytool -genkey -v -keystore brisa.jks -keyalg RSA -keysize 2048 -validity 10000 -alias brisa`.
Never commit the keystore or `keystore.properties`.

## File System Access API

`showOpenFilePicker()`, `showSaveFilePicker()` and `showDirectoryPicker()` reach Android WebView through
the same file chooser callback used for `<input type="file">`, which Brisa implements. Whether they work
depends on the installed WebView version, and save and folder pickers are limited on Android.
The Origin Private File System is a separate feature and does not need this.

## Not yet implemented

Private tabs are not possible with a plain `WebView`, which shares a single cookie store across the app.
History, a bookmark manager with folders, and per-site permissions are not implemented.

## License

MIT
