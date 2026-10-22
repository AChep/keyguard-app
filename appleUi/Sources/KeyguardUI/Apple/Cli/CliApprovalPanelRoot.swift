#if os(macOS)
import SwiftUI

extension AgentApprovalPanelController {
    /// The approval panel of the command-line tool.
    public static func cli(model: AppViewModel) -> AgentApprovalPanelController {
        let cliAccess = model.cliAccess
        cliAccess.start()
        return AgentApprovalPanelController(model: model, requests: { cliAccess.requests }) {
            CliApprovalView(request: $0)
        }
    }
}

#endif
