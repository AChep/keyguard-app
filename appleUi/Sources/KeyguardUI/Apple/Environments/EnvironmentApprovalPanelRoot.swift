#if os(macOS)
import SwiftUI

extension AgentApprovalPanelController {
    /// The Environments approval panel.
    public static func environments(model: AppViewModel) -> AgentApprovalPanelController {
        let environmentAccess = model.environmentAccess
        environmentAccess.start()
        return AgentApprovalPanelController(model: model, requests: { environmentAccess.requests }) {
            EnvironmentApprovalView(request: $0)
        }
    }
}

#endif
