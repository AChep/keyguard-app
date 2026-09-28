import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class UrlRulesModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Blocked-URL list (app-global), produced by the shared Kotlin
    /// `urlBlockListStateProducer` running headless inside `KeyguardCore`.
    private(set) var urlBlockList: UrlRuleListSnapshot = UrlRuleListSnapshot.companion.empty

    /// URL-override list (app-global), produced by the shared Kotlin
    /// `urlOverrideListStateProducer` running headless inside `KeyguardCore`.
    private(set) var urlOverrideList: UrlRuleListSnapshot = UrlRuleListSnapshot.companion.empty

    @ObservationIgnored private var urlBlockListSubscription: BridgeObservation?

    @ObservationIgnored private var urlOverrideListSubscription: BridgeObservation?

    func startUrlBlockListObservation() {
        startObservation(\.urlBlockListSubscription, into: \.urlBlockList, observe: core.observeUrlBlockList)
    }

    func stopUrlBlockListObservation() {
        stopObservation(\.urlBlockListSubscription, resetting: \.urlBlockList, to: UrlRuleListSnapshot.companion.empty)
    }

    func invokeUrlBlockListItemAction(id: String) {
        core.invokeUrlBlockListItemAction(id: id)
    }

    /// Runs a bulk action of the active blocked-URL multi-selection (Delete).
    func invokeUrlBlockListSelectionAction(id: String) {
        core.invokeUrlBlockListSelectionAction(id: id)
    }

    /// Opens the create-new blocked-URL form (the producer's primary action).
    func invokeUrlBlockListPrimaryAction() {
        core.invokeUrlBlockListPrimaryAction()
    }

    /// Toggles whether the blocked-URL row with `id` is part of the multi-selection.
    func toggleUrlBlockListSelection(id: String) {
        core.toggleUrlBlockListSelection(itemId: id)
    }

    /// Clears the active blocked-URL multi-selection.
    func clearUrlBlockListSelection() {
        core.clearUrlBlockListSelection()
    }

    func startUrlOverrideListObservation() {
        startObservation(\.urlOverrideListSubscription, into: \.urlOverrideList, observe: core.observeUrlOverrideList)
    }

    func stopUrlOverrideListObservation() {
        stopObservation(
            \.urlOverrideListSubscription, resetting: \.urlOverrideList, to: UrlRuleListSnapshot.companion.empty)
    }

    /// Runs a per-row dropdown action of a URL-override row (edit / duplicate /
    /// delete) by its opaque id. The edit/add forms and delete confirmations are
    /// rendered by the shared `DialogController`.
    func invokeUrlOverrideListItemAction(id: String) {
        core.invokeUrlOverrideListItemAction(id: id)
    }

    /// Runs a bulk action of the active URL-override multi-selection (Delete).
    func invokeUrlOverrideListSelectionAction(id: String) {
        core.invokeUrlOverrideListSelectionAction(id: id)
    }

    /// Opens the create-new URL-override form (the producer's primary action).
    func invokeUrlOverrideListPrimaryAction() {
        core.invokeUrlOverrideListPrimaryAction()
    }

    /// Toggles whether the URL-override row with `id` is part of the multi-selection.
    func toggleUrlOverrideListSelection(id: String) {
        core.toggleUrlOverrideListSelection(itemId: id)
    }

    /// Clears the active URL-override multi-selection.
    func clearUrlOverrideListSelection() {
        core.clearUrlOverrideListSelection()
    }
}
