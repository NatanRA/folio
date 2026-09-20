import SwiftUI
import AVFoundation

struct FreeTrack: Identifiable { var id: String { url.absoluteString }; var name: String; var url: URL }
struct FreeBook: Identifiable { var id: String; var title: String; var author: String; var audio: Bool; var download: URL?; var tracks: [FreeTrack] }
struct LocalAudioTrack: Codable { var file: String; var name: String; var durationMs: Int64 }
struct LocalAudioBundle: Codable { var tracks: [LocalAudioTrack] }

enum PublicCatalog {
    static func address(_ url: URL) throws -> URL {
        let host = url.host?.lowercased() ?? ""
        guard url.scheme == "https", url.user == nil, url.password == nil,
              host == "gutendex.com" || host == "librivox.org" || host == "gutenberg.org" || host.hasSuffix(".gutenberg.org") || host == "archive.org" || host.hasSuffix(".archive.org") else { throw PortalError.message("Unsupported catalogue download address.") }
        return url
    }
    static func fetch(_ url: URL, limit: Int = 8_000_000) async throws -> URL {
        var address = try address(url)
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        for _ in 0..<6 {
            try Task.checkCancellation()
            var request = URLRequest(url: address); request.timeoutInterval = 60
            let (file, response) = try await session.download(for: request)
            guard let http = response as? HTTPURLResponse else { try? FileManager.default.removeItem(at: file); throw PortalError.message("Invalid catalogue response.") }
            if (300..<400).contains(http.statusCode), let location = http.value(forHTTPHeaderField: "Location"), let next = URL(string: location, relativeTo: address)?.absoluteURL {
                try? FileManager.default.removeItem(at: file); address = try Self.address(next); continue
            }
            guard http.statusCode == 200, (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= limit else {
                try? FileManager.default.removeItem(at: file); throw PortalError.message("The catalogue download failed or exceeded its size limit. Try again later.")
            }
            return file
        }
        throw PortalError.message("Too many download redirects.")
    }
    static func search(audio: Bool, query: String, page: Int) async throws -> [FreeBook] {
        var url = URLComponents(string: audio ? "https://librivox.org/api/feed/audiobooks/" : "https://gutendex.com/books/")!
        url.queryItems = audio ? [.init(name: "format", value: "json"), .init(name: "extended", value: "1"), .init(name: "limit", value: "20"), .init(name: "offset", value: String((page - 1) * 20))] : [.init(name: "copyright", value: "false"), .init(name: "mime_type", value: "application/epub+zip"), .init(name: "search", value: query), .init(name: "page", value: String(page))]
        if audio && !query.isEmpty { url.queryItems?.append(.init(name: "title", value: "^" + query)) }
        // These catalogues parse queries as form data, where a literal + means a space.
        url.percentEncodedQuery = url.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        let file = try await fetch(url.url!); defer { try? FileManager.default.removeItem(at: file) }
        return try parse(Data(contentsOf: file), audio: audio)
    }
    static func parse(_ data: Data, audio: Bool) throws -> [FreeBook] {
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let books = root?[audio ? "books" : "results"] as? [[String: Any]] ?? []
        return books.compactMap { book in
            guard let id = book["id"], let title = book["title"] as? String else { return nil }
            let authors = (book["authors"] as? [[String: Any]] ?? []).map { a in audio ? "\(a["first_name"] as? String ?? "") \(a["last_name"] as? String ?? "")".trimmingCharacters(in: .whitespaces) : a["name"] as? String ?? "" }.joined(separator: ", ")
            let download = (book["formats"] as? [String: String])?["application/epub+zip"].flatMap(URL.init(string:))
            let tracks = (book["sections"] as? [[String: Any]] ?? []).sorted { Int($0["section_number"] as? String ?? "0") ?? 0 < Int($1["section_number"] as? String ?? "0") ?? 0 }.compactMap { track -> FreeTrack? in
                guard let url = (track["listen_url"] as? String).flatMap(URL.init(string:)) else { return nil }
                return FreeTrack(name: track["title"] as? String ?? "Chapter", url: url)
            }
            guard audio ? !tracks.isEmpty : download != nil else { return nil }
            return FreeBook(id: (audio ? "lv-" : "pg-") + String(describing: id), title: title, author: authors, audio: audio, download: download, tracks: tracks)
        }
    }
    @MainActor static func add(_ book: FreeBook, library: Library, progress: (String) -> Void) async throws {
        var created: [URL] = []
        defer { created.forEach { try? FileManager.default.removeItem(at: $0) } }
        if !book.audio, let url = book.download {
            let temp = try await fetch(url, limit: 268_435_456); created.append(temp)
            let epub = temp.appendingPathExtension("epub"); try FileManager.default.moveItem(at: temp, to: epub); created.append(epub)
            _ = try await library.importFile(epub, audio: false)
        } else {
            guard (1...1000).contains(book.tracks.count) else { throw PortalError.message("This audiobook has too many chapters.") }
            var tracks: [LocalAudioTrack] = []; var totalBytes = 0
            for (index, track) in book.tracks.enumerated() {
                try Task.checkCancellation(); progress("Downloading chapter \(index + 1) of \(book.tracks.count)")
                let temp = try await fetch(track.url, limit: min(268_435_456, 2_147_483_648 - totalBytes)); created.append(temp)
                totalBytes += (try temp.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
                let name = UUID().uuidString + ".mp3", target = library.file(name); created.append(target)
                try FileManager.default.moveItem(at: temp, to: target)
                let asset = AVURLAsset(url: target); let duration = try await asset.load(.duration).seconds
                guard duration.isFinite, duration > 0, try await asset.load(.isPlayable) else { throw PortalError.message("One of the audiobook chapters cannot be played.") }
                tracks.append(LocalAudioTrack(file: name, name: track.name, durationMs: Int64(duration * 1000)))
            }
            let name = UUID().uuidString + ".audiobook.json", file = library.file(name); created.append(file)
            try JSONEncoder().encode(LocalAudioBundle(tracks: tracks)).write(to: file, options: .atomic)
            let old = library.books
            library.books.append(MediaBook(title: book.title, author: book.author, audio: name, addedAt: Date()))
            do { try library.save() } catch { library.books = old; throw error }
            created = []
        }
    }
}

struct DiscoveryView: View {
    @EnvironmentObject var library: Library
    @State private var audio = false
    var query: String
    @State private var submitted = ""
    @State private var page = 1
    @State private var books: [FreeBook] = []
    @State private var loading = false
    @State private var message: String?
    @State private var retry = 0
    @State private var status: String?
    @State private var download: Task<Void, Never>?
    @State private var added = Set<String>()
    var body: some View {
        Group {
            VStack {
                Picker("Format", selection: $audio) { Text("E-books").tag(false); Text("Audiobooks").tag(true) }.pickerStyle(.segmented).padding(.horizontal).disabled(download != nil)
                Text(audio ? "LibriVox · volunteer-read classics" : "Free e-books from Project Gutenberg").font(.caption).foregroundStyle(.secondary)
                if audio { Text("Audiobook titles starting with your search").font(.caption).foregroundStyle(.secondary) }
                if loading { ProgressView("Loading catalogue…") }
                if let message { Text(message).font(.footnote).padding(.horizontal); if !loading && download == nil && books.isEmpty { Button("Retry") { retry += 1 }.frame(minHeight: 44) } }
                if let status { HStack { ProgressView(); Text(status); Button("Cancel") { download?.cancel() } }.font(.footnote).padding() }
                List {
                    if !loading && books.isEmpty && message == nil { Text("No matching titles.").listRowBackground(Color.clear) }
                    ForEach(books) { book in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(book.title).font(.headline); Text(book.author).font(.subheadline).foregroundStyle(.secondary)
                            Button(added.contains(book.id) ? "Added" : "Download") {
                                download = Task {
                                    status = "Downloading…"; message = nil
                                    defer { status = nil; download = nil }
                                    do { try await PublicCatalog.add(book, library: library) { status = $0 }; added.insert(book.id); message = "Added to your library." }
                                    catch is CancellationError { }
                                    catch { if !Task.isCancelled { message = error.localizedDescription } }
                                }
                            }.disabled(download != nil || added.contains(book.id)).buttonStyle(.bordered).frame(minHeight: 44)
                        }.padding(.vertical, 8).listRowBackground(Color.clear)
                    }
                    HStack {
                        Button("Previous") { page -= 1 }.disabled(page == 1 || loading || download != nil)
                        Spacer(); Text("Page \(page)").font(.caption); Spacer()
                        Button("Next") { page += 1 }.disabled(books.isEmpty || loading || download != nil)
                    }.buttonStyle(.borderless).listRowBackground(Color.clear)
                    Text("Keep this screen open while downloading. Availability varies by country.").font(.footnote).foregroundStyle(.secondary).listRowBackground(Color.clear)
                }.scrollContentBackground(.hidden)
            }.onChange(of: audio) { _, _ in page = 1 }
                .task(id: query) {
                    do { try await Task.sleep(for: .milliseconds(350)); submitted = query.trimmingCharacters(in: .whitespaces); page = 1 } catch { }
                }
                .task(id: "\(audio):\(submitted):\(page):\(retry)") {
                    books = []; loading = true; message = nil
                    do { let found = try await PublicCatalog.search(audio: audio, query: submitted, page: page); try Task.checkCancellation(); books = found }
                    catch is CancellationError { }
                    catch { if !Task.isCancelled { message = "Can’t reach the free book catalogue. Check your connection and try again." } }
                    if !Task.isCancelled { loading = false }
                }.onDisappear { download?.cancel() }
        }
    }
}
