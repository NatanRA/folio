import Foundation
import SwiftUI
import AVFoundation
import ReadiumShared
import ReadiumStreamer
import PDFKit

struct MediaBook: Codable, Identifiable, Hashable {
    var id = UUID().uuidString
    var title: String
    var author = ""
    var ebook: String?
    var audio: String?
    var cover: String?
    var download: URL?
    var downloadFormat: String?
    /// The server's audiobook edition, kept apart from the e-book one so a title can offer both.
    /// Optional so a library saved before audiobooks were read from the server still decodes.
    var audioDownload: URL?
    var audioDownloadFormat: String?
    /// The series a catalogue says this book belongs to, which beats guessing from its title.
    /// Optional so a library saved before the feed was read for series still decodes.
    var seriesName: String?
    var seriesPosition: Double?
    var remoteCover: URL?
    var locator: String?
    var readingProgress = 0.0
    var audioPosition = 0.0
    /// Optional so a library saved before listening progress was tracked still decodes.
    var audioDuration: Double?
    /// When this device last wrote `audioPosition`, so a position arriving from another device only
    /// wins when it is genuinely newer. `lastOpenedAt` cannot answer that: opening the e-book of the
    /// same title bumps it too, which would let a stale audio position beat a fresher one.
    /// Optional so a library saved before positions were synced still decodes.
    var audioModifiedAt: Date?
    /// When this title was last read or listened to, so Home can offer the most recent one.
    /// Also optional: a library saved before this was tracked simply has no stamp yet.
    var lastOpenedAt: Date?
    var finished = false
    var fixedPage: Int?
    var telegramSource: String?
    var addedAt: Date?
    /// How far through this book the reader is, whether they were reading it or listening to it.
    /// `readingProgress` covers only the e-book, so on its own it hides every audiobook from Home.
    /// A book that has been listened to but whose length is not yet known counts as barely started.
    var progress: Double {
        guard audioPosition > 0 else { return readingProgress }
        guard let length = audioDuration, length > 0 else { return max(readingProgress, 0.01) }
        return max(readingProgress, min(audioPosition / length, 1))
    }
    /// True when the reader has only ever listened to this book, so Home can say so.
    var listening: Bool { audioPosition > 0 && readingProgress <= 0 }
    var streaming: Bool { audio?.hasPrefix("telegram://") == true }
    var isLocal: Bool { ebook != nil || (audio != nil && !streaming) }
}

enum PortalError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

/// Runs `body` on a file chosen in Files once its provider has made it readable. A file kept in iCloud Drive or another
/// provider can be a placeholder until it is read through a file coordinator, which downloads it first; read directly,
/// such a file can come back empty, the likely reason a picture picked on a phone changed nothing.
func readPickedFile<T>(_ url: URL, _ body: @escaping @Sendable (URL) throws -> T) async throws -> T {
    try await Task.detached(priority: .userInitiated) {
        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        var coordination: NSError?, result: Result<T, Error>?
        NSFileCoordinator().coordinate(readingItemAt: url, options: .withoutChanges, error: &coordination) { local in result = Result { try body(local) } }
        if let coordination { throw coordination }
        guard let result else { throw PortalError.message("The file could not be read.") }
        return try result.get()
    }.value
}

@MainActor final class BookEngine {
    let http = DefaultHTTPClient()
    lazy var assets = AssetRetriever(httpClient: http)
    lazy var opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: http, assetRetriever: assets, pdfFactory: DefaultPDFDocumentFactory()))
    func open(_ url: URL) async throws -> Publication {
        guard let file = FileURL(url: url) else { throw PortalError.message("This book file could not be opened.") }
        let asset = try await assets.retrieve(url: file).get()
        let book = try await opener.open(asset: asset, allowUserInteraction: false).get()
        guard !book.isRestricted else { throw PortalError.message("This book is protected. Import an unprotected EPUB.") }
        return book
    }
}

