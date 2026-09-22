# Folio for iOS

Native SwiftUI/UIKit iPhone and iPad app, using Readium Swift 3.8 for EPUBs and AVPlayer for audio.

[Download the latest Folio IPA](https://github.com/FormulaLatest/natan-media-portal/releases/latest/download/Folio-iOS.ipa). The link follows each paired release; see the [latest release notes](https://github.com/FormulaLatest/natan-media-portal/releases/latest) for the version.

## Install with SideStore

The device build is packaged as an **unsigned IPA**. Import the IPA in SideStore and let SideStore sign it with your configured account. It is not a TestFlight/App Store package and cannot be installed by tapping it in Files alone. Physical SideStore installation must be checked on the user's device.

## Features

- Home, searchable library, downloads and server tabs using native iOS navigation.
- Local EPUB and audiobook import through Files. E-book and audio can share one cover, with independent saved positions.
- Embedded artwork extraction and manual JPG/PNG replacement.
- Readium EPUB pagination, chapters, estimated-page grid, left-aligned serif text, text-size/scroll options and an end-of-book completion action.
- Background audio, iOS Now Playing commands and chapter-relative progress for chapter metadata recognized by AVFoundation, on the lock screen and in the app.
- A full-screen player coloured from the cover, with chapters on their own timeline sheet and a mini player above the tab bar once it is closed.
- BookLore OPDS browsing/downloads with cached local books and Keychain credentials.
- Manual approximate KOReader progress receive/send and undo; sending requires the companion plugin in `../koreader`.
- System/light/dark appearance with aquamarine, blue, lilac and peach accents.
- In-book search, bookmarks, nested chapter navigation, highlights, editable notes, native Look Up and saved vocabulary.
- Reader paper, font, line-spacing and margin settings saved across books.
- Annotation JSON export/import and optional private sync with Android. Identical book files are required. See `../services/annotations/README.md` for the separate service configuration.
- Automatic listening-position sync for Telegram audiobooks over the same annotation service, with nothing to tap: positions are sent while listening, on pause and on backgrounding, and are collected on launch and after a Telegram scan. It is off until Notes sync is connected, and a failure changes no saved position.
- Audiobook playback speed and sleep timer, including end of chapter.
- Discover Gutenberg e-books and LibriVox audiobooks; downloaded chapter files play offline under one title.
- PDFKit PDF reading and CBZ comic viewing with zoom, cover extraction and saved page. CBR is unsupported; EPUB-specific notes/search tools do not apply to these formats yet.

This is a native port, not a Kotlin wrapper. It uses iOS page transitions instead of Android's custom corner fold. Chapter metadata support follows AVFoundation and may differ from Android for Nero `chpl` or ID3 chapter tags. Audio is imported locally; BookLore downloads support EPUB. Local libraries and artwork are not automatically uploaded to the server. No text-to-narration alignment is performed.

## Build

Requires Xcode (tested with 26.6 and 27.0), XcodeGen, CMake, Ninja, gperf, Homebrew OpenSSL 3,
and iOS 17 or newer. No signing keys or Apple credentials are included.

**On a fresh checkout, build and package TDLib first.** Its generated XCFramework
is ignored by Git; Xcode and Tandem's simulator cannot build the app without it.
Run these commands sequentially from the repository root (the initial source
build takes time; subsequent builds reuse the ignored `build/` workspace):

```sh
bash scripts/build-telegram-native.sh "$PWD/build/telegram-native" simulator
bash scripts/build-telegram-native.sh "$PWD/build/telegram-native" ios
python3 scripts/package-telegram-native.py "$PWD/build/telegram-native" --platform ios
```

This produces `ios/Frameworks/TDLib.xcframework` with arm64 device and simulator
slices, without requiring the Android SDK or NDK. Intel simulators are not
supported. See [Telegram native builds](../docs/telegram-library.md#native-builds)
for the pinned dependency versions and combined Android/iOS setup.

Then build below, or use **Rebuild** in Tandem's iOS simulator panel.
If an earlier build failed with "There is no XCFramework found" and the error
persists after packaging, clean that build's Xcode build folder first. When
running `xcodebuild clean`, use the same project, scheme and `-derivedDataPath`
as the failing build; cleaning a different DerivedData folder will not reset
its cached missing-framework error.

```sh
xcodegen generate --spec ios/project.yml
xcodebuild -project ios/MediaPortal.xcodeproj -scheme MediaPortal \
  -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build
```

Run `xcodebuild test` with a booted iPhone simulator destination. Unit tests cover real EPUB/audio import, paired media persistence, reader construction, playback resume and OPDS origin checks. UI tests exercise native navigation, appearance and server forms.

Run `bash ios/package-ipa.sh` from the repository root to build the unsigned device IPA into the ignored `dist/` directory. Simulator binaries cannot be used for an IPA.

Readium and its dependencies retain their respective open-source licences. Package versions are pinned in the Xcode project's `Package.resolved` file. The app bundle includes third-party licence notices in `Licenses`.
