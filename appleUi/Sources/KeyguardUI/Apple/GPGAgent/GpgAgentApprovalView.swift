#if os(macOS)
import SwiftUI
import KeyguardShared

/// Approval prompt for a signature or decryption of the GPG agent.
struct GpgAgentApprovalView: View {
    @Environment(GpgAgentModel.self) private var gpgAgentModel
    let request: GpgAgentRequestSnapshot

    private var title: String {
        request.operation == .decrypt
            ? L10n.gpgAgentRequestApprovalDecryptTitle : L10n.agentRequestApprovalSignTitle(L10n.protocolGpg)
    }

    private var message: String {
        if request.operation == .decrypt {
            return request.callerName.isEmpty
                ? L10n.gpgAgentRequestApprovalDecryptMessageUnknownApp
                : L10n.gpgAgentRequestApprovalDecryptMessageKnownApp(request.callerName)
        }
        return request.callerName.isEmpty
            ? L10n.agentRequestApprovalSignMessageUnknownApp(L10n.protocolGpg)
            : L10n.agentRequestApprovalSignMessageKnownApp(request.callerName, L10n.protocolGpg)
    }

    var body: some View {
        AgentApprovalPrompt(
            requestID: request.id,
            expiresAtEpochMs: request.expiresAtEpochMs,
            timeoutMs: request.timeoutMs,
            title: title,
            systemImage: request.operation == .decrypt ? "lock.open.fill" : "signature",
            message: message,
            currentRequestID: { gpgAgentModel.gpgAgentRequests.first?.id },
            resolve: { requestID, approved, _ in
                gpgAgentModel.resolveGpgAgentRequest(id: requestID, approved: approved)
            }
        ) {
            AgentApprovalDetails(
                rows: AgentApprovalDetail.key(name: request.keyName, fingerprint: request.keyFingerprint)
                    + AgentApprovalDetail.caller(name: request.callerName, path: request.callerPath))
        }
        .appToastOverlay()
    }
}
#endif
