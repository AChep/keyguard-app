import SwiftUI
import KeyguardShared

/// A contiguous run of directory rows under a single SECTION marker, used to
/// render native `Section` headers from the flat snapshot.
struct ServiceDirectoryGroup: Identifiable {
    let id: String
    /// The section header title, taken from the marker's `name`. Empty for the
    /// leading group of rows that precede any SECTION marker.
    let title: String
    var items: [ServiceDirectoryItemSnapshot]
}

/// A native renderer of the shared list contract. The navigation entry owns its lifetime.
struct ServiceDirectoryView: View {
    let snapshot: ServiceDirectorySnapshot
    let open: (String) -> Void
    let retry: () -> Void

    var body: some View {
        Group {
            if snapshot.status == ServiceDirectoryLoadStatus.failed {
                ContentUnavailableView {
                    Label(L10n.errorFailedUnknown, systemImage: "exclamationmark.triangle")
                } actions: {
                    Button(L10n.retry, action: retry)
                }
            } else if snapshot.status == ServiceDirectoryLoadStatus.loading {
                LoadingIndicator()
            } else if snapshot.items.isEmpty && !snapshot.searching {
                ContentUnavailableView.search(text: snapshot.resultQuery)
            } else {
                list
            }
        }
        .overlay(alignment: .top) {
            if snapshot.searching { ProgressView().controlSize(.small).padding(8) }
        }
    }

    private var list: some View {
        List {
            ForEach(Self.groups(snapshot.items)) { group in
                Section {
                    ForEach(group.items, id: \.id) { item in
                        Button {
                            open(item.id)
                        } label: {
                            row(item)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    if !group.title.isEmpty { Text(group.title) }
                }
            }
        }
        .scrollsToTop(onChangeOf: snapshot.resultQuery, topId: snapshot.items.first?.id)
    }

    private func row(_ item: ServiceDirectoryItemSnapshot) -> some View {
        HStack(spacing: 12) {
            favicon(item.faviconUrl)
            Text(
                highlightedText(
                    item.name,
                    utf16Ranges: item.highlights.compactMap { span in
                        guard span.start >= 0, span.endExclusive >= span.start else { return nil }
                        return Int(span.start)..<Int(span.endExclusive)
                    }))
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private func favicon(_ raw: String?) -> some View {
        if let iconURL = Self.faviconImageURL(raw) {
            AsyncImage(url: iconURL) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFit()
                } else {
                    placeholderIcon
                }
            }
            .frame(width: 24, height: 24)
            .clipShape(Circle())
        } else {
            placeholderIcon.frame(width: 24, height: 24)
        }
    }

    private var placeholderIcon: some View {
        Image(systemName: "globe").foregroundStyle(.secondary)
    }

    static func groups(_ items: [ServiceDirectoryItemSnapshot]) -> [ServiceDirectoryGroup] {
        var result: [ServiceDirectoryGroup] = []
        var current = ServiceDirectoryGroup(id: "ungrouped", title: "", items: [])
        for item in items {
            if item.kind == ServiceDirectoryItemKind.section {
                if !current.items.isEmpty { result.append(current) }
                current = ServiceDirectoryGroup(id: item.id, title: item.name, items: [])
            } else {
                current.items.append(item)
            }
        }
        if !current.items.isEmpty { result.append(current) }
        return result
    }

    static func faviconImageURL(_ raw: String?) -> URL? {
        guard let raw, !raw.isEmpty else { return nil }
        let host = URL(string: raw)?.host ?? URL(string: "https://\(raw)")?.host
        guard let host, !host.isEmpty else { return nil }
        return URL(string: "https://icons.duckduckgo.com/ip3/\(host).ico")
    }
}
