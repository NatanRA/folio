import XCTest
@testable import MediaPortal

/// A catalogue can offer a title as an audiobook as well as an e-book. Folio used to drop those
/// entries entirely: every audio acquisition link fell through `format` and left the book with no
/// download at all.
final class ServerAudiobookTests: XCTestCase {
    private func base() throws -> URL { try BookServer.endpoint("https://books.example") }

    private func feed(_ links: String) -> Data {
        Data("""
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry><title>Harry Potter and the Philosopher's Stone</title><id>urn:x:1</id>\(links)</entry>
        </feed>
        """.utf8)
    }

    private func acquisition(_ type: String, _ href: String = "https://books.example/f") -> String {
        "<link rel=\"http://opds-spec.org/acquisition\" type=\"\(type)\" href=\"\(href)\"/>"
    }

    func testReadsAnAudiobookEditionFromTheCatalogue() throws {
        let book = try XCTUnwrap(FeedParser.parse(feed(acquisition("audio/mpeg")), base: base()).books.first)
        XCTAssertEqual(book.audioDownloadFormat, "mp3")
        XCTAssertNotNil(book.audioDownload)
        XCTAssertNil(book.download, "an audiobook is not an e-book edition")
    }

    func testAcceptsTheAudioTypesTheDeviceCanPlay() {
        let expected = ["audio/mpeg": "mp3", "audio/mp4": "m4b", "audio/x-m4b": "m4b",
                        "audio/x-m4a": "m4a", "audio/aac": "aac", "audio/flac": "flac",
                        "audio/x-wav": "wav", "audio/aiff": "aiff"]
        for (mime, format) in expected {
            XCTAssertEqual(FeedParser.audioFormat(mime), format, "\(mime) should map to \(format)")
        }
        XCTAssertEqual(FeedParser.audioFormat("audio/mpeg; charset=utf-8"), "mp3", "parameters are ignored")
    }

    /// Offering a download the player cannot open would be worse than not offering it.
    func testRejectsAudioThisDeviceCannotPlay() {
        XCTAssertNil(FeedParser.audioFormat("audio/opus"), "AVFoundation does not decode Opus")
        XCTAssertNil(FeedParser.audioFormat("audio/ogg"))
        XCTAssertNil(FeedParser.audioFormat("application/epub+zip"))
        XCTAssertNil(FeedParser.audioFormat(""))
    }

    func testPrefersAChapteredContainerWhenBothAreOffered() throws {
        let links = acquisition("audio/mpeg", "https://books.example/plain.mp3")
            + acquisition("audio/mp4", "https://books.example/chaptered.m4b")
        let book = try XCTUnwrap(FeedParser.parse(feed(links), base: base()).books.first)
        XCTAssertEqual(book.audioDownloadFormat, "m4b", "m4b carries chapters, mp3 usually does not")
        XCTAssertEqual(book.audioDownload?.lastPathComponent, "chaptered.m4b")
    }

    func testPrefersTheChapteredContainerWhateverOrderTheFeedLists() throws {
        let links = acquisition("audio/mp4", "https://books.example/chaptered.m4b")
            + acquisition("audio/mpeg", "https://books.example/plain.mp3")
        let book = try XCTUnwrap(FeedParser.parse(feed(links), base: base()).books.first)
        XCTAssertEqual(book.audioDownload?.lastPathComponent, "chaptered.m4b")
    }

    /// One title, both formats — the thing Folio is for.
    func testATitleCanOfferBothAnEbookAndAnAudiobook() throws {
        let links = acquisition("application/epub+zip", "https://books.example/book.epub")
            + acquisition("audio/mpeg", "https://books.example/book.mp3")
        let book = try XCTUnwrap(FeedParser.parse(feed(links), base: base()).books.first)
        XCTAssertEqual(book.downloadFormat, "epub")
        XCTAssertEqual(book.audioDownloadFormat, "mp3")
        XCTAssertEqual(book.download?.lastPathComponent, "book.epub")
        XCTAssertEqual(book.audioDownload?.lastPathComponent, "book.mp3")
    }

    /// A library saved before audiobooks were read from the server has neither key.
    func testALibrarySavedBeforeThisChangeStillDecodes() throws {
        let saved = """
        [{"id":"abc","title":"Alice","author":"Lewis Carroll",\
        "readingProgress":0,"audioPosition":0,"finished":false}]
        """
        let books = try JSONDecoder().decode([MediaBook].self, from: Data(saved.utf8))
        XCTAssertNil(books[0].audioDownload)
        XCTAssertNil(books[0].audioDownloadFormat)
    }
}
