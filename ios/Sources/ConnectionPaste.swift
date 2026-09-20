import Foundation

/// A server connection pasted as a single string, so the whole form fills in one tap instead of
/// three fields typed on a phone. Accepts the shapes a person is likely to already have to hand:
/// a URL carrying credentials, the JSON a pairing tool writes, or three whitespace-separated values.
struct PastedConnection: Equatable, Identifiable {
    /// Identifies the request being confirmed, never the secret it carries.
    var id: String { "\(address)\u{0}\(user)" }

    var address: String
    var user: String
    var password: String

    init?(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 4096 else { return nil }
        guard let parsed = Self.fromJSON(text) ?? Self.fromURL(text) ?? Self.fromFields(text) else { return nil }
        self = parsed
    }

    private init(address: String, user: String, password: String) {
        self.address = address
        self.user = user
        self.password = password
    }

    /// `{"address": "https://books.example.com", "user": "reader", "password": "…"}`
    private static func fromJSON(_ text: String) -> PastedConnection? {
        guard text.hasPrefix("{"), let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let address = (object["address"] ?? object["url"] ?? object["server"]) as? String ?? ""
        let user = (object["user"] ?? object["username"]) as? String ?? ""
        let password = (object["password"] ?? object["pass"]) as? String ?? ""
        guard looksLikeAddress(address), !user.isEmpty, !password.isEmpty else { return nil }
        return PastedConnection(address: address, user: user, password: password)
    }

    /// `https://reader:secret@books.example.com`
    private static func fromURL(_ text: String) -> PastedConnection? {
        guard var components = URLComponents(string: text), let host = components.host,
              let user = components.user, let password = components.password,
              !user.isEmpty, !password.isEmpty else { return nil }
        _ = host
        components.user = nil
        components.password = nil
        components.query = nil
        components.fragment = nil
        if components.path == "/" { components.path = "" }
        guard let address = components.string, looksLikeAddress(address) else { return nil }
        return PastedConnection(address: address, user: user, password: password)
    }

    /// Three values separated by spaces, tabs or newlines, address first.
    private static func fromFields(_ text: String) -> PastedConnection? {
        let parts = text.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count == 3, looksLikeAddress(parts[0]) else { return nil }
        return PastedConnection(address: parts[0], user: parts[1], password: parts[2])
    }

    /// The link `scripts/make-pairing-code.swift` produces, which the phone's own Camera app
    /// offers to open. Opening it must never connect on its own: the caller confirms first,
    /// because a code can come from anywhere and this one carries a password.
    init?(pairingLink url: URL) {
        guard url.scheme?.lowercased() == PastedConnection.scheme,
              url.host?.lowercased() == "booklore",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        func field(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard let address = field("address"), let user = field("user"), let password = field("password"),
              PastedConnection.looksLikeAddress(address), !user.isEmpty, !password.isEmpty else { return nil }
        self.init(address: address, user: user, password: password)
    }

    static let scheme = "folio-pair"

    private static func looksLikeAddress(_ value: String) -> Bool {
        guard value.hasPrefix("https://") || value.hasPrefix("http://") else { return false }
        return URLComponents(string: value)?.host?.isEmpty == false
    }
}
