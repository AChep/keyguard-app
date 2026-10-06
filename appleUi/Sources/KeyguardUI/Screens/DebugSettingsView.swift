import SwiftUI
import KeyguardShared

struct DebugSettingsView: View {
    @Environment(SettingsModel.self) private var settingsModel
    let item: SettingsItemSnapshot

    private var s: DebugSettingsSnapshot { settingsModel.debugSettings }

    var body: some View {
        SettingsForm(ready: s.loaded) {
            if !s.loaded {
                ProgressView()
            } else if s.premiumOverrideAvailable {
                Section {
                    Toggle(
                        L10n.prefItemDebugPremiumTitle,
                        isOn: Binding(
                            get: { s.premiumOverrideEnabled },
                            set: { settingsModel.setDebugPremium($0) }
                        )
                    )
                    .settingsSearchTarget(.debugPremium)
                } footer: {
                    Text(L10n.prefItemDebugPremiumText)
                }
            }
        }
        .navigationTitle(item.title)
        .observing(
            start: { settingsModel.startDebugSettingsObservation() },
            stop: { settingsModel.stopDebugSettingsObservation() }
        )
    }
}
