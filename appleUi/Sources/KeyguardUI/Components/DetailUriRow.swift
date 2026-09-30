import SwiftUI
import KeyguardShared

/// One native menu control owns the entire linked URI/app row.
struct DetailUriRow: View {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void

    var body: some View {
        if item.actions.isEmpty {
            rowLabel
        } else {
            Menu {
                listActionMenuItems(actions: item.actions) { invoke($0) }
            } label: {
                rowLabel
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .frame(maxWidth: .infinity, alignment: .leading)
            .help(L10n.moreActions)
        }
    }

    private var rowLabel: some View {
        DetailRowLabel(
            title: item.title ?? "",
            subtitle: item.text,
            disclosure: item.actions.isEmpty ? nil : "ellipsis",
            favicon: FaviconView(
                url: item.uriIcon?.url, placeholder: nil,
                fallbackSymbol: fallbackSymbol, size: 20),
            colorizeTitle: item.colorize
        )
        .touchTarget()
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var fallbackSymbol: String {
        switch item.uriIcon?.kind {
        case .website: "globe"
        case .app: "app"
        default: "link"
        }
    }
}
