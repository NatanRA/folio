import Foundation

struct PositionRecord: Codable {
    var key: String
    var position: Int
    var duration: Int
    var modified: Int64
}

struct PositionBundle: Codable { var version = 1; var records: [PositionRecord] }

/// Listening positions travel between devices as whole milliseconds, while this app keeps them as
/// seconds in a Double. Every value crosses that boundary twice, so the conversion lives here and
/// nowhere else: one missed crossing puts a position out by a factor of a thousand.
enum PositionProtocol {
    /// Thirty days, matching the service. Beyond it the server rejects the whole exchange.
    static let maximumMilliseconds = 2_592_000_000
    static let maximumRecords = 5000

    /// Only Telegram audiobooks qualify. `tg-<account>-<chat>-<message>` is derived identically on
    /// Android and iOS, so one book carries a byte-identical id on every device. A catalogue book
    /// is hashed differently per platform and an imported file gets a fresh UUID on each device, so
    /// syncing those would land a position on the wrong book or on none at all.
    static func syncable(_ book: MediaBook) -> Bool {
        book.id.hasPrefix("tg-") && book.id.range(of: "^[A-Za-z0-9:_.-]{1,200}$", options: .regularExpression) != nil
    }

    /// The cap is applied before the conversion to an integer: a player reporting a nonsense length
    /// scales past what `Int` can hold, and converting that traps.
    static func milliseconds(_ seconds: Double) -> Int {
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return Int(min((seconds * 1000).rounded(), Double(maximumMilliseconds)))
    }

    static func seconds(_ milliseconds: Int) -> Double { Double(min(maximumMilliseconds, max(0, milliseconds))) / 1000 }

    static func stamp(_ date: Date?) -> Int64 {
        guard let date else { return 0 }
        let value = (date.timeIntervalSince1970 * 1000).rounded()
        guard value.isFinite, value > 0 else { return 0 }
        return Int64(min(value, 9_007_199_254_740_992))
    }

    static func date(_ modified: Int64) -> Date { Date(timeIntervalSince1970: Double(max(0, modified)) / 1000) }

    /// The time to write for a move this device just made, never earlier than the one already
    /// stored for that book. The wall clock goes backwards — an NTP correction, a date changed by
    /// hand, a battery pulled — and every write after that would be dated before this device's own
    /// earlier work: it would lose every merge against itself and keep pulling the older position
    /// back in. Only a device's own stamps are kept monotonic; a record arriving carries its own
    /// time, which is the whole basis of the comparison.
    static func stamped(_ stored: Date?, now: Date = Date()) -> Date { max(now, stored ?? .distantPast) }

    /// What this device has to offer for one book, or nothing when it has never written a position
    /// for it. The write time is the whole measure of a book at both ends of the exchange: one the
    /// upgrade could not date holds nothing this device can vouch for, so it neither travels nor
    /// stands in the way of what arrives. A record stamped zero could never be applied by anyone
    /// anyway, and would only take up one of the rows the service holds.
    static func offer(_ book: MediaBook) -> PositionRecord? {
        let modified = stamp(book.audioModifiedAt)
        guard modified > 0 else { return nil }
        return PositionRecord(key: book.id, position: milliseconds(book.audioPosition),
                              duration: milliseconds(book.audioDuration ?? 0), modified: modified)
    }

    /// Every book this device holds a listening position worth offering for.
    static func records(_ books: [MediaBook]) -> [PositionRecord] {
        Array(books.filter(syncable).compactMap(offer).prefix(maximumRecords))
    }

    /// The records worth taking: written on another device after this one last wrote that book, and
    /// never the book being listened to here, whose listener would be yanked mid-sentence.
    static func applicable(_ records: [PositionRecord], to books: [MediaBook], playing: String?) -> [PositionRecord] {
        var known: [String: MediaBook] = [:]
        for book in books where syncable(book) { known[book.id] = book }
        return records.filter { record in
            guard record.key != playing, let book = known[record.key], record.position >= 0, record.position <= maximumMilliseconds,
                  record.duration >= 0, record.duration <= maximumMilliseconds else { return false }
            return record.modified > stamp(book.audioModifiedAt)
        }
    }

