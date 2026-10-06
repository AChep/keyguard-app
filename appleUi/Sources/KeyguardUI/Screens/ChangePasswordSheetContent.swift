import SwiftUI
import KeyguardShared

struct ChangePasswordSheetContent: View {
    let s: ChangePasswordSnapshot
    let setCurrentPassword: (String) -> Void
    let setNewPassword: (String) -> Void
    let setBiometric: (Bool) -> Void
    let submit: () -> Void
    @State private var currentPassword = ""
    @State private var newPassword = ""

    var body: some View {
        ModalSheet(
            title: L10n.changepasswordMasterPasswordTitle,
            width: 460,
            height: 420,
            // Full-height on iOS: two SecureFields plus the software keyboard cramp
            // the .medium detent (detents are a no-op on macOS).
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    SecureField(
                        L10n.currentPassword,
                        text: Binding(
                            get: { currentPassword },
                            set: {
                                currentPassword = $0
                                setCurrentPassword($0)
                            }
                        ))
                } footer: {
                    if let error = s.currentError, !error.isEmpty {
                        Text(error).foregroundStyle(.red)
                    }
                }
                Section {
                    SecureField(
                        L10n.newPassword,
                        text: Binding(
                            get: { newPassword },
                            set: {
                                newPassword = $0
                                setNewPassword($0)
                            }
                        ))
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        if let error = s.newError, !error.isEmpty {
                            Text(error).foregroundStyle(.red)
                        }
                        Text(L10n.changepasswordDisclaimerLocalNote)
                        Text(L10n.changepasswordDisclaimerAbuseNote)
                    }
                }
                if s.biometricVisible {
                    Section {
                        Toggle(
                            L10n.changepasswordBiometricAuthCheckbox,
                            isOn: Binding(
                                get: { s.biometricChecked },
                                set: { setBiometric($0) }
                            ))
                    }
                }
            }
            .formStyle(.grouped)
            .disabled(!s.loaded || s.isLoading)
        } actions: {
            Button(L10n.change) {
                submit()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(
                !s.canConfirm || s.isLoading || s.currentPassword != currentPassword || s.newPassword != newPassword
            )
        }
        .onChange(of: s.loaded, initial: true) { _, loaded in
            guard loaded else { return }
            // Seed once from the producer. Later snapshots echo earlier edits
            // and must not replace the local buffer while typing.
            currentPassword = s.currentPassword
            newPassword = s.newPassword
        }
        // Password validation failures arrive on the shared message bus. Keep
        // their feedback above the native sheet covering the root overlay.
        .appToastOverlay()
    }
}
