import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class WatchtowerModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Watchtower (security dashboard) state, produced by the shared Kotlin
    /// `watchtowerStateProducer` running headless inside `KeyguardCore`. Only live
    /// while the watchtower screen is on screen.
    private(set) var watchtower: WatchtowerSnapshot = WatchtowerSnapshot.companion.empty

    #if os(macOS)
    private(set) var watchtowerFilterToolbar = FilterToolbarState.empty
    #endif

    #if os(macOS)
    private(set) var watchtowerOptionsToolbar = WatchtowerOptionsToolbarState.empty
    #endif

    /// Watchtower "new alerts" list, produced by the shared Kotlin
    /// `watchtowerNewAlertsStateProducer` running headless inside `KeyguardCore`.
    /// Only live while the watchtower alerts screen is on screen.
    private(set) var watchtowerAlerts: WatchtowerAlertsSnapshot = WatchtowerAlertsSnapshot.companion.empty

    /// Watchtower settings, produced by the shared Kotlin Watchtower settings use
    /// cases running inside `KeyguardCore`. Only live while the Watchtower settings
    /// screen is on screen.
    private(set) var watchtowerSettings: WatchtowerSettingsSnapshot = WatchtowerSettingsSnapshot.companion.empty

    @ObservationIgnored private var watchtowerSubscription: BridgeObservation?

    @ObservationIgnored private var watchtowerAlertsSubscription: BridgeObservation?

    @ObservationIgnored private var watchtowerSettingsSubscription: BridgeObservation?

    /// Starts observing the Watchtower settings. Call when the Watchtower settings
    /// screen appears; balance with `stopWatchtowerSettingsObservation()`.
    func startWatchtowerSettingsObservation() {
        startObservation(
            \.watchtowerSettingsSubscription, into: \.watchtowerSettings, observe: core.observeWatchtowerSettings)
    }

    func stopWatchtowerSettingsObservation() {
        stopObservation(
            \.watchtowerSettingsSubscription, resetting: \.watchtowerSettings,
            to: WatchtowerSettingsSnapshot.companion.empty)
    }

    func setCheckPwnedPasswords(_ value: Bool) { core.setCheckPwnedPasswords(value: value) }

    func setCheckPwnedServices(_ value: Bool) { core.setCheckPwnedServices(value: value) }

    func setCheckTwoFa(_ value: Bool) { core.setCheckTwoFa(value: value) }

    func setCheckPasskeys(_ value: Bool) { core.setCheckPasskeys(value: value) }

    func setHibpApiToken(_ token: String) -> Bool {
        guard core.isValidHibpApiToken(token: token) else { return false }
        core.setHibpApiToken(token: token)
        return true
    }

    /// Starts running the shared watchtower producer. Call when the watchtower
    /// screen appears; balance with `stopWatchtowerObservation()` on disappear.
    func startWatchtowerObservation() {
        guard watchtowerSubscription == nil else { return }
        watchtowerSubscription = BridgeObservation(
            core.observeWatchtower { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.setWatchtowerSnapshot(snapshot)
                }
            })
    }

    func stopWatchtowerObservation() {
        watchtowerSubscription?.cancel()
        watchtowerSubscription = nil
        setWatchtowerSnapshot(WatchtowerSnapshot.companion.empty)
    }

    private func setWatchtowerSnapshot(_ snapshot: WatchtowerSnapshot) {
        watchtower = snapshot
        #if os(macOS)
        assignIfChanged(&watchtowerFilterToolbar, FilterToolbarState(snapshot: snapshot))
        assignIfChanged(&watchtowerOptionsToolbar, WatchtowerOptionsToolbarState(snapshot: snapshot))
        #endif
    }

    /// Invokes a watchtower navigation closure (card / strength chip / new-alerts
    /// row / directory option) by its opaque snapshot id.
    func invokeWatchtowerAction(id: String) {
        core.invokeWatchtowerAction(id: id)
    }

    /// Toggles a watchtower filter on/off (or expands/collapses a section) by its id.
    func invokeWatchtowerFilter(id: String) {
        core.invokeWatchtowerFilter(id: id)
    }

    /// Clears every active watchtower filter.
    func clearWatchtowerFilters() {
        core.clearWatchtowerFilters()
    }

    /// Starts running the shared watchtower new-alerts producer. Call when the
    /// alerts screen appears; balance with `stopWatchtowerAlertsObservation()`.
    func startWatchtowerAlertsObservation() {
        guard watchtowerAlertsSubscription == nil else { return }
        watchtowerAlertsSubscription = BridgeObservation(
            core.observeWatchtowerNewAlerts { [weak self] snapshot in
                Task { @MainActor [weak self] in self?.watchtowerAlerts = snapshot }
            })
    }

    func stopWatchtowerAlertsObservation() {
        watchtowerAlertsSubscription?.cancel()
        watchtowerAlertsSubscription = nil
        watchtowerAlerts = WatchtowerAlertsSnapshot.companion.empty
    }

    /// Opens the cipher affected by a watchtower alert (by its alert id). Routes
    /// through the bound nav interceptor, which pushes the cipher detail.
    func invokeWatchtowerAlertItem(id: String) {
        core.invokeWatchtowerAlertItem(id: id)
    }

    /// Marks every watchtower alert as read; the shared producer then pops the
    /// alerts list.
    func markAllWatchtowerAlertsRead() {
        core.markAllWatchtowerAlertsRead()
    }
}
