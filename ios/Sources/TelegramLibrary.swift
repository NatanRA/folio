import Foundation
import Security
import TDLib
import AVFoundation
import UIKit

struct TelegramFile: Identifiable {
    let chat: Int64
    let message: Int64
    let file: Int32
    let name: String
    let size: Int64
    var thumbnail: Int32 = 0
    var title: String = ""
    var performer: String = ""
    var id: String { "\(chat):\(message)" }
    var audio: Bool { !["epub", "pdf", "cbz"].contains((name as NSString).pathExtension.lowercased()) }
    static func parse(_ message: [String: Any]) -> TelegramFile? {
        guard let content = message["content"] as? [String: Any], let type = content["@type"] as? String,
              let attachment = content[type == "messageAudio" ? "audio" : "document"] as? [String: Any],
              ["messageAudio", "messageDocument"].contains(type), let name = attachment["file_name"] as? String,
              ["epub", "pdf", "cbz", "mp3", "m4a", "m4b", "aac", "flac", "wav", "aiff"].contains((name as NSString).pathExtension.lowercased()),
              let file = attachment[type == "messageAudio" ? "audio" : "document"] as? [String: Any],
              let fileID = file["id"] as? NSNumber, let chatID = message["chat_id"] as? NSNumber,
              let messageID = message["id"] as? NSNumber else { return nil }
        return TelegramFile(chat: chatID.int64Value, message: messageID.int64Value, file: fileID.int32Value, name: name,
                            size: max((file["size"] as? NSNumber)?.int64Value ?? 0, (file["expected_size"] as? NSNumber)?.int64Value ?? 0),
                            thumbnail: (((attachment["album_cover_thumbnail"] ?? attachment["thumbnail"]) as? [String: Any])?["file"] as? [String: Any])?["id"] as? Int32 ?? 0,
                            title: attachment["title"] as? String ?? "", performer: attachment["performer"] as? String ?? "")
    }
}

/// A TDLib error. `detail` is TDLib's own message; `errorDescription` is what the UI shows.
struct TelegramError: LocalizedError {
    let code: Int
    let detail: String
    /// The selected chat can no longer be read: it was left, deleted, or TDLib has not loaded it after a restart.
    var chatUnavailable: Bool { detail.contains("Chat not found") || detail.contains("CHANNEL_PRIVATE") || detail.contains("Can't access the chat") || detail.contains("CHAT_ID_INVALID") }
    var errorDescription: String? {
        if detail.contains("PHONE_NUMBER_INVALID") { return "Check your phone number, including its country code." }
        if detail.contains("PHONE_CODE") { return "That code is invalid or expired. Please try again." }
        if detail.contains("PASSWORD_HASH_INVALID") { return "That two-step verification password is incorrect." }
        if detail.contains("FLOOD") || detail.contains("Too Many") { return "Telegram has asked you to wait before trying again." }
        if detail.contains("Chat not found") || detail.contains("CHAT_ID_INVALID") { return "That chat is no longer available. Choose another chat or use Saved Messages." }
        if detail.contains("CHANNEL_PRIVATE") || detail.contains("Can't access the chat") { return "You no longer have access to that chat. Choose another chat or use Saved Messages." }
        if detail.contains("Message not found") || detail.contains("MESSAGE_ID_INVALID") { return "This file is no longer in Telegram." }
        if detail.contains("Timeout expired") || detail.range(of: "network", options: .caseInsensitive) != nil || code == 502 { return "Telegram couldn’t reach its servers. Check your connection and try again." }
        return "Telegram couldn’t complete this request (\(detail)). Try again."
    }
}

