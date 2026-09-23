#if DEBUG
import QuartzCore
import os

/// Debug builds started with `-frameMonitor YES` count the frames that arrive late, so dropped frames can be measured
/// on a simulator or a phone rather than judged by eye. It sees the main thread missing frames, not work the GPU finds
/// slow, and while it runs it asks for the display's highest rate. Each late frame and a running total go to the unified log:
/// `log show --predicate 'subsystem == "com.natan.mediaportal" AND category == "frames"'`. With `-playerMorphAudit YES`
/// as well, the app exercises the player on its own; `xcrun simctl launch --console-pty` returns when it's done.
@MainActor final class FrameMonitor: NSObject {
    static let shared = FrameMonitor()
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private var frames = 0
    private var late = 0
    private let log = Logger(subsystem: "com.natan.mediaportal", category: "frames")

    func start() {
        guard UserDefaults.standard.bool(forKey: "frameMonitor"), link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick(_ link: CADisplayLink) {
        defer { last = link.timestamp }
        guard last > 0 else { return }
        let expected = link.targetTimestamp - link.timestamp
        let gap = link.timestamp - last
        frames += 1
        // A frame half again as long as expected means the main thread missed at least one refresh.
        if gap > expected * 1.5 {
            late += 1
            log.notice("late frame \(gap * 1000, format: .fixed(precision: 1)) ms, expected \(expected * 1000, format: .fixed(precision: 1)) ms")
        }
        if frames % 300 == 0 { log.notice("frames \(self.frames) late \(self.late)") }
    }
}
#endif

#if DEBUG
/// Debug builds started with `-playerMorphAudit YES` play the first audiobook, open and close the player ten times from
/// the mini player on their own, then quit. With the frame monitor on, that measures the app's own work during the
/// animation, without a UI test's accessibility queries stalling the main thread in between.
enum PlayerMorphAudit {
    @MainActor static func run(audio: AudioPlayer, library: Library) async {
        guard UserDefaults.standard.bool(forKey: "playerMorphAudit"), let book = library.books.first(where: { $0.audio != nil }) else { return }
        let log = Logger(subsystem: "com.natan.mediaportal", category: "frames")
        await audio.load(book, library: library); audio.play()
        try? await Task.sleep(for: .seconds(2))
        log.notice("audit start")
        for _ in 0..<10 {
            audio.playerRoute = PlayerRoute(id: book.id, fromMiniPlayer: true); try? await Task.sleep(for: .seconds(1.5))
            audio.playerRoute = nil; try? await Task.sleep(for: .seconds(1.5))
        }
        log.notice("audit end")
        exit(0)
    }
}
#endif
