import AppKit

// The approved SVG is the source of truth for every platform.
let master = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    .appendingPathComponent("docs/branding/folio-rounded-stack.svg")
let source = try String(contentsOf: master, encoding: .utf8).replacingOccurrences(of: "currentColor", with: "#58DCCB")
guard let logo = NSImage(data: Data(source.utf8)) else { fatalError("Could not render the Folio SVG") }
let cg = CGContext(data: nil, width: 1024, height: 1024, bitsPerComponent: 8,
    bytesPerRow: 4096, space: CGColorSpace(name: CGColorSpace.sRGB)!,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: false)
NSColor(srgbRed: 11.0 / 255, green: 17.0 / 255, blue: 22.0 / 255, alpha: 1).setFill()
NSBezierPath(rect: NSRect(x: 0, y: 0, width: 1024, height: 1024)).fill()
logo.draw(in: NSRect(x: 102.4, y: 102.4, width: 819.2, height: 819.2))
NSGraphicsContext.restoreGraphicsState()
let bitmap = NSBitmapImageRep(cgImage: cg.makeImage()!)
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
