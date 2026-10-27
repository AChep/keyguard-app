#if os(macOS)
import Foundation

/// Native macOS uses an externally launched helper, so this state never reports an applied or verified result.
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
        !oneHost || trimmedHost.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil
    }
    var canContinue: Bool {
        if step != .settings { return true }
        return agent.ready && hostValid && (agent.isSsh || !selectedShells.isEmpty)
    }
    var needsDiscardConfirmation: Bool { hasEdits && step != .check }
    private var trimmedHost: String { host.trimmingCharacters(in: .whitespacesAndNewlines) }

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
        if mode == .undo { return "\(Self.quote(executable)) agents undo \(agent.argument) --review" }
        var parts = [Self.quote(executable), "agents setup --agent", agent.argument]
        switch agent {
        case .ssh(let socket):
            parts += ["--socket", Self.quote(socket)]
            if oneHost { parts += ["--host", Self.quote(trimmedHost)] }
        case .gpg(let home, _): parts += ["--gpg-home", Self.quote(home)]
        }
        let shells = Self.shells.filter { selectedShells.contains($0) }
        if !shells.isEmpty { parts += ["--shells", shells.joined(separator: ",")] }
        return parts.joined(separator: " ")
    }

    var checkCommand: String {
        switch agent {
        case .ssh(let socket): "SSH_AUTH_SOCK=\(Self.quote(socket)) ssh-add -L"
        case .gpg(let home, _): "GNUPGHOME=\(Self.quote(home)) gpg-connect-agent --no-autostart 'KEYINFO --list' /bye"
        }
    }

    private static func quote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\"'\"'") + "'"
    }
}
#endif
