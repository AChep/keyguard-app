import SwiftUI
import KeyguardShared

/// The searchable items of a picker of another item.
struct CipherLinkPickerList: View {
    let query: String
    let queryRevision: Int32
    let items: [CipherLinkPickerItemSnapshot]
    let emptyText: String
    let setQuery: (String) -> Void
    let select: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            BridgedTextField(
                label: L10n.vaultMainSearchPlaceholder,
                text: query,
                textRevision: queryRevision,
                secure: false,
                send: setQuery,
                style: .roundedBorder
            )
            if items.isEmpty {
                Text(emptyText)
                    .foregroundStyle(.secondary)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(items, id: \.id) { item in
                            Button {
                                select(item.id)
                            } label: {
                                HStack {
                                    CipherLinkPickerTitle(title: item.title, text: item.text)
                                    Spacer()
                                    Image(systemName: "chevron.forward")
                                }
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding()
    }
}

/// The title and the optional subtitle of a picker row.
struct CipherLinkPickerTitle: View {
    let title: String
    let text: String?

    var body: some View {
        VStack(alignment: .leading) {
            Text(title).foregroundStyle(.primary)
            if let text, !text.isEmpty {
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}
