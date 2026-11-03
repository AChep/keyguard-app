#if os(macOS)
import Foundation
import KeyguardShared

/// Native macOS uses an externally launched helper, so this state never reports an applied or verified result.
/// The commands come from the shared code, which the Flatpak wizard uses too.
struct AgentSetupWizardState {
    enum Agent {
        case ssh(socket: String)
        case gpg(home: String, preparationCommand: String?)

        var argument: String {
            switch self {
            case .ssh: "ssh"
            case .gpg: "gpg"
            }
        }

        /// The SSH socket or the GnuPG home.
        var endpoint: String {
            switch self {
            case .ssh(let socket): socket
            case .gpg(let home, _): home
            }
        }

        var isSsh: Bool {
            if case .ssh = self { return true }
            return false
        }

        var ready: Bool {
            if case .gpg(_, let command) = self { return command != nil }
            return true
        }
    }

    enum Mode: String, Identifiable {
        case configure, undo
        var id: String { rawValue }
    }

    enum Step: Int { case settings = 1, run, check }
    enum BackAction { case stay, dismiss, confirmDiscard }

    static let shells = ["bash", "zsh", "fish", "powershell"]
    let agent: Agent
    let mode: Mode
    private(set) var step: Step
    private(set) var selectedShells: Set<String> = []
    private(set) var oneHost = false
    private(set) var host = ""
    private(set) var hasEdits = false

    init(agent: Agent, mode: Mode) {
        self.agent = agent
        self.mode = mode
        step = mode == .undo ? .run : .settings
    }

    var stepCount: Int { mode == .undo ? 2 : 3 }
    var stepNumber: Int { step.rawValue - (mode == .undo ? 1 : 0) }
    var hostValid: Bool {
        !oneHost || AgentSetupWizardCommandsKt.isAgentSetupWizardHostValid(host: host)
    }
    var canContinue: Bool {
        if step != .settings { return true }
        return agent.ready && hostValid && (agent.isSsh || !selectedShells.isEmpty)
    }
    var needsDiscardConfirmation: Bool { hasEdits && step != .check }

    mutating func setShell(_ shell: String, selected: Bool) {
        guard step == .settings, Self.shells.contains(shell) else { return }
        if selected { selectedShells.insert(shell) } else { selectedShells.remove(shell) }
        hasEdits = true
    }

    mutating func setOneHost(_ value: Bool) {
        guard step == .settings else { return }
        oneHost = value
        hasEdits = true
    }

    mutating func setHost(_ value: String) {
        guard step == .settings else { return }
        host = value
        hasEdits = true
    }

    mutating func next() {
        guard canContinue else { return }
        step = step == .settings ? .run : .check
    }

    mutating func back() -> BackAction {
        if step == .run && mode == .configure {
            step = .settings
            return .stay
        }
        return needsDiscardConfirmation ? .confirmDiscard : .dismiss
    }

    func command(executable: String) -> String {
        if mode == .undo {
            return AgentSetupWizardCommandsKt.agentSetupWizardUndoCommand(
                executable: executable, agent: agent.argument)
        }
        return AgentSetupWizardCommandsKt.agentSetupWizardCommand(
            executable: executable,
            agent: agent.argument,
            endpoint: agent.endpoint,
            shells: selectedShells,
            host: oneHost ? host : ""
        )
    }

    var checkCommand: String {
        AgentSetupWizardCommandsKt.agentSetupWizardCheckCommand(agent: agent.argument, endpoint: agent.endpoint)
    }
}
#endif
