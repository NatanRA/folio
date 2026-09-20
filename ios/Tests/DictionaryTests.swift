import XCTest
@testable import MediaPortal

final class DictionaryTests: XCTestCase {
    func testBundledDefinitionsHandleInflectionsAndUnknownWords() async throws {
        // Same assertions as Android's DictionaryTest, so both dictionaries answer alike.
        let tough = try await OfflineDictionary.lookup("“Tougher,”")
        XCTAssertTrue(tough.contains { $0.word == "tough" && $0.partOfSpeech == "adjective" })
        XCTAssertTrue(tough.contains { !$0.examples.isEmpty })
        let children = try await OfflineDictionary.lookup("children"); XCTAssertTrue(children.contains { $0.word == "child" })
        let running = try await OfflineDictionary.lookup("running"); XCTAssertTrue(running.contains { $0.word == "run" })
        let happier = try await OfflineDictionary.lookup("happier"); XCTAssertTrue(happier.contains { $0.word == "happy" })
        let phrase = try await OfflineDictionary.lookup("ice cream"); XCTAssertFalse(phrase.isEmpty)
        let unknown = try await OfflineDictionary.lookup("zzzzfolioxyz"); XCTAssertTrue(unknown.isEmpty)
        let empty = try await OfflineDictionary.lookup(""); XCTAssertTrue(empty.isEmpty)
        let long = try await OfflineDictionary.lookup(String(repeating: "a", count: 81)); XCTAssertTrue(long.isEmpty)
    }
    func testSensesKeepWordNetOrderAndSplitExamplesFromGlosses() async throws {
        let meanings = try await OfflineDictionary.lookup("run")
        let verbs = meanings.filter { $0.partOfSpeech == "verb" }
        XCTAssertFalse(verbs.isEmpty)
        XCTAssertLessThanOrEqual(verbs.count, 12)
        XCTAssertEqual(verbs.first?.definition, "move fast by using one's feet, with one foot off the ground at any given time")
        XCTAssertTrue(verbs.first?.examples.contains("Don't run--you'll be out of breath") == true)
        XCTAssertFalse(verbs.contains { $0.definition.contains("\"") })
        XCTAssertEqual(meanings.map(\.partOfSpeech).reduce(into: [String]()) { if $0.last != $1 { $0.append($1) } }, ["noun", "verb"])
    }
}
