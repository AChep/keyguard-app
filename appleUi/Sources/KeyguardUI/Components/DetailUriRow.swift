import SwiftUI
import KeyguardShared

/// One native menu control owns the entire linked URI/app row.
struct DetailUriRow: View {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void

    var body: some View {
        if item.uriOverrides.isEmpty {
            uriRow
        } else {
            VStack(alignment: .leading, spacing: 8) {
                uriRow
                ForEach(Array(item.uriOverrides.enumerated()), id: \.offset) { _, override in
                    // Aligns with the URI's title, past its 20 pt icon.
                    DetailUriOverrideRow(override: override, invoke: invoke)
                        .padding(.leading, 32)
                }
            }
        }
    }

    private var uriRow: some View {
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

/// A URL override that matches the URI: its name and the expanded URL, or the
/// reason the URL could not be built.
private struct DetailUriOverrideRow: View {
    let override: VaultUriOverrideSnapshot
    let invoke: (String) -> Void

    var body: some View {
        if override.actions.isEmpty {
            label
        } else {
            Menu {
                listActionMenuItems(actions: override.actions) { invoke($0) }
            } label: {
                label
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .frame(maxWidth: .infinity, alignment: .leading)
            .help(L10n.moreActions)
        }
    }

    private var label: some View {
        HStack(spacing: 12) {
            Image(systemName: override.error ? "exclamationmark.triangle" : "terminal")
                .foregroundStyle(override.error ? Color.red : Color.secondary)
                .frame(width: 20)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(override.title)
                    .font(.subheadline)
                Text(override.text)
                    .font(.caption.monospaced())
                    .foregroundStyle(override.error ? Color.red : Color.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if !override.actions.isEmpty {
                Image(systemName: "ellipsis")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .touchTarget()
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
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
