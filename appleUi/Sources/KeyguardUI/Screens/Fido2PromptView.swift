import SwiftUI

struct Fido2PromptView: View {
    @Environment(VaultSessionModel.self) private var authModel
    @State private var pin = ""
    @FocusState private var pinFocused: Bool

    private var needsPin: Bool { authModel.fido2Phase == .pinRequired || authModel.fido2Phase == .pinInvalid }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L10n.fido2PromptTitle).font(.headline)
            Text(needsPin ? L10n.fido2PinPrompt : L10n.fido2TouchPrompt)
            if needsPin {
                SecureField(L10n.fido2PinLabel, text: $pin)
                    .focused($pinFocused)
                    .onSubmit(submit)
                if authModel.fido2Phase == .pinInvalid {
                    Label(L10n.fido2ErrorInvalidPin, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            } else {
                ProgressView().accessibilityLabel(L10n.fido2TouchPrompt)
            }
            HStack {
                Spacer()
                Button(L10n.cancel, role: .cancel) { authModel.cancelFido2Prompt() }
                if needsPin {
                    Button(L10n.continue, action: submit)
                        .disabled(pin.isEmpty)
                        .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(24)
        .frame(idealWidth: 400)
        .onChange(of: needsPin) { _, value in pinFocused = value }
        .onDisappear { pin = "" }
    }

    private func submit() {
        guard needsPin && !pin.isEmpty else { return }
        authModel.submitFido2Pin(pin)
        pin = ""
    }
}
