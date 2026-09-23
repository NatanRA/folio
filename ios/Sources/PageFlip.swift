import SwiftUI
import UIKit
import ReadiumShared

/// A chapter as the page picker shows it: its title and the index of its first page among the book's positions.
struct BookChapter: Equatable {
    let title: String
    let start: Int
    /// The label a numbered title carries, such as "CHAPTER I"; nil when it has none.
    var label: String? { ChapterTitle(title).label }
    /// The chapter's name without its label ("Down the Rabbit-Hole"), or the whole title.
    var name: String { ChapterTitle(title).name }
    /// One line for the header: "I · Down the Rabbit-Hole" when the title is numbered, otherwise the title.
    var caption: String { ChapterTitle(title).caption ?? title }
}

/// Splits a numbered title ("CHAPTER I. Down the Rabbit-Hole", "Part 2: The Sea", "IV. Pig and Pepper") into its label
/// and name, so a chapter opening can set the label small above the name. A title without its own number keeps no
/// label: a table of contents also lists covers and contents pages, which an invented number would mislabel.
/// The patterns are the Android picker's, so both apps split the same titles the same way.
struct ChapterTitle {
    let label: String?
    let name: String
    let caption: String?

    // A well-formed roman numeral, so words such as "Mild" or "Civil" aren't read as numbers.
    private static let roman = "M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"
    private static let numbered = try! NSRegularExpression(pattern: "^(chapter|part|book|section)\\s+([0-9]+|(?=[ivxlcdm])\(roman))(?=$|[\\s.:\\-–—])\\.?\\s*(?:[:\\-–—]\\s*)?(.*)$", options: .caseInsensitive)
    private static let bare = try! NSRegularExpression(pattern: "^([0-9]{1,3}|(?=[IVXLCDM])\(roman))\\s*[.:\\-–—]\\s+(.+)$")

    init(_ title: String) {
        let text = title.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        let range = NSRange(text.startIndex..., in: text)
        func group(_ match: NSTextCheckingResult, _ index: Int) -> String {
            Range(match.range(at: index), in: text).map { String(text[$0]).trimmingCharacters(in: .whitespaces) } ?? ""
        }
        if let match = Self.numbered.firstMatch(in: text, range: range) {
            let kind = group(match, 1), numeral = group(match, 2).uppercased(), rest = group(match, 3)
            if rest.isEmpty { (label, name, caption) = (nil, text, nil); return }
            let word = kind.prefix(1).uppercased() + kind.dropFirst().lowercased()
            (label, name, caption) = ("\(kind.uppercased()) \(numeral)", rest, word == "Chapter" ? "\(numeral) · \(rest)" : "\(word) \(numeral) · \(rest)")
        } else if let match = Self.bare.firstMatch(in: text, range: range) {
            let number = group(match, 1), rest = group(match, 2)
            (label, name, caption) = ("CHAPTER \(number)", rest, "\(number) · \(rest)")
        } else {
            (label, name, caption) = (nil, text, nil)
        }
    }
}

/// What one side of the book shows. Page 1 is the front cover: the book lies closed on it, and opening it shows the
/// inside of the cover on the left, as a real book does, rather than the first page's text.
enum SpreadFace: Equatable {
    case none, cover, insideCover, page(Int)

    /// The left-hand side under a spread: nothing while closed, the inside cover opposite page 2, then the page before.
    static func left(_ page: Int) -> SpreadFace { page >= 2 ? .page(page) : page == 1 ? .insideCover : .none }
    static func right(_ page: Int, total: Int) -> SpreadFace { page == 1 ? .cover : (2...max(2, total)).contains(page) ? .page(page) : .none }
    /// A turning page shows its front on the right and its back once it lands on the left.
    static func front(of page: Int) -> SpreadFace { page == 1 ? .cover : .page(page) }
    static func back(of page: Int) -> SpreadFace { page == 1 ? .insideCover : .page(page) }
}

