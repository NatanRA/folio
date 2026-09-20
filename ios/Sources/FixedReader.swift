import SwiftUI
import PDFKit
import ReadiumZIPFoundation
import ImageIO

final class ComicPages {
    let archive: Archive
    let entries: [Entry]
    init(url: URL) async throws {
        archive = try await Archive(url: url, accessMode: .read)
        entries = try await archive.entries().filter { $0.type == .file && ["jpg", "jpeg", "png", "webp"].contains(URL(fileURLWithPath: $0.path).pathExtension.lowercased()) && !$0.path.hasPrefix("__MACOSX/") }.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
        guard (1...5000).contains(entries.count) else { throw PortalError.message("This CBZ has no supported pages or is too large.") }
    }
    func image(_ index: Int) async throws -> UIImage {
        guard entries.indices.contains(index), entries[index].uncompressedSize <= 50_000_000 else { throw PortalError.message("Comic page is too large.") }
        var bytes = Data()
        _ = try await archive.extract(entries[index]) { chunk in
            try Task.checkCancellation()
            guard bytes.count + chunk.count <= 50_000_000 else { throw PortalError.message("Comic page is too large.") }
            bytes.append(chunk)
        }
        guard let source = CGImageSourceCreateWithData(bytes as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 2200, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { throw PortalError.message("This comic page cannot be decoded.") }
        return UIImage(cgImage: image)
    }
}

struct NativePDF: UIViewRepresentable {
    let document: PDFDocument
    @Binding var page: Int
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView(); view.document = document; view.autoScales = true
        view.displayDirection = .horizontal; view.displayMode = .singlePage; view.usePageViewController(true)
        context.coordinator.observer = NotificationCenter.default.addObserver(forName: .PDFViewPageChanged, object: view, queue: .main) { [weak view, weak coordinator = context.coordinator] _ in
            guard let view, let current = view.currentPage, let coordinator else { return }
            coordinator.parent.page = document.index(for: current)
        }
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) {
        context.coordinator.parent = self
        if let target = document.page(at: page), view.currentPage != target { view.go(to: target) }
    }
    final class Coordinator {
        var parent: NativePDF; var observer: NSObjectProtocol?
        init(_ parent: NativePDF) { self.parent = parent }
        deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
    }
}

struct ZoomableComic: UIViewRepresentable {
    let image: UIImage
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView(); scroll.minimumZoomScale = 1; scroll.maximumZoomScale = 4; scroll.delegate = context.coordinator
        scroll.showsHorizontalScrollIndicator = false; scroll.showsVerticalScrollIndicator = false
        let view = context.coordinator.image; view.contentMode = .scaleAspectFit; view.image = image
        scroll.addSubview(view); view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([view.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor), view.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor), view.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor), view.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor), view.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor), view.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)])
        return scroll
    }
    func updateUIView(_ view: UIScrollView, context: Context) { if context.coordinator.image.image !== image { view.setZoomScale(1, animated: false); context.coordinator.image.image = image } }
    final class Coordinator: NSObject, UIScrollViewDelegate {
        let image = UIImageView()
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { image }
    }
}

struct ComicPageView: View {
    let source: ComicPages
    let index: Int
    @State private var image: UIImage?
    @State private var error: String?
    var body: some View {
        Group { if let image { ZoomableComic(image: image).accessibilityLabel("Page \(index + 1)") } else if let error { Text(error).padding() } else { ProgressView() } }
            .task(id: index) { do { let loaded = try await source.image(index); try Task.checkCancellation(); image = loaded } catch is CancellationError { } catch { self.error = error.localizedDescription } }
    }
}

struct FixedReaderScreen: View {
    let id: String
    @EnvironmentObject var library: Library
    @Environment(\.dismiss) var dismiss
    @State private var pdf: PDFDocument?
    @State private var comic: ComicPages?
    @State private var page = 0
    @State private var error: String?
    @State private var picker = false
    var count: Int { pdf?.pageCount ?? comic?.entries.count ?? 0 }
    var body: some View {
        NavigationStack {
            Group {
                if let pdf { NativePDF(document: pdf, page: $page) }
                else if let comic { TabView(selection: $page) { ForEach(comic.entries.indices, id: \.self) { index in
                    Group { if abs(page - index) <= 1 { ComicPageView(source: comic, index: index) } else { Color.clear } }.tag(index)
                } }.tabViewStyle(.page(indexDisplayMode: .never)) }
                else if let error { ContentUnavailableView("Couldn’t open book", systemImage: "book.closed", description: Text(error)) }
                else { ProgressView("Opening book…") }
            }.navigationTitle(library.book(id)?.title ?? "Book").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("\(page + 1) / \(max(1, count))") { picker = true }.disabled(count == 0) } }
                .safeAreaInset(edge: .bottom) {
                    HStack(spacing: 12) {
                        Button { dismiss() } label: { Image(systemName: "house").frame(width: 48, height: 48) }.accessibilityLabel("Home")
                        Button { withAnimation { page -= 1 } } label: { Image(systemName: "arrow.left").frame(width: 48, height: 48) }.disabled(page == 0).accessibilityLabel("Previous page")
                        Button { withAnimation { page += 1 } } label: { Image(systemName: "arrow.right").frame(width: 48, height: 48) }.disabled(page + 1 >= count).accessibilityLabel("Next page")
                        if count > 0 && page == count - 1 { Button(library.book(id)?.finished == true ? "Finished · Undo" : "Finish") { library.update(id) { $0.finished.toggle() } } }
                    }.padding(8).background(.regularMaterial, in: Capsule()).padding(8)
                }
                .task {
                    do {
                        guard let book = library.book(id), let name = book.ebook else { throw PortalError.message("Download the book first.") }
                        if name.lowercased().hasSuffix(".pdf") {
                            guard let document = PDFDocument(url: library.file(name)), !document.isLocked, document.pageCount > 0 else { throw PortalError.message("This PDF is protected or damaged.") }
                            page = min(max(0, book.fixedPage ?? 0), document.pageCount - 1); pdf = document
                        } else { let source = try await ComicPages(url: library.file(name)); page = min(max(0, book.fixedPage ?? 0), source.entries.count - 1); comic = source }
                    } catch { self.error = error.localizedDescription }
                }
                .onChange(of: page) { _, value in if count > 0 { library.update(id) { $0.fixedPage = value; $0.readingProgress = count > 1 ? Double(value) / Double(count - 1) : 1; $0.lastOpenedAt = Date() } } }
                .sheet(isPresented: $picker) { NavigationStack {
                    ScrollView { LazyVGrid(columns: [GridItem(.adaptive(minimum: 72))]) { ForEach(0..<count, id: \.self) { index in Button("\(index + 1)") { page = index; picker = false }.frame(minWidth: 56, minHeight: 56).buttonStyle(.bordered) } }.padding() }
                        .navigationTitle("Pages").toolbar { Button("Done") { picker = false } }
                } }
        }
    }
}
