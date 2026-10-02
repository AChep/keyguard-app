import SwiftUI
import KeyguardShared

struct SendDetailScreen: View {
    @State private var model: DetailSessionModel<ItemDetailTarget, SendDetailSnapshot, SendDetailSession>
    let target: ItemDetailTarget
    var showsNavigationTitle: Bool

    init(
        target: ItemDetailTarget,
        showsNavigationTitle: Bool = false,
        makeSession: @escaping (ItemDetailTarget) -> SendDetailSession
    ) {
        self.target = target
        self.showsNavigationTitle = showsNavigationTitle
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model, target: target) { detail, perform in
            #if os(macOS)
            if showsNavigationTitle {
                detailContent(detail, perform: perform)
                    .navigationTitle(detail.title.isEmpty ? L10n.credentialExchangeImportUntitled : detail.title)
            } else {
                detailContent(detail, perform: perform)
            }
            #else
            detailContent(detail, perform: perform)
            #endif
        }
    }

    private func detailContent(_ detail: SendDetailSnapshot, perform: SessionActions<SendDetailSession>) -> some View {
        SendDetailView(
            detail: detail,
            invoke: { id in perform { $0.invokeAction(id: id) } },
            copy: { perform { $0.sendCopy() } },
            share: { perform { $0.sendShare() } },
            edit: { perform { $0.sendEdit() } }
        )
    }
}