/// The page picker's arithmetic, kept out of the view so it can be tested. The Android picker uses the same numbers.
enum PageFlipMath {
    /// How shut the book is, from 1 (closed on its cover) to 0 (open). While the cover turns it follows the cover, so the
    /// closed book slides out of the middle as it opens and back as it closes.
    static func closedness(page: Int, coverAngle: Double?) -> Double {
        if let coverAngle { return 1 - min(180, abs(coverAngle)) / 180 }
        return page == 1 ? 1 : 0
    }

    /// A released page falls over when it is turned past halfway or flicked faster than this, in points per second.
    static let commitVelocity = 350.0
    /// A flick faster than this riffles on through more pages.
    static let riffleVelocity = 1100.0
    /// Lifting the finger this far above the page edges switches to fine scrubbing.
    static let fineLift = 44.0
    static let fineSpeed = 0.2

    /// Chapters from the table of contents, each starting at the first position in its resource, in reading order.
    static func chapters(toc: [(title: String?, href: String)], positionHrefs: [String]) -> [BookChapter] {
        func resource(_ href: String) -> String {
            let bare = href.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? href
            return bare.removingPercentEncoding ?? bare
        }
        var firstPosition: [String: Int] = [:]
        for (index, href) in positionHrefs.enumerated() where firstPosition[resource(href)] == nil { firstPosition[resource(href)] = index }
        var taken = Set<Int>(), chapters: [BookChapter] = []
        for (number, entry) in toc.enumerated() {
            guard let start = firstPosition[resource(entry.href)], taken.insert(start).inserted else { continue }
            let title = entry.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            chapters.append(BookChapter(title: title.isEmpty ? "Section \(number + 1)" : title, start: start))
        }
        return chapters.sorted { $0.start < $1.start }
    }
    /// The chapter a page (counted from 1) belongs to: the last one starting at or before it.
    static func chapter(of page: Int, in chapters: [BookChapter]) -> BookChapter? { chapters.last { $0.start <= page - 1 } }
    static func opensChapter(_ page: Int, in chapters: [BookChapter]) -> Bool { chapters.contains { $0.start == page - 1 } }
    /// Whether moving between two pages lands in a different chapter, in either direction.
    static func crossesChapter(from: Int, to: Int, in chapters: [BookChapter]) -> Bool {
        from != to && chapter(of: from, in: chapters) != chapter(of: to, in: chapters)
    }
    static func commits(turned degrees: Double, velocity: Double, forward: Bool) -> Bool {
        degrees > 90 || (forward ? velocity < -commitVelocity : velocity > commitVelocity)
    }
    /// Pages a hard flick riffles through after the one it turned, up to 19. Only speed in the direction of the turn
    /// counts, so flicking back as a page lets go doesn't riffle it onward.
    static func extraPages(velocity: Double, forward: Bool) -> Int {
        let speed = forward ? -velocity : velocity
        return min(19, max(0, Int(((speed - riffleVelocity) / 110).rounded())))
    }
    /// The page under a point on the page edges, where 0 is the first page and 1 the last.
    static func page(atFraction fraction: Double, total: Int) -> Int {
        min(total, max(1, Int((fraction * Double(total - 1)).rounded()) + 1))
    }
    /// Fine scrubbing moves at a fifth of the speed, from the page where it began.
    static func finePage(from anchor: Int, moved: Double, width: Double, total: Int) -> Int {
        min(total, max(1, anchor + Int((moved / max(width, 1) * Double(total - 1) * fineSpeed).rounded())))
    }
}

/// The feel of the book: a tick for each page passing, a tap as a page lands, a firmer knock at a new chapter.
@MainActor final class PageHaptics {
    private let selection = UISelectionFeedbackGenerator()
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let medium = UIImpactFeedbackGenerator(style: .medium)
    private let soft = UIImpactFeedbackGenerator(style: .soft)
    private var lastTick = Date.distantPast

    /// A page passing while riffling or scrubbing. The Taptic Engine can't render ticks closer than this, as with pickers.
    func passed(crossingChapter: Bool) {
        if crossingChapter { medium.impactOccurred(); return }
        let now = Date(); guard now.timeIntervalSince(lastTick) >= 0.032 else { return }
        lastTick = now; selection.selectionChanged()
    }
    func landed(crossingChapter: Bool) { crossingChapter ? medium.impactOccurred() : light.impactOccurred() }
    func threshold() { selection.selectionChanged() }
    func opened() { soft.impactOccurred() }
}

