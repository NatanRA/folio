import Foundation

/// Read-only destinations shared with Android and the project navigation catalog.
struct FolioNavigation: Equatable {
    let id = UUID()
    let tab: Int
    let query: String?

    init?(url: URL) {
        guard url.absoluteString.utf16.count <= 2048,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == "folio", components.host == "navigate", components.path.isEmpty,
              components.user == nil, components.password == nil, components.port == nil,
              components.fragment == nil, let raw = components.percentEncodedQuery else { return nil }
        // URLSearchParams uses form encoding: a plus is a space; %2B stays plus.
        var fields: [String: String] = [:]
        for entry in raw.components(separatedBy: "&") {
            let pair = entry.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard pair.count == 2, ["screen", "q"].contains(String(pair[0])), fields[String(pair[0])] == nil,
                  let value = String(pair[1]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding else { return nil }
            fields[String(pair[0])] = value
        }
        guard let screen = fields["screen"], let tab = ["home", "library", "downloads", "server"].firstIndex(of: screen) else { return nil }
        let query = fields["q"]
        if let query {
            guard tab == 1, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, query.utf16.count <= 200,
                  !query.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else { return nil }
        }
        self.tab = tab
        self.query = query
    }
}
