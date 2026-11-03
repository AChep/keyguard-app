import SwiftUI
import KeyguardShared

/// One native menu control owns the entire linked URI/app row.
struct DetailUriRow: View {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void

    var body: some View {
        row
            .modifier(DetailUriDrag(url: item.dragUrl.flatMap(URL.init(string:))))
    }

    @ViewBuilder
    private var row: some View {
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
                iconKey: item.uriIcon?.iconKey, placeholder: nil,
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

/// Offers the row's link to other apps; Kotlin decides which links are draggable.
private struct DetailUriDrag: ViewModifier {
    let url: URL?

    func body(content: Content) -> some View {
        if let url {
            content
                .onDrag { NSItemProvider(object: url as NSURL) }
        } else {
            content
        }
    }
}
