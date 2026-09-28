import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class NavigationSettingsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// The "Navigation items" settings screen state, produced by the shared
    /// Kotlin producer running headless inside `KeyguardCore`. Only live while
    /// that settings screen is on screen.
    private(set) var navItemsSettings: NavItemsSettingsSnapshot = NavItemsSettingsSnapshot.companion.empty

    @ObservationIgnored private var navItemsSettingsSubscription: BridgeObservation?

    /// Observes the "Navigation items" settings screen state; only live while
    /// that screen is on screen.
    func startNavItemsSettingsObservation() {
        startObservation(
            \.navItemsSettingsSubscription, into: \.navItemsSettings, observe: core.observeNavItemsSettings)
    }

    func stopNavItemsSettingsObservation() {
        stopObservation(
            \.navItemsSettingsSubscription, resetting: \.navItemsSettings, to: NavItemsSettingsSnapshot.companion.empty)
    }

    func toggleNavItemVisibility(key: String) { core.toggleNavItemVisibility(key: key) }

    func moveNavItemUp(key: String) { core.moveNavItemUp(key: key) }

    func moveNavItemDown(key: String) { core.moveNavItemDown(key: key) }

    func removeNavItem(key: String) { core.removeNavItem(key: key) }

    func addNavItem(key: String) { core.addNavItem(key: key) }

    /// Commits a drag-reorder; `keys` is the full row order the user dropped.
    func reorderNavItems(keys: [String]) { core.reorderNavItems(keys: keys) }

    /// Fires the reset flow (native confirmation dialog, then defaults).
    func resetNavItems() { core.resetNavItems() }
}
