import SwiftUI
import KeyguardShared

struct WatchtowerAlertsView: View {
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(WatchtowerModel.self) private var watchtowerModel

    /// When non-nil this view renders a navigation-stack entry's inline snapshot
    /// instead of the single-slot observation, and skips owning the observation.
    var entry: ScreenEntrySnapshot? = nil

    private var snapshot: WatchtowerAlertsSnapshot { entry?.watchtowerAlerts ?? watchtowerModel.watchtowerAlerts }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.watchtowerAlertsEmptyTitle, systemImage: "checkmark.shield")
            } description: {
                Text(L10n.watchtowerAlertsEmptyText)
            }
        } content: {
            list
        }
        .navigationTitle(L10n.watchtowerAlertsNewTitle)
        .toolbar { markAllToolbar }
        .observing(
            enabled: entry == nil,
            start: { watchtowerModel.startWatchtowerAlertsObservation() },
            stop: { watchtowerModel.stopWatchtowerAlertsObservation() }
        )
    }

    private var list: some View {
        List {
            ForEach(
                snapshotListSections(
                    snapshot.items, id: { $0.id },
                    sectionTitle: {
                        $0.kind == WatchtowerAlertItemKind.section ? $0.title : nil
                    })
            ) { section in
                Section {
                    ForEach(section.items, id: \.id) { item in
                        if item.canClick {
                            Button {
                                if let entry {
                                    navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: item.id)
                                } else {
                                    watchtowerModel.invokeWatchtowerAlertItem(id: item.id)
                                }
                            } label: {
                                HStack {
                                    row(item)
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.forward")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.tertiary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        } else {
                            row(item)
                        }
                    }
                } header: {
                    if let title = section.title { Text(title) }
                }
            }
        }
    }

    /// "Mark all as read" lives in the toolbar; the shared producer marks every
    /// alert read and pops this list. Hidden when there are no alerts.
    @ToolbarContentBuilder
    private var markAllToolbar: some ToolbarContent {
        if snapshot.canMarkAllRead {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    if let entry {
                        navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: "markAllRead")
                    } else {
                        watchtowerModel.markAllWatchtowerAlertsRead()
                    }
                } label: {
                    Label(L10n.watchtowerMarkAllAsReadTitle, systemImage: "checkmark.circle")
                }
            }
        }
    }

    private func row(_ item: WatchtowerAlertItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.shield")
                .foregroundStyle(.orange)
                .frame(width: 24)
                .overlay(alignment: .topTrailing) {
                    if !item.read {
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 8, height: 8)
                            .offset(x: 4, y: -4)
                    }
                }
                // Decorative severity glyph; the alert text carries the meaning.
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title.isEmpty ? L10n.credentialExchangeImportUntitled : item.title)
                    .font(.body)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !item.text.isEmpty {
                    Text(item.text)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let date = item.date, !date.isEmpty {
                    Text(date)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityValue(item.read ? "" : L10n.watchtowerAlertsUnreadLabel)
    }
}
