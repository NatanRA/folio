#!/usr/bin/env swift
//
// Build a Folio pairing code for a BookLore library.
//
// Run this on the Mac, point the iPhone's Camera app at the code it prints, and Folio asks whether
// to connect. Nothing is typed on the phone.
//
// The code carries the account's password, so it IS the password: anyone who photographs it can
// read your library. Prefer a BookLore OPDS account created for one device, which you can delete
// server-side if a code gets out. The code is drawn in the terminal and never written to a file,
// so closing the window is enough to be rid of it.
//
// Usage: swift scripts/make-pairing-code.swift
//
import Foundation
import CoreImage
import Darwin

let scheme = "folio-pair"

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("error: \(message)\n".utf8))
    exit(1)
}

func ask(_ label: String) -> String {
    print(label, terminator: "")
    guard let line = readLine(strippingNewline: true)?.trimmingCharacters(in: .whitespaces), !line.isEmpty else {
        fail("\(label.trimmingCharacters(in: .whitespaces)) is required.")
    }
    return line
}

/// Read without echoing, so the password does not stay on screen or in shell history. When input
/// is piped rather than typed there is no terminal to suppress, and the caller already owns it.
func askSecret(_ label: String) -> String {
    let value: String
    if isatty(STDIN_FILENO) == 1 {
        guard let raw = getpass(label) else { fail("could not read the password.") }
        value = String(cString: raw)
    } else {
        value = readLine(strippingNewline: true) ?? ""
    }
    let trimmed = value.trimmingCharacters(in: .whitespaces)
    if trimmed.isEmpty { fail("password is required.") }
    return trimmed
}

let address = ask("BookLore address (https://books.example.com): ")
guard address.hasPrefix("https://") || address.hasPrefix("http://"),
      URLComponents(string: address)?.host?.isEmpty == false else {
    fail("the address must start with https:// or http:// and name a host.")
}
if address.hasPrefix("http://") {
    print("warning: http:// sends this password unencrypted over the network.")
}
let user = ask("OPDS username: ")
let password = askSecret("OPDS password (hidden): ")

// Encode everything that is not unreserved, so passwords with & # + / survive the round trip.
let unreserved = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
func escape(_ value: String) -> String {
    guard let escaped = value.addingPercentEncoding(withAllowedCharacters: unreserved) else {
        fail("could not encode a value for the link.")
    }
    return escaped
}
let link = "\(scheme)://booklore?address=\(escape(address))&user=\(escape(user))&password=\(escape(password))"

guard let generator = CIFilter(name: "CIQRCodeGenerator") else { fail("QR generation is unavailable.") }
generator.setValue(Data(link.utf8), forKey: "inputMessage")
generator.setValue("M", forKey: "inputCorrectionLevel")
guard let coded = generator.outputImage else { fail("could not build the code.") }

let context = CIContext()
guard let cgImage = context.createCGImage(coded, from: coded.extent) else { fail("could not render the code.") }
let width = cgImage.width, height = cgImage.height
var pixels = [UInt8](repeating: 0, count: width * height)
guard let raster = CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8,
                             bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
                             bitmapInfo: CGImageAlphaInfo.none.rawValue) else { fail("could not read the code.") }
raster.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

/// Decode what we are about to draw, rather than trusting it. A mirrored grid still looks like a
/// QR code but no scanner will read it, and that is not something to discover on the phone.
func decodes(_ grid: [UInt8]) -> Bool {
    var copy = grid
    guard let check = CGContext(data: &copy, width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
                                bitmapInfo: CGImageAlphaInfo.none.rawValue)?.makeImage() else { return false }
    let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: context,
                              options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
    let found = detector?.features(in: CIImage(cgImage: check)) as? [CIQRCodeFeature]
    return found?.first?.messageString == link
}

func flippedVertically(_ grid: [UInt8]) -> [UInt8] {
    var output = [UInt8](repeating: 0, count: grid.count)
    for row in 0..<height {
        let source = (height - 1 - row) * width
        output.replaceSubrange(row * width ..< row * width + width, with: grid[source ..< source + width])
    }
    return output
}

if !decodes(pixels) {
    let flipped = flippedVertically(pixels)
    guard decodes(flipped) else { fail("the generated code did not scan back correctly; nothing was printed.") }
    pixels = flipped
}

// Two spaces per module keeps the code square in a terminal's tall character cells.
let dark = "\u{1B}[40m  \u{1B}[0m", light = "\u{1B}[47m  \u{1B}[0m"
let quiet = String(repeating: light, count: width + 8)
print("")
for _ in 0..<2 { print(quiet) }
for row in 0..<height {
    var line = String(repeating: light, count: 4)
    for column in 0..<width { line += pixels[row * width + column] < 128 ? dark : light }
    print(line + String(repeating: light, count: 4))
}
for _ in 0..<2 { print(quiet) }
print("")
print("Point the iPhone's Camera at this code, then tap the Folio notification.")
print("Folio will show the server and account and ask before connecting.")
print("This code contains the password for \(user). Close this window when you are done.")
