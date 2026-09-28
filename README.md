# Folio

Personal Android and native iOS ebook and audiobook library. One title can hold both formats, with independent reading and listening progress.

Formerly Media Portal. Folio keeps the same application identifiers so upgrades retain your library, settings and saved progress.

## Download

| Android | iPhone / iPad |
| --- | --- |
| **[⬇ Download APK](https://github.com/NatanRA/folio/releases/latest/download/Folio-Android.apk)** | **[⬇ Download IPA](https://github.com/NatanRA/folio/releases/latest/download/Folio-iOS.ipa)** |
| Android 8+ · install over the existing app | iOS 17+ · unsigned, sign using SideStore |

If a direct download does not open, use the **[latest release page](https://github.com/NatanRA/folio/releases/latest)** and select the file under Assets.

These links always download the newest release. Versions and changes are listed on the [latest release page](https://github.com/NatanRA/folio/releases/latest). See the [native iOS features and build instructions](ios/README.md).

## Connecting a library

In **Server**, enter your BookLore address and separate OPDS credentials. After connecting, select a cover and choose **Download EPUB**, then **Read book**. Downloaded books and separate reading/listening progress remain on the device after disconnecting the server.

### Without typing: scan a code

Run `swift scripts/make-pairing-code.swift` on your computer to turn a BookLore address, OPDS
username and password into a QR code drawn in the terminal. Scan it with the phone's own Camera
app and Folio asks before connecting; no camera permission is required. The code carries the
password, so see [pairing](docs/pairing.md) for what that means and why a per-device OPDS account
is worth creating.

### Telegram audiobooks

Native Telegram integration
adds audiobook streaming, a remembered chat catalogue in Library, and optional
offline downloads that retain saved progress. See [Telegram setup and native
build instructions](docs/telegram-library.md), including remaining live-account
verification.

### KOReader progress

Manual two-way approximate reading-position sync is available under Server → KOReader progress and the reader's Sync button. Receiving works with standard KOReader; sending requires the included companion plugin. Both devices need the identical EPUB and the same sync account. See [setup and limitations](koreader/README.md). Completion status and audiobook progress remain local and independent.

## Reading and listening

- Native Kotlin / Compose library and title screens.
- Import EPUB, PDF, CBZ and audio through the document picker; copy into private app storage for offline access.
- Readium EPUB rendering with Home, Previous and Next controls, plus native PDF/comic viewing.
- The page picker is an open book: swipe to turn pages in 3D, flick to riffle, or drag the page edges to jump, with a haptic tick for each page and a firmer one at each new chapter.
- Five reading papers: white, sepia, sakura (cherry-blossom pink), night and indigo. The reader's buttons and page numbers follow the paper, light on light pages and dark on dark ones, whatever the app's own appearance.
- Aquamarine, blue, lilac, peach and sakura accents, each with a deeper shade for light mode so accent text stays readable.
- Media3 audio playback with seeking and separate persisted timestamp.
- A full-screen player in the app's theme that grows out of a mini player above the tab bar and shrinks back into it when swiped down, with chapters on their own timeline sheet. One spring drives the whole morph, so it follows a dragging finger and a tap or drag can catch it mid-way and turn it around; nothing waits for an animation to finish. While it moves it asks for the display's highest refresh rate, and playback saves its position off the main thread, so nothing stalls a frame. The progress bar glides at the screen's frame rate, and skipping eases it straight to the new place without flicking back.
- A streamed Telegram audiobook's embedded cover is found with a few small reads of the file (about 55 KB for a 20-hour M4B), so covers load in the background instead of waiting for the book to be played.
- Attach an audio file to an ebook title (or an ebook to an audio title).

Embedded EPUB artwork is extracted when available, with typographic cover placeholders otherwise.

This is a development build. Automatic cross-device sync, exact cross-engine bookmarks, full navigation customization and resumable downloads remain future work. BookLore downloads currently support EPUB (up to 256 MiB); PDF/CBZ can be imported locally. Audio can be imported locally or downloaded from LibriVox in Discover. Annotation transfers are manual and need the separate service for network sync. No reading-to-narration alignment is planned.

## Build

Requires JDK 17 or newer and Android SDK 36.

```sh
ANDROID_HOME=/path/to/android/sdk ./gradlew :app:assembleDebug
```

Minimum Android version: 8.0. App ID: `com.natan.mediaportal`.

Generated development APKs are kept in the ignored `dist/` directory rather than source control.

No server credentials, personal books, or signing keys belong in this repository. Folio is a client of your BookLore server and of Telegram; it does not modify either.

## Tests

`./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug`

Instrumented tests require a clean emulator installation without a signed-in server. They cover import/link persistence, encrypted credential storage, navigation, search and cover rendering. The Alice EPUB fixture comes from Project Gutenberg, ebook 11 (`https://www.gutenberg.org/ebooks/11.epub3.images`); it is test-only and is not bundled into the app. Live sign-in to a personal server requires the user's OPDS credentials.

`chaptered.mp3` and `chaptered.m4b` are test-only 12-second generated tones with two six-second chapters and the same public-domain Alice cover. Media polish tests verify both chapter formats, invalid metadata, embedded artwork, replacement/recovery without losing progress, import copy, and chapter seeking.

## Publishing updates

Build and test locally, then:

1. Bump the version in `app/build.gradle.kts` (`versionName`, `versionCode`) and `ios/project.yml`
   (`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`). Both platforms carry the same version; the
   two build numbers only ever increment.
2. Write the full notes as `docs/releases/v<version>.md`.
3. Add an entry to [Release history](#release-history), a sentence or two linking to those notes.
   The README is the changelog most people see first, so it does not get to fall behind.
4. Commit and push the source, then publish both verified packages together:

```sh
python3 scripts/publish-release.py RELEASE_TAG --apk dist/ANDROID.apk --ipa dist/IOS.ipa --notes release-notes.md
```

Build the packages without a Telegram identity installed: anything inside them can be read by whoever downloads them, and the helper refuses a package that contains `telegram-private.json` (see [Telegram account setup](docs/telegram-library.md#account-setup)).

Build the APK with `./gradlew :app:assembleRelease` and the IPA with `bash ios/package-ipa.sh`. The APK
is signed with Folio's release key, which never enters the repository: the keystore is
`~/.android/folio-release.jks` and its passwords are in `~/.android/folio-release.properties`, read by
`app/build.gradle.kts`. Keep both files backed up somewhere safe, such as a password manager. Android
only installs an update signed with the same key as the installed app, so a lost key means everyone
uninstalls and starts again; to publish from another machine, copy both files there. The helper
refuses an APK signed with anything else: its certificate SHA-256 must be
`cd51ae36a3f1feabf8866113a40153d49bad794aa48b29164a11fe8cc86d6bf2` (`ANDROID_CERTIFICATE_SHA256` in
`scripts/publish-release.py`). Releases up to 0.9.2 were signed with whichever machine's debug key
built them.

The helper uploads both packages as `Folio-Android.apk` and `Folio-iOS.ipa`, plus SHA-256 checksums, and verifies the draft assets before marking the release as latest. The README links automatically follow every update; never replace them with a version-specific URL. Legacy `Media-Portal-Android.apk` and `Media-Portal-iOS.ipa` aliases are also uploaded so older shared links keep working. When only one platform changes, include the latest verified package for the other platform and state that in the release notes. No hosted build runners are required.

## Remaining work

1. Extend BookLore OPDS formats and resume interrupted downloads.
2. Broader Bluetooth and physical-device background playback testing.
3. Automatic cross-device synchronization, keeping ebook and audio progress independent.

## License

Folio is released under the [MIT License](LICENSE). Bundled third-party components keep their own
licences, included in `app/src/main/assets/licenses`, `app/src/main/assets/dictionary` and
`ios/Resources/Licenses`.

## Release history

Every release adds an entry here. Full notes live in [docs/releases](docs/releases) and on the
[releases page](https://github.com/NatanRA/folio/releases).

Android and iOS were versioned separately until 0.9.0, so earlier entries name two numbers — for
example Android 0.8.8 alongside iOS 0.3.8. From 0.9.0 both platforms share one version.

### 0.9.8

Listening positions move between phones reliably: a phone set up fresh catches up, opening a book
no longer rewinds the other phone, and a paused book takes a newer position from elsewhere. On
iPhone, switching audiobooks keeps the one you left where it was. Only Telegram audiobooks sync,
through the notes server. [Full notes](docs/releases/v0.9.8.md).

### 0.9.7

The player's animation runs at the screen's full rate on iPhone, up to 120 Hz, and no longer stalls
twice a second while audio plays; Android saves positions off the main thread and asks for its
highest refresh rate while the card moves. Streamed Telegram audiobooks show their covers without
being played first. On iPhone, choosing a cover from Files works, including pictures in iCloud Drive
and HEIC, and "Add e-book" and "Add audiobook" on a book's page work again.
[Full notes](docs/releases/v0.9.7.md).

### 0.9.6

The player can be tapped or dragged at any moment while it grows out of the mini player or shrinks
back, and it keeps the speed of your swipe. The progress bar moves smoothly and eases into skips
instead of stepping or jumping back. On Android the player's title and close arrow are readable in
dark mode. [Full notes](docs/releases/v0.9.6.md).

### 0.9.5

The page picker is a book you flip through: swipe to turn pages in 3D, flick to riffle, drag the page
edges to jump, with haptics for each page and chapter, and a closed cover at page 1. The reader's
controls follow the paper so they stay readable, accents are readable in light and dark mode, and there
are new Sakura and Indigo papers and a Sakura accent. The iPhone book page fits without scrolling.
[Full notes](docs/releases/v0.9.5.md).

### 0.9.4

The player follows the app's light or dark theme and your accent colour, grows out of the mini
player when you tap it and shrinks back into it when you swipe it down. Telegram setup reads as a
first-time step, and a Telegram audiobook no longer starts a half-finished connection before setup.
[Full notes](docs/releases/v0.9.4.md).

### 0.9.3

The audiobook player is rebuilt around chapters on both platforms: it fits on one screen, takes its
colour from the cover, keeps every chapter on its own timeline and leaves a mini player above the
tab bar when you close it. Android can save its library again after 0.9.2 broke imports and
downloads for titles outside a series. [Full notes](docs/releases/v0.9.3.md).

### 0.9.2

Audiobooks held on your library server are downloadable at last: Folio used to discard every audio
edition a catalogue offered. Series now come from the catalogue rather than being guessed from
title strings, in both the BookLore and BookOrbit shapes, and listening positions for Telegram
audiobooks follow you between devices through the private annotation service.
[Full notes](docs/releases/v0.9.2.md).

### 0.9.1

Telegram no longer asks for an API ID and API hash. The app carries its own application identity,
so connecting Telegram is an ordinary sign-in: a phone number and the code Telegram sends.
Connection failures now say what went wrong instead of repeating one generic sentence.
[Full notes](docs/releases/v0.9.1.md).

### 0.9.0

One version number for both platforms, from Android 0.8.8 and iOS 0.3.8. Connect a BookLore
library by scanning a code generated on your computer, with Folio asking before it connects and
neither app requesting camera access. The iOS credential fields work with password managers and
gained a Paste button, audiobooks appear in Continue on Home, which now offers the title you
opened most recently, and Telegram application credentials can ship with the build instead of
being asked of every reader. [Full notes](docs/releases/v0.9.0.md).

### Android 0.8.8 / iOS 0.3.8

The iPhone Appearance sheet changes theme the moment you choose one rather than waiting until it
is closed and reopened, and the Android server form no longer arrives with a personal server
address and username already filled in.
[Full notes](docs/releases/v0.8.8-ios.0.3.8.md).

### Android 0.8.7 / iOS 0.3.7

The Android bottom bar became a floating frosted capsule matching the iOS one, with an accent
pill that glides to the selected tab and press-and-slide selection.
[Full notes](docs/releases/v0.8.7-ios.0.3.7.md).

### Android 0.8.6 / iOS 0.3.6

First attempt at making the iOS Appearance sheet follow the Light, Dark or System setting
immediately. It still kept the scheme it opened with on iOS 26, which 0.8.8 fixed.
[Full notes](docs/releases/v0.8.6-ios.0.3.6.md).

### Android 0.8.5 / iOS 0.3.5

Library sorting on both platforms — Title, Author, Series or Date added, remembered between
visits — with series recognised from the titles themselves, so numbered sets fall into order
without extra metadata. iOS gains the offline dictionary Android already had.
[Full notes](docs/releases/v0.8.5-ios.0.3.5.md).

### Android 0.8.4 / iOS 0.3.4

Search moved out of a pinned strip and into the toolbar, opening a blurred layer over the page.
The audiobook player is aligned across both platforms, and Telegram streaming on iOS recovers
after an app update. [Full notes](docs/releases/v0.8.4-ios.0.3.4.md).

### Android 0.8.3 / iOS 0.3.3

Android reads QuickTime text chapter tracks in M4B audiobooks, so books whose chapters appeared
missing show their names and timestamps without reimporting.
[Full notes](docs/releases/v0.8.3-ios.0.3.3.md).

### Android 0.8.2 / iOS 0.3.2

The app became Folio, with the rounded stack-of-books logo, in-app branding that follows your
accent colour and wallpaper-themed launcher icons on Android 13+. This release also carried the
Telegram streaming, unified search and PDF/CBZ OPDS support built since 0.7.0.
[Full notes](docs/releases/v0.8.2-ios.0.3.2.md).

### Android 0.7.1 / iOS 0.2.1

One universal search field at the top of the main app, with My library and Free books results. The separate Discover tab and duplicate library search fields are removed. Free-catalogue connection errors have a readable message and Retry action; personal-library searches do not depend on Gutendex.

BookLore OPDS downloads now accept PDF and CBZ as well as EPUB on Android and iOS. EPUB is preferred when a title offers multiple supported editions. The download retains its format, server identity and offline library entry. Interrupted-download resume is still pending.

Built but never published on its own; these changes reached readers in 0.8.2.

### Android 0.7.0 / iOS 0.2.0

- Reader menu → Options: white, sepia or night paper, serif/sans serif, text size, line spacing and margins. Settings persist across books on this device; text remains left aligned.
- Long-press book text to highlight it or attach a note. Reader menu → Highlights & notes supports searching this book's notes, returning to the passage, editing and deletion.
- In-book search, nested chapter navigation, bookmarks, dictionary lookup and saved words on both platforms. Both apps bundle an offline English dictionary (WordNet 3.0): Define in the selection menu shows definitions and examples inside Folio, and saved words can be defined again from the Saved words list.
- Private annotation sync and portable JSON export/import for highlights, notes, bookmarks and vocabulary. Identical book files are matched by SHA-256. Changes merge by revision time, with persistent deletion records. Configure a separate annotation service in Server; this does not sync annotations with BookLore or KOReader. See [annotation service setup](services/annotations/README.md).
- Audiobook sleep timer: 15/30/45/60 minutes or end of chapter, owned by background playback rather than the screen.
- Discover free Gutenberg e-books (Gutendex catalogue) and LibriVox audiobooks. Downloads stay local. LibriVox chapter files preserve names, separate chapter scrubbing and offline playback. Keep Discover open while downloading; leaving cancels unfinished downloads. Public catalogues need internet and availability varies by country.
- Local PDF and CBZ comics: native page viewing, pinch zoom, page navigation, cover extraction and saved page. CBR is not supported. EPUB-specific search, highlights and notes are not available in PDF/CBZ yet.
- Includes the recent reader Home/control alignment fixes and persistent audiobook playback speed (0.5×–3×).

### 0.6.13

- Customizable Material 3 appearance, compact branding, and directional navigation transitions.
- Paper-style EPUB reader, page picker, home/previous/next controls, and chapter-aware page-fold rendering.
- Background audiobook playback with Android media controls. Supported embedded chapters provide chapter-relative notification seeking while the app preserves the whole-book position.
- Themed reader menus and page picker, collapsible server settings, and local/cloud availability badges.

### 0.3.0 — KOReader resume

Manual two-way approximate reading-position sync under Server → KOReader progress. See [Connecting a library](#koreader-progress) for how it works today.

### 0.2.3 — book completion

- Mark an e-book as finished from the reader or title detail, including before references and credits.
- Completion stays at 100% when reopened; Undo restores progress from the saved reading position. Audiobook progress is independent.
- End matter remains accessible; no pages are automatically removed.

### 0.2.2 — media polish

- Load e-book / Load audiobook actions open a confirmation with supported file types.
- Audiobook imports extract embedded artwork; missing covers on existing audio titles are recovered when displayed.
- Edit artwork lets users choose JPG, PNG or WebP (up to 20 MB) or recover embedded artwork. Custom covers are stored separately on the device and take precedence over server/embedded covers.
- Audio timeline markers, current chapter and a seekable chapter list support MP3 ID3 CHAP, MP4/M4A/M4B Nero `chpl` metadata, and QuickTime text chapter tracks. External chapter files are not supported yet.
- Chapter extraction skips audio payloads and bounds metadata reads. Files without supported chapters retain the normal seekable timeline.

### 0.2 — first release

- Dark, cover-focused Home, Library, Downloads and Server screens.
- BookLore OPDS sign-in, complete paginated catalogue browsing, search and cached covers.
- EPUB downloads validated by Readium before adding them to the offline library.
- HTTPS-only requests; credentials encrypted using Android Keystore. Redirects and feed links must stay on the configured server origin.
- Cached catalogue remains available when the server is offline. Refresh failures preserve existing books.
