import SwiftUI
import KeyguardShared

struct StackServiceDirectoryView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot
    @State private var query = ""

    private var currentEntry: ScreenEntrySnapshot {
        // NavigationStack may retain its destination value after a detail push.
        // Read the observable stack here so query/result updates invalidate this
        // screen independently of the presenting navigation closure.
        navigationModel.navStacks.values.joined().first { $0.instanceId == entry.instanceId } ?? entry
    }

    private var snapshot: ServiceDirectorySnapshot {
        currentEntry.serviceDirectory ?? ServiceDirectorySnapshot.companion.empty
    }

    private var searchQuery: Binding<String> {
        Binding(
            get: { query },
            set: { text in
                query = text
                // A restored native search field can clear to the value already
                // held by @State. Forward every edit, including that clear.
                navigationModel.setEntryListQuery(instanceId: entry.instanceId, text: text)
            }
        )
    }

    var body: some View {
        ServiceDirectoryView(
            snapshot: snapshot,
            open: { navigationModel.openEntryListItem(instanceId: entry.instanceId, itemId: $0) },
            retry: { navigationModel.retryEntryList(instanceId: entry.instanceId) }
        )
        .navigationTitle(currentEntry.title)
        #if os(macOS)
        .toolbar {
            ToolbarItem(placement: .automatic) {
                NativeListSearchField(text: searchQuery, prompt: L10n.settingssearchHeaderTitle)
                .frame(minWidth: 180, idealWidth: 240, maxWidth: 300)
            }
        }
        #else
        .listSearchable(text: searchQuery, prompt: Text(L10n.settingssearchHeaderTitle))
        #endif
        .onChange(of: snapshot.queryRevision) { _, _ in
            query = snapshot.query
        }
        .onAppear {
            query = snapshot.query
        }
    }

}
