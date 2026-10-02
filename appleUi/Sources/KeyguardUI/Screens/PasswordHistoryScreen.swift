import SwiftUI
import KeyguardShared

struct PasswordHistoryScreen: View {
    @State private var model: DetailSessionModel<String, PasswordHistorySnapshot, ListSession<PasswordHistorySnapshot>>
    let itemId: String

    init(itemId: String, makeSession: @escaping (String) -> ListSession<PasswordHistorySnapshot>) {
        self.itemId = itemId
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model, target: itemId) { snapshot, perform in
            VaultViewPasswordHistoryView(
                snapshot: snapshot,
                invokeItem: { id in perform { $0.invokeItemAction(id: id) } },
                invokeSelection: { id in perform { $0.invokeSelectionAction(id: id) } },
                invokeAction: { id in perform { $0.invokeAction(id: id) } },
                toggleSelection: { id in perform { $0.toggleSelection(itemId: id) } },
                clearSelection: { perform { $0.clearSelection() } }
            )
        }
        .navigationTitle(L10n.passwordhistoryHeaderTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}
