import SwiftUI
import Observation
import KeyguardShared

@MainActor
@Observable
final class AppPreferencesModel: SnapshotObserving {
    private let core: KeyguardCore
    private let links: LinkOpeningCoordinator

    init(core: KeyguardCore, links: LinkOpeningCoordinator) {
        self.core = core
        self.links = links
    }

    func start() {
        // App-wide preferences apply to the whole shell, so observe them for the
        // app's lifetime.
        startObservation(\.appPreferencesSubscription) { deliver in
            BridgeObservation(
                core.observeAppPreferences { snapshot in
                    deliver { model in
                        AppLocalization.shared.languageTag = snapshot.locale
                        model.appPreferences = snapshot
                        model.links.updatePreference(useExternalBrowser: snapshot.useExternalBrowser)
                        Self.applyAppearance(theme: snapshot.theme)
                    }
                })
        }
    }

    /// Only live while the Appearance settings screen is on screen.
    private(set) var appearanceSettings: AppearanceSettingsSnapshot = AppearanceSettingsSnapshot.companion.empty

    private(set) var appPreferences: AppPreferencesSnapshot = AppPreferencesSnapshot.companion.empty

    @ObservationIgnored private let appearanceSettingsObservation = SharedObservation()

    @ObservationIgnored private var appearanceSettingsSubscription: BridgeObservation?

    @ObservationIgnored private var appPreferencesSubscription: BridgeObservation?

    private static func applyAppearance(theme: String?) {
        #if os(macOS)
        switch theme {
        case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
        case "light": NSApp.appearance = NSAppearance(named: .aqua)
        default: NSApp.appearance = nil
        }
        #endif
        // iOS applies the theme via SwiftUI `preferredColorScheme` at the scene root.
    }

    /// The "Accent color" preference; `nil` keeps the system accent.
    var accentColor: Color? {
        guard let argb = appPreferences.accentArgb?.int64Value else { return nil }
        return Color(argb: UInt32(truncatingIfNeeded: argb))
    }

    func startAppearanceSettingsObservation() {
        appearanceSettingsObservation.acquire {
            sharedSnapshotObservation(
                \.appearanceSettingsSubscription, into: \.appearanceSettings,
                empty: AppearanceSettingsSnapshot.companion.empty,
                observe: core.observeAppearanceSettings)
        }
    }

    func stopAppearanceSettingsObservation() {
        appearanceSettingsObservation.release()
    }

    func setAmoledDark(_ value: Bool) { core.setAmoledDark(value: value) }

    func setExpressive(_ value: Bool) { core.setExpressive(value: value) }

    func setMarkdown(_ value: Bool) { core.setMarkdown(value: value) }

    func setNavLabel(_ value: Bool) { core.setNavLabel(value: value) }

    func setUseExternalBrowser(_ value: Bool) { core.setUseExternalBrowser(value: value) }

    func setWebDavTransactions(_ value: Bool) { core.setWebDavTransactions(value: value) }

    func setKeepScreenOn(_ value: Bool) { core.setKeepScreenOn(value: value) }

    func setMinimizeOnCopy(_ value: Bool) { core.setMinimizeOnCopy(value: value) }

    func setCloseToTray(_ value: Bool) { core.setCloseToTray(value: value) }

    func setTwoPanelPortrait(_ value: Bool) { core.setTwoPanelPortrait(value: value) }

    func setTwoPanelLandscape(_ value: Bool) { core.setTwoPanelLandscape(value: value) }

    func setTheme(_ optionId: String) { core.setTheme(optionId: optionId) }

    func setFont(_ optionId: String) { core.setFont(optionId: optionId) }

    func setColors(_ optionId: String) { core.setColors(optionId: optionId) }

    func setLocale(_ optionId: String) { core.setLocale(optionId: optionId) }

    func setNavAnimation(_ optionId: String) { core.setNavAnimation(optionId: optionId) }

    /// Mirrors the effective SwiftUI appearance into the shared bridge so
    /// headless producers (attachment preview syntax highlighting) pick the
    /// matching light / dark palette.
    func setInterfaceDarkMode(_ isDark: Bool) {
        core.setInterfaceDarkMode(isDark: isDark)
    }
}
