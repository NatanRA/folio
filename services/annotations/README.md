# Private annotation and position sync

A small Python/SQLite service for one personal or household library. This is separate from BookLore and KOReader position sync. It stores annotations, book fingerprints and listening positions, not the books themselves.

Annotations sync explicitly from **Reader → Highlights & notes → Sync & export**; offline edits stay local until the next sync. Listening positions sync on their own in the background, with no button to press.

## Deploy behind HTTPS

Use an existing server; no paid API or third-party subscription is required. The host still needs its own backups and capacity monitoring. No production deployment is performed by the build scripts.

1. Build this folder's Dockerfile as `portal-annotations`.
2. Create a writable data directory owned by UID/GID 65534. Generate a private access token with `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'` and store it in a mode-600 environment file as `ANNOTATION_TOKEN=...`.
3. Start the container with the env file, mount that directory at `/data`, and publish only `127.0.0.1:6062:8080`. Set a memory limit of 128 MB and restart policy `unless-stopped`. Do not publish port 8080 directly to the internet.
4. Add an HTTPS reverse-proxy route such as `/annotations/*` to `127.0.0.1:6062` (or the container service name when the proxy runs in Docker). Limit request bodies to 8 MB and set a request timeout.
5. Enter `https://your-existing-host/annotations` and the same access token under **Server → Notes sync** on both devices. The token is encrypted with Android Keystore or stored in iOS Keychain. The same address and token carry listening positions; there is nothing further to configure.

Back up `/data/annotations.sqlite3` using SQLite's backup API. It holds both the `notes` and `positions` tables. Rotating `ANNOTATION_TOKEN` preserves existing data. Never include the env file, token or database in Git.

## Protocol

Two routes share one address and one token. The last path component selects which.

### Annotations

`POST /annotations/<sha256-of-book-file>` with `Authorization: Bearer <token>` and JSON:

```json
{"version":1,"document":"<64 lowercase hex characters>","records":[
  {"id":"<uuid>","kind":"note","locator":"<Readium Locator JSON string>","note":"My note","modified":0,"revision":"<uuid>","deleted":false}
]}
```

Kinds are `note`, `bookmark`, `word`. The response has the same shape and contains the merged journal. For each ID, higher `modified` (Unix milliseconds) wins; equal times prefer deletion, then lexicographically higher revision UUID. Device clocks should be correct. Old annotations migrate at timestamp zero. Tombstones are retained to prevent stale devices restoring deletions. Maximum 10,000 records / 8 MB per book. EPUB files must be byte-identical on both devices; different editions are deliberately kept separate.

Exported JSON is readable and transferable to the other platform without this service. It contains selected book passages and personal notes; share it accordingly.

### Listening positions

`POST /annotations/positions` with the same `Authorization: Bearer <token>` and JSON:

```json
{"version":1,"records":[
  {"key":"tg-123-456-789","position":8241000,"duration":27360000,"modified":1789941600000}
]}
```

`position` and `duration` are integer milliseconds into the whole book, `modified` is the Unix
millisecond time the sending device last wrote that position. The response has the same shape and
contains every position in the library, so a single request both sends and receives; an empty
`records` array is how a new device pulls. For each key the higher `modified` wins, and an
equal-time tie takes the further `position` so a replayed push can never rewind a listener.
Maximum 5,000 books / 8 MB.

`key` is the book's id, which only Telegram-sourced audiobooks share across devices: it is
`tg-<telegram account>-<chat>-<message>`, derived identically on Android and iOS.

Nothing else is sent, because nothing else has an id both devices agree on. A catalogue audiobook
is named by `UUID.nameUUIDFromBytes` on Android and by SHA-256 on iOS, so the same book arrives
under two different ids and would sync into thin air; a locally imported file gets a fresh random
id on each device. Giving catalogue books one shared identity would let them sync too, and is the
obvious next step now that the OPDS parser reads audio editions.

Positions are deliberately kept out of the annotations table: they are per library, not per book
file, and unlike a highlight they are worth nothing once superseded.

## Tests

`python3 -m unittest -v test_server.py` from this folder covers stale updates, deletions, timestamp ties, and mismatched-book rejection, plus the position route: authentication, a stale push failing to rewind a newer position, equal-time ties, bulk round trips, and atomic rejection of malformed batches. The mobile suites exercise import/export against different local IDs for the same EPUB.
