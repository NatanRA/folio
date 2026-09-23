import SwiftUI
import UIKit

#if DEBUG
/// Debug builds can slow the player's spring (`-playerMotionScale 10`), so UI tests and people can catch it mid-flight.
private let playerMotionScale = max(1, UserDefaults.standard.double(forKey: "playerMotionScale"))
#else
private let playerMotionScale = 1.0
#endif

/// The spring every open and close of the player uses: a 0.42 s response, damped to 0.86 so it settles without a
/// wobble. Android's player uses the same numbers.
let playerSpring = Spring(response: 0.42 * playerMotionScale, dampingRatio: 0.86)

/// How far open the player is as it is drawn right now, which can differ from the value it is heading for while it
/// animates. The morph writes it every frame and a drag starts from it, so catching the card mid-flight never jumps.
/// It is a plain reference, never observed, so writing it doesn't redraw anything.
final class PlayerMorphProbe {
    var expansion = 0.0
}

/// The full player as a card that grows out of the mini player and shrinks back into it. One spring value drives the
/// whole morph, from 0 (the mini player) to 1 (full screen), so a tap or a drag can catch the card at any point and send
/// it the other way, keeping its speed: nothing waits for an animation to finish.
struct PlayerCard: View {
    let bookID: String
    let expansion: Double
    /// Opening or open, as opposed to closing or closed. It decides what a tap on the card means, whatever its size.
    let isOpen: Bool
    /// The mini player's capsule and thumbnail on screen; without them the card rises from below the screen.
    let start: CGRect?
    let startCover: CGRect?
    let probe: PlayerMorphProbe
    let open: () -> Void
    let close: () -> Void
    /// A drag following the finger reports the expansion under it, then the expansion and speed it was let go at.
    let drag: (Double) -> Void
    let release: (_ expansion: Double, _ velocity: Double, _ flung: Bool) -> Void
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var audio: AudioPlayer
    @State private var cover: UIImage?
    @State private var coverInPlayer: CGRect?
    @State private var dragFrom: Double?

    var body: some View {
        // The outer reader stays inside the safe area to learn its insets; the card itself covers the whole screen.
        GeometryReader { safe in
            let insets = safe.safeAreaInsets
            GeometryReader { proxy in
                card(full: CGRect(origin: .zero, size: proxy.size), insets: insets)
            }
            .ignoresSafeArea()
        }
        .task(id: bookID) { cover = library.book(bookID)?.cover.flatMap { UIImage(contentsOfFile: library.file($0).path) } }
    }

    private func card(full: CGRect, insets: EdgeInsets) -> some View {
            let from = start ?? full.offsetBy(dx: 0, dy: full.height)
            let book = library.book(bookID)
            return PlayerScreen(id: bookID, hidesArtwork: cover != nil, onArtworkFrame: { coverInPlayer = $0 })
                .environment(\.closePlayer, close)
                .padding(.top, insets.top).padding(.bottom, insets.bottom)
                .coordinateSpace(.named(PlayerCard.space))
                .modifier(PlayerMorph(
                    expansion: expansion, start: from, full: full, startCover: start == nil ? nil : startCover,
                    coverInPlayer: coverInPlayer, cover: cover,
                    title: book?.title ?? "", detail: miniDetail(book), playing: audio.playing && audio.bookID == bookID, isOpen: isOpen, probe: probe,
                    gesture: dragGesture(travel: max(1, from.minY - full.minY)), open: open))
    }

    static let space = "player"

    private func miniDetail(_ book: MediaBook?) -> String {
        audio.bookID == bookID && audio.chapters.indices.contains(audio.chapterIndex) ? audio.chapters[audio.chapterIndex].name : book?.author ?? ""
    }

    /// Dragging down follows the finger: the card's top edge stays under it all the way to the mini player.
    private func dragGesture(travel: Double) -> some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .onChanged { value in
                if dragFrom == nil {
                    // Only a mostly downward drag takes the card; sideways drags are left to the scrubber and pages.
                    guard value.translation.height > abs(value.translation.width) else { return }
                    dragFrom = probe.expansion
                }
                guard let from = dragFrom else { return }
                drag(min(1, max(0, from - value.translation.height / travel)))
            }
            .onEnded { value in
                guard let from = dragFrom else { return }
                dragFrom = nil
                // A downward fling faster than 800 points a second closes it wherever it is.
                release(min(1, max(0, from - value.translation.height / travel)), -value.velocity.height / travel, value.velocity.height > 800)
            }
    }
}

