import SwiftUI
import KeyguardShared

struct StackCipherFiltersView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot
    @State private var query = ""

    private var snapshot: CipherFiltersListSnapshot {
        entry.cipherFilters ?? CipherFiltersListSnapshot.companion.empty
    }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.customfiltersHeaderTitle, systemImage: "line.3.horizontal.decrease.circle")
            } description: {
                Text(L10n.customfiltersEmptyHint)
            }
        } content: {
            List {
                ForEach(snapshot.items, id: \.id) { item in
                    Button {
                        navigationModel.openEntryListItem(instanceId: entry.instanceId, itemId: item.id)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "line.3.horizontal.decrease.circle")
                                .foregroundStyle(.tint)
                                .frame(width: 24)
                            Text(item.name).lineLimit(1)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.forward")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 2)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle(entry.title)
        .listSearchable(text: $query, prompt: Text(L10n.customfiltersSearchPlaceholder))
        .bridgedText(
            $query,
            remote: snapshot.query,
            remoteRevision: snapshot.queryRevision,
            send: { navigationModel.setEntryListQuery(instanceId: entry.instanceId, text: $0) }
        )
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}
