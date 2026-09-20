import Foundation
import SQLite3
import zlib

struct WordMeaning: Equatable {
    let word: String
    let partOfSpeech: String
    let definition: String
    let examples: [String]
}

/// WordNet is bundled with the app. Lookups never send reading text to a server.
/// Mirrors the Android lookup exactly: same normalisation, exception lists, suffix rules and sense limits.
enum OfflineDictionary {
    private static let lock = NSLock()
    /// Where the expanded database lives; tests may point this at a scratch directory.
    nonisolated(unsafe) static var storage = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]

    static func lookup(_ text: String) async throws -> [WordMeaning] {
        try await Task.detached(priority: .userInitiated) { try lookupNow(text) }.value
    }

    static func lookupNow(_ text: String) throws -> [WordMeaning] {
        let word = text.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet.alphanumerics.inverted).lowercased()
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        guard !word.isEmpty, word.count <= 80 else { return [] }
        let path = try database()
        var handle: OpaquePointer?
        guard sqlite3_open_v2(path.path, &handle, SQLITE_OPEN_READONLY, nil) == SQLITE_OK, let db = handle else {
            sqlite3_close(handle); throw PortalError.message("Couldn’t open the dictionary.")
        }
        defer { sqlite3_close(db) }
        var found: [WordMeaning] = []
        for pos in ["noun", "verb", "adj", "adv"] {
            var candidates = [word]
            func add(_ candidate: String) { if !candidates.contains(candidate) { candidates.append(candidate) } }
            try strings(db, "SELECT lemma FROM exceptions WHERE word=? AND pos=?", [word, pos]).forEach(add)
            let endings: [(String, String)]
            switch pos {
            case "noun": endings = [("s", ""), ("ses", "s"), ("xes", "x"), ("zes", "z"), ("ches", "ch"), ("shes", "sh"), ("men", "man"), ("ies", "y")]
            case "verb": endings = [("s", ""), ("ies", "y"), ("es", "e"), ("es", ""), ("ed", "e"), ("ed", ""), ("ing", "e"), ("ing", "")]
            case "adj": endings = [("er", ""), ("est", ""), ("er", "e"), ("est", "e"), ("ier", "y"), ("iest", "y")]
            default: endings = []
            }
            for (suffix, replacement) in endings where word.hasSuffix(suffix) && word.count > suffix.count + 1 {
                let base = String(word.dropLast(suffix.count)) + replacement
                add(base)
                if ["ed", "ing", "er", "est"].contains(suffix), replacement.isEmpty, base.count > 2, base.last == base[base.index(base.endIndex, offsetBy: -2)] {
                    add(String(base.dropLast()))
                }
            }
            for candidate in candidates {
                let glosses = try strings(db, "SELECT s.gloss FROM entries e JOIN senses s ON s.pos=e.pos AND s.offset=e.offset WHERE e.word=? AND e.pos=? ORDER BY e.rank LIMIT 12", [candidate, pos])
                if glosses.isEmpty { continue }
                found += glosses.map { meaning(candidate, pos, $0) }
                break
            }
        }
        var distinct: [WordMeaning] = []
        for meaning in found where !distinct.contains(meaning) { distinct.append(meaning) }
        return distinct
    }

    private static let examplePattern = try! NSRegularExpression(pattern: #";\s*"([^"]+)""#)
    private static func meaning(_ word: String, _ pos: String, _ gloss: String) -> WordMeaning {
        let matches = examplePattern.matches(in: gloss, range: NSRange(gloss.startIndex..., in: gloss))
        let end = matches.first.flatMap { Range($0.range, in: gloss)?.lowerBound } ?? gloss.endIndex
        let label = pos == "adj" ? "adjective" : pos == "adv" ? "adverb" : pos
        return WordMeaning(word: word, partOfSpeech: label, definition: String(gloss[..<end]).trimmingCharacters(in: .whitespaces),
                           examples: matches.map { String(gloss[Range($0.range(at: 1), in: gloss)!]) })
    }

    private static func strings(_ db: OpaquePointer, _ sql: String, _ parameters: [String]) throws -> [String] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw PortalError.message("Couldn’t open the dictionary.") }
        defer { sqlite3_finalize(statement) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, value) in parameters.enumerated() { sqlite3_bind_text(statement, Int32(index + 1), value, -1, transient) }
        var rows: [String] = []
        while sqlite3_step(statement) == SQLITE_ROW { if let text = sqlite3_column_text(statement, 0) { rows.append(String(cString: text)) } }
        return rows
    }

    /// Expands the bundled database once, into app-private storage, under a versioned name.
    private static func database() throws -> URL {
        lock.lock(); defer { lock.unlock() }
        try FileManager.default.createDirectory(at: storage, withIntermediateDirectories: true)
        let file = storage.appendingPathComponent("dictionary-wordnet-3.0-v1.db")
        if FileManager.default.fileExists(atPath: file.path) { return file }
        guard let source = Bundle.main.url(forResource: "wordnet.db", withExtension: "gzip") else { throw PortalError.message("Could not prepare the dictionary.") }
        let temporary = storage.appendingPathComponent("dictionary-wordnet-3.0-v1.tmp")
        defer { try? FileManager.default.removeItem(at: temporary) }
        try gunzip(try Data(contentsOf: source), to: temporary)
        try FileManager.default.moveItem(at: temporary, to: file)
        return file
    }

    private static func gunzip(_ input: Data, to destination: URL) throws {
        var stream = z_stream()
        guard inflateInit2_(&stream, 16 + MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw PortalError.message("Could not prepare the dictionary.") }
        defer { inflateEnd(&stream) }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var buffer = [UInt8](repeating: 0, count: 1 << 20)
        var status = Z_OK
        try input.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            stream.next_in = UnsafeMutablePointer(mutating: raw.bindMemory(to: Bytef.self).baseAddress!)
            stream.avail_in = uInt(input.count)
            repeat {
                try buffer.withUnsafeMutableBufferPointer { out in
                    stream.next_out = out.baseAddress; stream.avail_out = uInt(out.count)
                    status = inflate(&stream, Z_NO_FLUSH)
                    guard status == Z_OK || status == Z_STREAM_END else { throw PortalError.message("Could not prepare the dictionary.") }
                    let produced = out.count - Int(stream.avail_out)
                    if produced > 0 { output.write(Data(bytes: out.baseAddress!, count: produced)) }
                }
            } while status != Z_STREAM_END
        }
    }
}