extension EnvironmentValues {
    /// Closes the player card; the player's own chevron calls it.
    @Entry var closePlayer: () -> Void = {}
}

/// Draws the card at a given expansion. It is animatable, so every in-between frame of the spring is laid out afresh:
/// the card's frame and corners, the mini player's row fading out, the player fading in, and the cover flying between
/// the thumbnail and its place in the player.
private struct PlayerMorph<CardGesture: Gesture>: ViewModifier, Animatable {
    var expansion: Double
    var animatableData: Double { get { expansion } set { expansion = newValue } }
    let start: CGRect
    let full: CGRect
    let startCover: CGRect?
    let coverInPlayer: CGRect?
    let cover: UIImage?
    let title: String
    let detail: String
    let playing: Bool
    let isOpen: Bool
    let probe: PlayerMorphProbe
    let gesture: CardGesture
    let open: () -> Void

    // Self.Content: ReadiumShared has a type named Content too.
    func body(content: Self.Content) -> some View {
        probe.expansion = expansion
        let progress = min(1, max(0, expansion))
        let frame = Self.mix(start, full, expansion)
        let scale = max(0.01, frame.width / max(1, full.width))
        let corner = Self.mix(start.height / 2, 44, progress)
        let flying: CGRect? = coverInPlayer.map { target in
            startCover.map { Self.mix($0, target, expansion) }
                // Rising from below, the cover rides along with the rest of the player.
                ?? CGRect(x: frame.minX + target.minX * scale, y: frame.minY + target.minY * scale, width: target.width * scale, height: target.height * scale)
        }
        return ZStack(alignment: .topLeading) {
            ZStack(alignment: .topLeading) {
                // Frosted like the mini player while small, the app's background once open.
                Rectangle().fill(.regularMaterial)
                Color(uiColor: .systemBackground).opacity(min(1, progress / 0.2))
                content
                    .frame(width: full.width, height: full.height)
                    .scaleEffect(scale, anchor: .topLeading)
                    .opacity(min(1, max(0, (progress - 0.12) / 0.45)))
                    // The player's own controls work as soon as they are big enough to aim at, even while growing.
                    .allowsHitTesting(isOpen && progress > 0.5)
                miniRow.frame(width: frame.width, height: start.height).opacity(1 - min(1, progress / 0.3))
            }
            .frame(width: frame.width, height: frame.height, alignment: .topLeading)
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .shadow(color: .black.opacity(0.22 * sin(progress * .pi)), radius: 24, y: 6)
            // A closing card reopens on a tap anywhere on it, however far it has shrunk.
            .onTapGesture { if !isOpen { open() } }
            .gesture(gesture)
            .accessibilityAddTraits(isOpen ? .isModal : [])
            .position(x: frame.midX, y: frame.midY)
            if let cover, let flying {
                Image(uiImage: cover).resizable().scaledToFill()
                    .frame(width: flying.width, height: flying.height)
                    .clipShape(RoundedRectangle(cornerRadius: Self.mix(8, 14, progress), style: .continuous))
                    .shadow(color: .black.opacity(0.18 * progress), radius: 20, y: 10)
                    .position(x: flying.midX, y: flying.midY)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .frame(width: full.width, height: full.height, alignment: .topLeading)
    }

    /// The mini player's row, drawn over the card while it is small. Its thumbnail's place is left empty: the flying
    /// cover fills it.
    private var miniRow: some View {
        HStack(spacing: 4) {
            HStack(spacing: 12) {
                Color.clear.frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
            }
            Image(systemName: playing ? "pause.fill" : "play.fill").font(.title3).foregroundStyle(.tint).frame(width: 44, height: 44)
        }
        .padding(.leading, 10).padding(.trailing, 6).padding(.vertical, 6)
        .accessibilityHidden(true)
    }

    static func mix(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
    static func mix(_ a: CGRect, _ b: CGRect, _ t: Double) -> CGRect {
        CGRect(x: mix(a.minX, b.minX, t), y: mix(a.minY, b.minY, t), width: max(0, mix(a.width, b.width, t)), height: max(0, mix(a.height, b.height, t)))
    }
}
