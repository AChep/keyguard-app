#if os(macOS)
import SwiftUI

/// The helper must run in Terminal; a child process would inherit the app sandbox.
struct AgentSetupWizard: View {
    @Environment(\.dismiss) private var dismiss
    @State private var state: AgentSetupWizardState
    @State private var confirmingDiscard = false
    let executable: String
    let enabled: Bool
    private var setupAllowed: Bool { enabled || state.mode == .undo || state.step == .check }

    init(agent: AgentSetupWizardState.Agent, mode: AgentSetupWizardState.Mode, executable: String, enabled: Bool) {
        self.executable = executable
        self.enabled = enabled
        _state = State(initialValue: AgentSetupWizardState(agent: agent, mode: mode))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    WizardHeader(
                        title: heading,
                        subtitle: subtitle,
                        systemImage: "terminal",
                        step: state.stepNumber,
                        stepCount: state.stepCount
                    )
                    .padding(.vertical, 8)
                }
                if setupAllowed {
                    switch state.step {
                    case .settings: settings
                    case .run: run
                    case .check: check
                    }
                } else {
                    Text(
                        L10n.agentAutoSetupEnableRequired(
                            state.agent.protocolName)
                    )
                    .foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(title)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button {
                    guard setupAllowed else { return }
                    if state.step == .check { dismiss() } else { state.next() }
                } label: {
                    Text(state.step == .check ? L10n.done : L10n.continue)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!state.canContinue || !setupAllowed)
                .padding()
                .background(.bar)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(
                        state.step == .run && state.mode == .configure ? L10n.additemKeyGeneratorBackTitle : L10n.cancel
                    ) {
                        switch state.back() {
                        case .stay: break
                        case .dismiss: dismiss()
                        case .confirmDiscard: confirmingDiscard = true
                        }
                    }
                }
            }
            .confirmationDialog(L10n.agentAutoSetupDiscardTitle, isPresented: $confirmingDiscard) {
                Button(L10n.agentAutoSetupDiscardAction, role: .destructive) { dismiss() }
                Button(L10n.cancel, role: .cancel) {}
            } message: {
                Text(L10n.agentAutoSetupDiscardMessage)
            }
        }
        .interactiveDismissDisabled(state.needsDiscardConfirmation)
        .frame(minWidth: 480, idealWidth: 540, minHeight: 520, idealHeight: 660)
    }

    private var settings: some View {
        Group {
            if state.agent.isSsh {
                Section {
                    Picker(
                        L10n.agentAutoSetupStepSettingsTitle,
                        selection: Binding(
                            get: { state.oneHost }, set: { state.setOneHost($0) }
                        )
                    ) {
                        Text(L10n.agentAutoSetupHostsAll).tag(false)
                        Text(L10n.agentAutoSetupHostsOne).tag(true)
                    }
                    .pickerStyle(.radioGroup)
                    if state.oneHost {
                        TextField(
                            L10n.agentAutoSetupHostLabel,
                            text: Binding(
                                get: { state.host }, set: { state.setHost($0) }
                            ))
                        if !state.host.isEmpty && !state.hostValid {
                            Text(L10n.agentAutoSetupHostError).foregroundStyle(.red)
                        }
                    }
                }
            }
            Section {
                ForEach(AgentSetupWizardState.shells, id: \.self) { shell in
                    Toggle(
                        shell == "powershell" ? "PowerShell 7" : shell.capitalized,
                        isOn: Binding(
                            get: { state.selectedShells.contains(shell) },
                            set: { state.setShell(shell, selected: $0) }
                        )
                    )
                    .toggleStyle(.checkbox)
                }
            }
        }
    }

    private var run: some View {
        Group {
            Section {
                Text(L10n.agentAutoSetupMacosShellNote).foregroundStyle(.secondary)
            }
            if state.mode == .configure, case .gpg(_, let command) = state.agent, let command {
                Section(L10n.agentAutoSetupMacosGpgPrepareTitle) {
                    Text(L10n.agentAutoSetupMacosGpgPrepareDetail).foregroundStyle(.secondary)
                    GpgAgentCommandBlock(text: command)
                }
            }
            Section(state.mode == .undo ? L10n.agentAutoSetupStepUndoTitle : L10n.agentAutoSetupStepRunTitle) {
                GpgAgentCommandBlock(text: state.command(executable: executable))
            }
        }
    }

    private var check: some View {
        Section {
            Label(L10n.agentAutoSetupExternalUnverifiedNote, systemImage: "info.circle")
                .foregroundStyle(.secondary)
            if state.mode == .configure {
                Text(L10n.agentAutoSetupExternalCheckText)
                Text(L10n.agentAutoSetupMacosShellNote).foregroundStyle(.secondary)
                GpgAgentCommandBlock(text: state.checkCommand)
                Text(L10n.agentAutoSetupStepCheckDetail).foregroundStyle(.secondary)
            }
        }
    }

    private var title: String {
        let name = state.agent.protocolName
        return state.mode == .undo ? L10n.agentAutoSetupWizardUndoTitle(name) : L10n.agentAutoSetupWizardTitle(name)
    }

    private var heading: String {
        switch state.step {
        case .settings: L10n.agentAutoSetupStepSettingsTitle
        case .run: state.mode == .undo ? L10n.agentAutoSetupStepUndoTitle : L10n.agentAutoSetupStepRunTitle
        case .check: state.mode == .undo ? L10n.agentAutoSetupStepFinishTitle : L10n.agentAutoSetupStepCheckTitle
        }
    }

    private var subtitle: String {
        switch state.step {
        case .settings: L10n.agentAutoSetupStepSettingsDetail
        case .run: state.mode == .undo ? L10n.agentAutoSetupStepUndoDetail : L10n.agentAutoSetupStepRunDetail
        case .check: ""
        }
    }
}

extension AgentSetupWizardState.Agent {
    var protocolName: String { isSsh ? L10n.protocolSsh : L10n.protocolGpg }
}
#endif