/// TDLib owns the network session. The VM is never involved in file transfers.
@MainActor final class TelegramLibrary: ObservableObject {
    static let shared = TelegramLibrary()
    @Published var auth = "disconnected"
    @Published var error: String?
    @Published var entries: [TelegramFile] = []
    @Published var chats: [(id: Int64, name: String)] = []
    @Published var activity: String?
    @Published var progress = 0.0
    @Published var more = false
    /// True while the selected chat's history is being scanned, including the automatic scan after sign-in.
    @Published var scanning = false
    @Published var selectedName = "Saved Messages"
    weak var library: Library?
    private var rangeBusy = false
    private var client: Int32 = 0
    private var account: Int64 = 0
    private var chat: Int64 = 0
    private var cursor: Int64 = 0
    private var pending: [String: CheckedContinuation<[String: Any], Error>] = [:]
    private var files: [Int32: [String: Any]] = [:]
    private var downloadTask: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var artworkAttempted = Set<String>()
    private var refreshTask: Task<Void, Never>?
    private var receiveStarted = false
    private let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Telegram", isDirectory: true)
    /// Keeps the reason visible. "Couldn’t initialise the secure Telegram connection" on its own
    /// leaves no way forward, while the underlying error usually names the problem — a rejected
    /// application identity, no network, or a session that needs starting again.
    static func connectionFailure(_ error: Error) -> String {
        let detail = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        return detail.isEmpty ? "Couldn’t initialise the secure Telegram connection." : detail
    }
    /// Reported to Telegram and shown in the reader's list of active sessions, so it has to be
    /// this build rather than a number written down once and left behind.
    static var appVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    /// True once sign-in can start, whether the identity ships with the build or was entered here.
    var configured: Bool { TelegramIdentity.current != nil }
    /// Saves an identity for this device, overriding whatever the build shipped with.
    func configure(id: String, hash: String) throws {
        guard let number = Int(id), number > 0, TelegramIdentity.valid(hash: hash) else { throw PortalError.message("Enter the API ID and hash from my.telegram.org/apps.") }
        try SecureStore.write(JSONSerialization.data(withJSONObject: ["api_id": number, "api_hash": hash]), name: "telegram-app-identity")
    }

