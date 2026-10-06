import SwiftUI
import KeyguardShared

struct DownloadsView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    private var snapshot: DownloadsSnapshot {
        entry.downloads ?? DownloadsSnapshot.companion.empty
    }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.downloadsEmptyLabel, systemImage: "arrow.down.circle")
            }
        } content: {
            List {
                ForEach(
                    snapshotListSections(
                        snapshot.items, id: { $0.id },
                        sectionTitle: {
                            $0.isSection ? $0.title : nil
                        })
                ) { section in
                    Section {
                        ForEach(section.items, id: \.id) { row($0) }
                    } header: {
                        if let title = section.title { Text(title) }
                    }
                }
            }
        }
        .navigationTitle(L10n.downloads)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    @ViewBuilder
    private func row(_ item: DownloadItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "doc")
                .foregroundStyle(.secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).lineLimit(1)
                statusLine(item)
            }
            Spacer(minLength: 8)
            trailing(item)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .contextMenu {
            listActionMenuItems(actions: item.actions) {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: $0)
            }
        }
    }

    @ViewBuilder
    private func statusLine(_ item: DownloadItemSnapshot) -> some View {
        HStack(spacing: 6) {
            if let size = item.size, !size.isEmpty {
                Text(size)
            }
            if item.statusKind == "FAILED" {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                    .accessibilityLabel(L10n.fileStatusDownloadingFailed)
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    @ViewBuilder
    private func trailing(_ item: DownloadItemSnapshot) -> some View {
        switch item.statusKind {
        case "LOADING":
            if let d = item.downloaded?.int64Value, let t = item.total?.int64Value, t > 0 {
                ProgressView(value: Double(d), total: Double(t)).frame(width: 60)
            } else {
                ProgressView()
            }
        case "DOWNLOADED":
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                .accessibilityLabel(L10n.fileStatusDownloaded)
        case "PENDING":
            Image(systemName: "clock").foregroundStyle(.secondary)
        default:
            if !item.actions.isEmpty {
                Menu {
                    listActionMenuItems(actions: item.actions) {
                        navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: $0)
                    }
                } label: {
                    Label(L10n.moreActions, systemImage: "ellipsis.circle")
                        .foregroundStyle(.secondary)
                        .touchTarget()
                }
                .labelStyle(.iconOnly)
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
            }
        }
    }
}
