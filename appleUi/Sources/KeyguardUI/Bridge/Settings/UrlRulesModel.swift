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

    /// Blocked-URL list (app-global).
    private(set) var urlBlockList: UrlRuleListSnapshot = UrlRuleListSnapshot.companion.empty

    /// URL-override list (app-global).
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

    func invokeUrlBlockListSelectionAction(id: String) {
        core.invokeUrlBlockListSelectionAction(id: id)
    }

    /// Opens the create-new blocked-URL form.
    func invokeUrlBlockListPrimaryAction() {
        core.invokeUrlBlockListPrimaryAction()
    }

    func toggleUrlBlockListSelection(id: String) {
        core.toggleUrlBlockListSelection(itemId: id)
    }

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

    func invokeUrlOverrideListItemAction(id: String) {
        core.invokeUrlOverrideListItemAction(id: id)
    }

    func invokeUrlOverrideListSelectionAction(id: String) {
        core.invokeUrlOverrideListSelectionAction(id: id)
    }

    /// Opens the create-new URL-override form.
    func invokeUrlOverrideListPrimaryAction() {
        core.invokeUrlOverrideListPrimaryAction()
    }

    func toggleUrlOverrideListSelection(id: String) {
        core.toggleUrlOverrideListSelection(itemId: id)
    }

    func clearUrlOverrideListSelection() {
        core.clearUrlOverrideListSelection()
    }
}
