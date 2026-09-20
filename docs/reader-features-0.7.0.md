# Reader features: Android 0.7.0 / iOS 0.2.0

Implemented on both native apps, in the agreed order:

1. EPUB text search with contextual results and navigation to a match.
2. Saved bookmarks and a chapter list using the book's chapter names and hierarchy.
3. Audiobook sleep timer: 15/30/45/60 minutes or the end of the current chapter.
4. Definition lookup and saved vocabulary with passage context.
5. Portable annotation import/export and a private annotation sync client/service.
6. Free book discovery through Gutendex/Project Gutenberg and LibriVox, including multi-file audiobooks.
7. Local PDF and CBZ reading with page navigation, zoom, covers and saved position.

iOS also has the reader typography controls, highlights and notes introduced on Android. Ebook and audiobook positions remain independent.

## Verification

- Android emulator: reader appearance persistence, search, chapters, bookmarks, highlights/notes, annotation transfer, PDF/CBZ swipe and resume, playback speed, duration timer and chapter-end pause passed.
- iOS simulator: EPUB import/search/annotations, persistence, PDF/CBZ import and natural page order, multi-file audio loading/seeking and existing OPDS validation passed.
- Real public-catalogue smoke tests on **both platforms** downloaded and imported a Gutenberg EPUB and one chapter from a multi-chapter LibriVox audiobook. An initial Android request timed out; the subsequent complete smoke passed. The iOS smoke exposed and verified the fix for literal plus-sign query encoding.
- Annotation service: merge/deletion conflict tests, invalid document rejection, atomic batch validation, HTTP authentication and round trip passed locally.
- Android APK signature and manifest verified. iOS IPA archive checked as an arm64 iPhoneOS package, version 0.2.0, minimum iOS 17. The IPA is unsigned for SideStore to sign.

## Limits and deployment status

- The annotation service is **not deployed** to the Oracle VM. Its clients are ready, but network annotation sync requires configuring that service on both devices. Offline JSON export/import works without it. This service does not exchange annotations with BookLore or KOReader.
- Annotation matching requires identical EPUB files. Sync is manual, with last-write-wins revisions and deletion records; keep device clocks correct.
- PDF/CBZ do not yet have EPUB search, notes or highlights. CBR is not supported.
- Discovery downloads require keeping the screen open; interrupted downloads restart. Actual catalogue availability and latency depend on the public providers. The live smoke downloaded one audio chapter, not an entire long audiobook.
- Physical-device background playback, Bluetooth and SideStore installation remain user-device checks.
- These are local development packages. No new GitHub release or VM deployment was performed for this change.
