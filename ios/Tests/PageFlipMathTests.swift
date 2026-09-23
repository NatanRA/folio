import XCTest
@testable import MediaPortal

/// The page picker's arithmetic. Android's picker uses the same numbers, so these cases are its reference too.
final class PageFlipMathTests: XCTestCase {
    func testChaptersStartAtTheFirstPositionOfTheirResource() {
        let toc: [(title: String?, href: String)] = [
            ("Cover", "cover.xhtml"), ("Chapter 1", "text/ch1.xhtml#start"), ("A part of chapter 1", "text/ch1.xhtml#part"),
            ("  ", "text/ch%202.xhtml"), ("Not in the book", "missing.xhtml"),
        ]
        let positions = ["cover.xhtml", "text/ch1.xhtml", "text/ch1.xhtml", "text/ch 2.xhtml", "text/ch 2.xhtml"]
        XCTAssertEqual(PageFlipMath.chapters(toc: toc, positionHrefs: positions), [
            BookChapter(title: "Cover", start: 0), BookChapter(title: "Chapter 1", start: 1), BookChapter(title: "Section 4", start: 3),
        ])
    }

    func testPagesBelongToTheLastChapterStartingBeforeThem() {
        let chapters = [BookChapter(title: "One", start: 2), BookChapter(title: "Two", start: 5)]
        XCTAssertNil(PageFlipMath.chapter(of: 2, in: chapters), "Front matter before the first chapter has none")
        XCTAssertEqual(PageFlipMath.chapter(of: 3, in: chapters)?.title, "One")
        XCTAssertEqual(PageFlipMath.chapter(of: 5, in: chapters)?.title, "One")
        XCTAssertEqual(PageFlipMath.chapter(of: 6, in: chapters)?.title, "Two")
        XCTAssertTrue(PageFlipMath.opensChapter(6, in: chapters))
        XCTAssertFalse(PageFlipMath.opensChapter(7, in: chapters))
        XCTAssertTrue(PageFlipMath.crossesChapter(from: 5, to: 6, in: chapters))
        XCTAssertTrue(PageFlipMath.crossesChapter(from: 6, to: 5, in: chapters), "Turning back out of a chapter knocks too")
        XCTAssertFalse(PageFlipMath.crossesChapter(from: 6, to: 9, in: chapters))
        XCTAssertFalse(PageFlipMath.crossesChapter(from: 4, to: 4, in: chapters))
    }

    func testTheBookOpensFromItsCoverOntoTheInsideCover() {
        XCTAssertEqual(SpreadFace.right(1, total: 82), .cover)
        XCTAssertEqual(SpreadFace.left(0), .none, "Nothing lies left of a closed book")
        XCTAssertEqual(SpreadFace.left(1), .insideCover, "Opposite page 2 is the inside of the cover, never page 1's text")
        XCTAssertEqual(SpreadFace.left(2), .page(2))
        XCTAssertEqual(SpreadFace.right(2, total: 82), .page(2))
        XCTAssertEqual(SpreadFace.right(83, total: 82), .none)
        XCTAssertEqual(SpreadFace.front(of: 1), .cover)
        XCTAssertEqual(SpreadFace.back(of: 1), .insideCover)
        XCTAssertEqual(SpreadFace.back(of: 5), .page(5))
        XCTAssertEqual(PageFlipMath.closedness(page: 1, coverAngle: nil), 1)
        XCTAssertEqual(PageFlipMath.closedness(page: 2, coverAngle: nil), 0)
        XCTAssertEqual(PageFlipMath.closedness(page: 2, coverAngle: -90), 0.5, "Half-open, the book is halfway out of the middle")
        XCTAssertEqual(PageFlipMath.closedness(page: 1, coverAngle: -180), 0)
    }

    func testAReleasedPageFallsPastHalfwayOrWhenFlicked() {
        XCTAssertTrue(PageFlipMath.commits(turned: 91, velocity: 0, forward: true))
        XCTAssertFalse(PageFlipMath.commits(turned: 80, velocity: -200, forward: true))
        XCTAssertTrue(PageFlipMath.commits(turned: 30, velocity: -400, forward: true))
        XCTAssertFalse(PageFlipMath.commits(turned: 30, velocity: 400, forward: true), "A flick the other way doesn't turn it")
        XCTAssertTrue(PageFlipMath.commits(turned: 30, velocity: 400, forward: false))
    }

