import SwiftUI
import KeyguardShared

struct ChangeKdfSheetContent: View {
    let s: ChangeKdfSnapshot
    let setPassword: (String) -> Void
    let kdf: MasterKdfPerform
    let setBiometric: (Bool) -> Void
    let submit: () -> Void
    @State private var password = ""

    var body: some View {
        ModalSheet(
            title: L10n.masterkdfTitle,
            width: 460,
            height: 640,
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    SecureField(
                        L10n.currentPassword,
                        text: Binding(
                            get: { password },
                            set: {
                                password = $0
                                setPassword($0)
                            }
                        ))
                } footer: {
                    if let error = s.passwordError, !error.isEmpty {
                        Text(error).foregroundStyle(.red)
                    }
                }
                if let kdf = s.kdf {
                    Section {
                        MasterKdfFields(kdf: kdf, perform: self.kdf)
                    } footer: {
                        Text(L10n.changekdfDisclaimerUnlockMethodsNote)
                    }
                }
                BiometricReenrollSection(
                    visible: s.biometricVisible, checked: s.biometricChecked, setChecked: setBiometric)
            }
            .formStyle(.grouped)
            // The number pad of the key derivation fields has no return key.
            .scrollDismissesKeyboard(.interactively)
            .disabled(!s.loaded || s.isLoading)
        } actions: {
            Button(L10n.save) {
                submit()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!s.canConfirm || s.isLoading || s.password != password)
        }
        .onChange(of: s.loaded, initial: true) { _, loaded in
            guard loaded else { return }
            // Seed once from the producer. Later snapshots echo earlier edits
            // and must not replace the local buffer while typing.
            password = s.password
        }
        // Failures arrive on the shared message bus. Keep their feedback
        // above the native sheet covering the root overlay.
        .appToastOverlay()
    }
}
