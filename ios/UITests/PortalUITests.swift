import XCTest

final class PortalUITests: XCTestCase {
    func testTelegramSetupUsesNativeConnectionSection() {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Server"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Telegram'")).firstMatch.tap()
        let field = app.textFields["API ID"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(app.secureTextFields["API hash"].exists)
        XCTAssertFalse(app.buttons["Connect Telegram"].isEnabled)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Telegram setup"; shot.lifetime = .keepAlways; add(shot)
    }
    func testUniversalSearchAcrossTabs() throws {
        let app = XCUIApplication(); app.launch()
        for tab in ["Home", "Library", "Downloads", "Server"] {
            app.tabBars.buttons[tab].tap()
            XCTAssertTrue(app.buttons["Search"].exists)
            XCTAssertFalse(app.textFields["global-search"].exists)
        }
        XCTAssertFalse(app.tabBars.buttons["Discover"].exists)
        app.buttons["Search"].tap()
        let field = app.textFields["global-search"]; XCTAssertTrue(field.waitForExistence(timeout: 5)); field.typeText("Alice")
        app.segmentedControls.buttons["Free books"].tap()
        XCTAssertTrue(app.segmentedControls.buttons["Audiobooks"].exists)
        app.segmentedControls.buttons["My library"].tap()
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Universal search"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["Close search"].tap()
        XCTAssertTrue(app.tabBars.buttons["Server"].isSelected)
        XCTAssertTrue(app.buttons["Search"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.textFields["global-search"].exists)
    }
    func testFixedDocumentNavigationAndDiscovery() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Library"].tap()
        for name in ["Test PDF", "Test Comic"] {
            let title = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
            guard title.waitForExistence(timeout: 5) else { throw XCTSkip("Requires the simulator-only PDF/CBZ fixtures") }
            title.tap()
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'E-book'")).firstMatch.tap()
            let next = app.buttons["Next page"]; XCTAssertTrue(next.waitForExistence(timeout: 10))
            if app.buttons["Previous page"].isEnabled { app.buttons["Previous page"].tap() }
            next.tap()
            let count = name == "Test PDF" ? "2 / 2" : "2 / 3"
            XCTAssertTrue(app.buttons[count].waitForExistence(timeout: 5))
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot)
            try XCTUnwrap(app.buttons.matching(identifier: "Home").allElementsBoundByIndex.first(where: { $0.isHittable })).tap()
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'E-book'")).firstMatch.tap()
            XCTAssertTrue(app.buttons[count].waitForExistence(timeout: 10))
            try XCTUnwrap(app.buttons.matching(identifier: "Home").allElementsBoundByIndex.first(where: { $0.isHittable })).tap()
            app.navigationBars.buttons.firstMatch.tap()
        }
        app.buttons["Search"].tap()
        XCTAssertTrue(app.textFields["global-search"].waitForExistence(timeout: 5))
        app.segmentedControls.buttons["Free books"].tap()
        XCTAssertTrue(app.segmentedControls.buttons["Audiobooks"].exists)
        XCTAssertTrue(app.buttons["Download"].firstMatch.waitForExistence(timeout: 65))
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Discovery"; shot.lifetime = .keepAlways; add(shot)
    }
    // Optional populated smoke test: seed the documented simulator-only Alice fixture first.
    func testPopulatedReaderAndAudio() throws {
        let app = XCUIApplication(); app.launch()
        let title = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Alice' AND label CONTAINS 'Wonderland'")).firstMatch
        guard title.waitForExistence(timeout: 5) else { throw XCTSkip("Requires the simulator-only Alice library fixture") }
        title.tap()
        let ebook = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'E-book'")).firstMatch
        XCTAssertTrue(ebook.waitForExistence(timeout: 5)); ebook.tap()
        let pages = app.buttons["pagePicker"]
        XCTAssertTrue(pages.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Next page"].exists)
        app.buttons["Next page"].tap()
        pages.tap()
        goToPage("12", in: app)
        XCTAssertTrue(pages.waitForExistence(timeout: 5))
        let match = NSPredicate(format: "label BEGINSWITH '12 /'")
        expectation(for: match, evaluatedWith: pages); waitForExpectations(timeout: 10)
        app.buttons["Reader menu"].tap()
        app.buttons["Bookmarks"].tap()
        XCTAssertTrue(app.buttons["Bookmark this page"].waitForExistence(timeout: 5))
        app.buttons["Bookmark this page"].tap()
        app.segmentedControls.buttons["Search"].tap()
        let search = app.textFields["bookSearch"]
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("Rabbit")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'matches' AND label != '0 matches'")).firstMatch.waitForExistence(timeout: 15))
        let searchShot = XCTAttachment(screenshot: app.screenshot()); searchShot.name = "iOS in-book search"; searchShot.lifetime = .keepAlways; add(searchShot)
        app.buttons["Done"].tap()
        let readerShot = XCTAttachment(screenshot: app.screenshot()); readerShot.name = "Native EPUB reader"; readerShot.lifetime = .keepAlways; add(readerShot)
        app.buttons["Next page"].tap()
        let home = try XCTUnwrap(app.buttons.matching(identifier: "Home").allElementsBoundByIndex.first(where: { $0.isHittable }))
        home.tap()
        let audio = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Audiobook'")).firstMatch
        XCTAssertTrue(audio.waitForExistence(timeout: 5)); audio.tap()
        let play = app.buttons["Play"]
        XCTAssertTrue(play.waitForExistence(timeout: 10)); play.tap()
        XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 5)); app.buttons["Pause"].tap()
        let audioShot = XCTAttachment(screenshot: app.screenshot()); audioShot.name = "Native audiobook player"; audioShot.lifetime = .keepAlways; add(audioShot)
    }
    func testMiniPlayerKeepsTheTabBarReachable() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Library"].tap()
        let audiobook = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Audiobook'")).firstMatch
        let tileCount = app.scrollViews.firstMatch.buttons.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.height > 150 }.count
        for index in 0..<tileCount where !audiobook.exists {
            let tiles = app.scrollViews.firstMatch.buttons.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.height > 150 }
            guard tiles.indices.contains(index) else { break }
            tiles[index].tap()
            if !audiobook.waitForExistence(timeout: 2) { app.navigationBars.buttons.firstMatch.tap() }
        }
        guard audiobook.exists else { throw XCTSkip("Requires a seeded audiobook in the simulator library") }
        audiobook.tap()
        let play = app.buttons["Play"]; XCTAssertTrue(play.waitForExistence(timeout: 10))
        // Chapters open in a sheet of their own rather than scrolling under the controls.
        let chapters = app.buttons["Chapters"]
        if chapters.waitForExistence(timeout: 2), chapters.isEnabled {
            chapters.tap()
            // Starting from the first chapter leaves the test plenty of book, wherever an earlier run stopped.
            let first = app.buttons.matching(NSPredicate(format: "label ENDSWITH 'starts at 0:00'")).firstMatch
            XCTAssertTrue(first.waitForExistence(timeout: 5)); first.tap()
        }
        play.tap()
        XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 5))
        // Closing the player while it plays is what brings the mini player up.
        app.buttons["Close player"].tap()
        // The book page fits between the navigation bar and the mini player, so nothing needs scrolling to.
        let editArtwork = app.buttons["Edit artwork"]; XCTAssertTrue(editArtwork.waitForExistence(timeout: 5))
        let openPlayer = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Open player'")).firstMatch
        XCTAssertTrue(openPlayer.waitForExistence(timeout: 5))
        XCTAssertTrue(editArtwork.isHittable)
        XCTAssertLessThanOrEqual(editArtwork.frame.maxY, openPlayer.frame.minY, "The book page must end above the mini player")
        app.tabBars.buttons["Home"].tap()
        let mini = app.buttons["Pause audiobook"]; XCTAssertTrue(mini.waitForExistence(timeout: 5))
        XCTAssertLessThanOrEqual(mini.frame.maxY, app.tabBars.firstMatch.frame.minY + 1, "The mini player must sit above the tab bar, not over it")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Mini player above tab bar"; shot.lifetime = .keepAlways; add(shot)
        app.tabBars.buttons["Downloads"].tap()
        XCTAssertTrue(app.navigationBars["Downloads"].waitForExistence(timeout: 5))
        // The mini player opens the full player again.
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Open player'")).firstMatch.tap()
        XCTAssertTrue(app.buttons["Close player"].waitForExistence(timeout: 5))
        // A short seeded book can finish during the test, so only pause what is still playing.
        if app.buttons["Pause"].exists { app.buttons["Pause"].tap(); XCTAssertTrue(app.buttons["Play"].waitForExistence(timeout: 5)) }
        app.buttons["Close player"].tap()
    }
    func testNativeNavigationAndAppearance() {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.buttons["Add book"].waitForExistence(timeout: 15))
        app.buttons["Appearance"].tap()
        XCTAssertTrue(app.navigationBars["Appearance"].waitForExistence(timeout: 5))
        app.buttons["Blue"].tap()
        app.buttons["Done"].tap()
        app.tabBars.buttons["Server"].tap()
        XCTAssertTrue(app.navigationBars["Server settings"].waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'BookLore'")).firstMatch.tap()
        XCTAssertTrue(app.textFields["Server address"].waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Native iOS server settings"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.tabBars.buttons["Library"].tap()
        XCTAssertTrue(app.navigationBars["Library"].exists)
    }
    func testLibrarySortMenuOffersOptionsAndRemembersTheChoice() {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Home"].tap()
        XCTAssertFalse(app.buttons["Sort library"].exists)
        app.tabBars.buttons["Library"].tap()
        let sort = app.buttons["Sort library"]; XCTAssertTrue(sort.waitForExistence(timeout: 5)); sort.tap()
        for option in ["Title", "Author", "Series", "Date added"] { XCTAssertTrue(app.buttons[option].waitForExistence(timeout: 5)) }
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Library sort menu"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["Date added"].tap()
        app.tabBars.buttons["Downloads"].tap()
        XCTAssertTrue(app.buttons["Sort library"].waitForExistence(timeout: 5)); app.buttons["Sort library"].tap()
        XCTAssertTrue(app.buttons["Date added"].waitForExistence(timeout: 5)); XCTAssertTrue(app.buttons["Date added"].isSelected)
        app.buttons["Title"].tap()
    }
    func testLibraryGridRowsShareATopEdge() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Library"].tap()
        let tiles = app.scrollViews.firstMatch.buttons.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.height > 150 }
        guard tiles.count >= 2 else { throw XCTSkip("Requires at least two seeded books in the simulator library") }
        let first = tiles[0].frame, second = tiles[1].frame
        XCTAssertEqual(first.minY, second.minY, accuracy: 1, "Tiles in the same row should start at the same height")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Library grid"; shot.lifetime = .keepAlways; add(shot)
    }
    func testFinishButtonSitsAboveThePageControlsOnTheLastPage() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Library"].tap()
        let alice = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Alice'")).firstMatch
        guard alice.waitForExistence(timeout: 5) else { throw XCTSkip("Requires the simulator-only Alice fixture") }
        alice.tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'E-book'")).firstMatch.tap()
        let picker = app.buttons["pagePicker"]; XCTAssertTrue(picker.waitForExistence(timeout: 15))
        let total = try XCTUnwrap(picker.label.components(separatedBy: "/").last?.trimmingCharacters(in: .whitespaces))
        picker.tap()
        goToPage(total, in: app)
        let finish = app.buttons["Mark as finished"]
        let next = app.buttons["Next page"]; XCTAssertTrue(next.waitForExistence(timeout: 5))
        // Fallback in case the jump fell short of the end: page forward until the end of the book.
        var taps = 0
        while !finish.exists && taps < 120 { next.tap(); taps += 1 }
        XCTAssertTrue(finish.waitForExistence(timeout: 10))
        XCTAssertFalse(finish.frame.intersects(next.frame), "Finish button must not cover the page controls")
        XCTAssertLessThanOrEqual(finish.frame.maxY, next.frame.minY + 1, "Finish button should sit above the page controls")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Last page"; shot.lifetime = .keepAlways; add(shot)
    }
    func testAppearanceSheetFollowsTheSchemeItChanges() throws {
        let app = XCUIApplication(); app.launch()
        app.buttons["Appearance"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Appearance"].waitForExistence(timeout: 5))
        let picker = app.segmentedControls.firstMatch
        func sheetLuminance() -> CGFloat {
            // A point on the sheet's own background, just under the scheme picker.
            let point = CGPoint(x: picker.frame.midX, y: picker.frame.maxY + 16)
            return luminance(of: app.screenshot().image, at: point)
        }
        picker.buttons["Dark"].tap(); sleep(1)
        let dark = sheetLuminance()
        picker.buttons["Light"].tap(); sleep(1)
        let light = sheetLuminance()
        XCTAssertLessThan(dark, 0.35, "Sheet background should turn dark with the Dark setting (was \(dark))")
        XCTAssertGreaterThan(light, 0.65, "Sheet background should turn light with the Light setting (was \(light))")
        picker.buttons["System"].tap()
        app.buttons["Done"].tap()
    }
    /// Screenshots every screen that carries the accent, in each appearance, accent and reading paper, for a
    /// person to review legibility. It only runs when asked: `TEST_RUNNER_FOLIO_THEME_AUDIT=1 xcodebuild test …`.
    func testThemeAudit() throws {
        guard ProcessInfo.processInfo.environment["FOLIO_THEME_AUDIT"] != nil else { throw XCTSkip("Set TEST_RUNNER_FOLIO_THEME_AUDIT=1 to capture the theme audit") }
        let papers = ["White", "Sepia", "Sakura", "Night", "Indigo"]
        for appearance in ["Light", "Dark"] {
            for accent in ["Aquamarine", "Blue", "Lilac", "Peach", "Sakura"] {
                let app = XCUIApplication()
                app.launchArguments = ["-appearance", appearance, "-accent", accent, "-reader.paper", "White"]
                app.launch()
                func shot(_ name: String) { let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "\(appearance)-\(accent)-\(name)"; attachment.lifetime = .keepAlways; add(attachment) }
                shot("1-home")
                app.tabBars.buttons["Library"].tap()
                let book = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'The Lighthouse Keeper'")).firstMatch
                XCTAssertTrue(book.waitForExistence(timeout: 5)); book.tap()
                let ebook = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'E-book'")).firstMatch
                XCTAssertTrue(ebook.waitForExistence(timeout: 5)); shot("2-book")
                app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Audiobook'")).firstMatch.tap()
                XCTAssertTrue(app.buttons["Close player"].waitForExistence(timeout: 10)); sleep(1); shot("3-player")
                app.buttons["Close player"].tap()
                XCTAssertTrue(ebook.waitForExistence(timeout: 5)); ebook.tap()
                let pages = app.buttons["pagePicker"]; XCTAssertTrue(pages.waitForExistence(timeout: 10))
                for paper in papers {
                    if paper != "White" {
                        app.buttons["Reader menu"].tap(); app.buttons["Reading options"].tap()
                        let swatch = app.buttons[paper]; XCTAssertTrue(swatch.waitForExistence(timeout: 5)); swatch.tap()
                        if paper == "Sakura" { shot("5-options") }
                        app.buttons["Done"].tap(); sleep(1)
                    }
                    shot("4-reader-\(papers.firstIndex(of: paper)!)-\(paper)")
                }
                pages.tap(); XCTAssertTrue(app.descendants(matching: .any)["pageBook"].waitForExistence(timeout: 5)); sleep(1); shot("6-pages-Indigo")
                app.buttons["Done"].tap()
                app.terminate()
            }
        }
    }
    /// Swiping the page picker's book turns one page, and "Open page" takes the reader there.
    func testPageBookTurnsAPageAndOpensIt() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Library"].tap()
        let alice = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Alice'")).firstMatch
        guard alice.waitForExistence(timeout: 5) else { throw XCTSkip("Requires the simulator-only Alice fixture") }
        alice.tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'E-book'")).firstMatch.tap()
        let picker = app.buttons["pagePicker"]; XCTAssertTrue(picker.waitForExistence(timeout: 15))
        picker.tap(); goToPage("5", in: app)
        expectation(for: NSPredicate(format: "label BEGINSWITH '5 /'"), evaluatedWith: picker); waitForExpectations(timeout: 10)
        picker.tap()
        let book = app.descendants(matching: .any)["pageBook"]; XCTAssertTrue(book.waitForExistence(timeout: 5))
        let header = app.buttons["pageNumberEntry"]
        XCTAssertTrue(header.label.hasPrefix("Page 5 of"), header.label)
        // A slow drag with a pause before letting go turns exactly one page, by passing halfway rather than by a flick.
        book.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: book.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)), withVelocity: .slow, thenHoldForDuration: 0.3)
        expectation(for: NSPredicate(format: "label BEGINSWITH 'Page 6 of'"), evaluatedWith: header); waitForExpectations(timeout: 5)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Page book"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["Open page 6"].tap()
        expectation(for: NSPredicate(format: "label BEGINSWITH '6 /'"), evaluatedWith: picker); waitForExpectations(timeout: 10)
    }
    /// At page 1 the book lies closed on its cover; turning it opens onto page 2, and turning back closes it.
    func testPageBookOpensFromItsCover() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Library"].tap()
        let alice = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Alice'")).firstMatch
        guard alice.waitForExistence(timeout: 5) else { throw XCTSkip("Requires the simulator-only Alice fixture") }
        alice.tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'E-book'")).firstMatch.tap()
        let picker = app.buttons["pagePicker"]; XCTAssertTrue(picker.waitForExistence(timeout: 15))
        picker.tap(); goToPage("1", in: app)
        expectation(for: NSPredicate(format: "label BEGINSWITH '1 /'"), evaluatedWith: picker); waitForExpectations(timeout: 10)
        picker.tap()
        let book = app.descendants(matching: .any)["pageBook"]; XCTAssertTrue(book.waitForExistence(timeout: 5))
        XCTAssertTrue((book.value as? String)?.hasPrefix("Cover") == true, "\(String(describing: book.value))")
        let closed = XCTAttachment(screenshot: app.screenshot()); closed.name = "Closed book"; closed.lifetime = .keepAlways; add(closed)
        book.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: book.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)), withVelocity: .slow, thenHoldForDuration: 0.3)
        expectation(for: NSPredicate(format: "value BEGINSWITH 'Page 2 of'"), evaluatedWith: book); waitForExpectations(timeout: 5)
        let open = XCTAttachment(screenshot: app.screenshot()); open.name = "Opened onto the inside cover"; open.lifetime = .keepAlways; add(open)
        book.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: book.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)), withVelocity: .slow, thenHoldForDuration: 0.3)
        expectation(for: NSPredicate(format: "value BEGINSWITH 'Cover'"), evaluatedWith: book); waitForExpectations(timeout: 5)
    }
    /// Types a page into the page picker's "Go to page" prompt, which opens that page.
    private func goToPage(_ page: String, in app: XCUIApplication) {
        let header = app.buttons["pageNumberEntry"]; XCTAssertTrue(header.waitForExistence(timeout: 5)); header.tap()
        let field = app.textFields["Page number"]; XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText(page)
        app.alerts.buttons["Go"].tap()
        // Wait for the sheet to finish closing, so the reader's controls have settled where they'll stay.
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.descendants(matching: .any)["pageBook"])
        waitForExpectations(timeout: 5)
    }
    private func luminance(of image: UIImage, at point: CGPoint) -> CGFloat {
        guard let cg = image.cgImage else { return -1 }
        let scale = CGFloat(cg.width) / UIScreen.main.bounds.width
        let x = Int(point.x * scale), y = Int(point.y * scale)
        var pixel = [UInt8](repeating: 0, count: 4)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return -1 }
        context.draw(cg, in: CGRect(x: -x, y: -(cg.height - y - 1), width: cg.width, height: cg.height))
        return (0.2126 * CGFloat(pixel[0]) + 0.7152 * CGFloat(pixel[1]) + 0.0722 * CGFloat(pixel[2])) / 255
    }
}
