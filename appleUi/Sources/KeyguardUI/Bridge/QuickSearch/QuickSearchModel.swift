import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class QuickSearchModel: SnapshotObserving {
    private let core: KeyguardCore
    private let vaultActions: VaultActionsModel

    init(core: KeyguardCore, vaultActions: VaultActionsModel) {
        self.core = core
        self.vaultActions = vaultActions
    }

    /// Quick-search overlay state, produced by the shared Kotlin quick-search
    /// producer running headless inside `KeyguardCore`. Only live while the
    /// global-hotkey panel is on screen.
    private(set) var quickSearch: QuickSearchSnapshot = QuickSearchSnapshot.companion.empty

    /// Bumped on every quick-search `show()` to re-request keyboard focus for the
    /// search field (the cached panel's `.onAppear` only fires once). See
    /// `requestQuickSearchFocus()`.
    private(set) var quickSearchFocusToken: Int = 0

    private(set) var quickSearchVisible = false

    func setQuickSearchVisible(_ visible: Bool) { quickSearchVisible = visible }

    /// Diffable results owned by this Quick Search model.
    @ObservationIgnored private var _quickSearchListModel: QuickSearchListModel?

    var quickSearchListModel: QuickSearchListModel? { _quickSearchListModel }

    @ObservationIgnored private var quickSearchSubscription: BridgeObservation?

    var isObservingQuickSearch: Bool { quickSearchSubscription != nil }

    /// Starts the shared quick-search producer. Call when the panel appears;
    /// balance with `stopQuickSearchObservation()` on dismiss.
    func startQuickSearchObservation() {
        guard quickSearchSubscription == nil else { return }
        startObservation(\.quickSearchSubscription, into: \.quickSearch, observe: core.observeQuickSearch)
        // The results-list model rides the same lifecycle (and thus the 300s
        // preservation window) as the producer + detail + action + TOTP channels.
        let listModel =
            _quickSearchListModel
            ?? QuickSearchListModel(
                core: core,
                onCopy: { [weak self] secretId, accountId, field in
                    Task { @MainActor [weak self] in
                        self?.vaultActions.copyCipherField(secretId: secretId, accountId: accountId, field: field)
                    }
                }
            )
        _quickSearchListModel = listModel
        listModel.start()
    }

    func stopQuickSearchObservation() {
        stopObservation(\.quickSearchSubscription, resetting: \.quickSearch, to: QuickSearchSnapshot.companion.empty)
        _quickSearchListModel?.stop()
    }

    func requestQuickSearchFocus() {
        quickSearchFocusToken += 1
    }

    /// Clears the quick-search query as a revision-bumping command.
    func clearQuickSearchQuery() {
        core.clearQuickSearchQuery()
    }

    func setQuickSearchQuery(_ text: String) {
        core.setQuickSearchQuery(text: text)
    }

    func moveQuickSearchSelection(_ direction: Int32) {
        core.moveQuickSearchSelection(direction: direction)
    }

    func moveQuickSearchActionSelection(_ direction: Int32) {
        core.moveQuickSearchActionSelection(direction: direction)
    }

    func selectQuickSearchItem(id: String) {
        core.selectQuickSearchItem(id: id)
    }

    func invokeQuickSearchDefaultAction() {
        core.invokeQuickSearchDefaultAction()
    }

    func invokeQuickSearchAction(type: String) {
        core.invokeQuickSearchAction(typeName: type)
    }
}
