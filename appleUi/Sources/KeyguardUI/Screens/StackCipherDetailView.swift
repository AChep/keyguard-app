import SwiftUI
import KeyguardShared

struct StackCipherDetailView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    var body: some View {
        let detail = entry.detail ?? VaultDetailSnapshot.companion.empty
        let cipherId = detail.cipherId
        CipherDetailView(
            detail: detail,
            showsNavigationTitle: true,
            invoke: { navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: $0) },
            toggleFavorite: { navigationModel.toggleEntryFavorite(instanceId: entry.instanceId) },
            totpProvider: { [navigationModel] rowId in
                navigationModel.entryTotp[cipherId]?.states[rowId]
            }
        )
        .id(entry.instanceId)
    }
}
