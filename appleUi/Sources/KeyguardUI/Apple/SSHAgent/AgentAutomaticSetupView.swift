#if os(macOS)
import SwiftUI

struct AgentAutomaticSetupView: View {
    let agent: AgentSetupWizardState.Agent
    let enabled: Bool
    @State private var mode: AgentSetupWizardState.Mode?

    private static let executable = Bundle.main.url(forResource: "keyguard", withExtension: nil)?.path

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label(L10n.agentAutoSetupTitle, systemImage: "terminal")
                        .font(.headline)
                    Spacer()
                    if Self.executable != nil {
                        DetailIconButton(title: L10n.agentAutoSetupUndoAction, systemImage: "arrow.uturn.backward") {
                            mode = .undo
                        }
                    }
                }
                Text(Self.executable == nil ? L10n.agentAutoSetupUnavailable : L10n.agentAutoSetupIntro)
                    .foregroundStyle(.secondary)
                if !enabled {
                    Text(L10n.agentAutoSetupEnableRequired(agent.protocolName))
                        .foregroundStyle(.secondary)
                }
                Button {
                    if enabled { mode = .configure }
                } label: {
                    Text(L10n.agentAutoSetupConfigureAction).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(Self.executable == nil || !agent.ready || !enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(8)
        }
        .sheet(item: $mode) { mode in
            if let executable = Self.executable {
                AgentSetupWizard(agent: agent, mode: mode, executable: executable, enabled: enabled)
            }
        }
    }
}
#endif
