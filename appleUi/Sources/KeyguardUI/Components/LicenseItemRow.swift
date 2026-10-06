import SwiftUI

struct LicenseItemRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let name: String
    let version: String
    let license: String
    let url: String?

    var body: some View {
        let titleLayout =
            dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 6))

        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                titleLayout {
                    Text(name)
                        .font(.body.weight(.medium))
                        .fixedSize(horizontal: false, vertical: true)
                    if !version.isEmpty {
                        Text(version)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if !license.isEmpty {
                    Text(license)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let url, let link = URL(string: url) {
                Link(destination: link) {
                    Image(systemName: "arrow.up.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .touchTarget()
                }
                .buttonStyle(.borderless)
                .help(L10n.licenseOpenProjectWebsiteAction)
                .accessibilityLabel(L10n.licenseOpenProjectWebsiteAction)
            }
        }
        .compactControlRowInsets(4)
    }
}
