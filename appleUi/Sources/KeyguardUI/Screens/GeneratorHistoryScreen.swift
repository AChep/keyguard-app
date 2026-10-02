import SwiftUI
import KeyguardShared

struct GeneratorHistoryScreen: View {
    @State private var model: DetailSessionModel<Bool, GeneratorHistorySnapshot, ListSession<GeneratorHistorySnapshot>>

    init(makeSession: @escaping () -> ListSession<GeneratorHistorySnapshot>) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model) { snapshot, perform in
            GeneratorHistoryView(
                snapshot: snapshot,
                invokeItemAction: { id in perform { $0.invokeItemAction(id: id) } },
                invokeOption: { id in perform { $0.invokeAction(id: id) } },
                invokeSelectionAction: { id in perform { $0.invokeSelectionAction(id: id) }
                },
                toggleSelection: { id in perform { $0.toggleSelection(itemId: id) } },
                clearSelection: { perform { $0.clearSelection() } }
            )
        }
        .navigationTitle(L10n.generatorhistoryHeaderTitle)
    }
}
