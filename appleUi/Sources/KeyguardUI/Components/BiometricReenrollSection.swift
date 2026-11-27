import SwiftUI

/// The option to keep the biometric unlock after the vault key changes: the password
/// and key derivation editors re-enroll it. Empty while it is not available.
struct BiometricReenrollSection: View {
    let visible: Bool
    let checked: Bool
    let setChecked: (Bool) -> Void

    var body: some View {
        if visible {
            Section {
                Toggle(
                    L10n.changepasswordBiometricAuthCheckbox,
                    isOn: Binding(get: { checked }, set: setChecked)
                )
            }
        }
    }
}
