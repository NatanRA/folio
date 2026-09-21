import AVFoundation
import MediaPlayer
import SwiftUI

struct AudioChapter: Identifiable {
    var id: Double { start }
    var name: String
    var start: Double
}

@MainActor final class AudioPlayer: ObservableObject {
    @Published var bookID: String?
    @Published var playing = false
    /// Where playback is, updated twice a second. It isn't published: every screen watches this player, and redrawing
    /// them all twice a second stalled the main thread for tens of milliseconds each time, which showed as stutter in
    /// every animation. What shows it follows `clock` every frame, or `chapterIndex` when the chapter changes.
    var position = 0.0 { didSet { syncChapter() } }
    @Published var duration = 0.0
    @Published var chapters: [AudioChapter] = [] { didSet { syncChapter() } }
    /// The chapter playing, published only when it changes.
    @Published private(set) var chapterIndex = 0
    @Published var error: String?
    @Published var loading = false
    /// The audiobook open in the full-screen player. It is presented from the root, so the book page
    /// and the mini player can both open it.
    @Published var playerRoute: PlayerRoute?
    @Published var speed = UserDefaults.standard.object(forKey: "audio.speed") as? Float ?? 1
    @Published var sleepDeadline: Date?
    /// Where playback was last known to be, when, and how fast it was going. The player reports its position only twice
    /// a second; the progress bar draws from this clock every frame, so it glides instead of stepping.
    /// Not published, for the same reason as `position`; the progress bar reads it every frame while it moves.
    private(set) var clock = PlaybackClock()
    /// The latest jump, which the bar eases into for a moment instead of snapping.
    @Published private(set) var lastSeek: PlaybackSeek?
    /// A jump the player hasn't reached yet. Until it reports a position near it, or the hold runs out, its older
    /// reports are ignored, so the bar never flicks back to where it was.
    private var pendingSeek: PlaybackSeek?
    /// When the listening position is next written to the library while playing. Writing it saves the whole library
    /// and redraws every screen that shows it, which is too much work to do twice a second.
    private var nextPositionSave = Date.distantPast
    /// The lock screen's cover for the book playing, made once rather than on every position report.
    private var nowPlayingArtwork: (cover: String, artwork: MPMediaItemArtwork)?
    private var nowPlayingArtworkLoading: String?
    @Published var sleepChapterEnd: Double?
    private var sleepBoundary: Any?
    private var sleepTask: Task<Void, Never>?
    private var sleepPaused = false
    private func stopForSleep() { sleepPaused = true; pause(); setSleep() }
    func setSpeed(_ value: Float) { speed = min(3, max(0.5, value)); UserDefaults.standard.set(speed, forKey: "audio.speed"); if playing { player.rate = speed }; publish() }
    func setSleep(minutes: Int? = nil, endOfChapter: Bool = false) {
        sleepTask?.cancel(); sleepTask = nil
        if let sleepBoundary { player.removeTimeObserver(sleepBoundary); self.sleepBoundary = nil }
        sleepDeadline = nil; sleepChapterEnd = nil
        if let minutes {
            sleepDeadline = Date().addingTimeInterval(Double(minutes * 60))
            sleepTask = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(minutes * 60)); self?.stopForSleep() } catch { }
            }
        } else if endOfChapter, duration > 0 {
            sleepChapterEnd = chapterEnd
            sleepBoundary = player.addBoundaryTimeObserver(forTimes: [NSValue(time: CMTime(seconds: chapterEnd - trackStart, preferredTimescale: 600))], queue: .main) { [weak self] in
                Task { @MainActor in self?.stopForSleep() }
            }
        }
    }
    let player = AVPlayer()
    private var tick: Any?
    private var endObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var routeObserver: NSObjectProtocol?
    private var terminateObserver: NSObjectProtocol?
    private weak var library: Library?
    private var requestID = UUID()
    private var telegramResource: TelegramAudioResource?
    private var itemStatus: NSKeyValueObservation?
    private var tracks: [LocalAudioTrack] = []
    private var trackIndex = -1
    private var trackStart: Double { trackIndex >= 0 ? tracks.prefix(trackIndex).reduce(0) { $0 + Double($1.durationMs) / 1000 } : 0 }
    private func installTrack(_ index: Int) {
        guard tracks.indices.contains(index), let library else { return }
        trackIndex = index
        let item = AVPlayerItem(url: library.file(tracks[index].file)); player.replaceCurrentItem(with: item)
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in Task { @MainActor in
            guard let self else { return }
            if self.sleepPaused { self.pause() }
            else if self.sleepChapterEnd != nil { self.position = self.chapterEnd; self.stopForSleep() }
            else if self.tracks.indices.contains(index + 1) { self.seek(self.trackStart + Double(self.tracks[index].durationMs) / 1000); self.play() }
            else { self.position = self.duration; self.pause(); self.setSleep() }
        } }
    }
    private func syncChapter() {
        let index = chapters.lastIndex { $0.start <= position + 0.05 } ?? 0
        if index != chapterIndex { chapterIndex = index }
    }
    var chapterStart: Double { chapters.indices.contains(chapterIndex) ? chapters[chapterIndex].start : 0 }
    var chapterEnd: Double { chapters.indices.contains(chapterIndex + 1) ? chapters[chapterIndex + 1].start : duration }
    /// Restarts the bar's clock from the latest known position and speed. While a jump is pending, the clock carries on
    /// from the jump rather than from a report the player hasn't caught up with.
    private func resyncClock() {
        let now = Date(), rate = playing ? Double(speed) : 0
        clock = pendingSeek == nil
            ? PlaybackMotion.resync(clock, reported: position, rate: rate, now: now)
            : PlaybackClock(position: clock.position(at: now), at: now, rate: rate)
    }
    /// How long chapter `index` runs: up to the next chapter, or to the end of the book for the last one.
    func chapterLength(_ index: Int) -> Double {
        guard chapters.indices.contains(index) else { return 0 }
        let end = chapters.indices.contains(index + 1) ? chapters[index + 1].start : duration
        return max(0, end - chapters[index].start)
    }
    init() {
        // Position sync reads the book actually being listened to from here, and moves this player
        // when a position arrives for the book it is holding paused.
        PositionSync.shared.player = self
        tick = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.sample() }
        }
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.play() }; return .success }
        center.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.pause() }; return .success }
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipForwardCommand.preferredIntervals = [30]
        center.skipBackwardCommand.addTarget { [weak self] _ in Task { @MainActor in guard let self else { return }; self.seek(self.position - 15) }; return .success }
        center.skipForwardCommand.addTarget { [weak self] _ in Task { @MainActor in guard let self else { return }; self.seek(self.position + 30) }; return .success }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in guard let self else { return }; self.seek(min(self.chapterEnd, self.chapterStart + event.positionTime)) }; return .success
        }
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if type == AVAudioSession.InterruptionType.began.rawValue { Task { @MainActor in self?.pause() } }
        }
        routeObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            if note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { Task { @MainActor in self?.pause() } }
        }
        // Playback saves every 15 seconds; a quit mid-way saves at once rather than losing the rest.
        terminateObserver = NotificationCenter.default.addObserver(forName: UIApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.savePosition() }
        }
    }
    /// One reading of the player's clock: where it has reached, and what that means for the book
    /// being listened to. A tick is reported on the main queue and read a turn later, so it refuses
    /// a player caught between books: `load` pauses, which reports a tick, and that tick is read at
    /// the load's first await — with the tracks torn down, the clock it would read belongs to
    /// whatever item the player still holds rather than to any book it could name.
    func sample() {
        guard !loading, player.currentItem?.status == .readyToPlay else { return }
        let seconds = player.currentTime().seconds
        let reported = seconds.isFinite ? trackStart + seconds : trackStart
        let now = Date()
        if PlaybackMotion.accepts(reported, pending: pendingSeek, now: now) { pendingSeek = nil; position = reported }
        // Playback that stopped on its own, a stream running dry say, saves where it stopped.
        if playing != (player.rate > 0) { playing.toggle(); if !playing { savePosition() } }
        if let end = sleepChapterEnd, position >= end { stopForSleep() }
        if now >= nextPositionSave, let library {
            nextPositionSave = now.addingTimeInterval(15)
            savePosition()
            PositionSync.shared.pushWhileListening(library: library)
        }
        publish()
    }
    func load(_ book: MediaBook, library: Library) async {
        guard bookID != book.id else { return }
        let request = UUID(); requestID = request
        setSleep()
        pause(); loading = true; error = nil
        // The book being left goes with the tracks its position was measured against. Keeping it
        // named over a cleared frame of reference lets the tick the pause above reports save the
        // clock of whatever the player still holds as that book's position — five hours into a
        // bundle recorded as the half hour into its current track, stamped as a fresh write here
        // and pulled by the other device as the newest word on the book.
        bookID = nil; tracks = []; trackIndex = -1
        telegramResource?.cancel(); telegramResource = nil
        defer { if requestID == request { loading = false } }
        do {
            guard let file = book.audio else { throw PortalError.message("Add an audiobook to this title first.") }
            if file.hasSuffix(".audiobook.json") {
                let bytes = try Data(contentsOf: library.file(file)); guard bytes.count <= 2_000_000 else { throw PortalError.message("Invalid audiobook manifest.") }
                let bundle = try JSONDecoder().decode(LocalAudioBundle.self, from: bytes)
                guard (1...1000).contains(bundle.tracks.count), bundle.tracks.allSatisfy({ $0.file.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil && $0.file != "." && $0.file != ".." && $0.durationMs > 0 && $0.durationMs <= 86_400_000 && FileManager.default.fileExists(atPath: library.file($0.file).path) }) else { throw PortalError.message("Some audiobook chapters are missing or invalid.") }
                self.library = library; tracks = bundle.tracks; bookID = book.id
                var start = 0.0
                chapters = tracks.map { track in defer { start += Double(track.durationMs) / 1000 }; return AudioChapter(name: track.name, start: start) }
                duration = start
                restore(book, library: library); publish(); return
            }
            let asset: AVURLAsset
            if book.streaming, let url = URL(string: file) {
                let resource = TelegramAudioResource(url: url); telegramResource = resource; asset = resource.asset
            } else { asset = AVURLAsset(url: library.file(file)) }
            let total = try await asset.load(.duration).seconds
            guard total.isFinite, total > 0, try await asset.load(.isPlayable) else { throw PortalError.message("This audio file cannot be played.") }
            var loaded: [AudioChapter] = []
            let locales = try await asset.load(.availableChapterLocales)
            if let locale = locales.first {
                let groups = try await asset.loadChapterMetadataGroups(withTitleLocale: locale, containingItemsWithCommonKeys: [.commonKeyTitle])
                for (i, group) in groups.enumerated() {
                    let title = try? await group.items.first?.load(.stringValue)
                    loaded.append(AudioChapter(name: title.flatMap { $0.isEmpty ? nil : $0 } ?? "Chapter \(i + 1)", start: group.timeRange.start.seconds))
                }
            }
            guard requestID == request else { return }
            if book.streaming {
                let metadata = try await asset.load(.commonMetadata)
                for item in metadata {
                    // Only a book without a cover takes the embedded one; a cover chosen by hand stays.
                    if item.commonKey == .commonKeyArtwork, library.book(book.id)?.cover == nil, let bytes = try? await item.load(.dataValue) {
                        try? await library.setCover(bytes, id: book.id)
                    }
                    if item.commonKey == .commonKeyTitle, let title = try? await item.load(.stringValue), !title.isEmpty { library.update(book.id) { $0.title = title } }
                }
                guard requestID == request else { return }
            }
            self.library = library; duration = total; chapters = loaded.sorted { $0.start < $1.start }
            if chapters.first?.start ?? 1 > 0 { chapters.insert(AudioChapter(name: "Opening", start: 0), at: 0) }
            if chapters.isEmpty { chapters = [AudioChapter(name: "Audiobook", start: 0)] }
            bookID = book.id
            let item = AVPlayerItem(asset: asset)
            item.preferredForwardBufferDuration = 20
            itemStatus = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                if item.status == .failed { Task { @MainActor in self?.error = "Couldn’t buffer audio. Check your connection and retry." } }
            }
            player.replaceCurrentItem(with: item)
            if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
            endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in Task { @MainActor in self?.pause(); self?.setSleep() } }
            restore(book, library: library); publish()
        } catch { self.error = error.localizedDescription }
    }
    /// Where to resume from is read again here rather than taken from the book this load started
    /// with: opening an audiobook awaits its asset, and a position pulled from the other device in
    /// that time is the newer one — seeking to the stale snapshot would write it straight back out.
    private func restore(_ book: MediaBook, library: Library) {
        seek(min(library.book(book.id)?.audioPosition ?? book.audioPosition, duration), moved: false)
    }
    func play() {
        sleepPaused = false
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            if position >= duration { seek(0) }
            player.playImmediately(atRate: speed); playing = true; nextPositionSave = Date().addingTimeInterval(15); publish()
        } catch { self.error = error.localizedDescription }
    }
    func pause() {
        player.pause()
        // Stop the bar where the audio stopped, not where the last twice-a-second report left it.
        let seconds = player.currentTime().seconds
        if pendingSeek == nil, seconds.isFinite { position = trackStart + seconds }
        playing = false; savePosition(); publish(); if let library { PositionSync.shared.exchange(library: library) }
    }
    /// Writes where the listener is to the library now. Playback does it every 15 seconds; pausing, jumping and the
    /// app going to the background do it at once.
    ///
    /// `moved` says whether the reader asked for this. Restoring a book to where it was left, or clamping that position
    /// to a length only this device has measured, moves nothing: stamping it would have this device claim a write it
    /// never made and outrank the other device's newer one.
    func savePosition(moved: Bool = true) {
        guard let id = bookID, let library, let stored = library.book(id) else { return }
        // Only real progress is saved. An unchanged position stamped as new would win over newer listening on another
        // device when positions are exchanged, pulling that device back.
        guard abs(stored.audioPosition - position) >= 1 else { return }
        library.update(id) {
            $0.audioPosition = position; $0.lastOpenedAt = Date()
            // Held forward, so a clock corrected backwards cannot make this device lose to itself.
            if moved { $0.audioModifiedAt = PositionProtocol.stamped($0.audioModifiedAt) }
            if duration > 0 { $0.audioDuration = duration }
        }
    }
    func stopForOffline() { pause(); telegramResource?.cancel(); telegramResource = nil; player.replaceCurrentItem(with: nil); bookID = nil }
    /// Jumps playback. Repeated jumps build on the pending target, so three quick taps of +30 s move 90 s. `eased: false`
    /// is for a scrubber drag, where the bar already sits where the finger let go.
    /// `moved: false` is for a position this device is only returning to, which `savePosition` records unstamped.
    func seek(_ seconds: Double, eased: Bool = true, moved: Bool = true) {
        guard duration > 0 else { return }
        let now = Date()
        let shown = PlaybackMotion.shown(clock: clock, seek: lastSeek, now: now)
        position = min(duration, max(0, seconds))
        let jump = PlaybackSeek(from: eased ? shown : position, to: position, at: now)
        pendingSeek = jump; lastSeek = jump
        clock = PlaybackClock(position: position, at: now, rate: playing ? Double(speed) : 0)
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(PlaybackMotion.ease + 0.05))
            if lastSeek == jump { lastSeek = nil }
        }
        if !tracks.isEmpty {
            let index = chapters.lastIndex { $0.start <= position } ?? 0
            if index != trackIndex { let resume = playing; installTrack(index); if resume { player.playImmediately(atRate: speed) } }
        }
        player.seek(to: CMTime(seconds: max(0, position - trackStart), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            Task { @MainActor in if finished, let self, self.pendingSeek == jump { self.pendingSeek = nil } }
        }
        savePosition(moved: moved); publish()
    }
    private func publish() {
        resyncClock()
        guard let id = bookID, let book = library?.book(id) else { return }
        var info: [String: Any] = [MPMediaItemPropertyTitle: book.title,
            MPMediaItemPropertyArtist: chapters.indices.contains(chapterIndex) ? chapters[chapterIndex].name : book.author,
            MPMediaItemPropertyPlaybackDuration: max(0, chapterEnd - chapterStart),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: max(0, position - chapterStart),
            MPNowPlayingInfoPropertyPlaybackRate: playing ? Double(speed) : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue]
        if let name = book.cover {
            if let made = nowPlayingArtwork, made.cover == name { info[MPMediaItemPropertyArtwork] = made.artwork }
            else if nowPlayingArtworkLoading != name, let library { nowPlayingArtworkLoading = name; Task { @MainActor in
                guard let image = await CoverImages.shared.load(library.file(name), points: 600, scale: 1), self.library?.book(id)?.cover == name else { return }
                nowPlayingArtwork = (name, MPMediaItemArtwork(boundsSize: image.size) { _ in image }); publish()
            } }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}

func mediaTime(_ seconds: Double) -> String {
    guard seconds.isFinite else { return "0:00" }
    let t = max(0, Int(seconds)); return t >= 3600 ? String(format: "%d:%02d:%02d", t/3600, t/60%60, t%60) : String(format: "%d:%02d", t/60, t%60)
}

/// Names the audiobook the full-screen player shows, so any screen can open it.
/// A position that moves on its own at `rate` seconds per second from `position`, as known at `at`.
struct PlaybackClock: Equatable {
    var position = 0.0
    var at = Date.distantPast
    var rate = 0.0
    func position(at date: Date) -> Double { position + max(0, date.timeIntervalSince(at)) * rate }
}

/// A jump in playback: where the bar was drawn, where it is going, and when.
struct PlaybackSeek: Equatable {
    let from: Double
    let to: Double
    let at: Date
}

/// How the progress bar moves. Android's player uses the same rules and numbers.
enum PlaybackMotion {
    /// How long the bar eases into a jump.
    static let ease = 0.18
    /// How long a jump holds off older reports at most, and how near a report must be to count as having arrived.
    static let hold = 1.5
    static let arrived = 1.0

    /// Whether a position the player reports may replace the one shown. Reports from before a pending jump landed are
    /// ignored, which is what made the bar flick back and forth when skipping.
    static func accepts(_ reported: Double, pending: PlaybackSeek?, now: Date) -> Bool {
        guard let pending else { return true }
        return abs(reported - pending.to) <= arrived || now.timeIntervalSince(pending.at) >= hold
    }

    /// How big a difference between the bar and a report is still smoothed out rather than jumped.
    static let converge = 1.5

    /// The clock after a report while playing. Small differences, like the moment audio takes to restart after a jump,
    /// are absorbed by drawing on from where the bar is at a speed that meets playback a second later, so the bar never
    /// steps or runs backwards. A bigger difference, like a jump from the lock screen, moves straight there.
    static func resync(_ clock: PlaybackClock, reported: Double, rate: Double, now: Date) -> PlaybackClock {
        let shown = clock.position(at: now)
        let gap = reported - shown
        guard rate > 0, abs(gap) < converge else { return PlaybackClock(position: reported, at: now, rate: rate) }
        return PlaybackClock(position: shown, at: now, rate: max(0, rate + gap))
    }

    /// Where the bar is drawn at `now`: the running clock, eased in from where it was drawn just after a jump.
    static func shown(clock: PlaybackClock, seek: PlaybackSeek?, now: Date) -> Double {
        let live = clock.position(at: now)
        guard let seek else { return live }
        let progress = now.timeIntervalSince(seek.at) / ease
        guard progress < 1 else { return live }
        return seek.from + (live - seek.from) * (1 - pow(1 - max(0, progress), 3))
    }
}

struct PlayerRoute: Identifiable, Hashable {
    let id: String
    /// Opened from the mini player, so it grows out of it; otherwise it rises from the bottom of the screen.
    var fromMiniPlayer = false
}

/// The full-screen player. Nothing scrolls: chapters open in their own sheet, so the controls never cover them.
/// It follows the app's appearance like every other screen, with the reader's accent marking what can be touched.
struct PlayerScreen: View {
    let id: String
    /// The card draws its own flying cover over the artwork's place while it morphs.
    var hidesArtwork = false
    /// Reports where the artwork sits, in the card's coordinates, so the flying cover can land on it.
    var onArtworkFrame: (CGRect) -> Void = { _ in }
    @EnvironmentObject var library: Library
    @EnvironmentObject var audio: AudioPlayer
    @Environment(\.closePlayer) private var closePlayer
    @Environment(\.displayScale) private var displayScale
    @AppStorage("accent") private var accentName = "Aquamarine"
    @State private var cover: UIImage?
    /// The chapter a drag started in, held so playback crossing into the next chapter cannot move the scale mid-drag.
    @State private var scrubChapter: (start: Double, length: Double)?
    @State private var scrub = 0.0
    @State private var chaptersShown = false
    private var accent: Color { portalAccent(accentName) }
    var body: some View {
        if let book = library.book(id) {
            VStack(spacing: 0) {
                // The grabber says the card can be dragged down; the chevron closes it too.
                Capsule().fill(Color(uiColor: .tertiaryLabel)).frame(width: 36, height: 5).padding(.bottom, 4).accessibilityHidden(true)
                HStack {
                    Button { closePlayer() } label: { Image(systemName: "chevron.down").font(.body.weight(.semibold)).foregroundStyle(.primary).frame(width: 40, height: 40).background(Color(uiColor: .secondarySystemFill), in: Circle()) }.accessibilityLabel("Close player")
                    Spacer()
                }
                artwork(book).padding(.horizontal, 16).padding(.vertical, 20)
                details(book)
                scrubber.padding(.top, 14)
                transport.padding(.top, 10)
                controls.padding(.top, 10)
            }
            .padding(.horizontal, 24).padding(.top, 12).padding(.bottom, 8)
            .tint(accent)
            .sheet(isPresented: $chaptersShown) { ChapterTimeline() }
            .task(id: book.cover) { cover = nil; if let name = book.cover { cover = await CoverImages.shared.load(library.file(name), points: PlayerCard.coverPoints, scale: displayScale) } }
            .task { await audio.load(book, library: library) }
        }
    }
    @ViewBuilder private func artwork(_ book: MediaBook) -> some View {
        // Drawn from memory on the first frame when the mini player has already decoded it, so the card never opens empty.
        if let cover = cover ?? book.cover.flatMap({ CoverImages.shared.cached(library.file($0), points: PlayerCard.coverPoints, scale: displayScale) }) {
            Image(uiImage: cover).resizable().scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .shadow(color: .black.opacity(0.18), radius: 20, y: 10)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(PlayerCard.space)) } action: { onArtworkFrame($0) }
                .opacity(hidesArtwork ? 0 : 1)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityHidden(true)
        } else { BookCover(book: book).frame(maxWidth: 280, maxHeight: .infinity) }
    }
    private func details(_ book: MediaBook) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(book.title).font(.title3.bold()).lineLimit(1)
            if !book.author.isEmpty { Text(book.author).foregroundStyle(.secondary).lineLimit(1) }
            if audio.bookID == id, audio.chapters.count > 1, audio.chapters.indices.contains(audio.chapterIndex) {
                Button { chaptersShown = true } label: {
                    HStack(spacing: 4) { Text(audio.chapters[audio.chapterIndex].name).lineLimit(1); Image(systemName: "chevron.right").font(.caption.weight(.bold)) }
                        .font(.subheadline.weight(.semibold)).frame(minHeight: 32)
                }.accessibilityHint("Shows all chapters")
            }
            if audio.loading { ProgressView("Opening audiobook…").padding(.top, 8) }
            if let error = audio.error {
                Label(error, systemImage: "exclamationmark.triangle.fill").font(.subheadline).foregroundStyle(.red).padding(.top, 8)
                if book.streaming { Button("Retry streaming") { Task { audio.stopForOffline(); await audio.load(book, library: library) } }.buttonStyle(.bordered) }
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    /// Scrubs within the current chapter, as the lock screen does, so a long book still gives fine control. It redraws
    /// every frame while playing or easing into a jump, and rests otherwise.
    private var scrubber: some View {
        // Easing into a jump draws every frame; otherwise only as often as the bar moves a pixel or so, since on a long
        // chapter it creeps a fraction of a point a second and redrawing it 120 times a second would only cost battery.
        let length = max(1, audio.chapterEnd - audio.chapterStart)
        return TimelineView(.animation(minimumInterval: audio.lastSeek == nil ? min(0.5, length / 1000) : nil, paused: !audio.playing && audio.lastSeek == nil)) { context in
            scrubber(at: context.date)
        }
    }
    private func scrubber(at now: Date) -> some View {
        let start = scrubChapter?.start ?? audio.chapterStart
        let length = scrubChapter?.length ?? max(1, audio.chapterEnd - audio.chapterStart)
        // A jump into another chapter changes the bar's scale, so it lands there directly rather than easing across.
        let ease = audio.lastSeek.flatMap { (start...(start + length)).contains($0.from) ? $0 : nil }
        let shown = PlaybackMotion.shown(clock: audio.clock, seek: ease, now: now)
        let elapsed = scrubChapter == nil ? min(max(0, shown - start), length) : scrub
        return VStack(spacing: 6) {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(uiColor: .systemFill))
                    Capsule().fill(accent).frame(width: proxy.size.width * elapsed / length)
                }
                .frame(height: scrubChapter == nil ? 5 : 9)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if scrubChapter == nil { scrubChapter = (start, length) }
                        scrub = min(max(0, value.location.x / proxy.size.width), 1) * (scrubChapter?.length ?? length)
                    }
                    .onEnded { _ in
                        if let chapter = scrubChapter { audio.seek(chapter.start + scrub, eased: false) }
                        scrubChapter = nil
                    })
            }.frame(height: 28)
            HStack { Text(mediaTime(elapsed)); Spacer(); Text("−" + mediaTime(length - elapsed)) }
                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .animation(.easeOut(duration: 0.15), value: scrubChapter == nil)
        .disabled(audio.loading || audio.bookID != id)
        .accessibilityRepresentation {
            Slider(value: Binding(get: { elapsed }, set: { audio.seek(start + $0) }), in: 0...length) { Text("Chapter position") }
        }
    }
    private var transport: some View {
        HStack {
            Button { audio.seek(audio.position - 15) } label: { Image(systemName: "gobackward.15").font(.system(size: 28, weight: .medium)).frame(width: 60, height: 60) }.accessibilityLabel("Back 15 seconds")
            Spacer()
            Button { audio.playing ? audio.pause() : audio.play() } label: {
                Image(systemName: audio.playing ? "pause.fill" : "play.fill").font(.system(size: 30)).frame(width: 80, height: 64)
                    .background(accent.opacity(0.18), in: Capsule()).contentTransition(.symbolEffect(.replace))
            }.accessibilityLabel(audio.playing ? "Pause" : "Play")
            Spacer()
            Button { audio.seek(audio.position + 30) } label: { Image(systemName: "goforward.30").font(.system(size: 28, weight: .medium)).frame(width: 60, height: 60) }.accessibilityLabel("Forward 30 seconds")
        }
        .frame(maxWidth: 290)
        .disabled(audio.loading || audio.bookID != id)
    }
    private var controls: some View {
        HStack {
            Menu {
                ForEach([0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3], id: \.self) { speed in
                    Button("\(speed.formatted())×") { audio.setSpeed(Float(speed)) }
                }
            } label: { Text("\(audio.speed.formatted())×").font(.body.weight(.semibold)).frame(minWidth: 56, minHeight: 44) }.accessibilityLabel("Playback speed")
            Spacer()
            Menu {
                ForEach([15, 30, 45, 60], id: \.self) { minutes in Button("\(minutes) minutes") { audio.setSleep(minutes: minutes) } }
                Button("End of chapter") { audio.setSleep(endOfChapter: true) }
                Button("Turn off timer") { audio.setSleep() }
            } label: {
                Group {
                    if let deadline = audio.sleepDeadline { Label { Text(deadline, style: .timer).monospacedDigit() } icon: { Image(systemName: "moon.zzz.fill") } }
                    else { Image(systemName: audio.sleepChapterEnd == nil ? "moon.zzz" : "moon.zzz.fill") }
                }.frame(minWidth: 56, minHeight: 44)
            }
            .accessibilityLabel("Sleep timer")
            .accessibilityValue(audio.sleepDeadline != nil ? "On" : audio.sleepChapterEnd != nil ? "End of chapter" : "Off")
            Spacer()
            Button { chaptersShown = true } label: { Image(systemName: "list.bullet").frame(minWidth: 56, minHeight: 44) }
                .accessibilityLabel("Chapters").disabled(audio.bookID != id || audio.chapters.count < 2)
        }
        .font(.title3)
        .frame(maxWidth: 300)
    }
}

