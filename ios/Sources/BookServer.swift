import Foundation
import Security
import CryptoKit

struct ServerAccount: Codable {
    var url: URL
    var user: String
    var password: String
}

enum SecureStore {
    static func read(_ name: String) -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "MediaPortal", kSecAttrAccount as String: name, kSecReturnData as String: true]
        var result: CFTypeRef?
        return SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess ? result as? Data : nil
    }
    static func write(_ data: Data?, name: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "MediaPortal", kSecAttrAccount as String: name]
        guard let data else { let status = SecItemDelete(query as CFDictionary); guard status == errSecSuccess || status == errSecItemNotFound else { throw PortalError.message("Could not remove the saved account.") }; return }
        let values: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw PortalError.message("Could not securely save the account.") }
    }
}

final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct CatalogPage { var books: [MediaBook]; var next: URL?; var catalog: URL? }

final class FeedParser: NSObject, XMLParserDelegate {
    static func format(_ mime: String) -> String? {
        switch mime.components(separatedBy: ";")[0].trimmingCharacters(in: .whitespaces).lowercased() {
        case "application/epub+zip": return "epub"
        case "application/pdf": return "pdf"
        case "application/vnd.comicbook+zip", "application/x-cbz": return "cbz"
        default: return nil
        }
    }

    /// Audiobook editions a catalogue may offer. Only formats this device can actually play are
    /// listed: offering a download the player cannot open would be worse than not offering it.
    /// Opus is deliberately absent, as AVFoundation does not decode it.
    static func audioFormat(_ mime: String) -> String? {
        switch mime.components(separatedBy: ";")[0].trimmingCharacters(in: .whitespaces).lowercased() {
        case "audio/mpeg", "audio/mp3": return "mp3"
        case "audio/mp4", "audio/x-m4b", "audio/m4b": return "m4b"
        case "audio/x-m4a", "audio/m4a": return "m4a"
        case "audio/aac", "audio/aacp": return "aac"
        case "audio/flac", "audio/x-flac": return "flac"
        case "audio/wav", "audio/x-wav", "audio/wave": return "wav"
        case "audio/aiff", "audio/x-aiff": return "aiff"
        default: return nil
        }
    }

    /// Chaptered containers first, so a book that offers both arrives with its chapters intact.
    static let audioPreference = ["m4b", "m4a", "mp3", "aac", "flac", "wav", "aiff"]

