import XCTest
@testable import MediaPortal

final class PlayerPaletteTests: XCTestCase {
    private func hsb(_ color: UIColor) -> (hue: CGFloat, brightness: CGFloat) {
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        return (h, b)
    }
    /// WCAG contrast ratio of white text on `color`.
    private func whiteContrast(on color: UIColor) -> CGFloat {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        func linear(_ c: CGFloat) -> CGFloat { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 1.05 / (0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b) + 0.05)
    }

    func testKeepsTheCoverHueButDarkensIt() {
        let magenta = UIColor(red: 0.557, green: 0.184, blue: 0.353, alpha: 1)
        let backdrop = PlayerPalette.background(for: magenta)
        XCTAssertEqual(hsb(backdrop).hue, hsb(magenta).hue, accuracy: 0.01)
        XCTAssertEqual(hsb(backdrop).brightness, 0.24, accuracy: 0.01)
    }

    func testAGreyCoverGetsTheNeutralDark() {
        XCTAssertEqual(PlayerPalette.background(for: UIColor(white: 0.8, alpha: 1)), PlayerPalette.neutral)
    }

    func testWhiteTextStaysReadableWhateverTheCoverColour() {
        for step in 0..<24 {
            let cover = UIColor(hue: CGFloat(step) / 24, saturation: 1, brightness: 1, alpha: 1)
            XCTAssertGreaterThan(whiteContrast(on: PlayerPalette.background(for: cover)), 7, "hue \(step) of 24")
        }
    }

    func testAveragesTheCoverImage() throws {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.preferredRange = .standard
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 60), format: format).image { context in
            UIColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 0, width: 40, height: 30))
            UIColor(red: 0.6, green: 0.4, blue: 0.0, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 30, width: 40, height: 30))
        }
        let average = try XCTUnwrap(PlayerPalette.average(of: image))
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        average.getRed(&r, green: &g, blue: &b, alpha: &a)
        XCTAssertEqual(r, 0.4, accuracy: 0.02); XCTAssertEqual(g, 0.4, accuracy: 0.02); XCTAssertEqual(b, 0.4, accuracy: 0.02)
    }
}