/// Every chapter on one timeline: finished ones dimmed, the current one opened up to show how far in it is.
struct ChapterTimeline: View {
    @EnvironmentObject var audio: AudioPlayer
    @Environment(\.dismiss) private var dismiss
    @AppStorage("accent") private var accentName = "Aquamarine"
    private var accent: Color { portalAccent(accentName) }
    private let rail = Color(uiColor: .separator)
    private let surface = Color(uiColor: .systemBackground)
    var body: some View {
        // The player doesn't publish its position, so the time left and the current chapter's progress refresh on their own.
        TimelineView(.periodic(from: .now, by: 1)) { _ in timeline }
    }
    @ViewBuilder private var timeline: some View {
        let current = audio.chapterIndex
        let left = Duration.seconds(max(0, audio.duration - audio.position)).formatted(.units(allowed: [.hours, .minutes], width: .narrow))
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Chapters").font(.title.bold())
                    Text("\(audio.chapters.count) chapters · \(left) left").font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Done") { dismiss() }.buttonStyle(.bordered).buttonBorderShape(.capsule)
            }.padding(.horizontal, 20).padding(.top, 28).padding(.bottom, 12)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(audio.chapters.enumerated()), id: \.element.id) { index, chapter in
                            row(index, chapter, current: current).id(index)
                        }
                    }.padding(.horizontal, 20).padding(.bottom, 24)
                }
                .onAppear { proxy.scrollTo(current, anchor: .center) }
            }
        }
        .tint(accent)
        .presentationBackground(surface)
        .presentationDragIndicator(.visible)
    }
    private func row(_ index: Int, _ chapter: AudioChapter, current: Int) -> some View {
        let length = audio.chapterLength(index)
        return Button { audio.seek(chapter.start); dismiss() } label: {
            HStack(spacing: 16) {
                ZStack {
                    VStack(spacing: 0) {
                        Rectangle().fill(index == 0 ? .clear : rail).frame(width: 2)
                        Rectangle().fill(index == audio.chapters.count - 1 ? .clear : rail).frame(width: 2)
                    }
                    node(index, current: current)
                }.frame(width: 20).frame(maxHeight: .infinity)
                if index == current {
                    let elapsed = min(max(0, audio.position - chapter.start), length)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(chapter.name).font(.headline)
                        ProgressView(value: elapsed, total: max(length, 1)).tint(accent)
                        Text("\(mediaTime(elapsed)) of \(mediaTime(length)) · started at \(mediaTime(chapter.start))").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 16)).padding(.vertical, 6)
                } else {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(chapter.name).lineLimit(1).foregroundStyle(index < current ? .secondary : .primary)
                        Text("\(mediaTime(chapter.start)) · \(mediaTime(length))").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
                }
            }
            .fixedSize(horizontal: false, vertical: true).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(chapter.name), starts at \(mediaTime(chapter.start))")
        .accessibilityAddTraits(index == current ? .isSelected : [])
    }
    @ViewBuilder private func node(_ index: Int, current: Int) -> some View {
        if index == current {
            Circle().fill(accent).frame(width: 8, height: 8)
                .frame(width: 18, height: 18)
                .background(Circle().fill(surface))
                .overlay(Circle().strokeBorder(accent, lineWidth: 2))
        } else if index < current {
            Circle().fill(Color(uiColor: .tertiaryLabel)).frame(width: 10, height: 10)
        } else {
            Circle().fill(surface).frame(width: 10, height: 10).overlay(Circle().strokeBorder(Color(uiColor: .tertiaryLabel), lineWidth: 2))
        }
    }
}