/// The first words at each page, read lazily from the book for the pages near the one showing.
@MainActor final class PageSnippets: ObservableObject {
    @Published private(set) var text: [Int: String] = [:]
    private var loading = Set<Int>()

    func load(_ pages: ClosedRange<Int>, from model: ReaderModel) {
        guard let publication = model.publication else { return }
        for page in pages where text[page] == nil && !loading.contains(page) && model.positions.indices.contains(page - 1) {
            loading.insert(page)
            let locator = model.positions[page - 1]
            Task {
                text[page] = await Self.firstWords(publication, at: locator) ?? ""
                loading.remove(page)
            }
        }
    }

    /// The first body text from a position. Headings are skipped, as the preview already shows the chapter title,
    /// and so are short lines such as "CHAPTER I." that books often set as plain paragraphs.
    private static func firstWords(_ publication: Publication, at locator: Locator) async -> String? {
        guard let iterator = publication.content(from: locator)?.iterator() else { return nil }
        var short: String?
        for _ in 0..<8 {
            guard let element = try? await iterator.next() else { break }
            if let text = element as? TextContentElement, case .heading = text.role { continue }
            guard let words = (element as? TextualContentElement)?.text?.split(whereSeparator: \.isWhitespace).joined(separator: " "), words.count > 1 else { continue }
            if words.count < 24 { short = short ?? words; continue }
            return String(words.prefix(180))
        }
        return short
    }
}

/// A page of the book as the picker draws it: typeset from the book, not a picture of the rendered page.
struct PagePreview: View {
    let number: Int
    let chapter: BookChapter?
    let opensChapter: Bool
    let snippet: String?
    let paper: ReaderPaper
    let marked: Bool
    let size: CGSize

