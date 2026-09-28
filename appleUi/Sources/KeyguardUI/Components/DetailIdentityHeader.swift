import SwiftUI

/// Item identity inside a grouped form, adapting to the platform's detail density.
struct DetailIdentityHeader<Icon: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var icon: (CGFloat) -> Icon

    var body: some View {
        #if os(macOS)
        HStack(spacing: 12) {
            icon(40)
            identityLabels
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
        #else
        VStack(spacing: 12) {
            icon(56)
            identityLabels
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        #endif
    }

    private var identityLabels: some View {
        VStack(alignment: labelAlignment, spacing: 4) {
            titleLabel
            if let subtitle, !subtitle.isEmpty, subtitle != displayTitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
        }
    }

    private var labelAlignment: HorizontalAlignment {
        #if os(macOS)
        .leading
        #else
        .center
        #endif
    }

    private var titleLabel: some View {
        Text(displayTitle)
            .font(.title2.bold())
            .fixedSize(horizontal: false, vertical: true)
            .textSelection(.enabled)
            .accessibilityAddTraits(.isHeader)
            // AppKit's selectable heading otherwise retains its previous AX value.
            .id(displayTitle)
    }

    private var displayTitle: String {
        title.isEmpty ? L10n.credentialExchangeImportUntitled : title
    }
}
