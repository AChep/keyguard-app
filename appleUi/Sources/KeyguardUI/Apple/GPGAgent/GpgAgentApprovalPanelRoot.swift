#if os(macOS)
import SwiftUI
import KeyguardShared

/// SwiftUI root hosted inside `GpgAgentApprovalPanel`. The request id resets local
/// form state before the next request can be approved.
struct GpgAgentApprovalPanelRoot: View {
    @Environment(GpgAgentModel.self) private var gpgAgentModel

    var body: some View {
        Group {
            if let request = gpgAgentModel.gpgAgentRequests.first {
                GpgAgentApprovalView(request: request)
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
