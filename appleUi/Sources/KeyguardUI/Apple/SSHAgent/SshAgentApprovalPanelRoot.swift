#if os(macOS)
import SwiftUI

extension AgentApprovalPanelController {
    /// The SSH agent approval panel.
    public static func sshAgent(model: AppViewModel) -> AgentApprovalPanelController {
        let sshAgentModel = model.sshAgent
        // Start here, not in `ContentView.onAppear`, which never fires in
        // menu-bar-only mode.
        sshAgentModel.startSshAgentObservation()
        return AgentApprovalPanelController(model: model, requests: { sshAgentModel.sshAgentRequests }) {
            SshAgentApprovalView(request: $0)
        }
    }
}

#endif
