import SwiftUI
import CryptoKit
import ReadiumShared

struct KoAccount: Codable { var url: URL; var user: String; var key: String }
struct KoPosition: Codable, Equatable { var progress: String; var percentage: Double; var device: String? }

enum KoProtocol {
    static func md5(_ data: Data) -> String { Insecure.MD5.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    static func document(_ file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file); defer { try? handle.close() }
        let size = try handle.seekToEnd(); var digest = Insecure.MD5()
        for offset in [UInt64(0)] + (0...10).map({ UInt64(1024) << (2 * $0) }) {
            if offset >= size { break }
            try handle.seek(toOffset: offset)
            digest.update(data: try handle.read(upToCount: 1024) ?? Data())
        }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
    static func endpoint(_ value: String) throws -> URL {
        guard let c = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)), c.scheme == "https", c.host != nil, c.user == nil, c.password == nil, c.query == nil, c.fragment == nil,
              !c.path.split(separator: "/").contains(".."), let url = c.url else { throw PortalError.message("Enter an HTTPS sync server address.") }; return url
    }
}

@MainActor final class KoSync: ObservableObject {
    @Published var account: KoAccount?
    private let redirects = NoRedirect()
    private lazy var session = URLSession(configuration: .ephemeral, delegate: redirects, delegateQueue: nil)
    init() { account = SecureStore.read("kosync").flatMap { try? JSONDecoder().decode(KoAccount.self, from: $0) } }
    func request(_ account: KoAccount, path: String, body: Data? = nil) async throws -> Data? {
        guard !account.user.isEmpty, !account.user.contains("\n"), !account.user.contains("\r") else { throw PortalError.message("Enter a valid sync username.") }
        let base = try KoProtocol.endpoint(account.url.absoluteString)
        let url = base.appendingPathComponent(path)
        var request = URLRequest(url: url); request.timeoutInterval = 15
        request.setValue(account.user, forHTTPHeaderField: "x-auth-user"); request.setValue(account.key, forHTTPHeaderField: "x-auth-key")
        request.setValue("application/vnd.koreader.v1+json", forHTTPHeaderField: "Accept")
        if let body { request.httpMethod = "PUT"; request.httpBody = body; request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (file, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: file) }
        guard let http = response as? HTTPURLResponse else { throw PortalError.message("Invalid sync response.") }
        if http.statusCode == 404 && path.hasPrefix("syncs/progress/") { return nil }
        guard (200..<300).contains(http.statusCode), (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 65536 else { throw PortalError.message("Sync request failed (\(http.statusCode)). Check your KOReader account and server address.") }
        return try Data(contentsOf: file)
    }
    func login(url: String, user: String, password: String) async throws {
        let account = try KoAccount(url: KoProtocol.endpoint(url), user: user, key: KoProtocol.md5(Data(password.utf8)))
        _ = try await request(account, path: "users/auth")
        try SecureStore.write(JSONEncoder().encode(account), name: "kosync"); self.account = account
    }
    func disconnect() throws { try SecureStore.write(nil, name: "kosync"); account = nil }
    func pull(document: String) async throws -> KoPosition? {
        guard let account else { throw PortalError.message("Connect KOReader under Server first.") }
        guard let data = try await request(account, path: "syncs/progress/" + document) else { return nil }
        let result = try JSONDecoder().decode(KoPosition.self, from: data)
        guard result.percentage.isFinite, (0...1).contains(result.percentage), !result.progress.isEmpty, result.progress.count <= 16384 else { throw PortalError.message("The sync server returned an invalid position.") }
        return result
    }
    func push(document: String, percentage: Double) async throws {
        guard let account, percentage.isFinite, (0...1).contains(percentage) else { throw PortalError.message("Connect KOReader under Server first.") }
        let defaults = UserDefaults.standard
        let device = defaults.string(forKey: "koDevice") ?? UUID().uuidString
        defaults.set(device, forKey: "koDevice")
        let body = try JSONSerialization.data(withJSONObject: ["document": document, "progress": "nmp-percent-v1:\(percentage)", "percentage": percentage, "device": "Folio iOS", "device_id": device])
        _ = try await request(account, path: "syncs/progress", body: body)
    }
}

struct KoSettingsView: View {
    @EnvironmentObject var sync: KoSync
    @EnvironmentObject var library: Library
    @State var expanded = false
    @State var url = "https://sync.koreader.rocks"
    @State var user = ""
    @State var password = ""
    @State var busy = false
    @AppStorage("koBridge") var bridge = false
    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $expanded) {
                TextField("Sync server", text: $url).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL).textContentType(.URL)
                TextField("Sync username", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
                SecureField("Sync password", text: $password).textContentType(.password)
                Button("Connect progress sync") { Task {
                    busy = true; defer { busy = false }
                    do { try await sync.login(url: url, user: user, password: password); password = ""; bridge = false }
                    catch { library.error = error.localizedDescription }
                } }.disabled(busy || user.isEmpty || password.isEmpty)
                if busy { ProgressView() }
                if sync.account != nil {
                    Toggle("Companion plugin installed in KOReader", isOn: $bridge)
                    Button("Disconnect", role: .destructive) { do { try sync.disconnect(); bridge = false } catch { library.error = error.localizedDescription } }
                }
            } label: { Label { VStack(alignment: .leading, spacing: 4) { Text("KOReader sync").font(.headline); Text(sync.account == nil ? "Not connected" : "Connected").font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: "arrow.triangle.2.circlepath") } }
        } footer: { Text("Manual, approximate reading-position sync. Use the same EPUB and account on each reader. Sending to KOReader needs the companion plugin from the repository.") }
        .onAppear { if let account = sync.account { url = account.url.absoluteString; user = account.user } }
        .animation(.easeInOut(duration: 0.25), value: expanded)
    }
}

