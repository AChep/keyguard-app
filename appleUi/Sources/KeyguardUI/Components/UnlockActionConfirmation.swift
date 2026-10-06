import SwiftUI
import KeyguardShared

extension View {
    func unlockActionConfirmation(_ action: Binding<UnlockActionSnapshot?>) -> some View {
        modifier(UnlockActionConfirmation(action: action))
    }
}

private struct UnlockActionConfirmation: ViewModifier {
    @Environment(VaultSessionModel.self) private var authModel
    @Binding var action: UnlockActionSnapshot?

    func body(content: Content) -> some View {
        content.confirmationDialog(
            action?.title ?? "",
            isPresented: Binding(
                get: { action != nil },
                set: { if !$0 { action = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let pending = action {
                Button(pending.title, role: .destructive) {
                    authModel.invokeUnlockAction(pending.id)
                    action = nil
                }
            }
            Button(L10n.cancel, role: .cancel) { action = nil }
        } message: {
            Text(L10n.setupEraseVaultConfirmationText)
        }
    }
}
