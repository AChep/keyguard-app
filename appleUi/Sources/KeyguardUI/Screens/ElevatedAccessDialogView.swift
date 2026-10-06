import SwiftUI
import KeyguardShared

struct ElevatedAccessDialogView: View {
    @Environment(DialogsModel.self) private var dialogsModel
    @State private var password = ""
    @State private var pendingSubmission: String?

    private var canSubmit: Bool {
        guard let snapshot = dialogsModel.elevatedAccess else { return false }
        return snapshot.confirmEnabled && !snapshot.isLoading && snapshot.passwordValue == password
    }

    var body: some View {
        ModalSheet(
            title: dialogsModel.elevatedAccess?.title ?? "",
            width: 440,
            height: 340,
            detents: [.medium],
            dismissLabel: L10n.cancel
        ) {
            if let snapshot = dialogsModel.elevatedAccess {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        } actions: {
            let isLoading = dialogsModel.elevatedAccess?.isLoading ?? false
            // An action-specific verb rather than a generic "OK": "Continue" names
            // the outcome of confirming the master password.
            Button(action: submit) {
                if isLoading {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Text(L10n.continue)
                }
            }
            .accessibilityLabel(L10n.continue)
            .keyboardShortcut(.defaultAction)
            .disabled(!canSubmit)
        }
        .onChange(of: password) { _, value in
            if pendingSubmission != value { pendingSubmission = nil }
        }
        .onChange(of: canSubmit) { _, enabled in
            if enabled, pendingSubmission == password { submit() }
        }
        .onDisappear { pendingSubmission = nil }
    }

    @ViewBuilder
    private func content(_ snapshot: ElevatedAccessSnapshot) -> some View {
        Form {
            if !snapshot.message.isEmpty {
                Section {
                    Text(snapshot.message)
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
            }
            Section {
                SecureField(snapshot.passwordHint ?? "", text: $password)
                    .textFieldStyle(.automatic)
                    #if os(iOS)
                .textContentType(.password)
                    #endif
                    .accessibilityLabel(L10n.setupFieldAppPasswordLabel)
                    .disabled(snapshot.isLoading)
                    .bridgedText(
                        $password,
                        remote: snapshot.passwordValue,
                        remoteRevision: Int32(snapshot.passwordRevision),
                        send: { dialogsModel.setElevatedAccessPassword(text: $0) }
                    )
                    .onSubmit(submit)
                if let error = snapshot.passwordError, !error.isEmpty {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            if snapshot.hasBiometric || snapshot.hasYubiKey {
                Section {
                    if snapshot.hasBiometric {
                        Button {
                            dialogsModel.triggerElevatedAccessBiometric()
                        } label: {
                            Label(
                                AppleBiometry.current.confirmTitle(bundle: AppLocalization.shared.bundle),
                                systemImage: AppleBiometry.current.symbol)
                        }
                        .disabled(snapshot.isLoading || !snapshot.biometricEnabled)
                    }
                    if snapshot.hasYubiKey {
                        Button {
                            dialogsModel.triggerElevatedAccessYubiKey()
                        } label: {
                            Label(L10n.confirmYubikeyTitle, systemImage: "key.fill")
                        }
                        .disabled(snapshot.isLoading || !snapshot.yubiKeyEnabled)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    private func submit() {
        guard let snapshot = dialogsModel.elevatedAccess, !snapshot.isLoading else { return }
        // Return may precede the field's onChange or the acknowledged snapshot.
        dialogsModel.setElevatedAccessPassword(text: password)
        guard canSubmit else {
            pendingSubmission = password
            return
        }
        pendingSubmission = nil
        dialogsModel.confirmElevatedAccess()
    }
}

/// The active auxiliary surface owns its authentication sheet, including feedback
/// for an incorrect password. The main window must not present a second copy.
enum ElevatedAccessLocalHost: Int, Hashable {
    case menuBar
    case quickSearch
    case recents
}

private struct LocalElevatedAccessModifier: ViewModifier {
    @Environment(DialogsModel.self) private var dialogsModel
    let host: ElevatedAccessLocalHost
    let isEnabled: Bool

    func body(content: Content) -> some View {
        content
            .sheet(
                isPresented: Binding(
                    get: {
                        isEnabled && dialogsModel.elevatedAccessLocalHost == host && dialogsModel.elevatedAccess != nil
                    },
                    set: { presented in
                        if !presented && dialogsModel.elevatedAccessLocalHost == host {
                            dialogsModel.closeElevatedAccess()
                        }
                    }
                )
            ) {
                ElevatedAccessDialogView()
                    .appToastOverlay()
            }
            .onAppear {
                if isEnabled { dialogsModel.elevatedAccessLocalHosts.insert(host) }
            }
            .onChange(of: isEnabled) { _, enabled in
                if enabled {
                    dialogsModel.elevatedAccessLocalHosts.insert(host)
                } else {
                    unregister()
                }
            }
            .onDisappear { unregister() }
    }

    private func unregister() {
        if dialogsModel.elevatedAccessLocalHost == host && dialogsModel.elevatedAccess != nil {
            dialogsModel.closeElevatedAccess()
        }
        dialogsModel.elevatedAccessLocalHosts.remove(host)
    }
}

extension View {
    func localElevatedAccessHost(_ host: ElevatedAccessLocalHost, isEnabled: Bool = true) -> some View {
        modifier(LocalElevatedAccessModifier(host: host, isEnabled: isEnabled))
    }
}
