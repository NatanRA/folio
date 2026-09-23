import XCTest
import UIKit
@testable import MediaPortal

/// Finding a streamed audiobook's cover with small range reads, and storing covers at a sensible size.
final class EmbeddedArtworkTests: XCTestCase {
    private let jpeg: [UInt8] = [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0xFF, 0x00, 0xFF, 0xD9]
    private let png: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    // MARK: MP4

    func testFindsTheCoverAfterTheAudioWithoutReadingTheAudio() async throws {
        let audio = 50_000_000
        let file = SparseFile(head: box("ftyp", Array("M4B isom".utf8)), audio: audio, after: moov(udta: udta(meta(fullBox: true, cover: jpeg))))
        let found = try await EmbeddedArtwork.read(size: file.size) { try await file.read($0, $1) }
        XCTAssertEqual(found.map(Array.init), jpeg)
        // A box header is read 16 bytes at a time, in case it has a 64-bit size, so at most 8 bytes of audio.
        XCTAssertLessThanOrEqual(file.audioBytesRead, 8, "Only the audio box's header should have been read")
        XCTAssertLessThan(file.bytesRead, 4_096)
    }

    func testFindsTheCoverWhenTheIndexComesFirst() async throws {
        let bytes = box("ftyp", Array("M4A ".utf8)) + moov(udta: udta(meta(fullBox: true, cover: png))) + box("mdat", [UInt8](repeating: 1, count: 64))
        let found = try await EmbeddedArtwork.read(size: Int64(bytes.count)) { offset, length in Data(bytes[Int(offset)..<min(bytes.count, Int(offset) + length)]) }
        XCTAssertEqual(found.map(Array.init), png)
    }

    func testReadsQuickTimeMetaWithoutVersionAndFlags() {
        XCTAssertEqual(EmbeddedArtwork.mp4Cover(udta(meta(fullBox: false, cover: jpeg))).map(Array.init), jpeg)
        XCTAssertEqual(EmbeddedArtwork.mp4Cover(meta(fullBox: true, cover: jpeg)).map(Array.init), jpeg, "meta straight under moov")
    }

    func testAnMP4WithoutACoverHasNone() {
        let tags = box("meta", [0, 0, 0, 0] + box("hdlr", [UInt8](repeating: 0, count: 25)) + box("ilst", box("©nam", box("data", [0, 0, 0, 1, 0, 0, 0, 0] + Array("Title".utf8)))))
        XCTAssertNil(EmbeddedArtwork.mp4Cover(udta(tags)))
    }

    // MARK: ID3

    func testFindsTheFrontCoverInID3v23() {
        let back = apic(version: 3, type: 4, picture: png), front = apic(version: 3, type: 3, picture: jpeg)
        XCTAssertEqual(EmbeddedArtwork.id3Picture(tag(version: 3, frames: back + front)).map(Array.init), jpeg, "The front cover wins over an earlier picture")
        XCTAssertEqual(EmbeddedArtwork.id3Picture(tag(version: 3, frames: back)).map(Array.init), png, "Any picture beats none")
    }

    func testReadsID3v24AndItsUTF16Descriptions() {
        let description: [UInt8] = [0xFF, 0xFE, 0x43, 0x00, 0x00, 0x00] // BOM, "C", terminator
        XCTAssertEqual(EmbeddedArtwork.id3Picture(tag(version: 4, frames: apic(version: 4, type: 3, picture: jpeg, encoding: 1, description: description))).map(Array.init), jpeg)
    }

    func testReadsID3v22() {
        let body: [UInt8] = [0] + Array("JPG".utf8) + [3] + Array("Cover".utf8) + [0] + jpeg
        let frame = Array("PIC".utf8) + [UInt8(body.count >> 16), UInt8(body.count >> 8 & 0xFF), UInt8(body.count & 0xFF)] + body
        XCTAssertEqual(EmbeddedArtwork.id3Picture(tag(version: 2, frames: frame)).map(Array.init), jpeg)
    }

    func testUndoesTagWideUnsynchronisation() {
        // Unsynchronisation puts a zero after every 0xFF; the picture must come back without them.
        var frames: [UInt8] = []
        for byte in apic(version: 3, type: 3, picture: jpeg) { frames.append(byte); if byte == 0xFF { frames.append(0) } }
        XCTAssertEqual(EmbeddedArtwork.id3Picture(tag(version: 3, frames: frames, flags: 0x80)).map(Array.init), jpeg)
    }

    func testGarbageFindsNothingAndOtherFormatsAreLeftToAVFoundation() async {
        XCTAssertNil(EmbeddedArtwork.id3Picture(Array("ID3".utf8) + [3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F, 1, 2, 3]))
        XCTAssertNil(EmbeddedArtwork.mp4Cover([0, 0, 0, 200] + Array("udta".utf8)))
        let ogg = Array("OggS".utf8) + [UInt8](repeating: 0, count: 60)
        do {
            _ = try await EmbeddedArtwork.read(size: Int64(ogg.count)) { offset, length in Data(ogg[Int(offset)..<min(ogg.count, Int(offset) + length)]) }
            XCTFail("An Ogg file should be left to the general metadata reader")
        } catch { XCTAssertTrue(error is EmbeddedArtwork.Unsupported) }
    }

