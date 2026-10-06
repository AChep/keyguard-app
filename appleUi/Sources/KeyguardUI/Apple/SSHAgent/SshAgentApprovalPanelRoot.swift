#if os(macOS)
import SwiftUI
import KeyguardShared

/// SwiftUI root hosted inside `SshAgentApprovalPanel`. Supplies its own card
/// background + rounded corners because the hosting panel is transparent.
struct SshAgentApprovalPanelRoot: View {
    @Environment(SshAgentModel.self) private var sshAgentModel

    var body: some View {
        Group {
            if let request = sshAgentModel.sshAgentRequests.first {
                SshAgentApprovalView(request: request)
                    // A new request must not inherit the previous button's
                    // pressed/focused state or its pending activation.
                    .id(request.id)
            }
        }
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12).strokeBorder(.separator, lineWidth: 1)
        )
    }
}

#endif
