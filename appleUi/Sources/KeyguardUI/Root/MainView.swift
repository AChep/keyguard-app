import SwiftUI
import KeyguardShared

public struct MainView: View {
    public init() {}

    @Environment(AccountsModel.self) private var accountsModel
    @Environment(AppPreferencesModel.self) private var preferencesModel

    public var body: some View {
        UnlockedNavigation { sections, selection, currentSection in
            NavigationSplitView {
                List(
                    selection: Binding<String?>(
                        get: { selection.wrappedValue },
                        set: { selection.wrappedValue = UnlockedNavigationSelection.section(for: $0, in: sections).key }
                    )
                ) {
                    ForEach(sections) { section in
                        navigationLabel(section)
                            .accessibilityLabel(section.title)
                            .help(section.title)
                            .tag(section.key)
                    }
                }
                .navigationTitle("Keyguard")
                // Sync status pinned below the section list; an inset (rather than
                // a trailing List row) keeps the rows from scrolling under it.
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    SyncStatusFooter(status: accountsModel.syncStatus)
                }
            } detail: {
                navSectionDetail(currentSection)
            }
            #if os(macOS)
            // Keep the window's customizable toolbar alive across both section
            // changes and pushes. A destination must not replace the NSToolbar
            // while AppKit is still laying out the outgoing screen's items.
            .toolbar(id: KeyguardToolbar.sharedId) {
                // EmptyView's toolbar conformance requires macOS 27. A nil item
                // keeps the persistent toolbar empty on earlier systems too.
                Optional<ToolbarItem<String, EmptyView>>.none
            }
            #endif
        }
    }

    @ViewBuilder
    private func navigationLabel(_ section: NavSection) -> some View {
        if preferencesModel.appPreferences.navLabel {
            Label(section.title, systemImage: section.systemImage)
                .labelStyle(.titleAndIcon)
        } else {
            Label(section.title, systemImage: section.systemImage)
                .labelStyle(.iconOnly)
        }
    }
}

struct SyncStatusFooter: View {
    let status: SyncStatusSnapshot

    private var lastSyncDate: Date? {
        guard let ms = status.lastSyncTimestampMs?.int64Value else { return nil }
        return Date(timeIntervalSince1970: Double(ms) / 1000.0)
    }

    var body: some View {
        if status.loaded {
            HStack(spacing: 8) {
                if status.errorCount > 0 {
                    Image(systemName: "exclamationmark.circle")
                        .foregroundStyle(.red)
                    Text(L10n.syncstatusStatusFailed)
                    countBadge(status.errorCount)
                } else if status.pendingCount > 0 {
                    ProgressView()
                        .controlSize(.small)
                    Text(L10n.syncstatusStatusSyncing)
                    countBadge(status.pendingCount)
                } else {
                    Image(systemName: "checkmark.circle")
                        .foregroundStyle(.green)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(L10n.syncstatusStatusUpToDate)
                        if let date = lastSyncDate {
                            Text(date, style: .relative)
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }

    @ViewBuilder
    private func countBadge(_ count: Int32) -> some View {
        Text("\(count)")
            .font(.caption2)
            .monospacedDigit()
            .foregroundStyle(.tertiary)
    }
}
