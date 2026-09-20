import XCTest
@testable import MediaPortal

final class ConnectionPasteTests: XCTestCase {
    func testReadsCredentialsCarriedInALink() throws {
        let pasted = try XCTUnwrap(PastedConnection("https://reader:secret@books.example.com"))
        XCTAssertEqual(pasted.address, "https://books.example.com")
        XCTAssertEqual(pasted.user, "reader")
        XCTAssertEqual(pasted.password, "secret")
    }

    func testDecodesEscapedCharactersInALinkPassword() throws {
        let pasted = try XCTUnwrap(PastedConnection("https://reader:p%40ss%20word@books.example.com:8443"))
        XCTAssertEqual(pasted.address, "https://books.example.com:8443")
        XCTAssertEqual(pasted.password, "p@ss word")
    }

    func testReadsJSONWrittenByAPairingTool() throws {
        let pasted = try XCTUnwrap(PastedConnection(#"{"address":"https://books.example.com","user":"reader","password":"secret"}"#))
        XCTAssertEqual(pasted, PastedConnection("https://reader:secret@books.example.com"))
    }

    func testAcceptsTheAlternateJSONKeysAPairingToolMightUse() throws {
        let pasted = try XCTUnwrap(PastedConnection(#"{"url":"https://books.example.com","username":"reader","pass":"secret"}"#))
        XCTAssertEqual(pasted.user, "reader")
        XCTAssertEqual(pasted.password, "secret")
    }

    func testReadsThreeValuesOnSeparateLines() throws {
        let pasted = try XCTUnwrap(PastedConnection("  https://books.example.com\nreader\nsecret\n"))
        XCTAssertEqual(pasted.address, "https://books.example.com")
        XCTAssertEqual(pasted.user, "reader")
        XCTAssertEqual(pasted.password, "secret")
    }

    func testRejectsInputThatIsNotAConnection() {
        XCTAssertNil(PastedConnection(""))
        XCTAssertNil(PastedConnection("   \n "))
        XCTAssertNil(PastedConnection("https://books.example.com"), "an address alone carries no credentials")
        XCTAssertNil(PastedConnection("https://books.example.com reader"), "two values are not a connection")
        XCTAssertNil(PastedConnection("books.example.com reader secret"), "the address must name a scheme")
        XCTAssertNil(PastedConnection("ftp://reader:secret@books.example.com"), "only http and https are servers we speak to")
        XCTAssertNil(PastedConnection(#"{"address":"https://books.example.com","user":"reader"}"#), "no password")
    }

    func testRejectsAnUnreasonablyLongPaste() {
        XCTAssertNil(PastedConnection("https://books.example.com reader " + String(repeating: "x", count: 5000)))
    }

    // MARK: - Pairing links

    private func link(_ string: String) -> PastedConnection? {
        guard let url = URL(string: string) else { return nil }
        return PastedConnection(pairingLink: url)
    }

    func testReadsAPairingLink() throws {
        let pairing = try XCTUnwrap(link("folio-pair://booklore?address=https%3A%2F%2Fbooks.example.com&user=reader&password=secret"))
        XCTAssertEqual(pairing.address, "https://books.example.com")
        XCTAssertEqual(pairing.user, "reader")
        XCTAssertEqual(pairing.password, "secret")
    }

    func testDecodesEscapedCharactersInAPairingLink() throws {
        let pairing = try XCTUnwrap(link("folio-pair://booklore?address=https%3A%2F%2Fbooks.example.com%3A8443&user=a%40b.com&password=p%40ss%2Bword"))
        XCTAssertEqual(pairing.address, "https://books.example.com:8443")
        XCTAssertEqual(pairing.user, "a@b.com")
        XCTAssertEqual(pairing.password, "p@ss+word")
    }

    func testRejectsLinksThatAreNotOurs() {
        XCTAssertNil(link("https://booklore?address=https%3A%2F%2Fb.example.com&user=r&password=s"), "wrong scheme")
        XCTAssertNil(link("folio-pair://telegram?address=https%3A%2F%2Fb.example.com&user=r&password=s"), "wrong host")
        XCTAssertNil(link("folio-pair://booklore?address=https%3A%2F%2Fb.example.com&user=r"), "no password")
        XCTAssertNil(link("folio-pair://booklore?address=b.example.com&user=r&password=s"), "address names no scheme")
        XCTAssertNil(link("folio-pair://booklore?address=javascript%3Aalert(1)&user=r&password=s"), "not a server address")
        XCTAssertNil(link("folio-pair://booklore"), "nothing at all")
    }

    /// Opening a book must never be mistaken for a request to connect a library.
    func testAnImportedBookIsNotAPairingLink() {
        XCTAssertNil(link("file:///var/mobile/Containers/Data/Application/x/Documents/alice.epub"))
        XCTAssertNil(link("https://books.example.com/download/alice.epub"))
    }
}