    var body: some View {
        let unit = size.width / 17
        ZStack(alignment: .topTrailing) {
            paper.pageColor
            VStack(spacing: unit * 0.55) {
                if opensChapter, let chapter {
                    VStack(spacing: unit * 0.5) {
                        if let label = chapter.label {
                            Text(label).font(.system(size: unit * 0.62, weight: .semibold)).tracking(unit * 0.24).opacity(0.6)
                        }
                        Text(chapter.name).font(.system(size: unit * 1.2, weight: .semibold, design: .serif)).multilineTextAlignment(.center).lineLimit(3)
                    }.padding(.top, unit * 1.2).padding(.bottom, unit * 0.4)
                } else if let chapter {
                    Text(chapter.name.uppercased()).font(.system(size: unit * 0.55, weight: .semibold)).tracking(unit * 0.1).lineLimit(1).opacity(0.5)
                }
                if let snippet, !snippet.isEmpty {
                    Text(snippet).font(.system(size: unit * 0.8, design: .serif)).lineSpacing(unit * 0.1)
                        .lineLimit(opensChapter ? 3 : 4).frame(maxWidth: .infinity, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: unit * 0.62) {
                    ForEach(0..<lineCount, id: \.self) { line in
                        Capsule().fill(paper.inkColor.opacity(0.16)).frame(width: lineWidth(line), height: unit * 0.3)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Spacer(minLength: 0)
                Text("\(number)").font(.system(size: unit * 1.1, weight: .bold)).monospacedDigit().opacity(0.75)
            }
            .foregroundStyle(paper.inkColor)
            .padding(.horizontal, size.width * 0.11).padding(.top, size.height * 0.09).padding(.bottom, size.height * 0.06)
            if marked {
                Ribbon().fill(.tint).frame(width: unit * 1.3, height: unit * 3.4).padding(.trailing, size.width * 0.13)
            }
        }
        .frame(width: size.width, height: size.height).clipped()
    }

    private var lineCount: Int { opensChapter ? 7 : snippet?.isEmpty == false ? 9 : 13 }
    /// Line lengths vary like set text, and stay the same each time the page is drawn.
    private func lineWidth(_ line: Int) -> CGFloat {
        let seed = { (salt: Double) in let x = sin(Double(number) * 127.1 + (Double(line) + salt) * 311.7) * 43758.5453; return x - x.rounded(.down) }
        let measure = size.width * 0.78
        return measure * (seed(0) < 0.18 ? 0.3 + seed(40) * 0.4 : 0.88 + seed(80) * 0.12)
    }
}

/// The front cover of the closed book: its artwork, or a cloth board with the title set on it.
private struct BookCoverFace: View {
    let artwork: UIImage?
    let title: String
    let author: String
    let marked: Bool
    let size: CGSize

    var body: some View {
        let unit = size.width / 17
        ZStack(alignment: .topTrailing) {
            if let artwork {
                Image(uiImage: artwork).resizable().scaledToFill().frame(width: size.width, height: size.height).clipped()
            } else {
                Rectangle().fill(.tint).overlay(Color.black.opacity(0.45))
                VStack(spacing: unit * 0.9) {
                    Text(title).font(.system(size: unit * 1.45, weight: .semibold, design: .serif)).multilineTextAlignment(.center).lineLimit(5)
                    if !author.isEmpty { Text(author.uppercased()).font(.system(size: unit * 0.6, weight: .semibold)).tracking(unit * 0.16).lineLimit(2).multilineTextAlignment(.center) }
                }
                .foregroundStyle(.white.opacity(0.92))
                .padding(.horizontal, size.width * 0.13)
                .frame(width: size.width, height: size.height)
            }
            if marked { Ribbon().fill(.tint).frame(width: unit * 1.3, height: unit * 3.4).padding(.trailing, size.width * 0.13) }
        }
        // The hinge where the board meets the spine.
        .overlay { LinearGradient(colors: [.black.opacity(0.3), .white.opacity(0.08), .clear], startPoint: .leading, endPoint: UnitPoint(x: 0.09, y: 0.5)) }
        .frame(width: size.width, height: size.height)
        .accessibilityHidden(true)
    }
}

/// The inside of the front cover, as seen opposite page 2: an endpaper in the paper colour with a faint pattern.
private struct InsideCover: View {
    let paper: ReaderPaper
    let size: CGSize

    var body: some View {
        Canvas { context, canvas in
            context.fill(Path(CGRect(origin: .zero, size: canvas)), with: .color(paper.pageColor))
            let pitch = max(6, canvas.width / 18), dot = max(1.2, pitch * 0.16)
            var row = 0
            var y = pitch / 2
            while y < canvas.height {
                var x = row.isMultiple(of: 2) ? pitch / 2 : pitch
                while x < canvas.width {
                    context.fill(Path(ellipseIn: CGRect(x: x - dot / 2, y: y - dot / 2, width: dot, height: dot)), with: .style(.tint.opacity(0.14)))
                    x += pitch
                }
                y += pitch * 0.87; row += 1
            }
        }
        .frame(width: size.width, height: size.height)
        .accessibilityHidden(true)
    }
}

/// The bookmark ribbon on the page the reader was on.
struct Ribbon: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: .zero); path.addLine(to: CGPoint(x: rect.maxX, y: 0)); path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY * 0.76)); path.addLine(to: CGPoint(x: 0, y: rect.maxY)); path.closeSubpath()
        }
    }
}

/// Turns a page around the spine. The angle is animatable so the page shows its back only once past upright.
private struct PageTurn<Back: View>: ViewModifier, Animatable {
    var angle: Double
    let back: Back
    var animatableData: Double { get { angle } set { angle = newValue } }
    // Self.Content: ReadiumShared has a type named Content too.
    func body(content: Self.Content) -> some View {
        Group {
            // Past 90° the page is seen from behind; flip its back over so the left-hand side reads normally.
            if angle < -90 { back.scaleEffect(x: -1, y: 1) } else { content }
        }
            .overlay {
                LinearGradient(colors: [.black.opacity(0.22), .white.opacity(0.05), .black.opacity(0.06)], startPoint: .leading, endPoint: .trailing)
                    .opacity(abs(sin(angle * .pi / 180))).allowsHitTesting(false)
            }
            .rotation3DEffect(.degrees(angle), axis: (x: 0, y: 1, z: 0), anchor: .leading, perspective: 0.4)
    }
}

