import SwiftUI
import KeyguardShared

struct CipherFilterTabView: View {
    @Environment(VaultActionsModel.self) private var vaultActionsModel
    let section: NavSection

    @State private var model: VaultListSessionModel?

    var body: some View {
        NavStackContainer(scope: section.scope, rootList: .vault, rootVaultList: model) {
            StackVaultListContent(model: model)
                .navigationTitle(section.title)
        }
        .onAppear {
            if model == nil {
                model = VaultListSessionModel(
                    core: vaultActionsModel.keyguardCore,
                    config: .cipherFilter(id: section.filterId)
                )
            }
            model?.start()
        }
        .onDisappear { model?.stop() }
    }
}
