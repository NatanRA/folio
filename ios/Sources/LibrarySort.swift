import Foundation

enum LibrarySort: String, CaseIterable, Identifiable {
    case title, author, series, added
    var id: String { rawValue }
    var label: String {
        switch self {
        case .title: return "Title"
        case .author: return "Author"
        case .series: return "Series"
        case .added: return "Date added"
        }
    }
}

struct SeriesInfo: Equatable {
    var name: String
    var index: Double?
}

/// Orders the library grid. Series come from the catalogue when it states one, then from explicit
/// title markers, then from titles that share a leading phrase.
enum LibrarySorting {
    private static let number = #"(\d+(?:\.\d+)?)"#
    private static let volume = #"(?:#|book|vol\.?|volume|part)"#
    private static let bracketed = try! NSRegularExpression(pattern: #"^(.*?)\s*[(\[]\s*(.+?),?\s*"# + volume + #"?\s*"# + number + #"\s*[)\]]\s*$"#, options: .caseInsensitive)
    private static let leading = try! NSRegularExpression(pattern: #"^(.+?)\s*"# + volume + #"\s*"# + number + #"\s*[:\-–—]\s*.+$"#, options: .caseInsensitive)
    private static let trailing = try! NSRegularExpression(pattern: #"^(.+?)\s*[,:\-–—]?\s*"# + volume + #"\s*"# + number + #"\s*$"#, options: .caseInsensitive)
    private static let volumeWords: Set<String> = ["book", "vol", "volume", "part", "no", "number"]
    private static let connectors: Set<String> = ["and", "the", "of", "a", "an", "in", "&", "to", "for", "with"]
    private static let articles = try! NSRegularExpression(pattern: #"^(the|a|an)\s+"#, options: .caseInsensitive)
    private static let wordPattern = try! NSRegularExpression(pattern: #"[\p{L}\p{N}']+"#)

    static func sortKey(_ title: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespaces)
        return articles.stringByReplacingMatches(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed), withTemplate: "").lowercased()
    }

    static func parseSeries(_ title: String) -> SeriesInfo? { named(title) ?? numbered(title) }

    /// "Title (Series #3)" and "Series #3: Title" name the series outright.
    private static func named(_ title: String) -> SeriesInfo? { info(from: [bracketed, leading], in: title) }
    /// "Title, Book 3" only numbers the book; the text before the marker is its title, which doubles as the series name when it has no siblings.
    private static func numbered(_ title: String) -> SeriesInfo? { info(from: [trailing], in: title) }
    private static func info(from patterns: [NSRegularExpression], in title: String) -> SeriesInfo? {
        let range = NSRange(title.startIndex..., in: title)
        guard let match = patterns.lazy.compactMap({ $0.firstMatch(in: title, range: range) }).first else { return nil }
        let name = String(title[Range(match.range(at: match.numberOfRanges - 2), in: title)!])
            .trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: ",:-–— "))
        guard name.contains(where: \.isLetter), !volumeWords.contains(name.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))) else { return nil }
        return SeriesInfo(name: name, index: Double(title[Range(match.range(at: match.numberOfRanges - 1), in: title)!]))
    }

    static func series(for books: [MediaBook]) -> [String: SeriesInfo] {
        // A catalogue that states the series beats anything guessed from a title. Books the server
        // said nothing about still fall back to the heuristic, which is all a locally imported
        // file ever has, so the guessing cannot simply be deleted.
        var fromServer: [String: SeriesInfo] = [:]
        for book in books {
            guard let name = book.seriesName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { continue }
            fromServer[book.id] = SeriesInfo(name: name, index: book.seriesPosition)
        }
        let books = books.filter { fromServer[$0.id] == nil }
        var named: [String: SeriesInfo] = [:], numbered: [String: SeriesInfo] = [:]
        for book in books {
            if let info = self.named(book.title) { named[book.id] = info } else if let info = self.numbered(book.title) { numbered[book.id] = info }
        }
        let titleWords = Dictionary(books.map { ($0.id, words(of: numbered[$0.id]?.name ?? $0.title)) }, uniquingKeysWith: { first, _ in first })
        var result = named
        for book in books where named[book.id] == nil {
            let mine = titleWords[book.id] ?? []
            let longest = books.filter { $0.id != book.id && named[$0.id] == nil && sameAuthor(book, $0) }
                .map { commonPrefix(mine, titleWords[$0.id] ?? []) }.max() ?? 0
            if longest >= 2 { result[book.id] = SeriesInfo(name: prefixText(of: book.title, count: longest), index: numbered[book.id]?.index) }
            else if let info = numbered[book.id] { result[book.id] = info }
        }
        return result.merging(fromServer) { _, server in server }
    }

    static func sorted(_ books: [MediaBook], by sort: LibrarySort) -> [MediaBook] {
        switch sort {
        case .title: return stableSorted(books) { sortKey($0.title) < sortKey($1.title) }
        case .author:
            return stableSorted(books) { a, b in
                if a.author.isEmpty != b.author.isEmpty { return b.author.isEmpty }
                let left = a.author.trimmingCharacters(in: .whitespaces).lowercased(), right = b.author.trimmingCharacters(in: .whitespaces).lowercased()
                return left != right ? left < right : sortKey(a.title) < sortKey(b.title)
            }
        case .added:
            return stableSorted(books) { a, b in
                switch (a.addedAt, b.addedAt) {
                case let (x?, y?): return x > y
                case (.some, nil): return true
                default: return false
                }
            }
        case .series:
            let series = series(for: books)
            return stableSorted(books) { a, b in
                switch (series[a.id], series[b.id]) {
                case (nil, nil): return sortKey(a.title) < sortKey(b.title)
                case (.some, nil): return true
                case (nil, .some): return false
                case let (x?, y?):
                    let left = sortKey(x.name), right = sortKey(y.name)
                    if left != right { return left < right }
                    let i = x.index ?? .greatestFiniteMagnitude, j = y.index ?? .greatestFiniteMagnitude
                    return i != j ? i < j : sortKey(a.title) < sortKey(b.title)
                }
            }
        }
    }

    private static func stableSorted(_ books: [MediaBook], by less: (MediaBook, MediaBook) -> Bool) -> [MediaBook] {
        books.enumerated().sorted { less($0.element, $1.element) || (!less($1.element, $0.element) && $0.offset < $1.offset) }.map(\.element)
    }
    private static func words(of title: String) -> [String] {
        let lower = title.lowercased()
        return wordPattern.matches(in: lower, range: NSRange(lower.startIndex..., in: lower)).map { String(lower[Range($0.range, in: lower)!]) }
    }
    private static func sameAuthor(_ a: MediaBook, _ b: MediaBook) -> Bool {
        a.author.isEmpty || b.author.isEmpty || a.author.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(b.author.trimmingCharacters(in: .whitespaces)) == .orderedSame
    }
    /// Number of leading words two titles share, with trailing connector words dropped so "Harry Potter and the" reads as "Harry Potter".
    private static func commonPrefix(_ a: [String], _ b: [String]) -> Int {
        var n = 0
        while n < a.count, n < b.count, a[n] == b[n] { n += 1 }
        if n == a.count, n == b.count { return 0 }
        while n > 0, connectors.contains(a[n - 1]) { n -= 1 }
        return n
    }
    private static func prefixText(of title: String, count: Int) -> String {
        let tokens = wordPattern.matches(in: title, range: NSRange(title.startIndex..., in: title)).prefix(count)
        guard let first = tokens.first, let last = tokens.last else { return title }
        return String(title[Range(first.range, in: title)!.lowerBound..<Range(last.range, in: title)!.upperBound])
    }
}