    /// BookOrbit packs a series into a link title as "The Expanse #3", where BookLore uses two
    /// separate meta elements. Both shapes are OPDS 1.x; neither needs OPDS 2.0.
    static func series(fromLinkTitle title: String) -> (name: String, position: Double?)? {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        // A title that is only a number names no series: "#3" is a position, not a collection.
        guard let marker = trimmed.range(of: " #", options: .backwards) else {
            return trimmed.hasPrefix("#") ? nil : (trimmed, nil)
        }
        let name = trimmed[..<marker.lowerBound].trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return nil }
        return (name, Double(trimmed[marker.upperBound...].trimmingCharacters(in: .whitespaces)))
    }
    let base: URL
    var path: [String] = []
    var value = ""
    var entry: MediaBook?
    var entryID = ""
    var catalogLink: URL?
    var metaProperty = ""
    var page = CatalogPage(books: [], next: nil, catalog: nil)
    var failure: Error?
    init(base: URL) { self.base = base }
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes a: [String: String] = [:]) {
        path.append(elementName); value = ""
        if elementName == "entry" { entry = MediaBook(title: "Untitled"); entryID = ""; catalogLink = nil }
        if elementName == "meta" { metaProperty = a["property"] ?? "" }
        if elementName == "link", let href = a["href"] {
            do {
                let url = try BookServer.resolve(href, base: base)
                let rel = a["rel"] ?? ""
                if entry != nil {
                    if rel.hasPrefix("http://opds-spec.org/acquisition") {
                        let type = a["type"] ?? ""
                        if let format = Self.format(type) {
                            let order = ["epub", "pdf", "cbz"]
                            if entry?.download == nil || order.firstIndex(of: format)! < (order.firstIndex(of: entry?.downloadFormat ?? "epub") ?? 3) {
                                entry?.download = url; entry?.downloadFormat = format
                            }
                        } else if let audio = Self.audioFormat(type) {
                            let order = Self.audioPreference
                            let current = entry?.audioDownloadFormat.flatMap { order.firstIndex(of: $0) } ?? order.count
                            if entry?.audioDownload == nil || (order.firstIndex(of: audio) ?? order.count) < current {
                                entry?.audioDownload = url; entry?.audioDownloadFormat = audio
                            }
                        }
                    }
                    if rel == "http://opds-spec.org/sort/series", let parsed = Self.series(fromLinkTitle: a["title"] ?? "") {
                        entry?.seriesName = parsed.name
                        if let position = parsed.position { entry?.seriesPosition = position }
                    }
                    if rel == "http://opds-spec.org/image" || rel == "http://opds-spec.org/image/thumbnail" { entry?.remoteCover = url }
                    if rel == "subsection" { catalogLink = url }
                } else if rel == "next" { page.next = url }
            } catch { failure = error; parser.abortParsing() }
        }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { value += string }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName qName: String?) {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if entry != nil {
            if path.suffix(2) == ["entry", "title"] { entry?.title = text }
            if path.suffix(2) == ["entry", "id"] { entryID = text }
            if path.suffix(2) == ["author", "name"] { let author = entry?.author ?? ""; entry?.author = author + (author.isEmpty ? "" : ", ") + text }
            if path.suffix(2) == ["entry", "meta"] {
                if metaProperty == "belongs-to-collection", !text.isEmpty { entry?.seriesName = text }
                if metaProperty == "group-position", let position = Double(text) { entry?.seriesPosition = position }
            }
            if name == "entry", var book = entry {
                if entryID == "urn:booklore:catalog:all" || book.title.lowercased() == "all books" { page.catalog = catalogLink }
                // Keep a title offered only as an audiobook. Requiring an e-book edition here is
                // what made audiobooks on the server invisible. `download` stays the preferred
                // identity so existing books keep the id they were saved under.
                if let identity = book.download ?? book.audioDownload {
                    let origin = "\(base.scheme ?? "")://\(base.host ?? ""):\(base.port ?? 443)"
                    book.id = SHA256.hash(data: Data((origin + (entryID.isEmpty ? identity.absoluteString : entryID)).utf8)).map { String(format: "%02x", $0) }.joined()
                    page.books.append(book)
                }
                entry = nil
            }
        }
        _ = path.popLast(); value = ""
    }
    static func parse(_ data: Data, base: URL) throws -> CatalogPage {
        let source = String(decoding: data, as: UTF8.self).uppercased()
        guard !source.contains("<!DOCTYPE"), !source.contains("<!ENTITY") else { throw PortalError.message("Unsupported catalogue document.") }
        let delegate = FeedParser(base: base); let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true; parser.shouldResolveExternalEntities = false; parser.delegate = delegate
        guard parser.parse(), source.contains("<FEED") || source.contains(":FEED") else { throw delegate.failure ?? PortalError.message("This address did not return an OPDS catalogue.") }
        return delegate.page
    }
}

