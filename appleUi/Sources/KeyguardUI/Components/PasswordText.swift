import SwiftUI

/// Mirrors Compose's UTF-16 Char classification. Surrogate pairs stay plain;
/// combining marks and other non-letter BMP characters count as symbols.
enum PasswordCharacterStyle {
    case plain
    case digit
    case symbol

    init(_ scalar: Unicode.Scalar) {
        guard scalar.value <= 0xFFFF else {
            self = .plain
            return
        }
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter:
            self = .plain
        case .decimalNumber:
            self = .digit
        default:
            self = .symbol
        }
    }

    var color: Color? {
        switch self {
        case .plain: nil
        case .digit: .blue
        case .symbol: .red
        }
    }
}

func colorizedPassword(_ value: String, enabled: Bool = true) -> AttributedString {
    var text = AttributedString(value)
    guard enabled else { return text }

    // Apply contiguous runs without changing the source's Unicode scalars.
    let scalars = text.unicodeScalars
    var start = scalars.startIndex
    while start < scalars.endIndex {
        let style = PasswordCharacterStyle(scalars[start])
        var end = scalars.index(after: start)
        while end < scalars.endIndex, PasswordCharacterStyle(scalars[end]) == style {
            end = scalars.index(after: end)
        }
        if let color = style.color {
            text[start..<end].foregroundColor = color
        }
        start = end
    }
    return text
}

/// A single Text keeps native wrapping, truncation, selection and accessibility.
struct PasswordText: View {
    let value: String
    var colorize: Bool = true
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor

    init(_ value: String, colorize: Bool = true) {
        self.value = value
        self.colorize = colorize
    }

    var body: some View {
        Text(colorizedPassword(value, enabled: colorize && !differentiateWithoutColor))
    }
}
