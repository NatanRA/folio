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

    /// Every book this device has a listening position for. One saved before this device tracked a
    /// write time still travels, stamped zero, so it can seed a key no device knows yet without
    /// ever beating a dated position from the other device.
    static func records(_ books: [MediaBook]) -> [PositionRecord] {
        books.filter { syncable($0) && ($0.audioModifiedAt != nil || $0.audioPosition > 0) }
            .prefix(maximumRecords)
            .map { PositionRecord(key: $0.id, position: milliseconds($0.audioPosition), duration: milliseconds($0.audioDuration ?? 0), modified: stamp($0.audioModifiedAt)) }
    }

    /// The records worth taking: written on another device after this one last wrote that book, and
    /// never the book loaded in the player here, whose listener would be yanked mid-sentence.
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
        book.lastOpenedAt = date(record.modified)
    }
}

/// Listening position sync over the reader's own annotation service. One POST both pushes and
/// pulls: the service answers with the merged set for the whole library.
///
/// Nothing here is user-visible. The reader asked for this to happen on its own, so a failure shows
/// nothing, changes no saved position, and simply waits for the next trigger.
@MainActor final class PositionSync {
    static let shared = PositionSync()
    /// The book loaded in the player on this device, which a pulled record must never overwrite.
    var playing: String?
    private var last = Date.distantPast
    private var running = false

    private var account: ServerAccount? {
        SecureStore.read("notes-sync").flatMap { try? JSONDecoder().decode(ServerAccount.self, from: $0) }
    }

    /// Playback saves a position twice a second. At most one exchange every thirty seconds turns
    /// that into traffic; pause, stop and backgrounding go through `exchange` instead.
    func pushWhileListening(library: Library) {
        guard Date().timeIntervalSince(last) >= 30 else { return }
        exchange(library: library)
    }

    func exchange(library: Library) {
        guard !running, let account else { return }
        running = true; last = Date()
        Task {
            defer { running = false }
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
        for record in PositionProtocol.applicable(merged.records, to: library.books, playing: playing) {
            library.update(record.key) { PositionProtocol.apply(record, to: &$0) }
        }
    }
}
