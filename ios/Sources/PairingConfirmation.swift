import SwiftUI

/// Shown when a pairing link is opened, before anything is saved.
///
/// A scanned code can come from anywhere, so opening one must not connect on its own. The reader
/// sees which server it points at and which account it would sign in as, and decides. The password
/// the code carries is never displayed, and nothing is written until the server accepts it.
struct PairingConfirmation: View {
    let pairing: PastedConnection
    @EnvironmentObject private var server: BookServer
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var error: String?

    private func finish() {
        server.pendingPairing = nil
        dismiss()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Server", value: pairing.address)
                    LabeledContent("Signs in as", value: pairing.user)
                } header: {
                    Text("Connect this library?")
                } footer: {
                    Text("Connect only if you made this code yourself. It carries the password for this account, so treat the code the way you would treat the password.")
                }
                Section {
                    Button("Connect library") {
                        Task {
                            busy = true; error = nil; defer { busy = false }
                            do {
                                let account = try ServerAccount(url: BookServer.endpoint(pairing.address), user: pairing.user, password: pairing.password)
                                try await server.refresh(account, library: library)
                                finish()
                            } catch {
                                self.error = error.localizedDescription
                            }
                        }
                    }.disabled(busy)
                    Button("Not now", role: .cancel) { finish() }.disabled(busy)
                }
                if busy { Section { ProgressView("Connecting…") } }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .navigationTitle("Pair with BookLore")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(busy)
    }
}
