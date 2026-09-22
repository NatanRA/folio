import Foundation

/// Telegram issues an api_id and api_hash to the developer of a client. Published builds carry
/// none, so the reader enters a pair once and it is kept in this device's Keychain. A personal
/// build may still bundle one as an untracked `Resources/telegram-private.json`, which a saved
/// pair overrides. See docs/telegram-library.md.
enum TelegramIdentity {
    struct Pair {
        let id: Int
        let hash: String
    }

    /// The identity built into this app, if the build carried one.
    static var bundled: Pair? {
        guard let url = Bundle.main.url(forResource: "telegram-private", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return pair(from: data)
    }

    /// An identity this reader entered by hand, which wins over the built-in one.
    static var override: Pair? {
        guard let data = SecureStore.read("telegram-app-identity") else { return nil }
        return pair(from: data)
    }

    /// The identity TDLib should sign in with, or nil when the reader must supply one.
    static var current: Pair? { override ?? bundled }

    static func valid(hash: String) -> Bool {
        hash.range(of: "^[a-fA-F0-9]{32}$", options: .regularExpression) != nil
    }

    private static func pair(from data: Data) -> Pair? {
        guard let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let number = (config["api_id"] as? NSNumber)?.intValue ?? Int(config["api_id"] as? String ?? "")
        guard let number, number > 0, let hash = config["api_hash"] as? String, valid(hash: hash) else { return nil }
        return Pair(id: number, hash: hash)
    }
}