@MainActor final class Library: ObservableObject {
    @Published var books: [MediaBook] = []
    @Published var error: String?
    @Published var busy = false
    let root: URL
    let engine = BookEngine()
    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Library", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
            let index = self.root.appendingPathComponent("library.json")
            if FileManager.default.fileExists(atPath: index.path) { books = try JSONDecoder().decode([MediaBook].self, from: Data(contentsOf: index)) }
            backfillAddedDates()
        } catch { self.error = "Could not load the saved library: \(error.localizedDescription)" }
    }
    func file(_ name: String) -> URL { root.appendingPathComponent(name) }
    /// Books saved before timestamps existed take the date their file landed on this device.
    private func backfillAddedDates() {
        for i in books.indices where books[i].addedAt == nil {
            books[i].addedAt = [books[i].ebook, books[i].audio].compactMap { $0 }.compactMap { name -> Date? in
                let values = try? file(name).resourceValues(forKeys: [.creationDateKey, .contentModificationDateKey])
                return values?.creationDate ?? values?.contentModificationDate
            }.min()
        }
    }
    func book(_ id: String) -> MediaBook? { books.first { $0.id == id } }
    func save() throws { try JSONEncoder().encode(books).write(to: file("library.json"), options: .atomic) }
    func update(_ id: String, _ change: (inout MediaBook) -> Void) {
        guard let i = books.firstIndex(where: { $0.id == id }) else { return }
        change(&books[i])
        do { try save() } catch { self.error = "Your position could not be saved. \(error.localizedDescription)" }
    }
    func importFile(_ url: URL, audio: Bool, linking id: String? = nil, telegramSource: String? = nil) async throws -> String {
        if let telegramSource, let existing = books.first(where: { $0.telegramSource == telegramSource && (audio ? $0.audio != nil && !$0.streaming : $0.ebook != nil) }) { return existing.id }
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let ext = url.pathExtension.lowercased()
        guard audio ? ["mp3", "m4a", "m4b", "aac", "wav", "flac", "aiff"].contains(ext) : ["epub", "pdf", "cbz"].contains(ext) else {
            throw PortalError.message(audio ? "Choose an audiobook audio file." : "Choose an unprotected EPUB, PDF or CBZ e-book.")
        }
        var entry = id.flatMap(book) ?? MediaBook(title: url.deletingPathExtension().lastPathComponent)
        entry.telegramSource = telegramSource ?? entry.telegramSource
        let name = UUID().uuidString + "." + ext
        let destination = file(name)
        try await readPickedFile(url) { try FileManager.default.copyItem(at: $0, to: destination) }
        var artwork: Data?
        do {
            if audio {
                let asset = AVURLAsset(url: destination)
                guard try await asset.load(.isPlayable) else { throw PortalError.message("This audio format cannot be played on this device.") }
                let metadata = try await asset.load(.commonMetadata)
                if id == nil, let title = AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierTitle).first,
                   let value = try await title.load(.stringValue), !value.isEmpty { entry.title = value }
                if let artist = AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierArtist).first {
                    entry.author = (try? await artist.load(.stringValue)) ?? entry.author
                }
                if let image = AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierArtwork).first { artwork = try? await image.load(.dataValue) }
                entry.audio = name
                if id == nil { entry.audioPosition = 0 }
            } else if ext == "pdf" || ext == "cbz" {
                if ext == "pdf" {
                    guard let pdf = PDFDocument(url: destination), !pdf.isLocked, pdf.pageCount > 0 else { throw PortalError.message("This PDF is protected or damaged.") }
                    if id == nil { entry.title = pdf.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String ?? entry.title }
                    artwork = pdf.page(at: 0)?.thumbnail(of: CGSize(width: 600, height: 900), for: .mediaBox).jpegData(compressionQuality: 0.85)
                } else { artwork = try await ComicPages(url: destination).image(0).jpegData(compressionQuality: 0.85) }
                entry.ebook = name; entry.locator = nil; entry.fixedPage = 0; entry.readingProgress = 0; entry.finished = false
            } else {
                let publication = try await engine.open(destination)
                if id == nil { entry.title = publication.metadata.title ?? entry.title; entry.author = publication.metadata.authors.map(\.name).joined(separator: ", ") }
                artwork = (try? await publication.cover().get())?.jpegData(compressionQuality: 0.85)
                entry.ebook = name; entry.locator = nil; entry.readingProgress = 0; entry.finished = false
            }
            if entry.cover == nil, let artwork, let data = await Task.detached(priority: .userInitiated, operation: { CoverImages.storedJPEG(artwork) }).value {
                let cover = UUID().uuidString + ".jpg"; try data.write(to: file(cover), options: .atomic); entry.cover = cover
            }
            let old = books
            if entry.addedAt == nil { entry.addedAt = Date() }
            if let i = books.firstIndex(where: { $0.id == entry.id }) {
                // Importing took a while. What changed on the book meanwhile, a cover picked or a position saved, stays;
                // only what this import brings is added.
                var current = books[i]
                if audio { current.audio = entry.audio; current.author = entry.author }
                else { current.ebook = entry.ebook; current.locator = entry.locator; current.fixedPage = entry.fixedPage; current.readingProgress = entry.readingProgress; current.finished = entry.finished }
                current.telegramSource = entry.telegramSource
                if current.cover == nil { current.cover = entry.cover }
                else if let made = entry.cover, made != current.cover, !books.contains(where: { $0.cover == made }) { try? FileManager.default.removeItem(at: file(made)) }
                books[i] = current
            } else { books.append(entry) }
            do { try save() } catch { books = old; throw error }
            return entry.id
        } catch { try? FileManager.default.removeItem(at: destination); throw error }
    }
    /// Replaces a book's cover with a picture chosen in Files.
    func replaceCover(_ url: URL, id: String) async throws {
        let data = try await readPickedFile(url) { local in
            guard (try local.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 20_000_000 else { throw PortalError.message("Choose a picture under 20 MB.") }
            return try Data(contentsOf: local)
        }
        try await setCover(data, id: id)
    }
    /// Stores image bytes as the book's cover and removes the file it replaces. The picture is scaled and re-encoded off
    /// the main thread, and every cover gets a new file name, so nothing drawn from the old one can go stale.
    func setCover(_ data: Data, id: String) async throws {
        guard let jpeg = await Task.detached(priority: .userInitiated, operation: { CoverImages.storedJPEG(data) }).value else {
            throw PortalError.message("This picture couldn't be opened. Choose a JPG, PNG or HEIC image.")
        }
        let name = UUID().uuidString + ".jpg"
        try jpeg.write(to: file(name), options: .atomic)
        let old = book(id)?.cover
        update(id) { $0.cover = name }
        if let old, !books.contains(where: { $0.cover == old }) { try? FileManager.default.removeItem(at: file(old)) }
    }
    func merge(_ remote: [MediaBook]) throws {
        let old = books
        for item in remote {
            if let i = books.firstIndex(where: { $0.id == item.id }) {
                books[i].download = item.download; books[i].downloadFormat = item.downloadFormat; books[i].remoteCover = item.remoteCover
                books[i].audioDownload = item.audioDownload; books[i].audioDownloadFormat = item.audioDownloadFormat
                books[i].seriesName = item.seriesName; books[i].seriesPosition = item.seriesPosition
            } else { var added = item; added.addedAt = item.addedAt ?? Date(); books.append(added) }
        }
        do { try save() } catch { books = old; throw error }
    }
}
