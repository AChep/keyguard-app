import SwiftUI
import KeyguardShared

/// Keeps a SwiftUI `List(selection:)` in lockstep with a shared producer's
/// selection handle.
///
/// The producer owns the selection; the `List` only mirrors it. Two edges have to
/// meet without echoing each other:
///
/// * the user toggles a row — the delta is forwarded down as per-item toggles;
/// * the producer's selection changes (a bulk action ran, items disappeared, the
///   bar's X was tapped) — the new set is mirrored up into the `List`.
///
/// The mirror write is made self-cancelling by advancing `lastSeen` *before* it, so
/// the `onChange` it provokes diffs to nothing. That replaces the
/// `suppressSelectionDiff` flag the screens used to carry, and with it the
/// `DispatchQueue.main.async` hop that lifted the flag on an unordered turn of the
/// main queue.
@MainActor
@Observable
final class ListSelectionModel {
    /// Bound to `List(selection:)`.
    var selectedRowIds: Set<String> = []

    #if os(iOS)
    /// Edit-mode state. macOS has no Edit button, so no equivalent.
    var editMode: EditMode = .inactive
    #endif

    /// The last value this model observed in `selectedRowIds`, used to diff the
    /// user's additions and removals.
    @ObservationIgnored private var lastSeen: Set<String> = []

    /// `true` while the list is in its multi-selection mode: iOS Edit mode, or an
    /// active producer selection on macOS (which has no Edit button).
    func isEditing(selectionCount: Int32) -> Bool {
        #if os(iOS)
        return editMode.isEditing
        #else
        return selectionCount >= 1
        #endif
    }

    /// Whether a row's context menu should offer the bulk actions of the active
    /// multi-selection rather than that row's own actions.
    func showsBulkActions(selectionCount: Int32) -> Bool {
        #if os(iOS)
        return editMode.isEditing && selectionCount >= 1
        #else
        return selectionCount >= 1
        #endif
    }

    /// Forwards the list's selection delta to the producer's per-item toggle.
    /// `isKnownId` drops ids the current snapshot no longer contains.
    func sync(
        _ newValue: Set<String>,
        isKnownId: (String) -> Bool,
        toggle: (String) -> Void
    ) {
        defer { lastSeen = newValue }
        for id in newValue.symmetricDifference(lastSeen) where isKnownId(id) {
            toggle(id)
        }
    }

    /// Mirrors the producer's authoritative selection down into the list.
    func reconcile(_ producerSelection: Set<String>) {
        guard selectedRowIds != producerSelection else {
            lastSeen = producerSelection
            return
        }
        // Advance the diff baseline first so the write's own `onChange` is a no-op.
        lastSeen = producerSelection
        selectedRowIds = producerSelection
    }

    /// Count-only reconcile, for producers that publish no per-item `selected` flag.
    /// A producer-side clear is the only transition a bare count can identify.
    func reconcile(selectionCount: Int32) {
        if selectionCount == 0 { reconcile([]) }
    }

    /// Drops the selection on both edges — the iOS Edit-mode exit path.
    func clear(_ clearProducer: () -> Void) {
        guard !selectedRowIds.isEmpty else { return }
        clearProducer()
        reconcile([])
    }
}

extension View {
    /// Wires a `ListSelectionModel` to its screen: Edit-mode plumbing on iOS, the
    /// user-edit forwarding, and the producer-side reconcile.
    ///
    /// `producerSelection` is the snapshot's authoritative set where the items carry
    /// a `selected` flag; pass `nil` for a producer that only publishes a count, and
    /// the reconcile degrades to mirroring a clear.
    func listSelection(
        _ selection: ListSelectionModel,
        selectionCount: Int32,
        producerSelection: Set<String>?,
        isKnownId: @escaping (String) -> Bool,
        toggle: @escaping (String) -> Void,
        clear: @escaping () -> Void
    ) -> some View {
        modifier(
            ListSelectionModifier(
                selection: selection,
                selectionCount: selectionCount,
                producerSelection: producerSelection,
                isKnownId: isKnownId,
                toggle: toggle,
                clear: clear
            ))
    }

    /// The floating bulk-action bar of an active multi-selection, and its entry /
    /// exit transition. Shown whenever anything is selected.
    func selectionBar(
        count: Int32,
        actions: [VaultActionSnapshot],
        invoke: @escaping @MainActor @Sendable (String) -> Void,
        clear: @escaping @MainActor @Sendable () -> Void
    ) -> some View {
        selectionBar(count: count, visible: count >= 1, actions: actions, invoke: invoke, clear: clear)
    }

    /// The bulk-action bar with an explicit visibility gate, for surfaces whose bar
    /// appears on a different condition than "anything selected" (a higher
    /// threshold, or only while in Edit mode).
    func selectionBar(
        count: Int32,
        visible: Bool,
        actions: [VaultActionSnapshot],
        invoke: @escaping @MainActor @Sendable (String) -> Void,
        clear: @escaping @MainActor @Sendable () -> Void
    ) -> some View {
        modifier(
            SelectionBarModifier(
                count: count,
                visible: visible,
                actions: actions,
                invoke: invoke,
                clear: clear
            ))
    }
}

private struct ListSelectionModifier: ViewModifier {
    @Bindable var selection: ListSelectionModel
    let selectionCount: Int32
    let producerSelection: Set<String>?
    let isKnownId: (String) -> Bool
    let toggle: (String) -> Void
    let clear: () -> Void

    func body(content: Content) -> some View {
        content
            #if os(iOS)
        .environment(\.editMode, $selection.editMode)
        .onChange(of: selection.editMode) { _, mode in
            // Leaving Edit mode drops the selection on both edges.
            if !mode.isEditing { selection.clear(clear) }
        }
            #endif
            .onChange(of: selection.selectedRowIds) { _, newValue in
                selection.sync(newValue, isKnownId: isKnownId, toggle: toggle)
            }
            // `initial: true` also mirrors a selection the producer already held
            // when the screen mounted — the old per-screen copies only watched for a
            // clear, so a pre-existing selection rendered as an empty list.
            .onChange(of: producerSelection ?? [], initial: true) { _, newValue in
                if producerSelection != nil { selection.reconcile(newValue) }
            }
            .onChange(of: selectionCount) { _, count in
                if producerSelection == nil { selection.reconcile(selectionCount: count) }
            }
    }
}
