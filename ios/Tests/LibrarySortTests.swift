import XCTest
@testable import MediaPortal

@MainActor final class LibrarySortTests: XCTestCase {
    private func book(_ title: String, _ author: String = "", added: TimeInterval? = nil) -> MediaBook {
        MediaBook(id: title.lowercased().replacingOccurrences(of: " ", with: "-"), title: title, author: author, addedAt: added.map { Date(timeIntervalSince1970: $0) })
    }
    private func names(_ books: [MediaBook]) -> [String] { books.map(\.title) }

    func testTitleSortIgnoresLeadingArticles() {
        let books = [book("The Hobbit"), book("A Wizard of Earthsea"), book("Dune"), book("an Unkindness of Ghosts")]
        XCTAssertEqual(names(LibrarySorting.sorted(books, by: .title)), ["Dune", "The Hobbit", "an Unkindness of Ghosts", "A Wizard of Earthsea"])
    }
    func testAuthorSortGroupsByAuthorThenTitleAndPutsUnknownAuthorsLast() {
        let books = [book("Dune", "Frank Herbert"), book("Mystery"), book("The Hobbit", "J. R. R. Tolkien"), book("Emma", "Jane Austen"), book("Dune Messiah", "Frank Herbert")]
        XCTAssertEqual(names(LibrarySorting.sorted(books, by: .author)), ["Dune", "Dune Messiah", "The Hobbit", "Emma", "Mystery"])
    }
    func testDateAddedSortsNewestFirstAndKeepsUndatedBooksLastInOrder() {
        let books = [book("Old", added: 100), book("Undated one"), book("New", added: 300), book("Undated two"), book("Middle", added: 200)]
        XCTAssertEqual(names(LibrarySorting.sorted(books, by: .added)), ["New", "Middle", "Old", "Undated one", "Undated two"])
    }
    func testExplicitSeriesMarkersGiveNameAndIndex() {
        XCTAssertEqual(LibrarySorting.parseSeries("Equal Rites (Discworld #3)"), SeriesInfo(name: "Discworld", index: 3))
        XCTAssertEqual(LibrarySorting.parseSeries("Caliban's War (The Expanse, Book 2)"), SeriesInfo(name: "The Expanse", index: 2))
        XCTAssertEqual(LibrarySorting.parseSeries("Discworld #3: Equal Rites"), SeriesInfo(name: "Discworld", index: 3))
        XCTAssertEqual(LibrarySorting.parseSeries("Dune Book 2 - Dune Messiah"), SeriesInfo(name: "Dune", index: 2))
        XCTAssertEqual(LibrarySorting.parseSeries("Harry Potter, Book 3"), SeriesInfo(name: "Harry Potter", index: 3))
        XCTAssertEqual(LibrarySorting.parseSeries("A Closed and Common Orbit (Wayfarers #1.5)"), SeriesInfo(name: "Wayfarers", index: 1.5))
    }
    func testYearsAndBareVolumeWordsAreNotSeries() {
        XCTAssertNil(LibrarySorting.parseSeries("Nineteen Eighty-Four (1949)"))
        XCTAssertNil(LibrarySorting.parseSeries("Collected Poems (Book 1)"))
        XCTAssertNil(LibrarySorting.parseSeries("The Hobbit"))
    }
    func testSharedTitlePrefixByTheSameAuthorBecomesASeries() {
        let books = [
            book("Harry Potter and the Chamber of Secrets", "J. K. Rowling"),
            book("The Hobbit", "J. R. R. Tolkien"),
            book("Harry Potter and the Philosopher's Stone", "J. K. Rowling"),
            book("Harry Potter and the Prisoner of Azkaban", "J. K. Rowling"),
        ]
        let series = LibrarySorting.series(for: books)
        XCTAssertEqual(series[books[0].id]?.name, "Harry Potter")
        XCTAssertEqual(series[books[2].id]?.name, "Harry Potter")
        XCTAssertEqual(series[books[3].id]?.name, "Harry Potter")
        XCTAssertNil(series[books[1].id])
    }
    func testPrefixesNeedTwoWordsAndMatchingAuthors() {
        let books = [book("Dune", "Frank Herbert"), book("Dune Messiah", "Frank Herbert"), book("Star Wars: Heir to the Empire", "Timothy Zahn"), book("Star Wars: Thrawn", "Timothy Zahn"), book("Star Wars: Lost Stars", "Claudia Gray")]
        let series = LibrarySorting.series(for: books)
        XCTAssertNil(series["dune"]); XCTAssertNil(series["dune-messiah"])
        XCTAssertEqual(series[books[2].id]?.name, "Star Wars"); XCTAssertEqual(series[books[3].id]?.name, "Star Wars")
        XCTAssertNil(series[books[4].id])
    }
    func testBlankAuthorsCanJoinAPrefixSeries() {
        let books = [book("Harry Potter and the Goblet of Fire"), book("Harry Potter and the Order of the Phoenix", "J. K. Rowling")]
        XCTAssertEqual(LibrarySorting.series(for: books)[books[0].id]?.name, "Harry Potter")
    }
    func testSeriesSortOrdersByIndexThenTitleWithStandaloneBooksLast() {
        let books = [
            book("Zebra Tales"),
            book("Harry Potter and the Prisoner of Azkaban", "J. K. Rowling"),
            book("Equal Rites (Discworld #3)", "Terry Pratchett"),
            book("Harry Potter and the Chamber of Secrets", "J. K. Rowling"),
            book("The Colour of Magic (Discworld #1)", "Terry Pratchett"),
            book("Alpha Standalone"),
        ]
        XCTAssertEqual(names(LibrarySorting.sorted(books, by: .series)),
            ["The Colour of Magic (Discworld #1)", "Equal Rites (Discworld #3)", "Harry Potter and the Chamber of Secrets", "Harry Potter and the Prisoner of Azkaban", "Alpha Standalone", "Zebra Tales"])
    }
    func testLibraryDecodesBooksSavedWithoutTimestampsAndBackfillsFromFiles() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data("not really audio".utf8).write(to: root.appendingPathComponent("old.mp3"))
        let legacy = #"[{"id":"legacy","title":"Legacy","author":"","audio":"old.mp3","readingProgress":0,"audioPosition":0,"finished":false},{"id":"remote","title":"Remote","author":"","readingProgress":0,"audioPosition":0,"finished":false}]"#
        try Data(legacy.utf8).write(to: root.appendingPathComponent("library.json"))
        let library = Library(root: root)
        XCTAssertNil(library.error)
        XCTAssertNotNil(library.book("legacy")?.addedAt)
        XCTAssertNil(library.book("remote")?.addedAt)
    }
    func testTrailingBookNumbersGiveTheIndexWhileTheSeriesComesFromTheSharedPrefix() {
        let books = [
            book("Harry Potter and the Prisoner of Azkaban, Book 3"),
            book("Crime and Punishment", "Fyodor Dostoyevsky"),
            book("Harry Potter and the Philosopher's Stone, Book 1"),
            book("Harry Potter and the Chamber of Secrets, Book 2"),
        ]
        let series = LibrarySorting.series(for: books)
        XCTAssertEqual(series[books[0].id], SeriesInfo(name: "Harry Potter", index: 3))
        XCTAssertEqual(series[books[2].id], SeriesInfo(name: "Harry Potter", index: 1))
        XCTAssertNil(series[books[1].id])
        XCTAssertEqual(names(LibrarySorting.sorted(books, by: .series)),
            ["Harry Potter and the Philosopher's Stone, Book 1", "Harry Potter and the Chamber of Secrets, Book 2", "Harry Potter and the Prisoner of Azkaban, Book 3", "Crime and Punishment"])
    }
}
