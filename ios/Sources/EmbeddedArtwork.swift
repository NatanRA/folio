import Foundation

/// Finds the cover picture embedded in an audiobook by reading only the parts of the file that can hold it, so a
/// streamed book's cover costs a few small reads instead of downloading the file's whole index.
///
/// MP4 audio (M4B, M4A) keeps its cover in `moov/udta/meta/ilst/covr`. Long audiobooks usually put `moov` after the
/// audio, hundreds of megabytes in, and fill it with sample tables that a general-purpose metadata reader downloads in
/// full before it reaches the cover; that ran out of time or budget, so those covers only appeared once the book was
/// played. MP3 keeps its cover in an ID3v2 `APIC` frame at the start of the file.
enum EmbeddedArtwork {
    typealias Read = (_ offset: Int64, _ length: Int) async throws -> Data
    /// The largest metadata box or tag read in one go.
    static let limit = 16 * 1024 * 1024

    /// Thrown for a file that is neither MP4 nor an ID3-tagged MP3, which this reader can't search.
    struct Unsupported: Error {}

    /// The embedded cover's image bytes, or nil when the file has none.
    static func read(size: Int64, _ read: Read) async throws -> Data? {
        guard size >= 16 else { throw Unsupported() }
        let head = [UInt8](try await read(0, 16))
        guard head.count >= 10 else { throw Unsupported() }
        if head.starts(with: [0x49, 0x44, 0x33]) { return try await id3(size: size, read) }
        if fourCC(head, 4) == "ftyp" { return try await mp4(size: size, read) }
        throw Unsupported()
    }

    // MARK: MP4

    private struct Box { let type: String; let header: Int64; let length: Int64 }

    private static func mp4(size: Int64, _ read: Read) async throws -> Data? {
        // At the top level every box but `moov` is skipped by its size, so the audio itself is never read.
        var offset: Int64 = 0
        while offset + 8 <= size {
            guard let box = try await box(at: offset, end: size, read) else { return nil }
            if box.type == "moov" {
                // Inside `moov` the tracks are skipped the same way; only the metadata boxes are read whole.
                var child = offset + box.header
                let end = offset + box.length
                while child + 8 <= end {
                    guard let inner = try await self.box(at: child, end: end, read) else { return nil }
                    if inner.type == "udta" || inner.type == "meta", inner.length <= limit,
                       let cover = mp4Cover([UInt8](try await read(child, Int(inner.length)))) { return cover }
                    child += inner.length
                }
                return nil
            }
            offset += box.length
        }
        return nil
    }

    private static func box(at offset: Int64, end: Int64, _ read: Read) async throws -> Box? {
        let bytes = [UInt8](try await read(offset, Int(min(16, end - offset))))
        guard bytes.count >= 8 else { return nil }
        var length = Int64(be32(bytes, 0)), header: Int64 = 8
        if length == 1 {
            guard bytes.count >= 16, be64(bytes, 8) <= UInt64(Int64.max) else { return nil }
            length = Int64(be64(bytes, 8)); header = 16
        } else if length == 0 { length = end - offset }
        guard length >= header, length <= end - offset else { return nil }
        return Box(type: fourCC(bytes, 4), header: header, length: length)
    }

    /// The cover in a whole `udta` or `meta` box, header included.
    static func mp4Cover(_ bytes: [UInt8]) -> Data? {
        descend(bytes, 0..<bytes.count, ["udta", "meta", "ilst", "covr"]) ?? descend(bytes, 0..<bytes.count, ["meta", "ilst", "covr"])
    }

    private static func descend(_ bytes: [UInt8], _ range: Range<Int>, _ path: ArraySlice<String>) -> Data? {
        guard let next = path.first else { return coverData(bytes, range) }
        for box in boxes(bytes, range) where box.type == next {
            var body = box.body
            // `meta` is normally a full box, with four zero bytes of version and flags before its children. QuickTime
            // files leave them out, and there the first four bytes are a child's size instead.
            if next == "meta", body.count >= 4, be32(bytes, body.lowerBound) == 0 { body = (body.lowerBound + 4)..<body.upperBound }
            if let found = descend(bytes, body, path.dropFirst()) { return found }
        }
        return nil
    }

    /// The first picture in a `covr` item: a `data` box whose four bytes of type (13 JPEG, 14 PNG, 27 BMP, or 0 when a
    /// tagger left it implicit) and four of locale come before the picture.
    private static func coverData(_ bytes: [UInt8], _ range: Range<Int>) -> Data? {
        for box in boxes(bytes, range) where box.type == "data" && box.body.count > 8 {
            guard [0, 13, 14, 27].contains(be32(bytes, box.body.lowerBound) & 0x00FF_FFFF) else { continue }
            return Data(bytes[(box.body.lowerBound + 8)..<box.body.upperBound])
        }
        return nil
    }

    private static func boxes(_ bytes: [UInt8], _ range: Range<Int>) -> [(type: String, body: Range<Int>)] {
        var found: [(type: String, body: Range<Int>)] = []
        var i = range.lowerBound
        while i + 8 <= range.upperBound {
            var length = Int(be32(bytes, i)), header = 8
            if length == 1 {
                guard i + 16 <= range.upperBound, be64(bytes, i + 8) <= UInt64(range.upperBound - i) else { break }
                length = Int(be64(bytes, i + 8)); header = 16
            } else if length == 0 { length = range.upperBound - i }
            guard length >= header, length <= range.upperBound - i else { break }
            found.append((fourCC(bytes, i + 4), (i + header)..<(i + length)))
            i += length
        }
        return found
    }

