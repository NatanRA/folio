import XCTest
import ReadiumShared
@testable import MediaPortal

@MainActor final class LibraryTests: XCTestCase {
    func testLiveCatalogueDownloads() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["PORTAL_LIVE_CATALOG"] == "1", "Opt-in public network smoke test")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = Library(root: root)
        let books = try await PublicCatalog.search(audio: false, query: "Alice", page: 1)
        let alice = try XCTUnwrap(books.first { $0.id == "pg-11" })
        try await PublicCatalog.add(alice, library: library) { _ in }
        XCTAssertNotNil(library.books.first?.ebook)
        let audiobooks = try await PublicCatalog.search(audio: true, query: "Alice", page: 1)
        var audio = try XCTUnwrap(audiobooks.first)
        XCTAssertGreaterThan(audio.tracks.count, 1)
        audio.tracks = Array(audio.tracks.prefix(1))
        try await PublicCatalog.add(audio, library: library) { _ in }
        let filename = try XCTUnwrap(library.books.last?.audio)
        let bundle = try JSONDecoder().decode(LocalAudioBundle.self, from: Data(contentsOf: library.file(filename)))
        XCTAssertEqual(bundle.tracks.count, 1)
        XCTAssertGreaterThan(bundle.tracks[0].durationMs, 1000)
    }
    func testPDFComicAndChapterBundle() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = Library(root: root)
        for name in ["sample.pdf", "comic.cbz"] {
            let source = Bundle(for: Self.self).url(forResource: name.components(separatedBy: ".")[0], withExtension: name.components(separatedBy: ".")[1])!
            let id = try await library.importFile(source, audio: false)
            XCTAssertNotNil(library.book(id)?.cover)
            library.update(id) { $0.fixedPage = 1 }
            XCTAssertEqual(Library(root: root).book(id)?.fixedPage, 1)
        }
        let comic = try await ComicPages(url: library.file(library.books[1].ebook!))
        XCTAssertEqual(comic.entries.map(\.path), ["page1.png", "page2.png", "page10.png"])
        let image = try await comic.image(1); XCTAssertGreaterThan(image.size.height, 100)
        let source = Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b")!
        try FileManager.default.copyItem(at: source, to: library.file("one.m4b"))
        let bundle = LocalAudioBundle(tracks: [.init(file: "one.m4b", name: "First chapter", durationMs: 12000), .init(file: "one.m4b", name: "Second chapter", durationMs: 12000)])
        try JSONEncoder().encode(bundle).write(to: library.file("test.audiobook.json"))
        let book = MediaBook(title: "Two chapters", audio: "test.audiobook.json"); library.books.append(book); try library.save()
        let player = AudioPlayer(); await player.load(book, library: library)
        XCTAssertNil(player.error); XCTAssertEqual(player.duration, 24); XCTAssertEqual(player.chapters.count, 2)
        XCTAssertEqual(player.chapterLength(0), 12); XCTAssertEqual(player.chapterLength(1), 12); XCTAssertEqual(player.chapterLength(2), 0)
        player.seek(14); XCTAssertEqual(player.position, 14); XCTAssertEqual(player.chapterIndex, 1)
        player.setSleep(endOfChapter: true); XCTAssertEqual(player.sleepChapterEnd, 24)
        player.setSleep(); player.pause()
    }
    func testImportLinkAndResume() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = Library(root: root)
        let epub = Bundle(for: Self.self).url(forResource: "alice", withExtension: "epub")!
        let audio = Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b")!
        let id = try await library.importFile(epub, audio: false)
        XCTAssertTrue(library.books[0].title.contains("Alice"))
        XCTAssertNotNil(library.books[0].cover)
        let publication = try await library.engine.open(library.file(library.books[0].ebook!))
        let positions = try await publication.positions().get()
        XCTAssertGreaterThan(positions.count, 20)
        let linked = try await library.importFile(audio, audio: true, linking: id)
        XCTAssertEqual(id, linked); XCTAssertEqual(library.books.count, 1)
        library.update(id) { $0.locator = positions[8].jsonString; $0.readingProgress = 0.1; $0.audioPosition = 7 }
        let reopened = Library(root: root)
        XCTAssertEqual(reopened.books[0].audioPosition, 7)
        XCTAssertEqual(reopened.books[0].readingProgress, 0.1)
        XCTAssertNotNil(reopened.books[0].locator)
        let model = ReaderModel(); await model.open(reopened.books[0], library: reopened)
        XCTAssertNotNil(model.navigator); XCTAssertNil(model.error)
        let search = try await publication.search(query: "Rabbit").get()
        let matches = try await search.next().get(); search.close()
        XCTAssertFalse(matches?.locators.isEmpty ?? true)
        let savedMark = SavedMark(locator: try XCTUnwrap(matches?.locators.first?.jsonString), quote: "Rabbit", note: "Test note")
        model.saveMark(savedMark)
        let bookmark = SavedMark(locator: try XCTUnwrap(positions[8].jsonString), bookmark: true)
        model.saveMark(bookmark)
        let nextModel = ReaderModel(); await nextModel.open(reopened.books[0], library: reopened)
        XCTAssertEqual(nextModel.marks.count, 2)
        XCTAssertEqual(nextModel.marks.first?.note, "Test note")
        nextModel.removeMark(savedMark)
        XCTAssertEqual(nextModel.marks.count, 1)
        let exchange = NotesExchange(library: reopened, id: id)
        let word = SavedMark(locator: savedMark.locator, quote: "Rabbit")
        nextModel.saveWords([word])
        let bundle = try exchange.export(marks: nextModel.marks, words: nextModel.words)
        let decoded = try JSONDecoder().decode(AnnotationBundle.self, from: bundle)
        XCTAssertEqual(decoded.document.count, 64)
        XCTAssertTrue(decoded.records.contains { $0.id == word.id && $0.kind == "word" })
        let otherRoot = root.appendingPathComponent("other-device")
        let other = Library(root: otherRoot)
        let otherID = try await other.importFile(epub, audio: false)
        let otherModel = ReaderModel(); await otherModel.open(other.books[0], library: other)
        let otherExchange = NotesExchange(library: other, id: otherID)
        try otherExchange.merge(bundle, model: otherModel)
        XCTAssertEqual(otherModel.marks.count, 1)
        XCTAssertEqual(otherModel.words.count, 1)
        otherModel.removeMark(bookmark)
        let deletion = try otherExchange.export(marks: otherModel.marks, words: otherModel.words)
        try exchange.merge(deletion, model: nextModel)
        XCTAssertTrue(nextModel.marks.isEmpty)
        try exchange.merge(bundle, model: nextModel)
        XCTAssertTrue(nextModel.marks.isEmpty, "An old export must not revive a deleted bookmark")
        let player = AudioPlayer(); await player.load(reopened.books[0], library: reopened)
        XCTAssertNil(player.error); XCTAssertEqual(player.position, 7, accuracy: 0.1)
        XCTAssertGreaterThan(player.duration, 11)
        player.setSpeed(1.5)
        XCTAssertEqual(player.speed, 1.5)
        player.setSleep(minutes: 15)
        XCTAssertNotNil(player.sleepDeadline)
        player.setSleep(endOfChapter: true)
        XCTAssertNil(player.sleepDeadline)
        XCTAssertNotNil(player.sleepChapterEnd)
        player.setSleep()
        XCTAssertNil(player.sleepChapterEnd)
        player.setSpeed(1)
        player.pause()
    }
    func testSecureOPDSLinks() throws {
        let base = try BookServer.endpoint("https://books.example")
        XCTAssertEqual(base.path, "/api/v1/opds")
        XCTAssertThrowsError(try BookServer.endpoint("http://books.example"))
        XCTAssertThrowsError(try BookServer.resolve("https://other.example/book", base: base))
        let xml = Data("<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>1</id><title>Alice</title><author><name>Lewis Carroll</name></author><link rel='http://opds-spec.org/acquisition' type='application/epub+zip' href='/book.epub'/></entry></feed>".utf8)
        let page = try FeedParser.parse(xml, base: base)
        XCTAssertEqual(page.books.count, 1); XCTAssertEqual(page.books[0].title, "Alice")
        XCTAssertEqual(page.books[0].download?.host, "books.example")
        XCTAssertThrowsError(try FeedParser.parse(Data("<!DOCTYPE a><feed/>".utf8), base: base))
    }
    func testServerDocumentFormatsAndLegacyCache() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = Library(root: root)
        let base = try BookServer.endpoint("https://books.example")
        for (format, mime, fixture) in [("pdf", "application/pdf; charset=binary", "sample"), ("cbz", "application/x-cbz", "comic")] {
            let xml = Data("<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>\(format)</id><title>Server document</title><link rel='http://opds-spec.org/acquisition' type='\(mime)' href='/download/\(format)'/></entry></feed>".utf8)
            let book = try XCTUnwrap(FeedParser.parse(xml, base: base).books.first)
            XCTAssertEqual(book.downloadFormat, format)
            try library.merge([book])
            XCTAssertEqual(Library(root: root).book(book.id)?.downloadFormat, format)
            let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: fixture, withExtension: format))
            let imported = try await library.importFile(file, audio: false, linking: book.id)
            XCTAssertEqual(imported, book.id)
            XCTAssertTrue(library.book(book.id)?.ebook?.hasSuffix("." + format) == true)
        }
        let formats = Data("<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>multi</id><title>Multiple</title><link rel='http://opds-spec.org/acquisition' type='application/epub+zip' href='/ebook'/><link rel='http://opds-spec.org/acquisition' type='application/pdf' href='/pdf'/></entry></feed>".utf8)
        XCTAssertEqual(try FeedParser.parse(formats, base: base).books.first?.downloadFormat, "epub")
        let legacy = MediaBook(title: "Legacy", download: URL(string: "https://books.example/old"))
        XCTAssertNil(try JSONDecoder().decode(MediaBook.self, from: JSONEncoder().encode(legacy)).downloadFormat)
    }
}
