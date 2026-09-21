import XCTest
@testable import MediaPortal

/// The listening position the reader left on Android has to arrive on a freshly set up iPhone
/// unchanged. Android stores milliseconds and iOS stores seconds, so every value crosses that
/// boundary twice and a single missed conversion lands the reader a thousand times out.
final class PositionSyncTests: XCTestCase {
    private let key = "tg-777000-1001-4242"

    private func book(_ id: String? = nil, position: Double = 0, duration: Double? = nil, modified: Date? = nil, opened: Date? = nil) -> MediaBook {
        var book = MediaBook(title: "Harry Potter and the Philosopher's Stone")
        book.id = id ?? key
        book.audioPosition = position
        book.audioDuration = duration
        book.audioModifiedAt = modified
        book.lastOpenedAt = opened
        return book
    }

    private func record(_ id: String? = nil, position: Int, duration: Int = 0, modified: Int64) -> PositionRecord {
        PositionRecord(key: id ?? key, position: position, duration: duration, modified: modified)
    }

    /// A library.json as this app saved it before positions carried a write time: no
    /// `audioModifiedAt` key at all, and every non-optional field spelled out, as the synthesised
    /// decoder requires. `lastOpenedAt` is seconds since 2001, the format on disk.
    private func entry(position: Double, duration: Double? = nil, opened: Double? = nil) -> String {
        var fields = ["\"id\":\"\(key)\"", "\"title\":\"Harry Potter and the Philosopher's Stone\"",
                      "\"author\":\"J. K. Rowling\"", "\"readingProgress\":0", "\"audioPosition\":\(position)", "\"finished\":false"]
        if let duration { fields.append("\"audioDuration\":\(duration)") }
        if let opened { fields.append("\"lastOpenedAt\":\(opened)") }
        return "[{" + fields.joined(separator: ",") + "}]"
    }

    @MainActor private func openLibrary(saved: String) throws -> Library {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data(saved.utf8).write(to: root.appendingPathComponent("library.json"))
        return Library(root: root)
    }

    // MARK: units

    /// Two hours seventeen minutes into a seven-and-a-half-hour book, the value from the contract.
    func testASecondsPositionSurvivesTheTripThroughMilliseconds() {
        let seconds = 8241.0
        XCTAssertEqual(PositionProtocol.milliseconds(seconds), 8_241_000)
        XCTAssertEqual(PositionProtocol.seconds(PositionProtocol.milliseconds(seconds)), seconds, accuracy: 0.0005)
    }

    func testTheWholeRecordRoundTripsBackToTheSameBook() {
        let written = Date(timeIntervalSince1970: 1_789_941_600)
        let sent = PositionProtocol.records([book(position: 8241.317, duration: 27_360.0, modified: written)])
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(sent[0].position, 8_241_317)
        XCTAssertEqual(sent[0].duration, 27_360_000)
        XCTAssertEqual(sent[0].modified, 1_789_941_600_000)

        var arrived = book()
        PositionProtocol.apply(sent[0], to: &arrived)
        XCTAssertEqual(arrived.audioPosition, 8241.317, accuracy: 0.0005)
        XCTAssertEqual(try XCTUnwrap(arrived.audioDuration), 27_360, accuracy: 0.0005)
        XCTAssertEqual(try XCTUnwrap(arrived.audioModifiedAt), written)
        XCTAssertEqual(arrived.progress, 8241.317 / 27_360, accuracy: 0.0001, "the Continue card must read the real percentage")
    }

