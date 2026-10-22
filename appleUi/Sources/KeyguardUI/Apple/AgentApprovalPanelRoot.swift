#if os(macOS)
import SwiftUI
import KeyguardShared

/// A queued approval request; its id keys the hosted prompt.
protocol AgentApprovalRequest {
    var id: String { get }
}

extension SshAgentRequestSnapshot: AgentApprovalRequest {}
extension GpgAgentRequestSnapshot: AgentApprovalRequest {}
extension EnvironmentApprovalSnapshot: AgentApprovalRequest {}
extension CliApprovalSnapshot: AgentApprovalRequest {}

/// SwiftUI root hosted inside `AgentApprovalPanel`. Renders the prompt of the front
/// request.
struct AgentApprovalPanelRoot<Request: AgentApprovalRequest, Prompt: View>: View {
    let requests: () -> [Request]
    let prompt: (Request) -> Prompt

    var body: some View {
        if let request = requests().first {
            prompt(request)
                // A new request must not inherit the previous button's
                // pressed/focused state or its pending activation.
                .id(request.id)
        }
    }
}

extension View {
    /// The card background + rounded corners of a prompt, because the hosting
    /// panel is transparent.
    func agentApprovalCard() -> some View {
        background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12).strokeBorder(.separator, lineWidth: 1)
            )
    }
}

#endif
