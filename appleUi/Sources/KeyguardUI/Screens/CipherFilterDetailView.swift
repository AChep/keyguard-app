import SwiftUI
import KeyguardShared

struct CipherFilterDetailView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    private var detail: CipherFilterDetailSnapshot {
        entry.cipherFilterDetail ?? CipherFilterDetailSnapshot.companion.empty
    }

    var body: some View {
        Group {
            if !detail.loaded {
                LoadingIndicator()
            } else {
                List {
                    ForEach(
                        snapshotListSections(
                            detail.items, id: { $0.id },
                            sectionTitle: {
                                $0.isSection ? $0.title : nil
                            })
                    ) { section in
                        Section {
                            ForEach(section.items, id: \.id) { item in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title)
                                    if let text = item.text, !text.isEmpty {
                                        Text(text)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        } header: {
                            if let title = section.title { Text(title) }
                        }
                    }
                }
            }
        }
        .navigationTitle(detail.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if !detail.actions.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        listActionMenuItems(actions: detail.actions) {
                            navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: $0)
                        }
                    } label: {
                        Label(L10n.moreActions, systemImage: "ellipsis.circle")
                    }
                }
            }
        }
    }
}