    /// The service rejects the whole exchange when a number arrives as a float or a boolean, so the
    /// encoded shape matters as much as the value.
    func testTheWireCarriesWholeMilliseconds() throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        let sent = PositionProtocol.records([book(position: 8241.0, duration: 27_360.0, modified: Date(timeIntervalSince1970: 1_789_941_600))])
        let json = String(decoding: try encoder.encode(PositionBundle(records: sent)), as: UTF8.self)
        XCTAssertTrue(json.contains("\"position\":8241000"), json)
        XCTAssertTrue(json.contains("\"duration\":27360000"), json)
        XCTAssertTrue(json.contains("\"modified\":1789941600000"), json)
        XCTAssertTrue(json.contains("\"version\":1"), json)
        XCTAssertFalse(json.contains("."), "a decimal point in any number is a 400 from the service")
    }

    /// A player that never loaded reports a duration of zero and an unplayable one reports NaN.
    /// `Int(Double.nan)` traps, and a negative number is rejected by the service.
    func testNonsenseSecondsBecomeZeroRatherThanACrash() {
        XCTAssertEqual(PositionProtocol.milliseconds(.nan), 0)
        XCTAssertEqual(PositionProtocol.milliseconds(.infinity), 0, "an unmeasurable length is unknown, not thirty days")
        XCTAssertEqual(PositionProtocol.milliseconds(-12), 0)
        XCTAssertEqual(PositionProtocol.milliseconds(0), 0)
        XCTAssertEqual(PositionProtocol.seconds(-1), 0)
    }

    func testAnImpossiblyLongPositionIsCappedToWhatTheServiceAccepts() {
        XCTAssertEqual(PositionProtocol.milliseconds(60 * 24 * 60 * 60), PositionProtocol.maximumMilliseconds)
        XCTAssertLessThanOrEqual(PositionProtocol.milliseconds(.greatestFiniteMagnitude), PositionProtocol.maximumMilliseconds)
    }

    /// The wall clock goes backwards: an NTP correction, a date changed by hand, a battery pulled.
    /// A stamp written behind the one already stored would date this device's later listening
    /// before its own earlier listening, so it would lose every merge against itself and keep
    /// pulling the position it had already moved past back in.
    func testThisDevicesOwnStampNeverGoesBackwards() {
        let written = Date(timeIntervalSince1970: 1_789_941_600)
        XCTAssertEqual(PositionProtocol.stamped(written, now: written.addingTimeInterval(-3600)), written)
        XCTAssertEqual(PositionProtocol.stamped(written, now: written.addingTimeInterval(30)), written.addingTimeInterval(30))
        XCTAssertEqual(PositionProtocol.stamped(nil, now: written), written, "a book this device has never dated takes the clock as it is")

        // What the clamp is for: the exchange after the clock slipped still weighs this device as
        // the more recent writer of its own book.
        let slipped = book(position: 8241, modified: PositionProtocol.stamped(written, now: written.addingTimeInterval(-86_400)))
        XCTAssertTrue(PositionProtocol.applicable([record(position: 120_000, modified: PositionProtocol.stamp(written))], to: [slipped], playing: nil).isEmpty)
    }

    // MARK: which books travel

    /// Only a Telegram id means the same book on both devices. A catalogue book is hashed
    /// differently per platform and an imported file gets a fresh UUID, so sending either would
    /// drop a position onto the wrong book.
    func testOnlyTelegramBooksAreSent() {
        let books = [book(position: 100, modified: Date()),
                     book("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", position: 200, modified: Date()),
                     book(UUID().uuidString, position: 300, modified: Date())]
        let sent = PositionProtocol.records(books)
        XCTAssertEqual(sent.map(\.key), [key])
    }

    func testAKeyTheServiceWouldRejectIsNeverSent() {
        XCTAssertTrue(PositionProtocol.records([book("tg-777000-1001-42 42", position: 100, modified: Date())]).isEmpty, "a space is outside the key charset")
        XCTAssertTrue(PositionProtocol.records([book("tg-" + String(repeating: "9", count: 300), position: 100, modified: Date())]).isEmpty)
    }

    func testABookThisDeviceHasNeverListenedToIsNotSent() {
        XCTAssertTrue(PositionProtocol.records([book()]).isEmpty)
        XCTAssertTrue(PositionProtocol.records([book(opened: Date())]).isEmpty, "reading the e-book of a title is not a listening position")
    }

    /// The whole point of the feature: the position the reader left before this device tracked
    /// write times still has to travel. The upgrade gives it the date the title was last opened,
    /// which every save has written for many releases, and from then on that stamp speaks for it.
    @MainActor func testAPositionSavedBeforeStampsExistedStillTravels() throws {
        let library = try openLibrary(saved: entry(position: 640, duration: 3600, opened: 811_634_400))
        XCTAssertNil(library.error)
        XCTAssertEqual(try XCTUnwrap(library.books.first?.audioModifiedAt), Date(timeIntervalSince1970: 1_789_941_600))
        let sent = PositionProtocol.records(library.books)
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(sent[0].position, 640_000)
        XCTAssertEqual(sent[0].modified, 1_789_941_600_000)
    }

    /// The upgrade dates a position once. Opening a title writes its date again, so stamping from
    /// that date a second time would hand a position nobody moved a time newer than the position
    /// waiting on the other device, and rewind the reader by hours.
    @MainActor func testTheUpgradeDatesAPositionOnlyOnce() throws {
        let library = try openLibrary(saved: entry(position: 640))
        XCTAssertNil(library.books.first?.audioModifiedAt, "a position with no date of any kind cannot be dated")
        library.update(key) { $0.lastOpenedAt = Date() }
        let reopened = Library(root: library.root)
        XCTAssertNil(reopened.books.first?.audioModifiedAt)
        XCTAssertTrue(PositionProtocol.records(reopened.books).isEmpty)
    }

    /// A title the reader only ever opened holds no position to carry, and the upgrade leaves it
    /// undated. It must neither be offered nor stand in the way of the position arriving from the
    /// device the reader was actually listening on.
    func testATitleThatWasOnlyOpenedIsNeitherOfferedNorBlocksARecord() {
        let opened = [book(opened: Date(timeIntervalSince1970: 1_789_941_600))]
        XCTAssertTrue(PositionProtocol.records(opened).isEmpty)
        XCTAssertEqual(PositionProtocol.applicable([record(position: 8_241_000, modified: 1_789_900_000_000)], to: opened, playing: nil).count, 1)
    }

    /// A record stamped zero can never be applied by anyone and would only take up one of the five
    /// thousand rows the service holds.
    func testAPositionWithNoTimeOfAnyKindIsNotSent() {
        XCTAssertTrue(PositionProtocol.records([book(position: 640, duration: 3600)]).isEmpty)
    }

    /// Opening a title bumps its date every second it is merely on screen, and reading its e-book
    /// bumps it too. Only the write time speaks for a position, in both directions, or opening a
    /// book would inflate it past a genuinely newer one and rewind the other device.
    func testTheTimeATitleWasOpenedNeverSpeaksForItsPosition() {
        let local = [book(position: 640, modified: Date(timeIntervalSince1970: 1_789_900_000), opened: Date(timeIntervalSince1970: 1_789_941_600))]
        XCTAssertEqual(PositionProtocol.records(local).first?.modified, 1_789_900_000_000)
        XCTAssertEqual(PositionProtocol.applicable([record(position: 8_241_000, modified: 1_789_920_000_000)], to: local, playing: nil).count, 1,
                       "a record newer than the write time is taken however lately the title was opened")
    }

    // MARK: applying what comes back

    func testANewerServerRecordIsApplied() {
        let local = [book(position: 100, modified: Date(timeIntervalSince1970: 1_789_900_000))]
        let taken = PositionProtocol.applicable([record(position: 8_241_000, modified: 1_789_941_600_000)], to: local, playing: nil)
        XCTAssertEqual(taken.count, 1)
    }

    /// Never rewind a position this device wrote more recently.
    func testAStaleServerRecordNeverOverwritesANewerLocalPosition() {
        let written = Date(timeIntervalSince1970: 1_789_941_600)
        var local = book(position: 8241, duration: 27_360, modified: written)
        let stale = record(position: 120_000, duration: 27_360_000, modified: 1_789_900_000_000)
        XCTAssertTrue(PositionProtocol.applicable([stale], to: [local], playing: nil).isEmpty)

        for taken in PositionProtocol.applicable([stale], to: [local], playing: nil) { PositionProtocol.apply(taken, to: &local) }
        XCTAssertEqual(local.audioPosition, 8241, accuracy: 0.0005)
        XCTAssertEqual(local.audioModifiedAt, written)
    }

    /// The same write time on both sides is not newer, so the local value stands.
    func testAnEquallyOldServerRecordIsLeftAlone() {
        let local = [book(position: 8241, modified: Date(timeIntervalSince1970: 1_789_941_600))]
        XCTAssertTrue(PositionProtocol.applicable([record(position: 120_000, modified: 1_789_941_600_000)], to: local, playing: nil).isEmpty)
    }

    /// Zero means the other device does not know the length either; overwriting would leave the
    /// Continue card reading 1% for a book whose length this device already measured.
    func testAnUnknownServerDurationDoesNotClobberAKnownLocalOne() {
        var local = book(position: 10, duration: 27_360, modified: Date(timeIntervalSince1970: 1_789_900_000))
        let incoming = record(position: 8_241_000, duration: 0, modified: 1_789_941_600_000)
        let taken = PositionProtocol.applicable([incoming], to: [local], playing: nil)
        XCTAssertEqual(taken.count, 1)
        PositionProtocol.apply(taken[0], to: &local)
        XCTAssertEqual(try XCTUnwrap(local.audioDuration), 27_360, accuracy: 0.0005)
        XCTAssertEqual(local.audioPosition, 8241, accuracy: 0.0005)
    }

    /// Yanking the position of the book playing here would jump the listener mid-sentence.
    func testTheBookPlayingOnThisDeviceIsNeverOverwritten() {
        let local = [book(position: 100, modified: Date(timeIntervalSince1970: 1_789_900_000))]
        let incoming = record(position: 8_241_000, modified: 1_789_941_600_000)
        XCTAssertTrue(PositionProtocol.applicable([incoming], to: local, playing: key).isEmpty)
        XCTAssertEqual(PositionProtocol.applicable([incoming], to: local, playing: "tg-777000-1001-9999").count, 1)
    }

    /// The service returns the whole library's set, most of which this device may not own yet.
    func testARecordForNoLocalBookIsIgnored() {
        XCTAssertTrue(PositionProtocol.applicable([record("tg-777000-1001-9999", position: 8_241_000, modified: 1_789_941_600_000)], to: [book()], playing: nil).isEmpty)
    }

    /// Both directions weigh a book by its write time and nothing else. A book this device cannot
    /// date has nothing to offer, so it cannot refuse what arrives either: whatever it holds would
    /// otherwise sit there forever while the reader's real position waited on the service.
    func testABookWithNoWriteTimeOffersNothingAndRefusesNothing() {
        let local = [book(position: 640, opened: Date(timeIntervalSince1970: 1_789_941_600))]
        XCTAssertTrue(PositionProtocol.records(local).isEmpty)
        XCTAssertEqual(PositionProtocol.applicable([record(position: 8_241_000, modified: 1_789_900_000_000)], to: local, playing: nil).count, 1)
    }

    /// A record written after this device last wrote the position but before the reader last opened
    /// the title is still worth applying, but dating the book back would demote it on Home and hand
    /// Continue a book left far longer ago.
    func testAnAppliedRecordNeverDatesATitleBackwards() throws {
        let opened = Date(timeIntervalSince1970: 1_789_941_600)
        var local = book(position: 100, duration: 27_360, modified: Date(timeIntervalSince1970: 1_789_800_000), opened: opened)
        let taken = PositionProtocol.applicable([record(position: 8_241_000, modified: 1_789_900_000_000)], to: [local], playing: nil)
        XCTAssertEqual(taken.count, 1)
        PositionProtocol.apply(taken[0], to: &local)
        XCTAssertEqual(local.audioPosition, 8241, accuracy: 0.0005)
        XCTAssertEqual(try XCTUnwrap(local.lastOpenedAt), opened)
    }

    func testAnOutOfRangeServerRecordIsIgnored() {
        let local = [book(position: 10, modified: Date(timeIntervalSince1970: 1_789_900_000))]
        XCTAssertTrue(PositionProtocol.applicable([record(position: -1, modified: 1_789_941_600_000)], to: local, playing: nil).isEmpty)
        XCTAssertTrue(PositionProtocol.applicable([record(position: PositionProtocol.maximumMilliseconds + 1, modified: 1_789_941_600_000)], to: local, playing: nil).isEmpty)
    }

    /// Home offers the most recently opened unfinished title, so a position arriving from the other
    /// device has to carry its write time into `lastOpenedAt` or the card offers the wrong book.
    func testAnAppliedPositionReachesTheContinueCard() throws {
        var local = book()
        PositionProtocol.apply(record(position: 8_241_000, duration: 27_360_000, modified: 1_789_941_600_000), to: &local)
        XCTAssertEqual(try XCTUnwrap(local.lastOpenedAt), Date(timeIntervalSince1970: 1_789_941_600))
        XCTAssertTrue(local.listening)
        XCTAssertGreaterThan(local.progress, 0)
    }

    /// A library.json saved before positions were synced has no `audioModifiedAt` key at all, and
    /// decoding has to survive that. Until the upgrade dates it, such a book says nothing: the
    /// exchange reads a write time, it never guesses one.
    func testALibrarySavedBeforeThisChangeStillDecodes() throws {
        let books = try JSONDecoder().decode([MediaBook].self, from: Data(entry(position: 8241, duration: 27_360, opened: 811_634_400).utf8))
        XCTAssertEqual(books.count, 1)
        XCTAssertNil(books[0].audioModifiedAt)
        XCTAssertEqual(books[0].audioPosition, 8241, accuracy: 0.0005)
        XCTAssertEqual(try XCTUnwrap(books[0].lastOpenedAt), Date(timeIntervalSince1970: 1_789_941_600))
        XCTAssertTrue(PositionProtocol.records(books).isEmpty)
    }

    // MARK: opening an audiobook

    /// Opening an audiobook awaits its asset, and a position pulled in that time is the newer one.
    /// Restoring the book has to resume from what arrived rather than the snapshot the load began
    /// with, and restoring is not the reader moving: dating it would have this device claim a write
    /// it never made and push a position the reader had already left behind.
    @MainActor func testOpeningAnAudiobookResumesFromAPulledPositionWithoutClaimingAWrite() async throws {
        let library = try openLibrary(saved: entry(position: 3))
        let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b"))
        try FileManager.default.copyItem(at: source, to: library.file("one.m4b"))
        let tracks = LocalAudioBundle(tracks: [.init(file: "one.m4b", name: "First chapter", durationMs: 12000),
                                               .init(file: "one.m4b", name: "Second chapter", durationMs: 12000)])
        try JSONEncoder().encode(tracks).write(to: library.file("two.audiobook.json"))
        library.update(key) { $0.audio = "two.audiobook.json" }
        let stale = try XCTUnwrap(library.book(key))

        let written = Date(timeIntervalSince1970: 1_789_941_600)
        library.update(key) { PositionProtocol.apply(self.record(position: 14_000, duration: 24_000, modified: 1_789_941_600_000), to: &$0) }
        let player = AudioPlayer()
        await player.load(stale, library: library)
        XCTAssertNil(player.error)
        XCTAssertEqual(player.position, 14, accuracy: 0.1)
        XCTAssertEqual(try XCTUnwrap(library.book(key)?.audioModifiedAt), written)

        let moved = Date()
        player.seek(6)
        XCTAssertEqual(try XCTUnwrap(library.book(key)?.audioPosition), 6, accuracy: 0.1)
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(library.book(key)?.audioModifiedAt), moved, "a seek the reader asked for is this device writing a position")
        player.pause()
    }

    /// Opening the next audiobook must leave the one being closed exactly where the reader left it.
    /// The pause that begins a load reports a tick, and that tick is read at the load's first await
    /// — with the tracks already cleared, an unfixed player measures the clock of the item it still
    /// holds against no track at all and saves that as the outgoing book's position: hours into a
    /// bundle become minutes, stamped as a write this device never made, and the other device pulls
    /// the rewind.
    @MainActor func testOpeningAnotherAudiobookNeverWritesTheOutgoingBooksPosition() async throws {
        let library = try openLibrary(saved: entry(position: 0))
        let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b"))
        try FileManager.default.copyItem(at: source, to: library.file("one.m4b"))
        let tracks = LocalAudioBundle(tracks: [.init(file: "one.m4b", name: "First chapter", durationMs: 12000),
                                               .init(file: "one.m4b", name: "Second chapter", durationMs: 12000)])
        try JSONEncoder().encode(tracks).write(to: library.file("two.audiobook.json"))
        library.update(key) { $0.audio = "two.audiobook.json" }
        var next = MediaBook(title: "Harry Potter and the Chamber of Secrets")
        next.id = "tg-777000-1001-4343"; next.audio = "one.m4b"
        library.books.append(next); try library.save()

        let player = AudioPlayer()
        await player.load(try XCTUnwrap(library.book(key)), library: library)
        XCTAssertNil(player.error)
        // Well into the second track, so a reading taken without it lands far short of the truth.
        player.seek(18)
        // Whatever a tick would make of the player now it makes of it here, so the reading below is
        // one a tick agrees with and the load is the only thing left that can move it.
        player.sample()
        let left = try XCTUnwrap(library.book(key))
        XCTAssertGreaterThanOrEqual(left.audioPosition, 12, "a reading taken against no track cannot reach the second one")

        let opening = Task { await player.load(next, library: library) }
        await Task.yield()
        // Stands in for the tick the pause inside that load reported, read where the load leaves it.
        player.sample()
        await opening.value

        XCTAssertEqual(try XCTUnwrap(library.book(key)?.audioPosition), left.audioPosition, accuracy: 0.0005, "the book being closed keeps the place the reader left")
        XCTAssertEqual(library.book(key)?.audioModifiedAt, left.audioModifiedAt, "and this device claims no write it never made")
        player.pause()
    }
}
