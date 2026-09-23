import XCTest
@testable import MediaPortal

/// How the progress bar moves between the player's reports and through jumps. Android follows the same rules.
@MainActor final class PlaybackMotionTests: XCTestCase {
    private let start = Date(timeIntervalSinceReferenceDate: 1000)

    func testReportsFromBeforeAJumpLandsAreIgnored() {
        let jump = PlaybackSeek(from: 70, to: 100, at: start)
        XCTAssertTrue(PlaybackMotion.accepts(70, pending: nil, now: start))
        XCTAssertFalse(PlaybackMotion.accepts(70, pending: jump, now: start.addingTimeInterval(0.3)), "A stale report would flick the bar back")
        XCTAssertTrue(PlaybackMotion.accepts(100.6, pending: jump, now: start.addingTimeInterval(0.3)), "A report near the target means it landed")
        XCTAssertTrue(PlaybackMotion.accepts(70, pending: jump, now: start.addingTimeInterval(1.6)), "The hold runs out, so a failed jump can't freeze the bar")
    }

    func testTheBarGlidesBetweenReportsAndEasesIntoJumps() {
        let clock = PlaybackClock(position: 100, at: start, rate: 1.5)
        XCTAssertEqual(PlaybackMotion.shown(clock: clock, seek: nil, now: start.addingTimeInterval(2)), 103, accuracy: 0.0001)
        let paused = PlaybackClock(position: 100, at: start, rate: 0)
        XCTAssertEqual(PlaybackMotion.shown(clock: paused, seek: nil, now: start.addingTimeInterval(5)), 100)
        let jump = PlaybackSeek(from: 70, to: 100, at: start)
        XCTAssertEqual(PlaybackMotion.shown(clock: paused, seek: jump, now: start), 70, "A jump starts where the bar was drawn")
        let halfway = PlaybackMotion.shown(clock: paused, seek: jump, now: start.addingTimeInterval(PlaybackMotion.ease / 2))
        XCTAssertGreaterThan(halfway, 85, "It eases out, covering most of the way early"); XCTAssertLessThan(halfway, 100)
        XCTAssertEqual(PlaybackMotion.shown(clock: paused, seek: jump, now: start.addingTimeInterval(PlaybackMotion.ease)), 100)
    }

    func testSmallDifferencesAreAbsorbedWithoutSteppingBack() {
        // The bar was drawn 0.4 s ahead of where the audio really restarted after a jump.
        let clock = PlaybackClock(position: 100.4, at: start, rate: 1)
        let now = start.addingTimeInterval(0.5)
        let next = PlaybackMotion.resync(clock, reported: 100.5, rate: 1, now: now)
        XCTAssertEqual(next.position(at: now), clock.position(at: now), accuracy: 0.0001, "No step at the report")
        XCTAssertEqual(next.rate, 0.6, accuracy: 0.0001, "It slows so it meets playback a second later")
        XCTAssertEqual(next.position(at: now.addingTimeInterval(1)), 101.5, accuracy: 0.0001)
        // Far behind is never a negative speed: the bar waits rather than running backwards.
        XCTAssertEqual(PlaybackMotion.resync(PlaybackClock(position: 101.4, at: start, rate: 1), reported: 100.2, rate: 1, now: start).rate, 0)
        // A big difference, like a jump from the lock screen, moves straight there; so does anything while paused.
        XCTAssertEqual(PlaybackMotion.resync(clock, reported: 300, rate: 1, now: now).position(at: now), 300)
        XCTAssertEqual(PlaybackMotion.resync(clock, reported: 100.5, rate: 0, now: now).position(at: now), 100.5)
    }

    func testQuickSkipsBuildOnEachOther() {
        let audio = AudioPlayer()
        audio.duration = 3600; audio.position = 100
        for _ in 0..<3 { audio.seek(audio.position + 30) }
        XCTAssertEqual(audio.position, 190, "Three taps of +30 s move 90 s, not 30")
        audio.seek(audio.position - 15)
        XCTAssertEqual(audio.position, 175)
        XCTAssertEqual(audio.clock.position, 175)
    }
}
