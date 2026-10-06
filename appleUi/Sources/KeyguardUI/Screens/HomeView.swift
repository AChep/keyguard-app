import SwiftUI
import KeyguardShared

struct HomeView: View {
    @Environment(VaultActionsModel.self) private var vaultActionsModel

    var body: some View {
        VaultListScreen(core: vaultActionsModel.keyguardCore)
    }
}
