import SwiftUI

/// Prepares color runs and grapheme boundaries once, outside animation updates.
struct ConcealedTextValue {
    private let text: AttributedString
    private let boundaries: [AttributedString.Index]

    init(_ value: String, colorize: Bool = false) {
        text = colorizedPassword(value, enabled: colorize)
        boundaries = Array(text.characters.indices) + [text.endIndex]
    }

    func display(progress: Double) -> AttributedString {
        let progress = min(max(progress, 0), 1)
        let length = boundaries.count - 1
        let realCount = Int((Double(length) * progress).rounded())
        let minShownLength = max(
            Int(((1 - progress) * 8).rounded()),
            min(length, 8)
        )
        var result = AttributedString(text[text.startIndex..<boundaries[realCount]])
        let padding = minShownLength - realCount
        if padding > 0 {
            // Generated masks are plain, even when the source contains a red bullet.
            result.append(AttributedString(String(repeating: "•", count: padding)))
        }
        return result
    }
}

struct AnimatedConcealedText: View {
    let text: String
    /// `true` shows dots (animates toward concealed); `false` reveals the value.
    let masked: Bool
    var monospace: Bool = true
    var lineLimit: Int? = nil
    var colorize: Bool = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor

    var body: some View {
        let progress: Double = masked ? 0 : 1
        ConcealTextContent(
            progress: progress,
            value: ConcealedTextValue(text, colorize: colorize && !differentiateWithoutColor),
            monospace: monospace,
            lineLimit: lineLimit
        )
        .animation(reduceMotion ? nil : .spring(response: 0.4, dampingFraction: 1.0), value: progress)
    }
}

private struct ConcealTextContent: View, @MainActor Animatable {
    var progress: Double
    let value: ConcealedTextValue
    let monospace: Bool
    var lineLimit: Int?

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        Text(value.display(progress: progress))
            .font(monospace ? .body.monospaced() : .body)
            .textSelection(.enabled)
            .lineLimit(lineLimit)
    }
}