struct ReaderSyncSheet: View {
    @ObservedObject var model: ReaderModel
    @EnvironmentObject var sync: KoSync
    @EnvironmentObject var library: Library
    @Environment(\.dismiss) var dismiss
    @AppStorage("koBridge") var bridge = false
    @State var remote: KoPosition?
    @State var loaded = false
    @State var busy = false
    @State var message: String?
    @State var document = ""
    @State var confirmSend = false
    var body: some View {
        NavigationStack {
            Form {
                Section { Text("Positions are approximate because each reader lays out pages differently. Audio and finished status are separate.") }
                if busy { ProgressView("Contacting sync server…") }
                if let message { Text(message).foregroundStyle(.secondary) }
                if let remote {
                    Section("Other reader") {
                        Text("\(Int(remote.percentage * 100))% · \(remote.device ?? "KOReader")")
                        Button("Use this reading position") { Task {
                            if let current = library.book(model.id)?.locator { UserDefaults.standard.set(current, forKey: "koUndo:\(model.id)") }
                            let i = model.positions.indices.min { abs((model.positions[$0].locations.totalProgression ?? 0) - remote.percentage) < abs((model.positions[$1].locations.totalProgression ?? 0) - remote.percentage) }
                            if let i { await model.jump(i); dismiss() }
                        } }.disabled(busy)
                    }
                }
                if loaded { Button("Send this reading position") { confirmSend = true }.disabled(!bridge || busy) }
                if !bridge { Text("Enable the companion-plugin setting under Server before sending.").font(.footnote).foregroundStyle(.secondary) }
                if UserDefaults.standard.string(forKey: "koUndo:\(model.id)") != nil {
                    Button("Undo last received jump") { Task {
                        if let json = UserDefaults.standard.string(forKey: "koUndo:\(model.id)"), let locator = try? ReadiumShared.Locator(jsonString: json) {
                            _ = await model.navigator?.go(to: locator); UserDefaults.standard.removeObject(forKey: "koUndo:\(model.id)"); dismiss()
                        }
                    } }
                }
            }.navigationTitle("Reading sync").toolbar { Button("Done") { dismiss() } }
            .task {
                busy = true; defer { busy = false }
                do {
                    guard let name = library.book(model.id)?.ebook else { return }
                    document = try KoProtocol.document(library.file(name)); remote = try await sync.pull(document: document); loaded = true
                    if remote == nil { message = "No saved position on the sync server yet." }
                } catch { message = error.localizedDescription }
            }
            .confirmationDialog("Replace the server’s saved reading position?", isPresented: $confirmSend, titleVisibility: .visible) {
                Button("Send position") { Task {
                    busy = true; defer { busy = false }
                    do {
                        guard try await sync.pull(document: document) == remote else { throw PortalError.message("The other reader’s position changed. Reopen sync to review it first.") }
                        try await sync.push(document: document, percentage: library.book(model.id)?.readingProgress ?? 0); dismiss()
                    } catch { message = error.localizedDescription }
                } }
            }
        }.presentationDetents([.medium, .large])
    }
}
