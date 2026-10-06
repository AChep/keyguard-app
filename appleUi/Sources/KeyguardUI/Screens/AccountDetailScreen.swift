import SwiftUI
import KeyguardShared

struct AccountDetailScreen: View {
    @State private var model: DetailSessionModel<String, AccountDetailSnapshot, AccountDetailSession>
    let accountId: String

    init(accountId: String, makeSession: @escaping (String) -> AccountDetailSession) {
        self.accountId = accountId
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model, target: accountId) { detail, perform in
            AccountDetailView(
                detail: detail,
                invoke: { id in perform { $0.invokeAction(id: id) } }
            )
        }
    }
}
