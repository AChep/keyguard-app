import SwiftUI
import KeyguardShared

struct SshAgentHistoryScreen: View {
    @State private var model: DetailSessionModel<SshAgentHistoryTarget, SshAgentHistorySnapshot, SshAgentHistorySession>
    let target: SshAgentHistoryTarget

    init(cipherId: String? = nil, makeSession: @escaping (SshAgentHistoryTarget) -> SshAgentHistorySession) {
        target = SshAgentHistoryTarget(cipherId: cipherId)
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model, target: target) { snapshot, _ in
            SshAgentHistoryView(snapshot: snapshot)
        }
    }
}
