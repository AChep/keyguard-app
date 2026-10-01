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

public extension Color {
    init(platform color: PlatformColor) {
        #if os(macOS)
        self.init(nsColor: color)
        #else
        self.init(uiColor: color)
        #endif
    }

    /// Decodes a packed sRGB ARGB value (Compose's `Color.toArgb()`).
    internal init(argb: UInt32) {
        self.init(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255.0,
            green: Double((argb >> 8) & 0xFF) / 255.0,
            blue: Double(argb & 0xFF) / 255.0,
            opacity: Double((argb >> 24) & 0xFF) / 255.0
        )
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

public extension PlatformColor {
    /// Device-RGB components, normalized 0...1.
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

    static var platformAccent: PlatformColor {
        #if os(macOS)
        return .controlAccentColor
        #else
        return .tintColor
        #endif
    }

    static var platformDanger: PlatformColor { .systemRed }

    static var platformLabel: PlatformColor {
        #if os(macOS)
        return .textColor
        #else
        return .label
        #endif
    }

    static var platformSeparator: PlatformColor {
        #if os(macOS)
        return .separatorColor
        #else
        return .separator
        #endif
    }

    static var platformControlBackground: PlatformColor {
        #if os(macOS)
        return .controlBackgroundColor
        #else
        return .secondarySystemBackground
        #endif
    }
}

public extension Image {
    init(platform image: PlatformImage) {
        #if os(macOS)
        self.init(nsImage: image)
        #else
        self.init(uiImage: image)
        #endif
    }
}

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
