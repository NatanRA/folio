import XCTest
import TDLib
import AVFoundation
@testable import MediaPortal

final class TelegramLibraryTests: XCTestCase {
    func testParsesCoverAndAudioDetails() throws {
        let text = #"{"chat_id":1,"id":2,"content":{"@type":"messageAudio","audio":{"file_name":"book.m4b","title":"Book title","performer":"Author","audio":{"id":3,"size":1000},"album_cover_thumbnail":{"file":{"id":4}}}}}"#
        let message = try JSONSerialization.jsonObject(with: Data(text.utf8)) as! [String: Any]
        let file = try XCTUnwrap(TelegramFile.parse(message))
        XCTAssertEqual(file.thumbnail, 4)
        XCTAssertEqual(file.title, "Book title")
        XCTAssertEqual(file.performer, "Author")
    }
    @MainActor func testArtworkLoadsWithoutCreatingPlayer() async throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b"))
        let bytes = try Data(contentsOf: fixture)
        let entry = TelegramFile(chat: 1, message: 2, file: 3, name: "chaptered.m4b", size: Int64(bytes.count))
        var requested = 0
        let resource = TelegramAudioResource(url: URL(string: "telegram://1/1/2/chaptered.m4b")!, resolve: { _ in entry }, read: { _, offset, count in
            requested += count
            return bytes.subdata(in: Int(offset)..<min(bytes.count, Int(offset) + min(count, 4096)))
        })
        defer { resource.cancel() }
        let metadata = try await resource.asset.load(.commonMetadata)
        let cover = try XCTUnwrap(metadata.first { $0.commonKey == .commonKeyArtwork })
        let data = try await cover.load(.dataValue)
        XCTAssertFalse(try XCTUnwrap(data).isEmpty)
        XCTAssertLessThanOrEqual(requested, 8 * 1024 * 1024)
    }
    @MainActor func testNativePlayerReadsRangeBackedAudiobookAndChapters() async throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b"))
        let bytes = try Data(contentsOf: fixture)
        let entry = TelegramFile(chat: 1, message: 2, file: 3, name: "chaptered.m4b", size: Int64(bytes.count))
        var reads = 0
        let resource = TelegramAudioResource(url: URL(string: "telegram://1/1/2/chaptered.m4b")!, resolve: { _ in entry }, read: { _, offset, count in
            reads += 1
            return bytes.subdata(in: Int(offset)..<min(bytes.count, Int(offset) + min(count, 4096)))
        })
        let duration = try await resource.asset.load(.duration).seconds
        XCTAssertGreaterThan(duration, 0)
        let playable = try await resource.asset.load(.isPlayable)
        XCTAssertTrue(playable)
        XCTAssertGreaterThan(reads, 0)
        resource.cancel()
    }
    @MainActor func testOfflineUpgradeKeepsCloudIdentityAndPosition() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = Library(root: root)
        var book = MediaBook(title: "Cloud book")
        book.audio = "telegram://1/1/2/book.m4b"; book.telegramSource = "1:1:2"; book.audioPosition = 12
        library.books = [book]; try library.save()
        XCTAssertFalse(book.isLocal)
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b"))
        let id = try await library.importFile(fixture, audio: true, linking: book.id, telegramSource: book.telegramSource)
        XCTAssertEqual(id, book.id); XCTAssertEqual(library.books.count, 1)
        XCTAssertEqual(library.book(id)?.audioPosition, 12); XCTAssertTrue(library.book(id)?.isLocal == true)
    }
    @MainActor func testRepeatedImportPreservesListeningPosition() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = Library(root: root)
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "chaptered", withExtension: "m4b"))
        let first = try await library.importFile(fixture, audio: true, telegramSource: "1:-100123:9000000001")
        library.update(first) { $0.audioPosition = 123 }
        let second = try await library.importFile(fixture, audio: true, telegramSource: "1:-100123:9000000001")
        XCTAssertEqual(first, second)
        XCTAssertEqual(library.books.count, 1)
        XCTAssertEqual(library.book(first)?.audioPosition, 123)
        XCTAssertEqual(Library(root: root).book(first)?.telegramSource, "1:-100123:9000000001")
    }
    func testPreservesLargeIdentifiersAndFileSizes() throws {
        let text = #"{"id":8000000000000001,"chat_id":-1001234567890,"content":{"@type":"messageDocument","document":{"file_name":"Book.m4b","document":{"id":92,"size":4000000000}}}}"#
        let message = try JSONSerialization.jsonObject(with: Data(text.utf8)) as! [String: Any]
        let file = try XCTUnwrap(TelegramFile.parse(message))
        XCTAssertEqual(file.message, 8000000000000001)
        XCTAssertEqual(file.chat, -1001234567890)
        XCTAssertEqual(file.size, 4000000000)
        XCTAssertTrue(file.audio)
    }
    func testIgnoresUnsupportedAttachments() {
        let message: [String: Any] = ["id": 1, "chat_id": 2, "content": ["@type": "messageDocument", "document": ["file_name": "install.apk", "document": ["id": 92, "size": 4]]]]
        XCTAssertNil(TelegramFile.parse(message))
        XCTAssertNil(TelegramFile.parse(["content": ["@type": "messageText"]]))
    }
    func testNativeJsonInterfaceLoadsAndExecutes() throws {
        let pointer = try XCTUnwrap(td_execute(#"{"@type":"getTextEntities","text":"hello @telegram"}"#))
        let result = try JSONSerialization.jsonObject(with: Data(String(cString: pointer).utf8)) as! [String: Any]
        XCTAssertEqual(result["@type"] as? String, "textEntities")
        XCTAssertEqual((result["entities"] as? [Any])?.count, 1)
    }
}
