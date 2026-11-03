import SwiftUI
import KeyguardShared

struct CliAgentHistoryScreen: View {
    @State private var model: DetailSessionModel<CliAgentHistoryTarget, CliAgentHistorySnapshot, CliAgentHistorySession>
    let target: CliAgentHistoryTarget
    let clear: (String) -> Void

    init(
        client: String? = nil,
        cipherId: String? = nil,
        makeSession: @escaping (CliAgentHistoryTarget) -> CliAgentHistorySession,
        clear: @escaping (String) -> Void
    ) {
        target = CliAgentHistoryTarget(client: client, cipherId: cipherId)
        self.clear = clear
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model, target: target) { snapshot, _ in
            CliAgentHistoryView(snapshot: snapshot, clear: { target.client.map(clear) })
        }
    }
}

/// The settings entry that opens one client's request log in a sheet.
struct CliAgentHistorySection: View {
    @Environment(SessionFactory.self) private var sessions
    @State private var showsHistory = false
    let client: String
    let searchTarget: SettingsSearchTarget

    var body: some View {
        Section {
            Button {
                showsHistory = true
            } label: {
                Label(L10n.prefItemAgentHistoryTitle, systemImage: "clock.arrow.circlepath")
            }
            .settingsSearchTarget(searchTarget)
            .sheet(isPresented: $showsHistory) {
                ModalSheet(title: L10n.prefItemAgentHistoryTitle) {
                    CliAgentHistoryScreen(
                        client: client,
                        makeSession: sessions.makeCliAgentHistorySession, clear: sessions.clearCliAgentHistory
                    )
                }
            }
        }
    }
}
