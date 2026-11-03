#if os(macOS)
import SwiftUI
import KeyguardShared

struct CliApprovalView: View {
    @Environment(CliAccessModel.self) private var model
    let request: CliApprovalSnapshot

    var body: some View {
        AgentApprovalPrompt(
            requestID: request.id,
            expiresAtEpochMs: request.expiresAtEpochMs,
            title: L10n.environmentsApprovalTitle,
            systemImage: "terminal",
            elevatedAccessHost: .cliApproval,
            currentRequestID: { model.requests.first?.id },
            resolve: { requestID, approved, _ in model.respond(id: requestID, approved: approved) }
        ) {
            AgentApprovalDetails(rows: AgentApprovalDetail.caller(name: request.callerName, path: request.callerPath))
            if let name = request.environmentName { Text(name) }
            if let account = request.accountName { Text(account).foregroundStyle(.secondary) }
            Text(operationTitle)
            if let command = request.command {
                Text(command).font(.system(.body, design: .monospaced)).textSelection(.enabled)
            }
            if let directory = request.workingDirectory {
                Text(L10n.cliApprovalWorkingDirectory(directory)).foregroundStyle(.secondary)
            }
            Text(note).font(.callout).foregroundStyle(.secondary)
        }
    }

    private var operationTitle: String {
        switch request.operation {
        case .run: return L10n.cliCapabilityRun
        default: return L10n.environmentsApprovalTitle
        }
    }

    private var note: String {
        switch request.operation {
        case .run: return request.terminalSession ? L10n.cliApprovalRunTerminalNote : L10n.cliApprovalRunNote
        default: return L10n.cliApprovalRunNote
        }
    }
}
#endif
