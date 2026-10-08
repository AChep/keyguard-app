import SwiftUI

struct VaultRowIcon: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let row: VaultRow
    let colorScheme: ColorScheme

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            // Larger status symbols need their own space outside the favicon.
            VStack(spacing: 2) {
                favicon
                badges
            }
            .fixedSize()
        } else {
            favicon.overlay(alignment: .bottomTrailing) {
                badges.offset(x: 3, y: 3)
            }
        }
    }

    private var favicon: some View {
        FaviconView(
            url: row.iconUrl,
            placeholder: row.iconInitials,
            fallbackSymbol: row.typeSymbol ?? "key",
            size: 30,
            accent: vaultAccentColor(
                colorScheme == .dark ? row.accentDarkArgb : row.accentLightArgb
            )
        )
    }

    @ViewBuilder
    private var badges: some View {
        // Keep status meanings available to VoiceOver in both layouts.
        let glyphs: [(name: String, label: String)] = {
            var g: [(String, String)] = []
            if row.flags.contains(.favourite) { g.append(("star.fill", L10n.homeFavoritesLabel)) }
            if row.flags.contains(.reprompt) { g.append(("lock.fill", L10n.filterAuthRepromptItems)) }
            if row.flags.contains(.attachments) { g.append(("paperclip", L10n.attachments)) }
            return g
        }()
        if !glyphs.isEmpty {
            HStack(spacing: 1) {
                ForEach(glyphs, id: \.name) { glyph in
                    Image(systemName: glyph.name)
                        .font(.caption2.weight(.bold))
                        .imageScale(.small)
                        .foregroundStyle(glyph.name == "star.fill" ? Color.yellow : Color.secondary)
                        .accessibilityLabel(glyph.label)
                }
            }
            .padding(2)
            .background(.background, in: Capsule())
            .overlay(Capsule().strokeBorder(.quaternary, lineWidth: 0.5))
        }
    }
}
