import SwiftUI

struct FaviconView: View {
    let url: String?
    let placeholder: String?
    var fallbackSymbol: String = "globe"
    var size: CGFloat = 24
    /// Per-item accent (the cipher's color); tints the initials / symbol placeholder
    /// so icon-less rows match the Compose avatar. `nil` keeps the neutral default.
    var accent: Color? = nil

    private var cornerRadius: CGFloat { size * 0.22 }

    var body: some View {
        Group {
            if let url, let parsed = URL(string: url) {
                AsyncImage(url: parsed) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFit()
                    } else {
                        // Loading and failure both show the placeholder, keeping
                        // the row layout stable until the image resolves.
                        placeholder(content: phase.error == nil ? .loading : .failed)
                    }
                }
            } else {
                placeholder(content: .failed)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        // The favicon is a decorative leading icon; the row's title/subtitle carry
        // the semantics, so hide it from VoiceOver to avoid an unlabeled image
        // element (or the raw initials letters) adding noise to each row.
        .accessibilityHidden(true)
    }

    private enum PlaceholderContent { case loading, failed }

    /// The placeholder fill — a subtle tint of the item accent, or the neutral
    /// quaternary fill when there is no accent.
    private var placeholderFill: AnyShapeStyle {
        if let accent { return AnyShapeStyle(accent.opacity(0.18)) }
        return AnyShapeStyle(.quaternary)
    }

    @ViewBuilder
    private func placeholder(content: PlaceholderContent) -> some View {
        if let placeholder, !placeholder.isEmpty {
            Text(placeholder)
                .font(.system(size: size * 0.42, weight: .medium))
                .foregroundStyle(accent ?? .secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(width: size, height: size)
                .background(placeholderFill, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else if let accent {
            // Rich rows: a subtle accent box behind the cipher-type symbol.
            Image(systemName: fallbackSymbol)
                .font(.system(size: size * 0.5))
                .foregroundStyle(accent)
                .frame(width: size, height: size)
                .background(accent.opacity(0.18), in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            Image(systemName: fallbackSymbol)
                .font(.system(size: size * 0.5))
                .foregroundStyle(.secondary)
                .frame(width: size, height: size)
                .background(placeholderFill, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }
}
