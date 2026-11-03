import SwiftUI

struct FaviconView: View {
    /// An `iconKey` of a snapshot; loaded by the shared core.
    let iconKey: String?
    let placeholder: String?
    var fallbackSymbol: String = "globe"
    var size: CGFloat = 24
    /// Per-item accent (the cipher's color); tints the initials / symbol placeholder
    /// so icon-less rows match the Compose avatar. `nil` keeps the neutral default.
    var accent: Color? = nil

    @Environment(\.iconImageLoader) private var loader
    @State private var loaded: LoadedIcon?

    private var cornerRadius: CGFloat { size * 0.22 }

    var body: some View {
        Group {
            if let image {
                Image(decorative: image.cgImage, scale: 1)
                    .resizable()
                    .scaledToFit()
            } else {
                // Loading and failure both show the placeholder, keeping
                // the row layout stable until the image resolves.
                placeholder(content: isLoading ? .loading : .failed)
            }
        }
        .task(id: iconKey) {
            guard let iconKey, let loader else { return }
            // Returns at once for a cached icon; keeping it in the state
            // also keeps it on screen if the cache evicts it.
            let image = await loader.image(for: iconKey)
            loaded = LoadedIcon(key: iconKey, image: image)
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        // The favicon is a decorative leading icon; the row's title/subtitle carry
        // the semantics, so hide it from VoiceOver to avoid an unlabeled image
        // element (or the raw initials letters) adding noise to each row.
        .accessibilityHidden(true)
    }

    private var image: IconImage? {
        guard let iconKey else { return nil }
        if let cached = loader?.cachedImage(for: iconKey) {
            return cached
        }
        return loaded?.key == iconKey ? loaded?.image : nil
    }

    private var isLoading: Bool {
        iconKey != nil && loader != nil && loaded?.key != iconKey
    }

    private struct LoadedIcon {
        let key: String
        let image: IconImage?
    }

    private enum PlaceholderContent { case loading, failed }

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
