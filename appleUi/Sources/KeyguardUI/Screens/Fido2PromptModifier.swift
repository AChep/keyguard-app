import SwiftUI

struct Fido2PromptModifier: ViewModifier {
    @Environment(VaultSessionModel.self) private var authModel
    var isEnabled = true

    private var presented: Binding<Bool> {
        Binding(
            get: { isEnabled && authModel.fido2Phase != .hidden },
            set: { if !$0 { authModel.cancelFido2Prompt() } })
    }

    func body(content: Content) -> some View {
        content.sheet(isPresented: presented) { Fido2PromptView() }
    }
}
