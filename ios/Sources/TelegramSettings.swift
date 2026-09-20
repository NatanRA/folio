import SwiftUI

struct TelegramSettings: View {
    @ObservedObject private var telegram = TelegramLibrary.shared
    @EnvironmentObject private var library: Library
    @State private var value = ""
    @State private var busy = false
    @State private var expanded = false
    @State private var showChats = false
    private var selected: String { telegram.selectedName }
    @State private var configured = TelegramLibrary.shared.configured
    @State private var apiID = ""
    @State private var apiHash = ""
    private var inputLabel: String? {
        switch telegram.auth {
        case "authorizationStateWaitPhoneNumber": return "Phone number with country code"
        case "authorizationStateWaitCode": return "Telegram login code"
        case "authorizationStateWaitPassword": return "Two-step verification password"
        case "authorizationStateWaitEmailAddress": return "Email address for Telegram verification"
        case "authorizationStateWaitEmailCode": return "Email verification code"
        default: return nil
        }
    }
    @ViewBuilder private var credentialFields: some View {
        TextField("API ID", text: $apiID).keyboardType(.numberPad)
        SecureField("API hash", text: $apiHash).textInputAutocapitalization(.never).autocorrectionDisabled()
        Button("Save & connect") { run { try telegram.configure(id: apiID.trimmingCharacters(in: .whitespaces), hash: apiHash.trimmingCharacters(in: .whitespaces)); apiHash = ""; configured = true; telegram.start() } }.disabled(apiID.isEmpty || apiHash.isEmpty || busy)
    }
    private func run(_ action: @escaping () async throws -> Void) {
        Task { busy = true; telegram.error = nil; defer { busy = false }
            do { try await action() } catch is CancellationError {} catch { telegram.error = error.localizedDescription }
        }
    }
    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $expanded) {
                Text("Audiobooks appear in your library. Tap a title to stream, or keep a copy offline.")
                if !configured {
                    Text("This build carries no Telegram application credentials, so it needs yours. Create an application at my.telegram.org/apps, then paste its two values here. They stay in this device’s Keychain.")
                    Link("Open my.telegram.org/apps", destination: URL(string: "https://my.telegram.org/apps")!)
                    credentialFields
                }
                else if telegram.auth == "disconnected" {
                    Button("Connect Telegram") { telegram.start() }
                    Text("Telegram sends a login code to your other device, or by text message if this is your only one.").font(.caption).foregroundStyle(.secondary)
                    if TelegramIdentity.bundled != nil {
                        DisclosureGroup("Use my own Telegram credentials") {
                            Text("Only needed to sign in through an application you registered yourself.").font(.caption).foregroundStyle(.secondary)
                            Link("Open my.telegram.org/apps", destination: URL(string: "https://my.telegram.org/apps")!)
                            credentialFields
                        }
                    }
                }
                else if telegram.auth == "authorizationStateReady" {
                    Button("Saved Messages") { run { try await telegram.selectSaved() } }.disabled(busy || telegram.scanning || telegram.activity != nil)
                    Button("Choose chat") { run { try await telegram.loadChats(); showChats = true } }.disabled(busy || telegram.scanning || telegram.activity != nil)
                    if showChats { ForEach(telegram.chats, id: \.id) { chat in
                        Button(chat.name) { showChats = false; run { try await telegram.select(chat.id) } }.disabled(busy)
                    } }
                    if telegram.scanning { Text("Scanning \(selected) for books…").foregroundStyle(.secondary) }
                    else if telegram.entries.isEmpty && !busy { Text("No supported books in this part of the chat. Upload an e-book or audiobook to \(selected), then refresh.").foregroundStyle(.secondary) }
                    Text("\(telegram.entries.filter { $0.audio }.count) audiobooks available in Library")
                    ForEach(telegram.entries.filter { !$0.audio }) { entry in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(entry.name).font(.headline)
                            Text("\(entry.size / (1024 * 1024)) MB · \(entry.audio ? "Audiobook" : "E-book")").font(.caption).foregroundStyle(.secondary)
                            Button(telegram.downloaded(entry, library: library) ? "Downloaded" : "Download") { telegram.download(entry, library: library) }
                                .disabled(telegram.downloaded(entry, library: library) || telegram.activity != nil || busy)
                        }.padding(.vertical, 8)
                    }
                    if telegram.more { Button("Load older files") { run { try await telegram.loadMore() } }.disabled(busy || telegram.scanning) }
                    Button("Refresh") { run { try await telegram.refresh() } }.disabled(busy || telegram.scanning || telegram.activity != nil)
                    Button("Disconnect Telegram", role: .destructive) { run { try await telegram.disconnect() } }.disabled(busy || telegram.activity != nil)
                } else if let label = inputLabel {
                    if telegram.auth == "authorizationStateWaitCode" { Text("Check Telegram on your other device for the login code.") }
                    if telegram.auth == "authorizationStateWaitPhoneNumber" { TextField(label, text: $value).keyboardType(.phonePad) }
                    else { SecureField(label, text: $value).textContentType(telegram.auth == "authorizationStateWaitCode" ? .oneTimeCode : .password) }
                    Button("Continue") { let input = value; value = ""; run { try await telegram.signIn(input) } }.disabled(value.isEmpty || busy)
                } else { Text(telegram.auth == "starting" || telegram.auth == "authorizationStateWaitTdlibParameters" ? "Opening secure connection…" : "Telegram requires another verification step. Open Telegram on your other device and reconnect.") }
                if telegram.auth != "disconnected" && telegram.auth != "authorizationStateReady" { Button("Cancel sign-in") { run { try await telegram.close() } }.disabled(busy) }
                if busy || telegram.scanning { ProgressView() }
                if let activity = telegram.activity {
                    ProgressView(activity, value: telegram.progress)
                    Button("Cancel download") { telegram.cancel() }
                }
                if let error = telegram.error { Text(error).foregroundStyle(.red) }
            } label: {
                Label { VStack(alignment: .leading, spacing: 4) { Text("Telegram").font(.headline); Text(telegram.auth == "authorizationStateReady" ? "Connected · \(selected)" : "Not connected").font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: "cloud") }
            }
        } footer: { Text("Keep the app open while downloading. Downloaded copies stay on this device when you disconnect.") }
        .onChange(of: telegram.auth) { _, _ in value = "" }
    }
}
