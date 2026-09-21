import XCTest
@testable import MediaPortal

final class FolioNavigationTests: XCTestCase {
    func testMainScreensAndSearch() {
        for (tab, screen) in ["home", "library", "downloads", "server"].enumerated() {
            XCTAssertEqual(FolioNavigation(url: URL(string: "folio://navigate?screen=\(screen)")!)?.tab, tab)
        }
        let search = FolioNavigation(url: URL(string: "folio://navigate?screen=library&q=Alice+%26+C%2B%2B")!)
        XCTAssertEqual(search?.tab, 1)
        XCTAssertEqual(search?.query, "Alice & C++")
    }
    func testInvalidNavigationCannotChangeSettingsOrImport() {
        for link in ["folio://navigate?screen=delete", "folio://navigate?screen=home&screen=server",
                     "folio://navigate?screen=server&q=Alice", "folio://navigate?screen=library&q=",
                     "folio://navigate?screen=library&q=%00", "folio://navigate?screen=home&password=secret",
                     "folio://navigate/other?screen=home", "folio://user@navigate?screen=home",
                     "folio://navigate?screen=home#other", "folio-pair://booklore?address=x", "https://navigate?screen=home"] {
            XCTAssertNil(FolioNavigation(url: URL(string: link)!), link)
        }
    }
    func testRepeatedRequestsHaveDistinctIdentities() {
        let url = URL(string: "folio://navigate?screen=server")!
        XCTAssertNotEqual(FolioNavigation(url: url)?.id, FolioNavigation(url: url)?.id)
    }
}
