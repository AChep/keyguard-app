import SwiftUI
import KeyguardShared

struct BackupSetupWizard: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model: DetailSessionModel<Bool, BackupSetupSnapshot, BackupSetupSession>
    @State private var picker = FilePickerSession()
    let isValidWebDavURL: (String) -> Bool

    init(
        makeSession: @escaping () -> BackupSetupSession,
        isValidWebDavURL: @escaping (String) -> Bool
    ) {
        self.isValidWebDavURL = isValidWebDavURL
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribeWithCompletion: { BridgeObservation($0.observe(onChange: $1, onClose: $2)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model, initialSnapshot: BackupSetupSnapshot.companion.empty) {
            snapshot, perform in
            if snapshot.loaded {
                BackupSetupWizardContent(
                    s: snapshot,
                    setStoreKind: { kind in
                        perform {
                            picker.cancel()
                            $0.setStoreKind(kind: kind)
                        }
                    },
                    setWebDav: { url, username, password in
                        perform { $0.setWebDav(url: url, username: username, password: password) }
                    },
                    setPassword: { text in perform { $0.setPassword(text: text) } },
                    restorePassword: { perform { $0.restorePassword() } },
                    setIncludeAttachments: { value in
                        perform { $0.setIncludeAttachments(value: value) }
                    },
                    setRetention: { value in perform { $0.setRetention(maxSnapshots: value) } },
                    submit: { perform { $0.submit() } },
                    pickLocation: { pickLocation(perform) },
                    isValidWebDavURL: isValidWebDavURL
                )
            } else {
                NavigationStack {
                    Group {
                        if snapshot.initializationFailed {
                            ContentUnavailableView {
                                Label(
                                    L10n.prefItemAutomaticBackupsUnavailableTitle,
                                    systemImage: "externaldrive.badge.xmark")
                            } description: {
                                Text(L10n.prefItemAutomaticBackupsInitializationError)
                            } actions: {
                                Button(L10n.retry) {
                                    model.stop()
                                    model.setTarget(true)
                                }
                            }
                        } else {
                            ProgressView()
                        }
                    }
                    .navigationTitle(L10n.prefItemAutomaticBackupsWizardTitle)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L10n.cancel) { dismiss() }
                        }
                    }
                }
            }
        }
        #if os(iOS)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .pendingFileImporter(session: picker, defaultContentTypes: [.folder])
        #else
        .frame(minWidth: 480, idealWidth: 540, minHeight: 520, idealHeight: 660)
        #endif
        .onChange(of: model.didComplete) { _, completed in
            if completed {
                picker.cancel()
                dismiss()
            }
        }
        .onDisappear { picker.cancel() }
    }

    private func pickLocation(_ perform: SessionActions<BackupSetupSession>) {
        perform { _ in
            picker.present(
                PendingFilePicker(
                    requestId: UUID().uuidString,
                    kind: .openDirectory,
                    mimeTypes: [],
                    persistent: true,
                    resolve: { _, uri, _, _, token in
                        guard let token else { return }
                        perform { $0.setLocalDirectory(path: uri, accessToken: token) }
                    },
                    cancel: { _ in }
                ))
        }
    }
}
