# KOReader position sync

Version 0.3.0 adds manual, two-way **approximate** EPUB resume through the standard KOReader sync API. It does not translate exact CREngine XPointers into Readium locators. Readium's publication positions and KOReader's rendered percentages can differ, so check your place after changing readers.

## Setup

1. In KOReader, enable **Progress sync**, register or sign into a sync account, and choose **Binary** document matching. Keep an identical EPUB on both devices. Downloading the same edition from BookLore is a convenient way to do this. Renaming the file is fine; modifying or converting it may prevent matching.
2. Copy the entire `media-portal.koplugin` folder into KOReader's `plugins` folder and restart KOReader. Enable external plugins if your installation requires it. Open an EPUB and check **Folio bridge → Bridge status**. Leave the built-in Progress sync plugin enabled.
3. In Folio, open **Server → KOReader progress → Set up KOReader sync**. Enter the same sync server, username and password. These are separate from your BookLore/OPDS credentials. The default address is KOReader's public service; an HTTPS self-hosted KOReader sync server also works. No server is provisioned or account created by this update.
4. Once the bridge is installed on **every KOReader device using this sync account**, tick **Bridge installed on my KOReader devices** to enable sending from Folio.

## Use

- **KOReader → Folio:** push progress using KOReader's built-in menu. In Folio open the same book, tap **Sync**, and choose **Use other reader's position**.
- **Folio → KOReader:** tap **Sync → Send this position**, then use KOReader's **Pull progress from other devices now**.
- Fetching never automatically moves you. Cancel keeps the local position. The app rechecks the remote value before sending and stops if it changed while you were choosing. The underlying KOReader API has no atomic compare-and-set, so avoid simultaneously pushing from both devices.
- **Undo:** Folio's Sync menu restores the last received jump, even offline, including your prior completion state. The KOReader bridge offers **Undo last Folio jump**.
- **Finished status is local:** marking a book finished does not move its bookmark to the end. Sync shares the actual saved place, not the completion percentage. Audiobook progress is untouched.
- Manual sync needs a connection. Failed operations leave your local bookmark intact; retry with Sync when online. There is no background sync or queued upload in this version.

## Compatibility and privacy

Receiving percentages from ordinary KOReader works without the companion plugin. Sending uses a versioned `nmp-percent-v1:` value in the API's bookmark field; stock KOReader cannot interpret it, hence the explicit install gate. The bridge delegates normal native KOReader bookmarks unchanged and intercepts only Folio markers. It supports reflowable EPUBs, not PDFs. No exact paragraph/word matching is promised.

The app uses KOReader's sampled partial MD5 document identity, not the full-file MD5 or filename. It sends the document fingerprint, position, app name and a generated device ID. No book text, title, artwork or audiobook timestamp is uploaded. Sync credentials use KOReader's required password-derived MD5 key over HTTPS; the retained key is encrypted with Android Keystore. HTTP and redirects are rejected. Disconnecting removes the local sync credentials and keeps books and reading progress.

Protocol reference: [KOReader API](https://github.com/koreader/koreader/blob/master/plugins/kosync.koplugin/api.json), [KOReader client](https://github.com/koreader/koreader/blob/master/plugins/kosync.koplugin/KOSyncClient.lua), [document matching](https://github.com/koreader/koreader/blob/master/frontend/util.lua), [reader navigation](https://github.com/koreader/koreader/blob/master/frontend/apps/reader/modules/readerrolling.lua).

## Verification

Android instrumentation tests exercise the protocol using a fake transport, fingerprint fixtures, encrypted credentials, HTTP failures/redirect rejection, reader receive/undo and setup UI. `test_bridge.py` runs the actual companion Lua module with mocked KOReader UI under Lua 5.1 (requires Python `lupa`). Physical KOReader/Xteink interoperability and exact placement across different book layouts remain to be checked on the chosen device.
