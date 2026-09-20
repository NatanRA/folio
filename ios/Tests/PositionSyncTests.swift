import XCTest
@testable import MediaPortal

/// The listening position the reader left on Android has to arrive on a freshly set up iPhone
/// unchanged. Android stores milliseconds and iOS stores seconds, so every value crosses that
/// boundary twice and a single missed conversion lands the reader a thousand times out.
final class PositionSyncTests: XCTestCase {
    private let key = "tg-777000-1001-4242"

    private func book(_ id: String? = nil, position: Double = 0, duration: Double? = nil, modified: Date? = nil) -> MediaBook {
        var book = MediaBook(title: "Harry Potter and the Philosopher's Stone")
        book.id = id ?? key
        book.audioPosition = position
        book.audioDuration = duration
        book.audioModifiedAt = modified
        return book
    }

    private func record(_ id: String? = nil, position: Int, duration: Int = 0, modified: Int64) -> PositionRecord {
        PositionRecord(key: id ?? key, position: position, duration: duration, modified: modified)
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
    }

    /// A position saved before this device tracked a write time still has to reach the other
    /// device, stamped zero so it can seed an unknown key without beating a dated position.
    func testAPositionSavedBeforeStampsExistedStillTravels() {
        let sent = PositionProtocol.records([book(position: 640, duration: 3600)])
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(sent[0].position, 640_000)
        XCTAssertEqual(sent[0].modified, 0)
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

    /// A library.json saved before positions were synced has no `audioModifiedAt` key at all.
    /// Decoding has to survive that, or this change would empty an existing library.
    func testALibrarySavedBeforeThisChangeStillDecodes() throws {
        let saved = """
        [{"id":"tg-777000-1001-4242","title":"Harry Potter and the Philosopher's Stone","author":"J. K. Rowling",\
        "readingProgress":0,"audioPosition":8241,"audioDuration":27360,"finished":false}]
        """
        let books = try JSONDecoder().decode([MediaBook].self, from: Data(saved.utf8))
        XCTAssertEqual(books.count, 1)
        XCTAssertNil(books[0].audioModifiedAt)
        XCTAssertEqual(PositionProtocol.records(books).first?.position, 8_241_000)
    }
}
