import SwiftUI
import CryptoKit
import UniformTypeIdentifiers

struct AnnotationRecord: Codable, Identifiable {
    var id: String
    var kind: String
    var locator: String
    var note: String
    var modified: Int64
    var revision: String
    var deleted: Bool
    func newer(than other: AnnotationRecord) -> Bool {
        if modified != other.modified { return modified > other.modified }
        if deleted != other.deleted { return deleted }
        return revision > other.revision
    }
}
struct AnnotationBundle: Codable { var version = 1; var document: String; var records: [AnnotationRecord] }

@MainActor struct NotesExchange {
    let library: Library
    let id: String
    var journalURL: URL { library.file("journal-\(id).json") }
    func journal() throws -> [AnnotationRecord] {
        guard FileManager.default.fileExists(atPath: journalURL.path) else { return [] }
        return try JSONDecoder().decode([AnnotationRecord].self, from: Data(contentsOf: journalURL))
    }
    func write(_ records: [AnnotationRecord]) throws { try JSONEncoder().encode(records).write(to: journalURL, options: .atomic) }
    func record(before: [SavedMark], after: [SavedMark], words: Bool = false) throws {
        var known = Dictionary(uniqueKeysWithValues: try journal().map { ($0.id, $0) })
        for mark in after + before.filter({ old in !after.contains { $0.id == old.id } }) {
            let deleted = !after.contains { $0.id == mark.id }
            let old = before.first { $0.id == mark.id }
            if deleted || old != mark || known[mark.id] == nil {
                known[mark.id] = AnnotationRecord(id: mark.id, kind: words ? "word" : mark.bookmark ? "bookmark" : "note", locator: mark.locator, note: mark.note,
                    modified: Int64(Date().timeIntervalSince1970 * 1000), revision: UUID().uuidString.lowercased(), deleted: deleted)
            }
        }
        try write(Array(known.values))
    }
    func fingerprint() throws -> String {
        guard let name = library.book(id)?.ebook else { throw PortalError.message("Download the book before syncing its notes.") }
        let handle = try FileHandle(forReadingFrom: library.file(name)); defer { try? handle.close() }
        var hash = SHA256()
        while let bytes = try handle.read(upToCount: 65536), !bytes.isEmpty { hash.update(data: bytes) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    func export(marks: [SavedMark], words: [SavedMark]) throws -> Data {
        var known = Dictionary(uniqueKeysWithValues: try journal().map { ($0.id, $0) })
        for (mark, kind) in marks.map({ ($0, $0.bookmark ? "bookmark" : "note") }) + words.map({ ($0, "word") }) where known[mark.id] == nil {
            known[mark.id] = AnnotationRecord(id: mark.id, kind: kind, locator: mark.locator, note: mark.note, modified: 0, revision: "", deleted: false)
        }
        try write(Array(known.values))
        return try JSONEncoder().encode(AnnotationBundle(document: fingerprint(), records: Array(known.values)))
    }
    func merge(_ data: Data, model: ReaderModel) throws {
        guard data.count <= 8_000_000 else { throw PortalError.message("Annotation file is too large.") }
        let incoming = try JSONDecoder().decode(AnnotationBundle.self, from: data)
        guard incoming.version == 1, incoming.document == (try fingerprint()), incoming.records.count <= 10000 else { throw PortalError.message("These notes belong to a different book file. Use the same EPUB on both devices.") }
        var known = Dictionary(uniqueKeysWithValues: try journal().map { ($0.id, $0) })
        for record in incoming.records {
            guard ["note", "bookmark", "word"].contains(record.kind), record.note.count <= 10000, record.id.count <= 100, record.modified >= 0,
                  SavedMark(locator: record.locator).location != nil else { throw PortalError.message("Invalid annotation file.") }
            if known[record.id] == nil || record.newer(than: known[record.id]!) { known[record.id] = record }
        }
        let live = known.values.filter { !$0.deleted }
        func mark(_ record: AnnotationRecord) -> SavedMark {
            var mark = SavedMark(id: record.id, locator: record.locator, note: record.note, bookmark: record.kind == "bookmark")
            mark.quote = mark.location?.text.highlight ?? ""; return mark
        }
        let marks = live.filter { $0.kind != "word" }.map(mark), words = live.filter { $0.kind == "word" }.map(mark)
        try write(Array(known.values))
        try JSONEncoder().encode(marks).write(to: library.file("marks-\(id).json"), options: .atomic)
        try JSONEncoder().encode(words).write(to: library.file("words-\(id).json"), options: .atomic)
        model.marks = marks; model.words = words; model.decorate()
    }
    func sync(model: ReaderModel) async throws {
        guard let data = SecureStore.read("notes-sync"), let account = try? JSONDecoder().decode(ServerAccount.self, from: data) else { throw PortalError.message("Configure Notes sync under Server first.") }
        let payload = try export(marks: model.marks, words: model.words)
        var request = URLRequest(url: account.url.appendingPathComponent(try fingerprint()))
        request.httpMethod = "POST"; request.httpBody = payload; request.timeoutInterval = 30
        request.setValue("Bearer " + account.password, forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (file, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: file) }
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw PortalError.message("Notes sync failed. Your local notes are safe.") }
        guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 8_000_000 else { throw PortalError.message("Annotation file is too large.") }
        try merge(Data(contentsOf: file), model: model)
    }
}

struct NotesDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data = Data()) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct NotesTransferView: View {
    @ObservedObject var model: ReaderModel
    @State private var busy = false
    @State private var message: String?
    @State private var exporting = false
    @State private var importing = false
    @State private var document = NotesDocument()
    @Environment(\.dismiss) var dismiss
    var exchange: NotesExchange? { model.library.map { NotesExchange(library: $0, id: model.id) } }
    var body: some View {
        NavigationStack {
            Form {
                Text("Includes this book’s highlights, notes, bookmarks and saved words. Import merges changes with your existing collection.")
                Button("Sync notes") { Task {
                    busy = true; defer { busy = false }
                    do { try await exchange?.sync(model: model); message = "Notes synced." } catch { message = error.localizedDescription }
                } }.disabled(busy)
                Button("Export notes") {
                    do { document = NotesDocument(data: try exchange?.export(marks: model.marks, words: model.words) ?? Data()); exporting = true }
                    catch { message = error.localizedDescription }
                }.disabled(busy)
                Button("Import notes") { importing = true }.disabled(busy)
                if busy { ProgressView("Syncing…") }
                if let message { Text(message).foregroundStyle(.secondary) }
            }.navigationTitle("Sync & export").toolbar { Button("Done") { dismiss() }.disabled(busy) }.interactiveDismissDisabled(busy)
                .fileExporter(isPresented: $exporting, document: document, contentType: .json, defaultFilename: "book-notes") { result in if case .failure(let error) = result { message = error.localizedDescription } }
                .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
                    do {
                        let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 8_000_000 else { throw PortalError.message("Annotation file is too large.") }
                        _ = try exchange?.export(marks: model.marks, words: model.words)
                        try exchange?.merge(Data(contentsOf: url), model: model); message = "Notes imported."
                    } catch { message = error.localizedDescription }
                }
        }
    }
}

