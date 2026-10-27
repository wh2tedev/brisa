# Third-party notices

Brisa is released under the [MIT License](LICENSE). It is built on the open-source work below.
Nothing here changes the license of Brisa itself.

## Included in the app

| Component | License | Used for |
| --- | --- | --- |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Core KTX, AppCompat, Preference, SwipeRefreshLayout, DynamicAnimation, RecyclerView, DocumentFile, WorkManager, Biometric) and its transitive libraries | Apache-2.0 | Activities, settings screens, lists, spring animations, file access, background work |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache-2.0 | Material 3 widgets, dialogs and bottom sheets |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | Apache-2.0 | Language runtime |
| [Material Design icons](https://github.com/google/material-design-icons) (some adapted) | Apache-2.0 | Interface icons |

The exact versions are in `app/build.gradle.kts`. The full Apache License 2.0 text is in
[`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt) and in the app under Settings > About > Open-source licenses.

## Used only to build and test (not distributed)

| Component | License |
| --- | --- |
| [JUnit 4](https://github.com/junit-team/junit4) | Eclipse Public License 1.0 |
| [Hamcrest](https://github.com/hamcrest/JavaHamcrest) (JUnit dependency) | BSD-3-Clause |

## Landing page

| Component | License | Notes |
| --- | --- | --- |
| [Bricolage Grotesque](https://github.com/ateliertriay/bricolage) | SIL Open Font License 1.1 | Copyright 2022 The Bricolage Grotesque Project Authors |
| [Figtree](https://github.com/erikdkennedy/figtree) | SIL Open Font License 1.1 | Copyright 2022 The Figtree Project Authors |

The fonts are not stored in this repository until you add them. When you do, also add the `OFL.txt` file that comes
with each download and keep its copyright line unchanged, as the license requires. The license text is at
<https://openfontlicense.org>.

## Not bundled

- **Android System WebView / Chromium** renders web pages. It is provided by the device, is maintained by its
  own project and is licensed under its own terms (mainly BSD-style). Brisa does not copy or modify it.
- The tracker domain list in `app/src/main/assets/blocklist.txt` was written for Brisa and is not copied from any
  third-party list. If you import one, check its license (many are GPL or CC BY-SA) and add it here.

## Trademarks

Android, Google, Google Chrome, Google Search and Chromium are trademarks of Google LLC. DuckDuckGo, Bing, Brave,
Ecosia and Startpage are trademarks of their respective owners. They are used only to name the services Brisa can
open or to describe compatibility. Brisa is an independent project and is not affiliated with, sponsored or
endorsed by any of them.
