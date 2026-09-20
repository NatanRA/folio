import XCTest
@testable import MediaPortal

final class ListeningProgressTests: XCTestCase {
    private func book(reading: Double = 0, position: Double = 0, duration: Double? = nil) -> MediaBook {
        var book = MediaBook(title: "Harry Potter and the Philosopher's Stone")
        book.readingProgress = reading
        book.audioPosition = position
        book.audioDuration = duration
        return book
    }

    func testAReadBookStillReportsItsReadingProgress() {
        let read = book(reading: 0.42)
        XCTAssertEqual(read.progress, 0.42, accuracy: 0.0001)
        XCTAssertFalse(read.listening)
    }

    func testListeningCountsAsProgress() {
        // The case from the report: a chapter in, roughly 34 minutes of an eight-hour book.
        let listened = book(position: 2019, duration: 28_800)
        XCTAssertEqual(listened.progress, 2019 / 28_800, accuracy: 0.0001)
        XCTAssertTrue(listened.listening)
        XCTAssertGreaterThan(listened.progress, 0, "an audiobook in progress must reach Home")
    }

    func testAListenedBookOfUnknownLengthStillCountsAsStarted() {
        let listened = book(position: 2019)
        XCTAssertEqual(listened.progress, 0.01, accuracy: 0.0001)
        XCTAssertTrue(listened.listening)
    }

    func testABookBothReadAndListenedReportsWhicheverIsFurther() {
        XCTAssertEqual(book(reading: 0.8, position: 100, duration: 1000).progress, 0.8, accuracy: 0.0001)
        XCTAssertEqual(book(reading: 0.1, position: 900, duration: 1000).progress, 0.9, accuracy: 0.0001)
        XCTAssertFalse(book(reading: 0.8, position: 100, duration: 1000).listening, "reading wins the label")
    }

    func testProgressNeverExceedsTheWholeBook() {
        XCTAssertEqual(book(position: 5000, duration: 1000).progress, 1, accuracy: 0.0001)
    }

    func testAnUntouchedBookIsNotOfferedToContinue() {
        XCTAssertEqual(book().progress, 0, accuracy: 0.0001)
        XCTAssertFalse(book().listening)
    }

    /// A library saved before listening progress was tracked has no audioDuration key at all.
    /// Decoding must survive that, or the change would empty an existing library.
    func testALibrarySavedBeforeThisChangeStillDecodes() throws {
        let saved = """
        [{"id":"abc","title":"Alice's Adventures in Wonderland","author":"Lewis Carroll",\
        "readingProgress":0.98,"audioPosition":0,"finished":false}]
        """
        let books = try JSONDecoder().decode([MediaBook].self, from: Data(saved.utf8))
        XCTAssertEqual(books.count, 1)
        XCTAssertNil(books[0].audioDuration)
        XCTAssertEqual(books[0].progress, 0.98, accuracy: 0.0001)
    }
}
