import SwiftUI

extension VaultListSessionModel {
    /// Runs one step of the precedence above. Returns `true` when the event was
    /// consumed (a selection or query clear was issued) — the caller must NOT
    /// pop / dismiss then; `false` means nothing to clear, fall through.
    func handleBack() -> Bool {
        if selection.count > 0 {
            clearSelection()
            return true
        }
        if !header.query.isEmpty {
            clearQuery()
            return true
        }
        return false
    }
}

/// Wires the shared back decision to the macOS Esc key (`onExitCommand`). On
/// iOS this modifier is inert by design — there is no Esc key, and the
/// navigation back affordances stay with the caller (see the extension above).
struct VaultListEscHandler: ViewModifier {
    let model: VaultListSessionModel
    /// Invoked when the precedence falls all the way through (nothing to
    /// clear): pop the stacked list / dismiss the surface, or `{}` at the root.
    let fallback: () -> Void

    func body(content: Content) -> some View {
        #if os(macOS)
        content.onExitCommand {
            if !model.handleBack() {
                fallback()
            }
        }
        #else
        content
        #endif
    }
}

extension View {
    /// Applies the vault list's Esc precedence (selection → query → and only
    /// then `fallback`) — see `VaultListEscHandler`.
    func vaultListEscHandler(
        _ model: VaultListSessionModel,
        fallback: @escaping () -> Void = {}
    ) -> some View {
        modifier(VaultListEscHandler(model: model, fallback: fallback))
    }
}
