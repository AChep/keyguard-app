import SwiftUI
import KeyguardShared

/// A new identity for every activation, even when the same result is selected twice.
struct SettingsRevealRequest: Equatable {
    let id = UUID()
    var target: SettingsSearchTarget

    func resolved(ready: Bool, aliases: [SettingsSearchTarget: SettingsSearchTarget]) -> SettingsRevealRequest? {
        guard ready else { return nil }
        var copy = self
        copy.target = aliases[target] ?? target
        return copy
    }
}

private struct SettingsRevealRequestKey: EnvironmentKey {
    static var defaultValue: SettingsRevealRequest? { nil }
}

extension EnvironmentValues {
    var settingsRevealRequest: SettingsRevealRequest? {
        get { self[SettingsRevealRequestKey.self] }
        set { self[SettingsRevealRequestKey.self] = newValue }
    }
}
