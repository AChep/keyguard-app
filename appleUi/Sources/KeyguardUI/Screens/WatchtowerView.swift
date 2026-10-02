import SwiftUI
import KeyguardShared

struct WatchtowerView: View {
    var entry: ScreenEntrySnapshot? = nil
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(WatchtowerModel.self) private var watchtowerModel

    var body: some View {
        if let entry {
            WatchtowerDashboard(
                watchtower: entry.watchtower ?? WatchtowerSnapshot.companion.empty,
                invokeAction: { navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: $0) },
                invokeFilter: {
                    navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: "filter:" + $0)
                },
                clearFilters: {
                    navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: "clearFilters")
                }
            )
        } else {
            WatchtowerScreen(makeSession: watchtowerModel.makeSession)
        }
    }
}