    func start() {
        guard client == 0 else { return }
        error = nil
        do {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var directory = root; var values = URLResourceValues(); values.isExcludedFromBackup = true; try directory.setResourceValues(values)
            _ = td_execute("{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":0}")
            client = td_create_client_id(); auth = "starting"
            if !receiveStarted {
                receiveStarted = true
                DispatchQueue(label: "Telegram.receive", qos: .utility).async {
                    while true {
                        guard let pointer = td_receive(1.0) else { continue }
                        let data = Data(String(cString: pointer).utf8)
                        Task { @MainActor in
                            guard let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
                            TelegramLibrary.shared.receive(event)
                        }
                    }
                }
            }
            Task { do { _ = try await request("getAuthorizationState") } catch { self.error = error.localizedDescription } }
        } catch { self.error = TelegramLibrary.connectionFailure(error) }
    }
    private func request(_ type: String, _ fields: [String: Any] = [:]) async throws -> [String: Any] {
        let id = UUID().uuidString
        var body = fields; body["@type"] = type; body["@extra"] = id
        let encoded = String(decoding: try JSONSerialization.data(withJSONObject: body), as: UTF8.self)
        let timeout = Task {
            try await Task.sleep(nanoseconds: 60_000_000_000)
            pending.removeValue(forKey: id)?.resume(throwing: PortalError.message("Telegram didn’t respond. Check your connection and try again."))
        }
        defer { timeout.cancel() }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                pending[id] = continuation
                td_send(client, encoded)
            }
        } onCancel: { Task { @MainActor in self.pending.removeValue(forKey: id)?.resume(throwing: CancellationError()) } }
    }
    private func receive(_ event: [String: Any]) {
        if let id = event["@extra"] as? String, let continuation = pending.removeValue(forKey: id) {
            if event["@type"] as? String == "error" { continuation.resume(throwing: TelegramError(code: (event["code"] as? NSNumber)?.intValue ?? 0, detail: event["message"] as? String ?? "")) }
            else { continuation.resume(returning: event) }
        }
        if event["@type"] as? String == "updateFile", let file = event["file"] as? [String: Any], let id = file["id"] as? NSNumber { files[id.int32Value] = file }
        if event["@type"] as? String == "updateNewMessage", let message = event["message"] as? [String: Any], (message["chat_id"] as? NSNumber)?.int64Value == chat, activity == nil {
            refreshTask?.cancel(); refreshTask = Task { try? await Task.sleep(nanoseconds: 1_000_000_000); guard !Task.isCancelled else { return }; try? await refresh() }
        }
        guard event["@type"] as? String == "updateAuthorizationState", let state = event["authorization_state"] as? [String: Any], let type = state["@type"] as? String else { return }
        auth = type
        if type == "authorizationStateClosed" { client = 0; auth = "disconnected" }
        if type == "authorizationStateWaitTdlibParameters" { Task {
            do {
                guard let identity = TelegramIdentity.current else { throw PortalError.message("This build has no Telegram application identity. Add one under Telegram in Server settings.") }
                var key = SecureStore.read("telegram-database-key")
                if key == nil {
                    var bytes = [UInt8](repeating: 0, count: 32)
                    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw PortalError.message("Could not create a secure session.") }
                    key = Data(bytes); try SecureStore.write(key, name: "telegram-database-key")
                }
                _ = try await request("setTdlibParameters", ["database_directory": root.appendingPathComponent("database").path,
                    "files_directory": root.appendingPathComponent("files").path, "database_encryption_key": key!.base64EncodedString(),
                    "use_file_database": true, "use_chat_info_database": true, "use_message_database": false, "use_secret_chats": false,
                    "api_id": identity.id, "api_hash": identity.hash, "system_language_code": "en", "device_model": "iOS", "system_version": "17", "application_version": TelegramLibrary.appVersion])
            } catch { self.error = TelegramLibrary.connectionFailure(error) }
        } }
        if type == "authorizationStateReady" { Task {
            do {
                account = (try await request("getMe")["id"] as? NSNumber)?.int64Value ?? 0
                let saved = UserDefaults.standard.object(forKey: "telegram.selected:\(account)") as? Int64 ?? 0
                if saved != 0 { try await open(saved) } else { try await selectSaved() }
            } catch { self.error = error.localizedDescription }
        } }
    }
    func signIn(_ value: String) async throws {
        switch auth {
        case "authorizationStateWaitPhoneNumber": _ = try await request("setAuthenticationPhoneNumber", ["phone_number": value])
        case "authorizationStateWaitCode": _ = try await request("checkAuthenticationCode", ["code": value])
        case "authorizationStateWaitPassword": _ = try await request("checkAuthenticationPassword", ["password": value])
        case "authorizationStateWaitEmailAddress": _ = try await request("setAuthenticationEmailAddress", ["email_address": value])
        case "authorizationStateWaitEmailCode": _ = try await request("checkAuthenticationEmailCode", ["code": ["@type": "emailAddressAuthenticationCode", "code": value]])
        default: throw PortalError.message("Telegram requires an additional verification step. Open Telegram on your other device and retry.")
        }
    }
    func selectSaved() async throws {
        let saved = try await request("createPrivateChat", ["user_id": account, "force": false])
        guard let id = saved["id"] as? NSNumber else { return }
        try await select(id.int64Value)
    }
    func loadChats() async throws {
        _ = try? await request("loadChats", ["limit": 100])
        let result = try await request("getChats", ["limit": 100])
        var loaded: [(Int64, String)] = []
        for id in result["chat_ids"] as? [NSNumber] ?? [] {
            let chat = try await request("getChat", ["chat_id": id])
            loaded.append((id.int64Value, chat["title"] as? String ?? "Chat"))
        }
        chats = loaded
    }
    /// Selects `id`; if that chat can no longer be read, forgets it and falls back to Saved Messages instead of leaving nothing selected.
    func open(_ id: Int64) async throws {
        do { try await select(id) } catch let failure as TelegramError where failure.chatUnavailable {
            UserDefaults.standard.removeObject(forKey: "telegram.selected:\(account)")
            try await selectSaved()
            error = failure.localizedDescription
        }
    }
    private func chatTitle(_ id: Int64) async throws -> String {
        // Without a message database TDLib forgets the chat list between launches; loading it makes a remembered chat resolvable again.
        let chat: [String: Any]
        do { chat = try await request("getChat", ["chat_id": id]) } catch let failure as TelegramError where failure.chatUnavailable {
            _ = try? await request("loadChats", ["limit": 100])
            chat = try await request("getChat", ["chat_id": id])
        }
        let title = chat["title"] as? String ?? ""
        return title.isEmpty ? "Library" : title
    }
    func select(_ id: Int64) async throws {
        artworkTask?.cancel(); artworkTask = nil; artworkAttempted.removeAll()
        chat = id; cursor = 0; entries = []; more = false; scanning = true
        defer { if chat == id { scanning = false } }
        selectedName = try await chatTitle(id)
        UserDefaults.standard.set(id, forKey: "telegram.selected:\(account)")
        try await loadMore()
        var previous: Int64 = 0
        while chat == id && more && cursor != previous { previous = cursor; try await loadMore() }
    }
    func refresh() async throws { if chat == 0 { try await selectSaved() } else { try await open(chat) } }
    func loadMore() async throws {
        guard chat != 0 else { return }
        let selected = chat
        let result = try await request("getChatHistory", ["chat_id": chat, "from_message_id": cursor, "offset": 0, "limit": 100, "only_local": false])
        guard selected == chat else { return }
        let messages = result["messages"] as? [[String: Any]] ?? []
        more = !messages.isEmpty
        if let last = messages.last { cursor = (last["id"] as? NSNumber)?.int64Value ?? cursor }
        for entry in messages.compactMap(TelegramFile.parse) where !entries.contains(where: { $0.id == entry.id }) { entries.append(entry) }
        if let library {
            for entry in entries where entry.audio {
                let source = "\(account):\(entry.id)"
                if !library.books.contains(where: { $0.telegramSource == source }) {
                    var book = MediaBook(title: entry.title.isEmpty ? (entry.name as NSString).deletingPathExtension.replacingOccurrences(of: "_", with: " ") : entry.title)
                    book.author = entry.performer
                    book.id = "tg-\(account)-\(entry.chat)-\(entry.message)"
                    book.audio = "telegram://\(account)/\(entry.chat)/\(entry.message)/audio.\((entry.name as NSString).pathExtension)"
                    book.telegramSource = source; book.addedAt = Date(); library.books.append(book)
                }
            }
            try library.save()
            loadArtwork()
        }
    }
    private func loadArtwork() {
        guard artworkTask == nil else { return }
        let owner = account
        artworkTask = Task {
            while !Task.isCancelled {
                guard let entry = entries.first(where: { $0.audio && !artworkAttempted.contains($0.id) }), let library else { break }
                if activity != nil { try? await Task.sleep(nanoseconds: 500_000_000); continue }
                artworkAttempted.insert(entry.id)
                guard let book = library.books.first(where: { $0.telegramSource == "\(owner):\(entry.id)" }), book.cover == nil,
                      let audio = book.audio, let url = URL(string: audio), url.scheme == "telegram" else { continue }
                do {
                    var artwork: Data?
                    if entry.thumbnail != 0 {
                        do {
                        let info = try await request("getFile", ["file_id": entry.thumbnail])
                        let size = max((info["size"] as? NSNumber)?.int64Value ?? 0, (info["expected_size"] as? NSNumber)?.int64Value ?? 0)
                        if size > 0 && size <= 2 * 1024 * 1024 {
                            let thumbnail = TelegramFile(chat: entry.chat, message: entry.message, file: entry.thumbnail, name: "cover.jpg", size: size)
                            var data = Data()
                            while data.count < size {
                                try Task.checkCancellation()
                                data.append(try await readRange(thumbnail, offset: Int64(data.count), length: min(256 * 1024, Int(size) - data.count), background: true))
                            }
                            if UIImage(data: data) != nil { artwork = data }
                        }
                        } catch { try Task.checkCancellation() /* Fall back to embedded artwork. */ }
                    }
                    if artwork == nil {
                        var budget = 8 * 1024 * 1024
                        let deadline = Date().addingTimeInterval(20)
                        let resource = TelegramAudioResource(url: url, resolve: { _ in entry }, read: { [weak self] file, offset, length in
                            guard let self, budget >= length, Date() < deadline else { throw PortalError.message("Metadata read limit") }
                            budget -= length
                            return try await self.readRange(file, offset: offset, length: length, background: true)
                        })
                        let timeout = Task { try? await Task.sleep(nanoseconds: 20_000_000_000); if !Task.isCancelled { resource.asset.cancelLoading(); resource.cancel() } }
                        defer { timeout.cancel(); resource.asset.cancelLoading(); resource.cancel() }
                        let metadata = try await resource.asset.load(.commonMetadata)
                        for item in metadata where item.commonKey == .commonKeyArtwork {
                            artwork = try? await item.load(.dataValue)
                            if artwork != nil { break }
                        }
                    }
                    try Task.checkCancellation()
                    if let artwork, let image = UIImage(data: artwork), let data = image.jpegData(compressionQuality: 0.9),
                       library.books.first(where: { $0.id == book.id })?.cover == nil {
                        let name = "\(book.id).jpg"
                        try data.write(to: library.file(name), options: .atomic)
                        library.update(book.id) { $0.cover = name }
                    }
                } catch { /* Missing artwork can be retried on refresh. */ }
            }
            if !Task.isCancelled { artworkTask = nil }
        }
    }
    func resolve(_ url: URL) async throws -> TelegramFile {
        start()
        let deadline = Date().addingTimeInterval(20)
        while auth != "authorizationStateReady" {
            guard Date() < deadline else { throw PortalError.message("Connect your Telegram account under Server first.") }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let me = (try await request("getMe")["id"] as? NSNumber)?.int64Value
        let parts = url.pathComponents.filter { $0 != "/" }
        guard url.scheme == "telegram", url.host == me.map(String.init), parts.count >= 2,
              let chat = Int64(parts[0]), let message = Int64(parts[1]),
              let entry = TelegramFile.parse(try await request("getMessage", ["chat_id": chat, "message_id": message])) else {
            throw PortalError.message("This book is unavailable. Check the Telegram account and channel.")
        }
        return entry
    }
    func readRange(_ entry: TelegramFile, offset: Int64, length: Int, background: Bool = false) async throws -> Data {
        guard offset >= 0, offset < entry.size, length > 0 else { throw PortalError.message("Invalid audio range.") }
        while rangeBusy { try await Task.sleep(nanoseconds: 20_000_000) }
        rangeBusy = true; defer { rangeBusy = false }
        guard activity == nil else { throw PortalError.message("Wait for the offline download to finish before streaming.") }
        func prefix() async throws -> Int64 { (try await request("getFileDownloadedPrefixSize", ["file_id": entry.file, "offset": offset])["size"] as? NSNumber)?.int64Value ?? 0 }
        func localFile() async throws -> (path: String, info: [String: Any]) {
            let info = try await request("getFile", ["file_id": entry.file])["local"] as? [String: Any] ?? [:]
            return (info["path"] as? String ?? "", info)
        }
        var available = try await prefix()
        var (path, local) = try await localFile()
        if available > 0, path.isEmpty || !FileManager.default.fileExists(atPath: path) {
            // TDLib records absolute paths. iOS moves the app's data container on every update, so a partial
            // file cached before the update no longer exists at the recorded path. Drop the stale record and refetch.
            _ = try? await request("deleteFile", ["file_id": entry.file])
            available = 0
        }
        if available == 0 {
            _ = try await request("downloadFile", ["file_id": entry.file, "priority": background ? 1 : 32, "offset": offset, "limit": min(background ? Int64(length) : 1024 * 1024, entry.size - offset), "synchronous": false])
            let deadline = Date().addingTimeInterval(30)
            while available == 0 {
                guard Date() < deadline else { throw PortalError.message("Audio buffering timed out. Check your connection and retry.") }
                try await Task.sleep(nanoseconds: 100_000_000); available = try await prefix()
            }
            (path, local) = try await localFile()
        }
        guard !path.isEmpty, FileManager.default.fileExists(atPath: path) else { throw PortalError.message("Audio buffer is unavailable.") }
        let handle = try FileHandle(forReadingFrom: URL(fileURLWithPath: path)); defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        let bytes = try handle.read(upToCount: Int(min(Int64(length), available, entry.size - offset))) ?? Data()
        if (local["downloaded_size"] as? NSNumber)?.int64Value ?? 0 > 64 * 1024 * 1024 {
            _ = try await request("cancelDownloadFile", ["file_id": entry.file, "only_if_pending": false])
            _ = try await request("deleteFile", ["file_id": entry.file])
        }
        return bytes
    }
    func downloaded(_ entry: TelegramFile, library: Library) -> Bool {
        library.books.contains { $0.telegramSource == "\(account):\(entry.id)" && $0.isLocal }
    }
    func cancel() { downloadTask?.cancel() }
    func download(_ entry: TelegramFile, library: Library) {
        guard activity == nil, !downloaded(entry, library: library) else { return }
        activity = "Downloading \(entry.name)"; progress = 0; error = nil
        downloadTask = Task {
            var staging: URL?
            defer { if let staging { try? FileManager.default.removeItem(at: staging) }; activity = nil; downloadTask = nil }
            do {
                let free = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
                guard free > entry.size * 3 + 64 * 1024 * 1024 else { throw PortalError.message("Not enough device storage for this file.") }
                files[entry.file] = try await request("downloadFile", ["file_id": entry.file, "priority": 16, "synchronous": false])
                var local: [String: Any] = [:]
                while true {
                    try Task.checkCancellation()
                    local = files[entry.file]?["local"] as? [String: Any] ?? [:]
                    progress = entry.size > 0 ? min(1, ((local["downloaded_size"] as? NSNumber)?.doubleValue ?? 0) / Double(entry.size)) : 0
                    if local["is_downloading_completed"] as? Bool == true { break }
                    try await Task.sleep(nanoseconds: 300_000_000)
                }
                guard let path = local["path"] as? String, FileManager.default.fileExists(atPath: path) else { throw PortalError.message("The download is incomplete. Try again.") }
                activity = "Adding to your library…"
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent("telegram-\(UUID().uuidString)", isDirectory: true)
                staging = directory; try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let copy = directory.appendingPathComponent((entry.name as NSString).lastPathComponent)
                try FileManager.default.copyItem(at: URL(fileURLWithPath: path), to: copy)
                let existing = library.books.first { $0.telegramSource == "\(account):\(entry.id)" }
                let id = try await library.importFile(copy, audio: entry.audio, linking: existing?.id, telegramSource: "\(account):\(entry.id)")
                UserDefaults.standard.set(id, forKey: "telegram:\(account):\(entry.id)")
                _ = try? await request("deleteFile", ["file_id": entry.file])
            } catch is CancellationError {
                Task { _ = try? await self.request("cancelDownloadFile", ["file_id": entry.file, "only_if_pending": false]) }
            } catch { self.error = error.localizedDescription }
        }
    }
    func disconnect() async throws { artworkTask?.cancel(); artworkTask = nil; cancel(); _ = try await request("logOut"); entries = []; chats = []; account = 0; chat = 0 }
    func close() async throws { if client != 0 { _ = try await request("close") } }
}
