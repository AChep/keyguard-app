import SwiftUI
import KeyguardShared

struct WatchtowerScreen: View {
    @State private var model: DetailSessionModel<Bool, WatchtowerSnapshot, WatchtowerSession>

    init(makeSession: @escaping () -> WatchtowerSession) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model) {
            snapshot, perform in
            NavStackContainer(scope: "watchtower") {
                WatchtowerDashboard(
                    watchtower: snapshot,
                    invokeAction: { id in perform { $0.invokeWatchtowerAction(id: id) } },
                    invokeFilter: { id in perform { $0.invokeWatchtowerFilter(id: id) } },
                    clearFilters: { perform { $0.clearWatchtowerFilters() } }
                )
            }
        }
    }
}
