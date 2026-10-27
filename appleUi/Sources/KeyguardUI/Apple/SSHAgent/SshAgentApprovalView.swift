#if os(macOS)
import SwiftUI
import KeyguardShared

/// Per-sign approval prompt for the SSH agent.
struct SshAgentApprovalView: View {
    @Environment(SshAgentModel.self) private var sshAgentModel

    let request: SshAgentRequestSnapshot

    var body: some View {
        AgentApprovalPrompt(
            requestID: request.id,
            expiresAtEpochMs: request.expiresAtEpochMs,
            timeoutMs: request.timeoutMs,
            title: L10n.agentRequestApprovalSignTitle(L10n.protocolSsh),
            systemImage: "key.horizontal.fill",
            message: L10n.sshAgentApprovalSignText,
            approveHint: L10n.sshAgentApprovalApproveHint,
            currentRequestID: { sshAgentModel.sshAgentRequests.first?.id },
            resolve: { requestID, approved, _ in
                sshAgentModel.resolveSshAgentRequest(id: requestID, approved: approved)
            }
        ) {
            AgentApprovalDetails(
                rows: AgentApprovalDetail.key(name: request.keyName, fingerprint: request.keyFingerprint)
                    + AgentApprovalDetail.caller(name: request.callerName, path: request.callerPath))
        }
    }
}

#endif