@MainActor final class BookServer: ObservableObject {
    @Published var account: ServerAccount?
    /// A pairing link waiting to be confirmed. Set by opening the link, cleared by the sheet.
    @Published var pendingPairing: PastedConnection?
    private let redirect = NoRedirect()
    private lazy var session = URLSession(configuration: .ephemeral, delegate: redirect, delegateQueue: nil)
    init() { account = SecureStore.read("booklore").flatMap { try? JSONDecoder().decode(ServerAccount.self, from: $0) } }
    nonisolated static func endpoint(_ input: String) throws -> URL {
        guard var c = URLComponents(string: input.trimmingCharacters(in: .whitespacesAndNewlines)), c.scheme == "https", c.host != nil, c.user == nil, c.password == nil, c.query == nil, c.fragment == nil else { throw PortalError.message("Enter an HTTPS BookLore address.") }
        if c.path.isEmpty || c.path == "/" { c.path = "/api/v1/opds" }
        guard let url = c.url else { throw PortalError.message("Invalid server address.") }; return url
    }
    nonisolated static func resolve(_ href: String, base: URL) throws -> URL {
        guard let url = URL(string: href, relativeTo: base)?.absoluteURL, url.scheme == "https", url.host?.lowercased() == base.host?.lowercased(), (url.port ?? 443) == (base.port ?? 443), url.user == nil, url.password == nil else { throw PortalError.message("The catalogue linked outside its secure server address.") }; return url
    }
    func fetch(_ url: URL, account: ServerAccount, limit: Int) async throws -> URL {
        var address = try Self.resolve(url.absoluteString, base: account.url)
        for _ in 0..<6 {
            var request = URLRequest(url: address); request.timeoutInterval = 30
            request.setValue("Basic " + Data("\(account.user):\(account.password)".utf8).base64EncodedString(), forHTTPHeaderField: "Authorization")
            let (file, response) = try await session.download(for: request)
            guard let http = response as? HTTPURLResponse else { throw PortalError.message("Invalid server response.") }
            if (300..<400).contains(http.statusCode), let location = http.value(forHTTPHeaderField: "Location") {
                try? FileManager.default.removeItem(at: file); address = try Self.resolve(location, base: address); continue
            }
            guard http.statusCode == 200 else { try? FileManager.default.removeItem(at: file); throw PortalError.message(http.statusCode == 401 || http.statusCode == 403 ? "Sign-in failed. Use your BookLore OPDS credentials." : "Server request failed (\(http.statusCode)).") }
            guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= limit else { try? FileManager.default.removeItem(at: file); throw PortalError.message("The server file is too large.") }
            return file
        }
        throw PortalError.message("The server redirected too many times.")
    }
    func refresh(_ proposed: ServerAccount, library: Library) async throws {
        var url: URL? = proposed.url; var seen = Set<URL>(); var books: [MediaBook] = []
        while let address = url {
            guard seen.insert(address).inserted, seen.count <= 200 else { throw PortalError.message("The catalogue contains a pagination loop or too many pages.") }
            let file = try await fetch(address, account: proposed, limit: 8_000_000)
            defer { try? FileManager.default.removeItem(at: file) }
            let page = try FeedParser.parse(Data(contentsOf: file), base: address)
            books += page.books; url = page.next ?? (page.books.isEmpty ? page.catalog : nil)
        }
        try library.merge(books)
        try SecureStore.write(JSONEncoder().encode(proposed), name: "booklore"); account = proposed
        for book in books where library.book(book.id)?.cover == nil {
            guard let cover = book.remoteCover, let temp = try? await fetch(cover, account: proposed, limit: 20_000_000) else { continue }
            if let data = try? Data(contentsOf: temp) { try? await library.setCover(data, id: book.id) }
            try? FileManager.default.removeItem(at: temp)
        }
    }
    func download(_ book: MediaBook, library: Library) async throws {
        guard let account, let url = book.download else { throw PortalError.message("Connect your library first.") }
        let format = book.downloadFormat ?? "epub"
        guard ["epub", "pdf", "cbz"].contains(format) else { throw PortalError.message("Unsupported e-book format.") }
        try await keep(url, as: format, audio: false, account: account, limit: 268_435_456, book: book, library: library)
    }

    /// Downloads the catalogue's audiobook edition and files it against the same title, so a book
    /// read and listened to stays one entry with two formats.
    func downloadAudio(_ book: MediaBook, library: Library) async throws {
        guard let account, let url = book.audioDownload else { throw PortalError.message("Connect your library first.") }
        let format = book.audioDownloadFormat ?? "mp3"
        guard FeedParser.audioPreference.contains(format) else { throw PortalError.message("Unsupported audiobook format.") }
        // An audiobook is an order of magnitude larger than an e-book; the e-book cap would reject
        // almost every real one.
        try await keep(url, as: format, audio: true, account: account, limit: 2_147_483_648, book: book, library: library)
    }

    private func keep(_ url: URL, as format: String, audio: Bool, account: ServerAccount, limit: Int, book: MediaBook, library: Library) async throws {
        let temp = try await fetch(url, account: account, limit: limit)
        let document = temp.appendingPathExtension(format)
        defer { try? FileManager.default.removeItem(at: temp); try? FileManager.default.removeItem(at: document) }
        try FileManager.default.moveItem(at: temp, to: document)
        _ = try await library.importFile(document, audio: audio, linking: book.id)
    }
    func disconnect() throws { try SecureStore.write(nil, name: "booklore"); account = nil }
}