/// The page picker as an open book. Swipe to turn a page, flick to riffle through many, drag along the page edges to
/// jump anywhere, and tap the right-hand page or "Open page" to go there.
struct PageFlipSheet: View {
    @ObservedObject var model: ReaderModel
    /// The page the reader was on, marked with a ribbon.
    let here: Int
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var snippets = PageSnippets()
    @State private var haptics = PageHaptics()
    @State private var chapters: [BookChapter] = []
    @State private var page = 1
    /// The spread under any page that is mid-turn.
    @State private var left = 0
    @State private var right = 1
    @State private var leaves: [Leaf] = []
    @State private var turning: Turning?
    @State private var scrub: Scrub?
    @State private var typing = false
    @State private var typed = ""
    @State private var artwork: UIImage?

    private struct Leaf: Identifiable { let id = UUID(); let page: Int; var angle: Double }
    private struct Turning { let direction: Int; let leaf: UUID?; var turned = 0.0; var pastHalf = false }
    private struct Scrub { var fine = false; var anchorX = 0.0; var anchorPage = 1; var lastLeaf = Date.distantPast }

    private var total: Int { max(1, model.positions.count) }
    private var entry: MediaBook? { model.library?.book(model.id) }
    private var closedness: Double {
        PageFlipMath.closedness(page: page, coverAngle: leaves.last(where: { $0.page == 1 })?.angle)
    }
    private var paper: ReaderPaper { model.readerPaper }
    private func chapter(_ page: Int) -> BookChapter? { PageFlipMath.chapter(of: page, in: chapters) }
    private func crosses(_ from: Int, _ to: Int) -> Bool { PageFlipMath.crossesChapter(from: from, to: to, in: chapters) }