    // MARK: Stored covers

    func testStoredCoversAreScaledDownAndFlattenedOntoWhite() throws {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = false
        let picture = UIGraphicsImageRenderer(size: CGSize(width: 3200, height: 4800), format: format).pngData { context in
            UIColor.clear.setFill(); context.fill(CGRect(x: 0, y: 0, width: 3200, height: 4800))
            UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 3200, height: 2400))
        }
        let stored = try XCTUnwrap(CoverImages.storedJPEG(picture).flatMap(UIImage.init(data:)))
        XCTAssertEqual(stored.size.height * stored.scale, 1600)
        XCTAssertEqual(stored.size.width * stored.scale, 1067, accuracy: 1)
        XCTAssertGreaterThan(brightness(of: stored, atFraction: 0.9), 0.95, "The transparent lower half should come out white, not black")
        XCTAssertNil(CoverImages.storedJPEG(Data("not a picture".utf8)))
    }

    private func brightness(of image: UIImage, atFraction y: CGFloat) -> CGFloat {
        var pixel = [UInt8](repeating: 0, count: 4)
        guard let cg = image.cgImage, let context = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                                             space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return -1 }
        let row = Int(CGFloat(cg.height) * y)
        context.draw(cg, in: CGRect(x: -cg.width / 2, y: -(cg.height - row - 1), width: cg.width, height: cg.height))
        return (CGFloat(pixel[0]) + CGFloat(pixel[1]) + CGFloat(pixel[2])) / 765
    }

    // MARK: Builders

    private func box(_ type: String, _ body: [UInt8]) -> [UInt8] { be32(8 + body.count) + Array(type.utf8) + body }
    private func moov(udta: [UInt8]) -> [UInt8] { box("moov", box("mvhd", [UInt8](repeating: 0, count: 100)) + box("trak", [UInt8](repeating: 7, count: 3000)) + udta) }
    private func udta(_ meta: [UInt8]) -> [UInt8] { box("udta", meta) }
    private func meta(fullBox: Bool, cover: [UInt8]) -> [UInt8] {
        let data = box("data", be32(cover == png ? 14 : 13) + [0, 0, 0, 0] + cover)
        return box("meta", (fullBox ? [0, 0, 0, 0] : []) + box("hdlr", [UInt8](repeating: 0, count: 25)) + box("ilst", box("covr", data)))
    }
    private func tag(version: UInt8, frames: [UInt8], flags: UInt8 = 0) -> [UInt8] {
        let padded = frames + [UInt8](repeating: 0, count: 16)
        return Array("ID3".utf8) + [version, 0, flags] + syncsafe(padded.count) + padded
    }
    private func apic(version: UInt8, type: UInt8, picture: [UInt8], encoding: UInt8 = 0, description: [UInt8] = [0]) -> [UInt8] {
        let body = [encoding] + Array("image/jpeg".utf8) + [0, type] + description + picture
        return Array("APIC".utf8) + (version == 4 ? syncsafe(body.count) : be32(body.count)) + [0, 0] + body
    }
    private func be32(_ value: Int) -> [UInt8] { [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)] }
    private func syncsafe(_ value: Int) -> [UInt8] { [UInt8(value >> 21 & 0x7F), UInt8(value >> 14 & 0x7F), UInt8(value >> 7 & 0x7F), UInt8(value & 0x7F)] }
}

/// A file with a large stretch of audio that is never stored, so a test can prove the reader skipped it.
private final class SparseFile: @unchecked Sendable {
    let head: [UInt8]
    let audioStart: Int
    let audioLength: Int
    let tail: [UInt8]
    var size: Int64 { Int64(audioStart + audioLength + tail.count) }
    private(set) var audioBytesRead = 0
    private(set) var bytesRead = 0

    /// `head`, then an `mdat` box holding `audio` bytes, then `after`.
    init(head: [UInt8], audio: Int, after: [UInt8]) {
        let size = audio + 8
        self.head = head + [UInt8(size >> 24 & 0xFF), UInt8(size >> 16 & 0xFF), UInt8(size >> 8 & 0xFF), UInt8(size & 0xFF)] + Array("mdat".utf8)
        audioStart = self.head.count; audioLength = audio; tail = after
    }

    func read(_ offset: Int64, _ length: Int) async throws -> Data {
        var out: [UInt8] = []
        for position in Int(offset)..<min(Int(size), Int(offset) + length) {
            if position < audioStart { out.append(head[position]) }
            else if position < audioStart + audioLength { audioBytesRead += 1; out.append(0) }
            else { out.append(tail[position - audioStart - audioLength]) }
        }
        bytesRead += out.count
        return Data(out)
    }
}
