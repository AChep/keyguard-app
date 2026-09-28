import SwiftUI

#if os(macOS)
import AppKit
public typealias PlatformColor = NSColor
public typealias PlatformFont = NSFont
public typealias PlatformImage = NSImage
#else
import UIKit
public typealias PlatformColor = UIColor
public typealias PlatformFont = UIFont
public typealias PlatformImage = UIImage
#endif

// MARK: - Color bridging

public extension Color {
    /// Cross-platform bridge from a platform color (NSColor / UIColor).
    init(platform color: PlatformColor) {
        #if os(macOS)
        self.init(nsColor: color)
        #else
        self.init(uiColor: color)
        #endif
    }

    var contrastingTextColor: Color {
        let (r, g, b, _) = PlatformColor(self).rgbaComponents()
        // WCAG relative luminance: linearize each sRGB component, then weight.
        func linearize(_ c: CGFloat) -> CGFloat {
            c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        let luminance = 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
        // Contrast ratio against black is (L + 0.05) / 0.05; against white it is
        // 1.05 / (L + 0.05). White wins exactly when L < sqrt(1.05*0.05) - 0.05
        // (≈ 0.179). Pick whichever yields the greater ratio.
        let contrastWithBlack = (luminance + 0.05) / 0.05
        let contrastWithWhite = 1.05 / (luminance + 0.05)
        return contrastWithBlack >= contrastWithWhite ? .black : .white
    }
}

// MARK: - PlatformColor helpers

public extension PlatformColor {
    /// Device-RGB components, normalized 0...1. Works on both NSColor and UIColor.
    func rgbaComponents() -> (red: CGFloat, green: CGFloat, blue: CGFloat, alpha: CGFloat) {
        #if os(macOS)
        let c = usingColorSpace(.deviceRGB) ?? self
        return (c.redComponent, c.greenComponent, c.blueComponent, c.alphaComponent)
        #else
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b, a)
        #endif
    }

    /// The system control-accent / tint color.
    static var platformAccent: PlatformColor {
        #if os(macOS)
        return .controlAccentColor
        #else
        return .tintColor
        #endif
    }

    /// The system "danger" red.
    static var platformDanger: PlatformColor { .systemRed }

    /// The primary label / text color.
    static var platformLabel: PlatformColor {
        #if os(macOS)
        return .textColor
        #else
        return .label
        #endif
    }

    /// The hairline separator color.
    static var platformSeparator: PlatformColor {
        #if os(macOS)
        return .separatorColor
        #else
        return .separator
        #endif
    }

    /// A subtle control / grouped-content background fill.
    static var platformControlBackground: PlatformColor {
        #if os(macOS)
        return .controlBackgroundColor
        #else
        return .secondarySystemBackground
        #endif
    }
}

// MARK: - Image bridging

public extension Image {
    /// Cross-platform bridge from a platform image (NSImage / UIImage).
    init(platform image: PlatformImage) {
        #if os(macOS)
        self.init(nsImage: image)
        #else
        self.init(uiImage: image)
        #endif
    }
}

// MARK: - Touch targets

public extension View {
    @ViewBuilder
    func touchTarget(_ minSize: CGFloat = 44) -> some View {
        #if os(iOS)
        self
            .frame(minWidth: minSize, minHeight: minSize)
            .contentShape(Rectangle())
        #else
        self
        #endif
    }
}