    /// Position, length and both stamps move together. A position written without its length leaves
    /// the Continue card reading 1%, and without `lastOpenedAt` the card offers the wrong book. A
    /// length of zero means the other device does not know it either, so the local one is kept.
    static func apply(_ record: PositionRecord, to book: inout MediaBook) {
        book.audioPosition = seconds(record.position)
        if record.duration > 0 { book.audioDuration = seconds(record.duration) }
        book.audioModifiedAt = date(record.modified)
        // Forwards only: a record older than the last time this device opened the title would
        // demote it on Home and hand Continue a book the reader left far longer ago.
        book.lastOpenedAt = max(book.lastOpenedAt ?? .distantPast, date(record.modified))
    }
}

/// Listening position sync over the reader's own annotation service. One POST both pushes and
/// pulls: the service answers with the merged set for the whole library.
///
/// Nothing here is user-visible. The reader asked for this to happen on its own, so a failure shows
/// nothing, changes no saved position, and simply waits for the next trigger.
@MainActor final class PositionSync {
    static let shared = PositionSync()
    /// The player on this device, read for the book it is actually playing and moved when a
    /// position arrives for the book it is holding paused.
    weak var player: AudioPlayer?
    private var last = Date.distantPast
    private var running = false
    private var pending = false

    /// The book being listened to here, which a pulled record must never overwrite. A book merely
    /// loaded does not count: the player keeps it through a pause so the mini player survives, and
    /// excluding it for good would withhold the one book the reader most wants carried across.
    private var activeBook: String? { player?.playing == true ? player?.bookID : nil }

    private var account: ServerAccount? {
        SecureStore.read("notes-sync").flatMap { try? JSONDecoder().decode(ServerAccount.self, from: $0) }
    }

    /// Playback saves a position every fifteen seconds. At most one exchange every thirty seconds
    /// turns that into traffic; pause, stop and backgrounding go through `exchange` instead.
    func pushWhileListening(library: Library) {
        guard Date().timeIntervalSince(last) >= 30 else { return }
        // Dropped rather than queued when one is already in flight: the next save is half a second
        // away and the one after that thirty seconds later, so re-queueing every push an exchange
        // outlived would have the two chase each other for the rest of the session.
        exchange(library: library, queueIfBusy: false)
    }

    /// `queueIfBusy` says whether this call is the only chance its moment offers. The pull after a
    /// Telegram scan, a pause and coming to the front are each that, and the scan's pull is as
    /// likely as not to collide with the one the launch already started — losing it is exactly the
    /// case where a freshly set up device shows no Continue card. Playback's throttled push is not.
    func exchange(library: Library, queueIfBusy: Bool = true) {
        guard !running else { if queueIfBusy { pending = true }; return }
        // The clock moves before the account is read: with none saved there is nothing to read it
        // for, and playback would otherwise ask the Keychain twice a second all session long.
        last = Date()
        guard let account else { return }
        running = true
        Task {
            defer {
                running = false
                if pending { pending = false; exchange(library: library) }
            }
            await run(account: account, library: library)
        }
    }

    private func run(account: ServerAccount, library: Library) async {
        guard let payload = try? JSONEncoder().encode(PositionBundle(records: PositionProtocol.records(library.books))) else { return }
        var request = URLRequest(url: account.url.appendingPathComponent("positions"))
        request.httpMethod = "POST"; request.httpBody = payload; request.timeoutInterval = 30
        request.setValue("Bearer " + account.password, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        guard let (file, response) = try? await session.download(for: request) else { return }
        defer { try? FileManager.default.removeItem(at: file) }
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              ((try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) <= 8_000_000,
              let data = try? Data(contentsOf: file),
              let merged = try? JSONDecoder().decode(PositionBundle.self, from: data), merged.version == 1,
              merged.records.count <= PositionProtocol.maximumRecords else { return }
        for record in PositionProtocol.applicable(merged.records, to: library.books, playing: activeBook) {
            library.update(record.key) { PositionProtocol.apply(record, to: &$0) }
            // The player keeps its own copy of the position of the book it holds paused, and would
            // write that stale one back over what has just arrived on its next save. Moving it
            // there is not this device writing a position: the record already carries its own time,
            // and clamping it to a length measured here is no reason to claim a newer one.
            if record.key == player?.bookID { player?.seek(PositionProtocol.seconds(record.position), moved: false) }
        }
    }
}