    var body: some View {
        VStack(spacing: 0) {
            header
            Spacer(minLength: 16)
            book
            edges.padding(.top, 28)
            Spacer(minLength: 16)
            Button { open(page) } label: { Text("Open page \(page)").frame(maxWidth: .infinity).frame(minHeight: 30) }
                .buttonStyle(.borderedProminent).foregroundStyle(portalOnAccent).padding(.horizontal, 20).padding(.vertical, 16)
        }
        .padding(.top, 20)
        .presentationDragIndicator(.visible)
        // Like the reader's controls, the sheet is light on light papers and dark on dark ones. The paper can't
        // change while this sheet is open, so setting it once as the sheet appears is enough.
        .preferredColorScheme(paper.colorScheme)
        .alert("Go to page", isPresented: $typing) {
            TextField("Page number", text: $typed).keyboardType(.numberPad)
            Button("Go") { if let number = Int(typed), (1...total).contains(number) { open(number) } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Enter a page from 1 to \(total).") }
        .task {
            if let cover = entry?.cover, let library = model.library { artwork = UIImage(contentsOfFile: library.file(cover).path) }
            chapters = PageFlipMath.chapters(toc: flatten(model.contents), positionHrefs: model.positions.map { $0.href.string })
            page = min(max(here, 1), total); settle()
        }
        .task(id: page) {
            // Read the first words only once the pages stop moving past.
            try? await Task.sleep(for: .milliseconds(120))
            snippets.load(max(1, page - 2)...min(total, page + 2), from: model)
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            Button { typed = ""; typing = true } label: {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 0) { Text("Page \(page)").fontWeight(.bold); Text(" of \(total)").foregroundStyle(.secondary) }
                        .font(.title2).monospacedDigit()
                    Text(page == 1 ? entry?.title ?? " " : chapter(page)?.caption ?? " ").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Page \(page) of \(total)").accessibilityHint("Type a page number").accessibilityIdentifier("pageNumberEntry")
            Button("Done") { dismiss() }.buttonStyle(.bordered).buttonBorderShape(.capsule)
        }.padding(.horizontal, 20)
    }

    private var book: some View {
        GeometryReader { geo in
            let size = CGSize(width: geo.size.width / 2, height: geo.size.height)
            ZStack(alignment: .topLeading) {
                edgeLayers(count: layers(left), side: -1, size: size)
                edgeLayers(count: layers(total - right + 1), side: 1, size: size).offset(x: size.width)
                if SpreadFace.left(left) != .none {
                    face(.left(left), size: size)
                        .overlay { LinearGradient(colors: [.clear, .black.opacity(0.14)], startPoint: UnitPoint(x: 0.78, y: 0.5), endPoint: .trailing) }
                }
                face(.right(right, total: total), size: size)
                    .overlay { LinearGradient(colors: [.black.opacity(0.14), .clear], startPoint: .leading, endPoint: UnitPoint(x: 0.22, y: 0.5)) }
                    .overlay { LinearGradient(colors: [.black.opacity(0.28), .clear], startPoint: .leading, endPoint: .trailing).opacity(liftShadow) }
                    .offset(x: size.width)
                ForEach(leaves) { leaf in
                    face(.front(of: leaf.page), size: size)
                        .modifier(PageTurn(angle: leaf.angle, back: face(.back(of: leaf.page), size: size)))
                        .offset(x: size.width)
                }
            }
            // The right-hand page is drawn offset, so the spread needs its full frame to be touchable.
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .contentShape(Rectangle())
            // Shut, the book lies centred on its cover; it slides aside as the cover opens.
            .offset(x: -size.width / 2 * closedness)
            // Reduce Motion cross-fades between the closed and the open book instead.
            .id(reduceMotion && page == 1 ? "closed" : "book").transition(.opacity)
            .onTapGesture(coordinateSpace: .local) { point in if point.x > size.width { open(page) } else { step(-1) } }
            .gesture(turnGesture(pageWidth: size.width))
        }
        .aspectRatio(2 / 1.43, contentMode: .fit)
        .padding(.horizontal, 20)
        .rotation3DEffect(.degrees(reduceMotion ? 0 : 14), axis: (x: 1, y: 0, z: 0), perspective: 0.35)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Book")
        .accessibilityValue(page == 1 ? "Cover, page 1 of \(total)" : "Page \(page) of \(total)" + (chapter(page).map { ", \($0.name)" } ?? ""))
        .accessibilityAdjustableAction { direction in
            switch direction { case .increment: step(1); case .decrement: step(-1); @unknown default: break }
        }
        .accessibilityAction(named: "Open page \(page)") { open(page) }
        .accessibilityIdentifier("pageBook")
    }

    private var edges: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let x = { (page: Int) in CGFloat(page - 1) / CGFloat(max(1, total - 1)) * width }
            ZStack(alignment: .topLeading) {
                Canvas { context, size in
                    let strip = Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 4)
                    context.fill(strip, with: .color(paper.pageColor))
                    let pitch = max(2.5, size.width / CGFloat(total))
                    var edge = CGFloat(0)
                    while edge < size.width {
                        context.fill(Path(CGRect(x: edge, y: 0, width: 1, height: size.height)), with: .color(paper.inkColor.opacity(0.14)))
                        edge += pitch
                    }
                    context.stroke(strip, with: .color(paper.inkColor.opacity(0.22)), lineWidth: 1)
                }
                .frame(height: 22).offset(y: stripTop)
                ForEach(chapters, id: \.start) { chapter in
                    Rectangle().fill(.secondary).frame(width: 1.5, height: 30).offset(x: x(chapter.start + 1) - 0.75, y: 12)
                }
                Ribbon().fill(.tint).frame(width: 8, height: 9).offset(x: x(here) - 4, y: 3)
                Capsule().fill(.tint).frame(width: 4, height: 34)
                    .background(Capsule().stroke(Color(uiColor: .systemBackground), lineWidth: 3))
                    .offset(x: x(page) - 2, y: 10)
                if scrub != nil {
                    Text(scrub?.fine == true ? "p. \(page) · fine" : "p. \(page)" + (chapter(page).map { " · \($0.name)" } ?? ""))
                        .font(.caption.weight(.semibold)).monospacedDigit().lineLimit(1)
                        .padding(.horizontal, 9).padding(.vertical, 5)
                        .background(Color.primary, in: RoundedRectangle(cornerRadius: 9))
                        .foregroundStyle(Color(uiColor: .systemBackground))
                        .frame(maxWidth: width * 0.8)
                        .fixedSize()
                        .position(x: min(max(x(page), width * 0.28), width * 0.72), y: -14)
                }
            }
            .contentShape(Rectangle())
            .gesture(scrubGesture(width: width))
        }
        .frame(height: 54)
        .padding(.horizontal, 20)
        .accessibilityHidden(true)
    }

    @ViewBuilder private func face(_ face: SpreadFace, size: CGSize) -> some View {
        switch face {
        case .none:
            Color.clear.frame(width: size.width, height: size.height)
        case .page(let number):
            PagePreview(number: number, chapter: chapter(number), opensChapter: PageFlipMath.opensChapter(number, in: chapters),
                        snippet: snippets.text[number], paper: paper, marked: number == here, size: size)
        case .cover:
            BookCoverFace(artwork: artwork, title: entry?.title ?? "", author: entry?.author ?? "", marked: here == 1, size: size)
        case .insideCover:
            InsideCover(paper: paper, size: size)
        }
    }

    /// Page edges stacked under one side of the book, thicker the more pages lie there.
    private func edgeLayers(count: Int, side: CGFloat, size: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach((1...max(count, 1)).reversed(), id: \.self) { layer in
                Rectangle().fill(paper.pageColor).overlay(paper.inkColor.opacity(layer % 2 == 0 ? 0.18 : 0.08))
                    .frame(width: size.width, height: size.height)
                    .offset(x: side * CGFloat(layer) * 1.2, y: CGFloat(layer) * 0.9)
                    .opacity(count == 0 ? 0 : 1)
            }
        }
    }
    private func layers(_ pages: Int) -> Int { Int((Double(max(0, pages)) / Double(total) * 7).rounded(.up)) }
    private var liftShadow: Double {
        guard let turning, turning.direction > 0 else { return 0 }
        return sin(turning.turned * .pi / 180) * 0.9
    }

    // MARK: Turning

    private func settle() { left = page - 1; right = page }
    private func setAngle(_ id: UUID, _ angle: Double) {
        if let index = leaves.firstIndex(where: { $0.id == id }) { leaves[index].angle = angle }
    }
    private func finish(_ id: UUID) {
        leaves.removeAll { $0.id == id }
        if leaves.isEmpty && turning == nil { settle() }
    }
    /// Starts a leaf at one side of the spine and lets it fall to the other.
    private func fly(_ leaf: Leaf, to angle: Double, duration: Double) {
        leaves.append(leaf)
        // On the next run loop, so the leaf is drawn where it starts before it moves.
        DispatchQueue.main.async {
            withAnimation(.timingCurve(0.3, 0.6, 0.25, 1, duration: duration)) { setAngle(leaf.id, angle) } completion: { finish(leaf.id) }
        }
    }

    /// Turns one page forward (1) or back (-1).
    @discardableResult private func turn(_ direction: Int, duration: Double = 0.3) -> Bool {
        let from = page, to = page + direction
        guard (1...total).contains(to) else { return false }
        if reduceMotion {
            withAnimation(.easeInOut(duration: 0.2)) { page = to; settle() }
        } else if direction > 0 {
            page = to
            left = to - 2; right = to
            fly(Leaf(page: from, angle: 0), to: -180, duration: duration)
        } else {
            page = to
            left = to - 1; right = from
            fly(Leaf(page: to, angle: -180), to: 0, duration: duration)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + (reduceMotion ? 0 : duration * 0.45)) { haptics.passed(crossingChapter: crosses(from, to)) }
        return true
    }
    @discardableResult private func step(_ direction: Int) -> Bool { turn(direction) }

    /// Turns a run of pages in quick succession after a hard flick.
    private func riffle(_ count: Int, _ direction: Int) {
        guard count > 0 else { return }
        if reduceMotion {
            let from = page; page = min(total, max(1, page + count * direction)); settle()
            haptics.landed(crossingChapter: crosses(from, page)); return
        }
        Task { @MainActor in
            for _ in 0..<count {
                guard turn(direction, duration: 0.24) else { break }
                try? await Task.sleep(for: .milliseconds(55))
            }
            try? await Task.sleep(for: .milliseconds(240))
            haptics.landed(crossingChapter: false)
        }
    }

    private func turnGesture(pageWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { value in
                let dx = value.translation.width
                if turning == nil {
                    let direction = dx < 0 ? 1 : -1
                    var id: UUID?
                    if !reduceMotion, direction > 0 ? page < total : page > 1 {
                        let leaf = Leaf(page: direction > 0 ? page : page - 1, angle: direction > 0 ? 0 : -180)
                        leaves.append(leaf); id = leaf.id
                        if direction > 0 { left = page - 1; right = page + 1 } else { left = page - 2; right = page }
                    }
                    turning = Turning(direction: direction, leaf: id)
                }
                guard var current = turning, let id = current.leaf else { return }
                current.turned = min(180, abs(dx) / pageWidth * 180 * 0.95)
                if (current.turned > 90) != current.pastHalf {
                    current.pastHalf = current.turned > 90
                    if current.pastHalf { haptics.threshold() }
                }
                turning = current
                setAngle(id, current.direction > 0 ? -current.turned : -180 + current.turned)
            }
            .onEnded { value in
                guard let current = turning else { return }
                turning = nil
                let velocity = value.velocity.width
                let extra = PageFlipMath.extraPages(velocity: velocity, forward: current.direction > 0)
                guard let id = current.leaf else {
                    // With Reduce Motion there is no page to hold: a swipe steps, and a flick jumps on as a riffle would.
                    if reduceMotion && abs(value.translation.width) > 30 && step(current.direction) { riffle(extra, current.direction) }
                    return
                }
                let commit = PageFlipMath.commits(turned: current.turned, velocity: velocity, forward: current.direction > 0)
                let from = page
                if commit { page += current.direction }
                withAnimation(.timingCurve(0.3, 0.6, 0.25, 1, duration: 0.22)) {
                    setAngle(id, commit == (current.direction > 0) ? -180 : 0)
                } completion: {
                    finish(id)
                    // A hard flick riffles on once the swiped page has landed.
                    if commit { riffle(extra, current.direction) }
                }
                if commit { haptics.landed(crossingChapter: crosses(from, page)) }
            }
    }

    // MARK: Scrubbing the page edges

    /// Where the page-edge strip starts inside the scrubber; fine scrubbing is measured from here.
    private let stripTop = 16.0

    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                var current = scrub ?? Scrub(anchorX: value.location.x, anchorPage: page)
                let fine = stripTop - value.location.y > PageFlipMath.fineLift
                if fine != current.fine { current.fine = fine; current.anchorX = value.location.x; current.anchorPage = page }
                scrub = current
                jump(to: fine
                     ? PageFlipMath.finePage(from: current.anchorPage, moved: value.location.x - current.anchorX, width: width, total: total)
                     : PageFlipMath.page(atFraction: value.location.x / width, total: total))
            }
            .onEnded { _ in scrub = nil; haptics.landed(crossingChapter: false) }
    }

    private func jump(to target: Int) {
        guard target != page else { return }
        let from = page; page = target; settle()
        haptics.passed(crossingChapter: crosses(from, target))
        // A quick page flutters over now and then, so scrubbing feels like riffling the edges.
        guard !reduceMotion, var current = scrub, Date().timeIntervalSince(current.lastLeaf) > 0.045 else { return }
        current.lastLeaf = Date(); scrub = current
        let forward = target > from
        fly(Leaf(page: forward ? target - 1 : target, angle: forward ? 0 : -180), to: forward ? -180 : 0, duration: 0.16)
    }

    private func open(_ number: Int) {
        haptics.opened()
        Task { await model.jump(number - 1); dismiss() }
    }

    private func flatten(_ links: [ReadiumShared.Link]) -> [(title: String?, href: String)] {
        links.flatMap { [(title: $0.title, href: $0.href)] + flatten($0.children) }
    }
}