    // MARK: ID3

    private static func id3(size: Int64, _ read: Read) async throws -> Data? {
        let header = [UInt8](try await read(0, 10))
        guard header.count == 10 else { return nil }
        let length = syncsafe(header, 6)
        guard length > 0, length <= limit, 10 + Int64(length) <= size else { return nil }
        return id3Picture(header + [UInt8](try await read(10, length)))
    }

    /// The front cover in a whole ID3v2.2, 2.3 or 2.4 tag, or its first picture when none is marked as the front cover.
    static func id3Picture(_ tag: [UInt8]) -> Data? {
        guard tag.count > 10, tag.starts(with: [0x49, 0x44, 0x33]), (2...4).contains(tag[3]) else { return nil }
        let version = tag[3], flags = tag[5]
        var body = Array(tag[10..<min(tag.count, 10 + syncsafe(tag, 6))])
        // Before 2.4, unsynchronisation (an inserted zero after every 0xFF) covers the whole tag; 2.4 marks it per frame.
        if flags & 0x80 != 0, version < 4 { body = resynchronised(body) }
        var i = 0
        if flags & 0x40 != 0, version >= 3 {
            guard body.count >= 4 else { return nil }
            i = version == 4 ? syncsafe(body, 0) : Int(be32(body, 0)) + 4
        }
        let idLength = version == 2 ? 3 : 4, headerLength = version == 2 ? 6 : 10
        var first: Data?
        while i + headerLength <= body.count, body[i] != 0 {
            let id = String(decoding: body[i..<(i + idLength)], as: UTF8.self)
            let size = version == 2 ? Int(body[i + 3]) << 16 | Int(body[i + 4]) << 8 | Int(body[i + 5])
                : version == 4 ? syncsafe(body, i + 4) : Int(be32(body, i + 4))
            let start = i + headerLength
            guard size > 0, size <= body.count - start else { break }
            defer { i = start + size }
            guard id == (version == 2 ? "PIC" : "APIC") else { continue }
            var frame = Array(body[start..<(start + size)])
            if version >= 3 {
                let format = body[i + 9]
                // Compressed or encrypted pictures are skipped; a group byte and 2.4's length prefix come before the data.
                if version == 3 ? format & 0xC0 != 0 : format & 0x0C != 0 { continue }
                var skip = 0
                if format & (version == 3 ? 0x20 : 0x40) != 0 { skip += 1 }
                if version == 4, format & 0x01 != 0 { skip += 4 }
                frame = Array(frame.dropFirst(skip))
                if version == 4, format & 0x02 != 0 { frame = resynchronised(frame) }
            }
            guard let picture = picture(frame, version: version) else { continue }
            if picture.type == 3 { return picture.data }
            first = first ?? picture.data
        }
        return first
    }

    /// A picture frame's type and image: after the text encoding come the MIME type (a three-letter format in 2.2), the
    /// picture type and a description ending in a zero byte, or two on an even boundary in UTF-16.
    private static func picture(_ frame: [UInt8], version: UInt8) -> (type: UInt8, data: Data)? {
        guard let encoding = frame.first, frame.count > 4 else { return nil }
        var i = 1
        if version == 2 { i += 3 } else {
            guard let end = frame[i...].firstIndex(of: 0) else { return nil }
            i = end + 1
        }
        guard i < frame.count else { return nil }
        let type = frame[i]; i += 1
        if encoding == 1 || encoding == 2 {
            while i + 1 < frame.count, frame[i] != 0 || frame[i + 1] != 0 { i += 2 }
            i += 2
        } else {
            guard i < frame.count, let end = frame[i...].firstIndex(of: 0) else { return nil }
            i = end + 1
        }
        guard i < frame.count else { return nil }
        return (type, Data(frame[i...]))
    }

    private static func resynchronised(_ bytes: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []; out.reserveCapacity(bytes.count)
        var previous: UInt8 = 0
        for byte in bytes {
            if !(previous == 0xFF && byte == 0) { out.append(byte) }
            previous = byte
        }
        return out
    }

    // MARK: Bytes

    private static func be32(_ b: [UInt8], _ i: Int) -> UInt32 { UInt32(b[i]) << 24 | UInt32(b[i + 1]) << 16 | UInt32(b[i + 2]) << 8 | UInt32(b[i + 3]) }
    private static func be64(_ b: [UInt8], _ i: Int) -> UInt64 { UInt64(be32(b, i)) << 32 | UInt64(be32(b, i + 4)) }
    private static func syncsafe(_ b: [UInt8], _ i: Int) -> Int { Int(b[i] & 0x7F) << 21 | Int(b[i + 1] & 0x7F) << 14 | Int(b[i + 2] & 0x7F) << 7 | Int(b[i + 3] & 0x7F) }
    private static func fourCC(_ b: [UInt8], _ i: Int) -> String { String(decoding: b[i..<(i + 4)], as: UTF8.self) }
}
