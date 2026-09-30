import SwiftUI
@preconcurrency import KeyguardShared

/// The floating, glass-backed bar that stands in for an active multi-selection:
/// a clear button, the selected count, and an overflow menu of the bulk actions.
///
/// The chrome is identical on every surface; only the action model differs (the
/// bridged `VaultActionSnapshot` on the snapshot-driven screens, the V2 `VaultAction`
/// on the virtualizing vault list), so the menu content is supplied by the caller.
struct SelectionBarChrome<Actions: View>: View {
    let count: Int
    let clear: @MainActor @Sendable () -> Void
    /// Whether there is anything to show in the overflow menu — a `ViewBuilder`
    /// result cannot be asked whether it is empty, so the caller says so.
    var hasActions: Bool = true
    @ViewBuilder let actions: Actions
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 10) {
            Button {
                clear()
            } label: {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.semibold))
                    .touchTarget()
            }
            .buttonStyle(.plain)
            .help(L10n.selectionClearAction)
            .accessibilityLabel(L10n.selectionClearAction)

            Text(L10n.selectionNSelected(count))
                .font(.subheadline.weight(.medium))
                .contentTransition(reduceMotion ? .identity : .numericText())
                .animation(reduceMotion ? nil : .default, value: count)

            if hasActions {
                Menu {
                    actions
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .touchTarget()
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help(L10n.actions)
                .accessibilityLabel(L10n.selectionActionsTitle)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .contentShape(Capsule())
        .glassCapsule()
    }
}

/// The selection bar of a snapshot-driven list screen.
struct SelectionActionBar: View {
    let count: Int
    let actions: [VaultActionSnapshot]
    let invoke: @MainActor @Sendable (String) -> Void
    let clear: @MainActor @Sendable () -> Void

    var body: some View {
        SelectionBarChrome(count: count, clear: clear, hasActions: !actions.isEmpty) {
            listActionMenuItems(actions: actions, invoke: invoke)
        }
    }
}

/// Snapshot actions as menu items: a divider before each `startsSection` action,
/// a toggle for a `switchState`, and the destructive role for `danger`.
@ViewBuilder
func listActionMenuItems(
    actions: [VaultActionSnapshot],
    invoke: @escaping @MainActor @Sendable (String) -> Void
) -> some View {
    ForEach(actions, id: \.id) { action in
        if action.startsSection {
            Divider()
        }
        if let on = action.switchState?.boolValue {
            // `Binding`'s accessors are nonisolated; SwiftUI only runs them on the main
            // actor, which is where `invoke` belongs.
            Toggle(
                action.title,
                isOn: Binding(get: { on }, set: { _ in MainActor.assumeIsolated { invoke(action.id) } })
            )
        } else {
            Button(action.title, role: action.danger ? .destructive : nil) {
                invoke(action.id)
            }
        }
    }
}
