import XCTest
@testable import MediaPortal

/// Folio guessed series by parsing title strings, while the catalogue was already telling it the
/// answer in the same feed. BookLore states it in two meta elements; BookOrbit packs it into a
/// link title. Both are OPDS 1.x.
final class SeriesMetadataTests: XCTestCase {
    private func base() throws -> URL { try BookServer.endpoint("https://books.example") }

    private func entry(_ inner: String, title: String = "Leviathan Wakes") -> Data {
        Data("""
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry><title>\(title)</title><id>urn:x:1</id>\(inner)
          <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://books.example/b.epub"/></entry>
        </feed>
        """.utf8)
    }

    func testReadsBookLoreSeriesMetadata() throws {
        let inner = """
        <meta property="belongs-to-collection" id="series">The Expanse</meta>
        <meta property="group-position" refines="#series">3</meta>
        """
        let book = try XCTUnwrap(FeedParser.parse(entry(inner), base: base()).books.first)
        XCTAssertEqual(book.seriesName, "The Expanse")
        XCTAssertEqual(book.seriesPosition, 3)
    }

    /// BookLore stores the position as a float, so half-numbered entries are real.
    func testReadsAFractionalSeriesPosition() throws {
        let inner = """
        <meta property="belongs-to-collection" id="series">Discworld</meta>
        <meta property="group-position" refines="#series">3.5</meta>
        """
        let book = try XCTUnwrap(FeedParser.parse(entry(inner), base: base()).books.first)
        XCTAssertEqual(book.seriesPosition ?? 0, 3.5, accuracy: 0.001)
    }

    func testReadsBookOrbitSeriesLink() throws {
        let inner = #"<link rel="http://opds-spec.org/sort/series" href="https://books.example/s" title="The Expanse #3"/>"#
        let book = try XCTUnwrap(FeedParser.parse(entry(inner), base: base()).books.first)
        XCTAssertEqual(book.seriesName, "The Expanse")
        XCTAssertEqual(book.seriesPosition, 3)
    }

    func testReadsASeriesLinkWithNoNumber() {
        XCTAssertEqual(FeedParser.series(fromLinkTitle: "The Expanse")?.name, "The Expanse")
        XCTAssertNil(FeedParser.series(fromLinkTitle: "The Expanse")?.position)
    }

    func testIgnoresASeriesLinkWithNothingUsable() {
        XCTAssertNil(FeedParser.series(fromLinkTitle: ""))
        XCTAssertNil(FeedParser.series(fromLinkTitle: "   "))
        XCTAssertNil(FeedParser.series(fromLinkTitle: " #3"), "a number with no name is not a series")
    }

    func testABookWithNoSeriesMetadataKeepsNone() throws {
        let book = try XCTUnwrap(FeedParser.parse(entry(""), base: base()).books.first)
        XCTAssertNil(book.seriesName)
        XCTAssertNil(book.seriesPosition)
    }

    // MARK: - Precedence

    private func book(_ id: String, _ title: String, series: String? = nil, position: Double? = nil) -> MediaBook {
        var book = MediaBook(title: title)
        book.id = id
        book.author = "James S. A. Corey"
        book.seriesName = series
        book.seriesPosition = position
        return book
    }

    /// The title says one thing and the catalogue says another; the catalogue is authoritative.
    func testTheCatalogueBeatsTheTitleGuess() {
        let books = [book("1", "Leviathan Wakes (Some Other Series #9)", series: "The Expanse", position: 1)]
        let info = LibrarySorting.series(for: books)
        XCTAssertEqual(info["1"]?.name, "The Expanse")
        XCTAssertEqual(info["1"]?.index, 1)
    }

    /// A locally imported file has no catalogue behind it, so the heuristic must remain.
    func testBooksWithoutCatalogueDataStillFallBackToTheTitle() {
        let books = [book("1", "Leviathan Wakes (The Expanse #1)"), book("2", "Caliban's War (The Expanse #2)")]
        let info = LibrarySorting.series(for: books)
        XCTAssertEqual(info["1"]?.name, "The Expanse")
        XCTAssertEqual(info["2"]?.index, 2)
    }

    func testCatalogueAndGuessedBooksCoexist() {
        let books = [book("1", "Leviathan Wakes", series: "The Expanse", position: 1),
                     book("2", "Caliban's War (The Expanse #2)")]
        let info = LibrarySorting.series(for: books)
        XCTAssertEqual(info["1"]?.name, "The Expanse", "from the catalogue")
        XCTAssertEqual(info["2"]?.name, "The Expanse", "from the title")
    }

    func testALibrarySavedBeforeThisChangeStillDecodes() throws {
        let saved = """
        [{"id":"abc","title":"Alice","author":"","readingProgress":0,"audioPosition":0,"finished":false}]
        """
        let books = try JSONDecoder().decode([MediaBook].self, from: Data(saved.utf8))
        XCTAssertNil(books[0].seriesName)
    }
}
