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
    @Published var position = 0.0
    @Published var duration = 0.0
    @Published var chapters: [AudioChapter] = []
    @Published var error: String?
    @Published var loading = false
    /// True while the full player screen is on screen, so the root view can hide the mini player.
    @Published var playerScreenVisible = false
    @Published var speed = UserDefaults.standard.object(forKey: "audio.speed") as? Float ?? 1
    @Published var sleepDeadline: Date?
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
    var chapterIndex: Int { chapters.lastIndex { $0.start <= position + 0.05 } ?? 0 }
    var chapterStart: Double { chapters.indices.contains(chapterIndex) ? chapters[chapterIndex].start : 0 }
    var chapterEnd: Double { chapters.indices.contains(chapterIndex + 1) ? chapters[chapterIndex + 1].start : duration }
    init() {
        tick = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let seconds = self.player.currentTime().seconds
                self.position = seconds.isFinite ? self.trackStart + seconds : self.trackStart
                self.playing = self.player.rate > 0
                if let end = self.sleepChapterEnd, self.position >= end { self.stopForSleep() }
                if let id = self.bookID { self.library?.update(id) { $0.audioPosition = self.position; $0.lastOpenedAt = Date(); if self.duration > 0 { $0.audioDuration = self.duration } } }
                self.publish()
            }
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
    }
    func load(_ book: MediaBook, library: Library) async {
        guard bookID != book.id else { return }
        let request = UUID(); requestID = request
        setSleep()
        pause(); loading = true; error = nil
        telegramResource?.cancel(); telegramResource = nil
        defer { if requestID == request { loading = false } }
        do {
            guard let file = book.audio else { throw PortalError.message("Add an audiobook to this title first.") }
            tracks = []; trackIndex = -1
            if file.hasSuffix(".audiobook.json") {
                let bytes = try Data(contentsOf: library.file(file)); guard bytes.count <= 2_000_000 else { throw PortalError.message("Invalid audiobook manifest.") }
                let bundle = try JSONDecoder().decode(LocalAudioBundle.self, from: bytes)
                guard (1...1000).contains(bundle.tracks.count), bundle.tracks.allSatisfy({ $0.file.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil && $0.file != "." && $0.file != ".." && $0.durationMs > 0 && $0.durationMs <= 86_400_000 && FileManager.default.fileExists(atPath: library.file($0.file).path) }) else { throw PortalError.message("Some audiobook chapters are missing or invalid.") }
                self.library = library; tracks = bundle.tracks; bookID = book.id
                var start = 0.0
                chapters = tracks.map { track in defer { start += Double(track.durationMs) / 1000 }; return AudioChapter(name: track.name, start: start) }
                duration = start
                seek(min(book.audioPosition, duration)); publish(); return
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
                    if item.commonKey == .commonKeyArtwork, let bytes = try? await item.load(.dataValue) {
                        let name = "\(book.id).jpg"
                        if let image = UIImage(data: bytes), let jpg = image.jpegData(compressionQuality: 0.9) { try? jpg.write(to: library.file(name)); library.update(book.id) { $0.cover = name } }
                    }
                    if item.commonKey == .commonKeyTitle, let title = try? await item.load(.stringValue), !title.isEmpty { library.update(book.id) { $0.title = title } }
                }
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
            seek(min(book.audioPosition, total)); publish()
        } catch { self.error = error.localizedDescription }
    }
    func play() {
        sleepPaused = false
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            if position >= duration { seek(0) }
            player.playImmediately(atRate: speed); playing = true; publish()
        } catch { self.error = error.localizedDescription }
    }
    func pause() { player.pause(); playing = false; publish() }
    func stopForOffline() { pause(); telegramResource?.cancel(); telegramResource = nil; player.replaceCurrentItem(with: nil); bookID = nil }
    func seek(_ seconds: Double) {
        guard duration > 0 else { return }
        position = min(duration, max(0, seconds))
        if !tracks.isEmpty {
            let index = chapters.lastIndex { $0.start <= position } ?? 0
            if index != trackIndex { let resume = playing; installTrack(index); if resume { player.playImmediately(atRate: speed) } }
        }
        player.seek(to: CMTime(seconds: max(0, position - trackStart), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        if let id = bookID { library?.update(id) { $0.audioPosition = position } }; publish()
    }
    private func publish() {
        guard let id = bookID, let book = library?.book(id) else { return }
        var info: [String: Any] = [MPMediaItemPropertyTitle: book.title,
            MPMediaItemPropertyArtist: chapters.indices.contains(chapterIndex) ? chapters[chapterIndex].name : book.author,
            MPMediaItemPropertyPlaybackDuration: max(0, chapterEnd - chapterStart),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: max(0, position - chapterStart),
            MPNowPlayingInfoPropertyPlaybackRate: playing ? Double(speed) : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue]
        if let name = book.cover, let library, let image = UIImage(contentsOfFile: library.file(name).path) { info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image } }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}

func mediaTime(_ seconds: Double) -> String {
    guard seconds.isFinite else { return "0:00" }
    let t = max(0, Int(seconds)); return t >= 3600 ? String(format: "%d:%02d:%02d", t/3600, t/60%60, t%60) : String(format: "%d:%02d", t/60, t%60)
}

struct PlayerScreen: View {
    let id: String
    @EnvironmentObject var library: Library
    @EnvironmentObject var audio: AudioPlayer
    @State private var scrub = 0.0
    @State private var dragging = false
    var body: some View {
        if let book = library.book(id) {
            ScrollView {
                VStack(spacing: 24) {
                    BookCover(book: book).frame(width: 200, height: 290).shadow(color: .black.opacity(0.15), radius: 20, y: 10)
                    Text(book.title).font(.title2.bold()).multilineTextAlignment(.center)
                    if audio.loading { ProgressView("Opening audiobook…") }
                    if let error = audio.error {
                        Text(error).foregroundStyle(.red)
                        if book.streaming { Button("Retry streaming") { Task { audio.stopForOffline(); await audio.load(book, library: library) } } }
                    }
                    if audio.chapters.count > 1 {
                        VStack(alignment: .leading, spacing: 0) {
                            Text("Chapters").font(.title3.bold()).padding(.bottom)
                            ForEach(Array(audio.chapters.enumerated()), id: \.element.id) { index, chapter in
                                Button { UISelectionFeedbackGenerator().selectionChanged(); audio.seek(chapter.start) } label: {
                                    HStack { Text(chapter.name); Spacer(); Text(mediaTime(chapter.start)).monospacedDigit() }
                                        .foregroundStyle(index == audio.chapterIndex ? Color.accentColor : .primary).padding(.vertical, 14)
                                }
                                Divider()
                            }
                        }
                    }
                }.padding(24)
            }
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    if audio.chapters.indices.contains(audio.chapterIndex) { Text(audio.chapters[audio.chapterIndex].name).font(.subheadline.weight(.semibold)).lineLimit(1) }
                    Slider(value: Binding(get: { dragging ? scrub : audio.position }, set: { scrub = $0 }), in: 0...max(1, audio.duration), onEditingChanged: { editing in
                        if editing { scrub = audio.position; dragging = true } else { audio.seek(scrub); dragging = false }
                    }).accessibilityLabel("Audiobook position")
                    HStack { Text(mediaTime(dragging ? scrub : audio.position)); Spacer(); Text(mediaTime(audio.duration)) }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    HStack {
                        Menu {
                            ForEach([15, 30, 45, 60], id: \.self) { minutes in Button("\(minutes) minutes") { audio.setSleep(minutes: minutes) } }
                            Button("End of chapter") { audio.setSleep(endOfChapter: true) }
                            Button("Turn off timer") { audio.setSleep() }
                        } label: {
                            if let deadline = audio.sleepDeadline { Label { Text(deadline, style: .timer) } icon: { Image(systemName: "moon.zzz") } }
                            else { Label(audio.sleepChapterEnd == nil ? "Sleep timer" : "End of chapter", systemImage: "moon.zzz") }
                        }.frame(minHeight: 44)
                        Spacer()
                        Menu {
                            ForEach([0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3], id: \.self) { speed in
                                Button("\(speed.formatted())×") { audio.setSpeed(Float(speed)) }
                            }
                        } label: { Text("\(audio.speed.formatted())×").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel("Playback speed")
                    }
                    HStack(spacing: 40) {
                        Button { audio.seek(audio.position - 15) } label: { Image(systemName: "gobackward.15").font(.title) }.accessibilityLabel("Back 15 seconds")
                        Button { audio.playing ? audio.pause() : audio.play() } label: { Image(systemName: audio.playing ? "pause.fill" : "play.fill").font(.title).frame(width: 72, height: 60).background(Color.accentColor.opacity(0.18), in: Capsule()) }.accessibilityLabel(audio.playing ? "Pause" : "Play")
                        Button { audio.seek(audio.position + 30) } label: { Image(systemName: "goforward.30").font(.title) }.accessibilityLabel("Forward 30 seconds")
                    }.padding(.top, 4).disabled(audio.loading || audio.bookID != id)
                }.padding(.horizontal, 24).padding(.vertical, 12).background(.regularMaterial)
            }
            .navigationTitle("Audiobook").navigationBarTitleDisplayMode(.inline)
            .onAppear { audio.playerScreenVisible = true }
            .onDisappear { audio.playerScreenVisible = false }
            .task { await audio.load(book, library: library) }
        }
    }
}
