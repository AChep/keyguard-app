#if os(macOS)
import SwiftUI

extension AgentApprovalPanelController {
    /// The GPG agent approval panel.
    public static func gpgAgent(model: AppViewModel) -> AgentApprovalPanelController {
        let gpgAgentModel = model.gpgAgent
        // A controller owned by the app delegate also observes in menu-bar-only mode.
        gpgAgentModel.startGpgAgentObservation()
        return AgentApprovalPanelController(model: model, requests: { gpgAgentModel.gpgAgentRequests }) {
            GpgAgentApprovalView(request: $0)
        }
    }
}

#endif