    func testHardFlicksRiffleOnUpToNineteenPages() {
        // Turning forward means flicking left, so the speed that counts is negative.
        XCTAssertEqual(PageFlipMath.extraPages(velocity: -1000, forward: true), 0)
        XCTAssertEqual(PageFlipMath.extraPages(velocity: -1100, forward: true), 0)
        XCTAssertEqual(PageFlipMath.extraPages(velocity: -1150, forward: true), 0)
        XCTAssertEqual(PageFlipMath.extraPages(velocity: -1210, forward: true), 1)
        XCTAssertEqual(PageFlipMath.extraPages(velocity: -1650, forward: true), 5)
        XCTAssertEqual(PageFlipMath.extraPages(velocity: 1650, forward: false), 5)
        XCTAssertEqual(PageFlipMath.extraPages(velocity: -9000, forward: true), 19)
        XCTAssertEqual(PageFlipMath.extraPages(velocity: 1650, forward: true), 0, "Flicking back as the page lets go doesn't riffle on")
    }

    func testNumberedTitlesSplitIntoLabelAndName() {
        let alice = BookChapter(title: "CHAPTER I. Down the Rabbit-Hole", start: 5)
        XCTAssertEqual(alice.label, "CHAPTER I"); XCTAssertEqual(alice.name, "Down the Rabbit-Hole"); XCTAssertEqual(alice.caption, "I · Down the Rabbit-Hole")
        let storm = BookChapter(title: "Chapter 3: The Storm", start: 1)
        XCTAssertEqual(storm.label, "CHAPTER 3"); XCTAssertEqual(storm.name, "The Storm"); XCTAssertEqual(storm.caption, "3 · The Storm")
        let sea = BookChapter(title: "Part ii — The Sea", start: 1)
        XCTAssertEqual(sea.label, "PART II"); XCTAssertEqual(sea.name, "The Sea"); XCTAssertEqual(sea.caption, "Part II · The Sea")
        XCTAssertEqual(BookChapter(title: "IV. Pig and Pepper", start: 1).label, "CHAPTER IV")
        XCTAssertEqual(BookChapter(title: "IV. Pig and Pepper", start: 1).name, "Pig and Pepper")
        XCTAssertEqual(BookChapter(title: "Chapter XIV: Home", start: 1).label, "CHAPTER XIV")
        // Nothing to split: no invented numbers, no words read as numerals, and no label repeated as the name.
        for title in ["Contents", "Chapter 12", "Part of the Plan", "1984: A Novel", "Down the Rabbit-Hole", "Chapter Mild Mischief", "Section Civil Law", "Mild. Up"] {
            let chapter = BookChapter(title: title, start: 1)
            XCTAssertNil(chapter.label, title); XCTAssertEqual(chapter.name, title); XCTAssertEqual(chapter.caption, title)
        }
    }

    func testScrubbingMapsTheEdgesToPagesAndFineModeSlowsIt() {
        XCTAssertEqual(PageFlipMath.page(atFraction: 0, total: 82), 1)
        XCTAssertEqual(PageFlipMath.page(atFraction: 1, total: 82), 82)
        XCTAssertEqual(PageFlipMath.page(atFraction: 0.25, total: 82), 21)
        XCTAssertEqual(PageFlipMath.page(atFraction: -0.2, total: 82), 1)
        XCTAssertEqual(PageFlipMath.page(atFraction: 1.3, total: 82), 82)
        // A full strip's width in fine mode moves a fifth of the book.
        XCTAssertEqual(PageFlipMath.finePage(from: 40, moved: 300, width: 300, total: 82), 56)
        XCTAssertEqual(PageFlipMath.finePage(from: 40, moved: -30, width: 300, total: 82), 38)
        XCTAssertEqual(PageFlipMath.finePage(from: 80, moved: 300, width: 300, total: 82), 82)
    }
}