struct NotesSyncSettings: View {
    @State private var url = ""
    @State private var token = ""
    @State private var message: String?
    var body: some View {
        DisclosureGroup("Notes sync") {
            Text("Your private annotation service connects highlights, notes, bookmarks and saved words across Android and iOS. Use identical book files.").font(.footnote)
            TextField("HTTPS sync address", text: $url).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL).textContentType(.URL)
            SecureField("Access token", text: $token).textContentType(.password)
            Button("Save connection") {
                do {
                    guard let address = URLComponents(string: url.trimmingCharacters(in: .whitespacesAndNewlines)), address.scheme == "https", address.host != nil, address.user == nil, address.password == nil, address.query == nil, address.fragment == nil, let endpoint = address.url, token.count >= 24 else { throw PortalError.message("Enter an HTTPS address and the full access token.") }
                    try SecureStore.write(JSONEncoder().encode(ServerAccount(url: endpoint, user: "", password: token.trimmingCharacters(in: .whitespacesAndNewlines))), name: "notes-sync")
                    token = ""; message = "Saved. Choose Sync & export in a book’s Notes tab."
                } catch { message = error.localizedDescription }
            }.disabled(token.isEmpty)
            Button("Disconnect") { do { try SecureStore.write(nil, name: "notes-sync"); message = "Disconnected. Your notes are kept." } catch { message = error.localizedDescription } }
            if let message { Text(message).font(.footnote) }
        }.onAppear { if let data = SecureStore.read("notes-sync"), let account = try? JSONDecoder().decode(ServerAccount.self, from: data) { url = account.url.absoluteString } }
    }
}
