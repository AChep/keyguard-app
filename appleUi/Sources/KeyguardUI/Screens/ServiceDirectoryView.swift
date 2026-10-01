import SwiftUI
import KeyguardShared

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
            ForEach(
                snapshotListSections(
                    snapshot.items, id: { $0.id },
                    sectionTitle: {
                        $0.kind == ServiceDirectoryItemKind.section ? $0.name : nil
                    })
            ) { section in
                Section {
                    ForEach(section.items, id: \.id) { item in
                        Button {
                            open(item.id)
                        } label: {
                            row(item)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    if let title = section.title { Text(title) }
                }
            }
        }
        .scrollsToTop(
            onChangeOf: snapshot.resultQuery,
            topId: snapshot.items.first(where: { $0.kind != ServiceDirectoryItemKind.section })?.id
        )
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
        if let raw, let iconURL = URL(string: raw) {
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
}
