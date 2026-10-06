import SwiftUI
import KeyguardShared

struct SendListScreen: View {
    @State private var model: DetailSessionModel<Bool, SendListSnapshot, SendListSession>

    init(makeSession: @escaping () -> SendListSession) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model) { snapshot, perform in
            SendListView(
                snapshot: snapshot,
                actions: SendListActions(
                    setQuery: { text in perform { $0.setQuery(text: text) } },
                    invokeFilter: { id in perform { $0.invokeFilter(id: id) } },
                    invokeSort: { id in perform { $0.invokeSort(id: id) } },
                    clearFilters: { perform { $0.clearFilters() } },
                    clearSort: { perform { $0.clearSort() } },
                    toggleSelection: { id in perform { $0.toggleSelection(itemId: id) } },
                    invokeSelectionAction: { id in perform { $0.invokeSelectionAction(id: id) } },
                    invokeAction: { id in perform { $0.invokeAction(id: id) } },
                    clearSelection: { perform { $0.clearSelection() } },
                    dropFile: { url in
                        perform { session in
                            let file = url.fileNameAndSize
                            session.dropFile(uri: url.absoluteString, name: file.name, size: file.size)
                        }
                    }
                )
            )
        }
    }
}
