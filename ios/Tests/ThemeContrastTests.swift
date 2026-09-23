import XCTest
import UIKit
@testable import MediaPortal

/// Every accent and paper must stay readable on the surfaces it is drawn on. The thresholds are WCAG's:
/// 4.5:1 for text, 3:1 for icons, and 7:1 for a paper's body text, since that is read for hours.
final class ThemeContrastTests: XCTestCase {
    private let lightSurfaces: [String: UInt32] = ["white": 0xFFFFFF, "grouped": 0xF2F2F7, "sepia paper": 0xFAF4E8, "sakura paper": 0xF9EDEF]
    private let darkSurfaces: [String: UInt32] = ["black": 0x000000, "elevated": 0x1C1C1E, "grouped row": 0x2C2C2E, "indigo paper": 0x1B2130]

    func testAccentsAreReadableOnEverySurfaceTheyUse() {
        for name in portalAccentNames {
            for (style, surfaces) in [(UIUserInterfaceStyle.light, lightSurfaces), (.dark, darkSurfaces)] {
                let accent = UIColor(portalAccent(name)).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
                for (surface, rgb) in surfaces {
                    let background = UIColor(rgb: rgb)
                    XCTAssertGreaterThanOrEqual(contrast(accent, background), 4.5, "\(name) on \(surface)")
                    // Text on cards tinted with the accent itself, like "Continue reading" (at most 13%)…
                    XCTAssertGreaterThanOrEqual(contrast(accent, blend(accent, over: background, alpha: 0.13)), 4.5, "\(name) text on its own tint over \(surface)")
                    // …and icons on the stronger tint of the play button, where WCAG asks 3:1 of graphics.
                    XCTAssertGreaterThanOrEqual(contrast(accent, blend(accent, over: background, alpha: 0.18)), 3, "\(name) icon on its own tint over \(surface)")
                }
                let onAccent = UIColor(portalOnAccent).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
                XCTAssertGreaterThanOrEqual(contrast(onAccent, accent), 4.5, "Text on a filled \(name) button")
            }
        }
    }

    func testPapersKeepTheirTextAndControlsReadable() {
        for paper in ReaderPaper.all {
            let traits = UITraitCollection(userInterfaceStyle: paper.dark ? .dark : .light)
            let page = UIColor(paper.pageColor).resolvedColor(with: traits)
            XCTAssertGreaterThanOrEqual(contrast(UIColor(paper.inkColor).resolvedColor(with: traits), page), 7, "\(paper.name) body text")
            for app in portalAccentNames {
                let accent = UIColor(paper.accentColor(app: app)).resolvedColor(with: traits)
                XCTAssertGreaterThanOrEqual(contrast(accent, page), 4.5, "\(paper.name) controls with the \(app) accent")
            }
            // The reader's sheets follow the app's appearance, so a paper's own accent has to hold there too.
            guard paper.accent != nil else { continue }
            for (style, surfaces) in [(UIUserInterfaceStyle.light, lightSurfaces), (.dark, darkSurfaces)] {
                let accent = UIColor(paper.accentColor(app: "Aquamarine")).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
                for (surface, rgb) in surfaces { XCTAssertGreaterThanOrEqual(contrast(accent, UIColor(rgb: rgb)), 4.5, "\(paper.name) accent on \(surface)") }
                XCTAssertGreaterThanOrEqual(contrast(UIColor(portalOnAccent).resolvedColor(with: UITraitCollection(userInterfaceStyle: style)), accent), 4.5, "Text on the \(paper.name) accent")
            }
        }
    }

    func testUnknownPaperFallsBackToWhite() {
        XCTAssertEqual(ReaderPaper.named("Parchment").name, "White")
    }

    private func luminance(_ color: UIColor) -> Double {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        func channel(_ value: CGFloat) -> Double { let v = Double(value); return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }
    private func contrast(_ a: UIColor, _ b: UIColor) -> Double {
        let (high, low) = (max(luminance(a), luminance(b)), min(luminance(a), luminance(b)))
        return (high + 0.05) / (low + 0.05)
    }
    private func blend(_ top: UIColor, over bottom: UIColor, alpha: CGFloat) -> UIColor {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0, r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        top.getRed(&r1, green: &g1, blue: &b1, alpha: &a1); bottom.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(red: r1 * alpha + r2 * (1 - alpha), green: g1 * alpha + g2 * (1 - alpha), blue: b1 * alpha + b2 * (1 - alpha), alpha: 1)
    }
}
