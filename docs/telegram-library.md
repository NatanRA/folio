# Direct Telegram library

Status: native integration implemented on Android and iOS. Parser, native bridge,
duplicate-import and connection UI tests pass. Live account authorization and a
real cloud download remain unverified; do not describe the library as connected.

## Storage and scope

Telegram cloud chats hold the original files. Android and iOS connect directly
through TDLib; the Oracle VM and existing book-inbox bot are not in this path.
Start with Saved Messages, with an explicit picker for another private chat or
channel. Index supported document/audio attachments only from the selected chat.
Do not send messages, mark chats read, or import unrelated conversations.

Audiobooks are indexed into the main library without full downloads. Media3
and AVPlayer request byte ranges directly through TDLib, including tail metadata
and seek offsets. Each request prefetches at most 1 MiB; a file's temporary cache
is discarded after exceeding 64 MiB and fetched again on demand. This is a
per-file limit, not a global disk quota. Keep offline runs the existing complete
download/import path and retains the cloud title's identity and position.
E-books currently use the existing complete-download flow in Server settings.
Cross-device position sync remains separate.
Reading and listening positions continue to use the existing device stores.

## Account setup

Register this application at https://my.telegram.org/apps to obtain its own
api_id/api_hash. Do not reuse Telegram's sample identity or the BotFather token.

Published packages carry no pair. Anything bundled into an APK or IPA can be read
by whoever has the file, and releases are uploaded to GitHub, so from 0.9.3 the
pair is entered once on each device under Server → Telegram instead. It is kept
per device — encrypted with Keystore on Android, in the Keychain on iOS — and the
account itself is still protected by the sign-in below. `scripts/publish-release.py`
refuses any package that contains `telegram-private.json`.

For a personal build that never leaves your machine,
`python3 scripts/set-telegram-identity.py` can still bundle the pair: it writes
`ios/Resources/telegram-private.json` and `app/src/main/assets/telegram-private.json`,
both gitignored. A pair entered on the device overrides a bundled one. Releases
up to 0.9.2 were built this way, so their packages contain the pair.

After installing the identity, regenerate the iOS project
(`xcodegen generate --spec ios/project.yml`) but do not commit the resulting
`project.pbxproj`: it references the untracked resource and breaks any checkout
without it.

Authenticate on each
device using TDLib's authorization state machine, including code, optional 2FA,
and actionable expired-code/session errors. Never log authorization payloads.

TDLib sessions grant account-level access, even though our UI limits itself to a
chosen chat. Explain that during connection. Encrypt TDLib's local database with
a random device key stored in Android Keystore / iOS Keychain. Exclude session
data from device backups. Disconnect must log out and clear the Telegram session
without deleting imported books or files in Telegram.

## Catalogue and downloads

- Persist account ID + chat ID + message ID as source identity, separate from
  the app's local book ID. Store Telegram 64-bit identifiers without narrowing.
- Page through chat history; do not assume one search response is the library.
- Support current shared formats: EPUB, PDF, CBZ, M4B, M4A, MP3, AAC, WAV, FLAC.
- Keep one message-to-local-book mapping so refreshing/re-downloading cannot
  silently create duplicates or reset progress. Linking ebook and audio remains
  an explicit existing title action.
- Preserve filenames for import, but never use an untrusted filename as a path.
- Show download progress and cancellation. Use TDLib download completion state,
  not file existence, to decide a file is ready. Check free device space first.
- Import via existing format validation/artwork/chapter extraction. Remove the
  temporary TDLib copy only after successful import to avoid double local storage.
- A removed Telegram message must not delete a downloaded local book. Mark the
  remote source unavailable and retain local reading/listening progress.
- Cancelling an import must not produce a successful download indicator.

## Native UI

Add a Telegram section to the existing connections screen, following the app's
chosen theme. Show Connect, selected chat, Refresh library, and Disconnect in
context. Audiobooks from the remembered channel appear in the main library;
new-message events refresh its catalogue. While a chat is being scanned the
section says so instead of reporting an empty chat. If the remembered chat can
no longer be read (left, deleted, or not yet loaded by TDLib after a restart)
the app reloads the chat list once, then falls back to Saved Messages and says
why. Unrecognised TDLib errors show TDLib's own reason in brackets so reports
are actionable. Streaming titles carry a cloud badge
without an offline checkmark. Embedded artwork is extracted when opened.
Downloaded entries enter the normal library, carry a cloud-source badge, and use
the existing title detail flow. There is no messaging interface or extra tab.

## Native builds

TDLib revision: `d1085f9cebc5a62379991ae1652673954f229c1f`.
OpenSSL version: `openssl-3.5.2`.

Run `scripts/build-telegram-native.sh /absolute/build/workspace android`, then
repeat for `ios` and `simulator`. On a clean workspace, finish the first command
before starting the others so generated TDLib source files are not raced.
Run `python3 scripts/package-telegram-native.py /absolute/build/workspace` before
the ordinary Gradle/Xcode builds. The generated binaries are ignored by Git.
The APK currently includes arm64 only. The XCFramework includes arm64 iPhone and
arm64 simulator slices; Intel simulators are not supported by these builds.
The Android library has 16 KB ELF alignment. iOS libraries target iOS 17.0.
TDLib (Boost Software License) and OpenSSL (Apache 2.0) notices are bundled.

E-books and explicit Keep offline requests download the complete file.
Keep the app open during these downloads. There is no automatic background mirroring
or Telegram-based reading-position sync in this version. Very old chat files
can require several presses of Load older files. Telegram Premium-purchase or
new-account-registration authorization states require the official Telegram app.

## Verification required before release

1. Both platforms: sign in, restart with saved session, code expiry, optional 2FA,
   logout, and isolation when switching Telegram accounts.
2. Index a selected chat with multiple history pages, duplicate filenames,
   unsupported attachments, deleted messages, and no supported books.
3. Download one real EPUB and a large chaptered M4B, interrupt/retry the transfer,
   then verify artwork, chapters, offline playback, and retained positions.
4. Verify no account/session secrets enter logs, backups, Git, or release files.
5. Verify UI loading/error/empty states on Android and iOS.
6. Only after Telegram copies and device import are verified, review the specific
   VM audiobook staging files for cleanup; preserve the Mac originals.

References: https://core.telegram.org/tdlib and
https://core.telegram.org/api/obtaining_api_id
