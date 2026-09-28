import SwiftUI

/// The same leading icon, text hierarchy, and disclosure treatment for detail links.
struct DetailRowLabel: View {
    var systemImage: String? = nil
    let title: String
    var subtitle: String? = nil
    var disclosure: String? = nil
    var favicon: FaviconView? = nil
    var colorizeTitle = false

    var body: some View {
        HStack(spacing: 12) {
            if let favicon {
                favicon
            } else if let systemImage {
                Image(systemName: systemImage)
                    .foregroundStyle(.secondary)
                    .frame(width: 20)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 4) {
                PasswordText(title, colorize: colorizeTitle)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if let disclosure {
                Image(systemName: disclosure)
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
