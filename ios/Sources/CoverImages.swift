import ImageIO
import SwiftUI
import UIKit

/// Covers decoded once, at the size they are drawn, and kept in memory.
///
/// Views used to read and decode the full-size cover file every time they were redrawn. During playback the library
/// changed twice a second, so every cover on screen was decoded again twice a second; that stalled the main thread for
/// tens of milliseconds each time and cost the player's animation its smoothness. A stored cover file is never rewritten
/// under the same name (a new cover gets a new file), so its URL is a safe key.
final class CoverImages: @unchecked Sendable {
    static let shared = CoverImages()
    private let images = NSCache<NSString, UIImage>()
    private let aspects = NSCache<NSString, NSNumber>()
    private let lock = NSLock()
    private var pinned: (key: String, image: UIImage)?

    init() { images.totalCostLimit = 96 * 1024 * 1024 }

    /// The cover already decoded for drawing `points` long on its long side, if there is one.
    func cached(_ file: URL, points: CGFloat, scale: CGFloat) -> UIImage? {
        let key = key(file, Self.pixels(points, scale))
        if let pinned = lock.withLock({ pinned }), pinned.key == key as String { return pinned.image }
        return images.object(forKey: key)
    }

    /// Decodes a cover and keeps it even when memory runs short and the cache is emptied: the playing book's, so the
    /// player card always has it on its first frame.
    func pin(_ file: URL, points: CGFloat, scale: CGFloat) async {
        guard let image = await load(file, points: points, scale: scale) else { return }
        let key = key(file, Self.pixels(points, scale)) as String
        lock.withLock { pinned = (key, image) }
    }

    /// Decodes the cover off the main thread, ready to draw without further work.
    func load(_ file: URL, points: CGFloat, scale: CGFloat) async -> UIImage? {
        let pixels = Self.pixels(points, scale), key = key(file, pixels)
        if let image = images.object(forKey: key) { return image }
        guard let image = await Task.detached(priority: .userInitiated, operation: { Self.decode(file, pixels: pixels) }).value else { return nil }
        images.setObject(image, forKey: key, cost: image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0)
        return image
    }

    /// Width over height, read from the file's header rather than its pixels.
    func aspect(_ file: URL) -> CGFloat? {
        let key = file.path as NSString
        if let known = aspects.object(forKey: key) { return CGFloat(known.doubleValue) }
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Double, let height = properties[kCGImagePropertyPixelHeight] as? Double,
              width > 0, height > 0 else { return nil }
        // EXIF orientations 5 to 8 are turned a quarter, which swaps the sides.
        let turned = ((properties[kCGImagePropertyOrientation] as? Int) ?? 1) >= 5
        let aspect = turned ? height / width : width / height
        aspects.setObject(NSNumber(value: aspect), forKey: key)
        return CGFloat(aspect)
    }

    /// Image bytes as the JPEG Folio stores: at most 1600 pixels on the long side, so a huge picture doesn't become a
    /// huge file that every screen then has to decode, and on white where it was transparent. Nil when it isn't an image.
    static func storedJPEG(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions(1600)) else { return nil }
        var picture = UIImage(cgImage: image)
        if ![.none, .noneSkipFirst, .noneSkipLast].contains(image.alphaInfo) {
            let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
            picture = UIGraphicsImageRenderer(size: picture.size, format: format).image { context in
                UIColor.white.setFill(); context.fill(CGRect(origin: .zero, size: picture.size)); picture.draw(at: .zero)
            }
        }
        return picture.jpegData(compressionQuality: 0.85)
    }

    private static func decode(_ file: URL, pixels: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions(pixels)) else { return nil }
        return UIImage(cgImage: image)
    }

    private static func thumbnailOptions(_ pixels: Int) -> CFDictionary {
        [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
         kCGImageSourceShouldCacheImmediately: true, kCGImageSourceThumbnailMaxPixelSize: pixels] as CFDictionary
    }

    /// Sizes round up to steps of 256 pixels, so covers drawn at nearly the same size share one decoded copy.
    private static func pixels(_ points: CGFloat, _ scale: CGFloat) -> Int { min(2048, max(256, Int((points * scale / 256).rounded(.up)) * 256)) }
    private func key(_ file: URL, _ pixels: Int) -> NSString { "\(file.path)@\(pixels)" as NSString }
}

/// A stored cover, drawn at once when it has been decoded at this size before and otherwise after decoding it off the
/// main thread, with `placeholder` shown meanwhile, and in its place if the file can't be read.
struct StoredCover<Content: View, Placeholder: View>: View {
    let file: URL
    /// The length of the cover's long side on screen.
    let points: CGFloat
    @ViewBuilder var content: (UIImage) -> Content
    /// Given `true` once the file has turned out unreadable.
    @ViewBuilder var placeholder: (_ failed: Bool) -> Placeholder
    @Environment(\.displayScale) private var scale
    @State private var loaded: (key: String, image: UIImage?)?

    var body: some View {
        let key = "\(file.path)@\(points)"
        let mine = loaded?.key == key ? loaded : nil
        if let image = CoverImages.shared.cached(file, points: points, scale: scale) ?? mine?.image {
            content(image)
        } else {
            placeholder(mine != nil).task(id: key) {
                let image = await CoverImages.shared.load(file, points: points, scale: scale)
                loaded = (key, image)
            }
        }
    }
}
