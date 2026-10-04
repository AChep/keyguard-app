import SwiftUI
import UniformTypeIdentifiers
import KeyguardShared

struct KeePassLoginView: View {
    @Environment(KeePassLoginModel.self) private var keepassModel
    @Environment(\.dismiss) private var dismiss

    private var keepass: KeePassLoginSnapshot { keepassModel.keepass }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header

                locationPicker

                modePicker

                if keepass.tabs.contains(where: { $0.checked }) {
                    databaseFileRow

                    passwordField

                    keyFileButtons
                }

                disclaimer

                submitButton
                    .padding(.top, 8)
            }
            .frame(maxWidth: 420, alignment: .leading)
            .padding(40)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(L10n.addkeepassHeaderTitle)
        .sheet(isPresented: webdavPresented) {
            if let snapshot = keepassModel.keepassWebDav { WebDavSettingsSheet(sessionId: snapshot.id) }
        }
        .sheet(isPresented: s3Presented) {
            if let snapshot = keepassModel.keepassS3 { S3SettingsSheet(sessionId: snapshot.id) }
        }
        #if os(iOS)
        // KeePass can itself be presented as an add-account sheet. Its document
        // pickers must be hosted here, above that sheet rather than beside it.
        .sheet(
            item: Binding(
                get: {
                    keepassModel.filePicker.pendingFilePicker.flatMap { $0.kind != .theNewDocument ? $0 : nil }
                },
                set: { _ in }
            )
        ) { request in
            DocumentOpenPicker(request: request) { result in
                keepassModel.filePicker.resolve(result: result, requestID: request.id)
            }
            .ignoresSafeArea()
            .interactiveDismissDisabled()
        }
        .sheet(
            item: Binding(
                get: { keepassModel.filePicker.pendingFileExport },
                // The document picker delegate consumes Save and Cancel results.
                set: { _ in }
            )
        ) { export in
            DocumentExportPicker(export: export)
            .ignoresSafeArea()
            .interactiveDismissDisabled()
        }
        #endif
        .onChange(of: keepassModel.keepassDidSucceed) { _, succeeded in
            if succeeded { dismiss() }
        }
        .onAppear { keepassModel.startKeePassLoginObservation() }
    }

    private var webdavPresented: Binding<Bool> {
        Binding(
            get: { keepassModel.keepassWebDav != nil },
            set: { if !$0 { keepassModel.dismissWebDavSettings() } }
        )
    }

    private var s3Presented: Binding<Bool> {
        Binding(
            get: { keepassModel.keepassS3 != nil },
            set: { if !$0 { keepassModel.dismissS3Settings() } }
        )
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.addkeepassHeaderBody)
                .font(.callout)
                .foregroundStyle(.secondary)
            Label(L10n.addkeepassUseAtYourOwnRiskBetaText, systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .foregroundStyle(.orange)
        }
    }

    // MARK: - Location (local / WebDAV / S3)

    @ViewBuilder
    private var locationPicker: some View {
        if !keepass.locations.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(L10n.databaseLocationTitle)
                    .font(.headline)
                Picker(L10n.databaseLocationTitle, selection: locationSelection) {
                    ForEach(keepass.locations, id: \.key) { location in
                        Label(
                            location.title,
                            systemImage: locationSymbol(location.key)
                        )
                        .tag(location.key)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
        }
    }

    private func locationSymbol(_ key: String) -> String {
        switch key {
        case "webdav": return "cloud"
        case "s3": return "shippingbox"
        default: return "folder"
        }
    }

    private var selectedLocationKey: String {
        keepass.locations.first(where: { $0.checked })?.key ?? "local"
    }

    private var locationSelection: Binding<String> {
        Binding(
            get: { selectedLocationKey },
            set: { keepassModel.selectKeePassLocation(key: $0) }
        )
    }

    // MARK: - Mode (open / create database)

    /// Not a segmented picker on purpose: tapping a mode (even the already
    /// selected one) asks the shared producer to launch the file picker, and a
    /// segmented control swallows re-taps of the selected segment.
    private var modePicker: some View {
        HStack(spacing: 8) {
            ForEach(keepass.tabs, id: \.key) { tab in
                Button {
                    keepassModel.selectKeePassTab(key: tab.key)
                } label: {
                    HStack(spacing: 4) {
                        if tab.checked {
                            Image(systemName: "checkmark")
                                .font(.caption.weight(.semibold))
                        }
                        Text(tab.title)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(tab.checked ? .accentColor : nil)
                .accessibilityAddTraits(tab.checked ? .isSelected : [])
            }
        }
    }

    // MARK: - Database file

    @ViewBuilder
    private var databaseFileRow: some View {
        HStack(spacing: 8) {
            Button {
                keepassModel.pickKeePassDbFile()
            } label: {
                HStack {
                    Image(systemName: selectedLocationKey == "local" ? "doc" : locationSymbol(selectedLocationKey))
                        .foregroundStyle(.secondary)
                    Text(keepass.dbFile?.name ?? L10n.chooseFile)
                        .fontDesign(.monospaced)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let size = fileSizeLabel(keepass.dbFile) {
                        Text(size)
                            .font(.caption)
                            .fontDesign(.monospaced)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if keepass.canClearDbFile {
                Button {
                    keepassModel.clearKeePassDbFile()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .touchTarget()
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.clearFile)
            }
        }
    }

    private func fileSizeLabel(_ file: KeePassFileSnapshot?) -> String? {
        guard let file, file.size >= 0 else { return nil }
        return file.size.formatted(.byteCount(style: .file))
    }

    // MARK: - Password + key file

    private var passwordField: some View {
        VStack(alignment: .leading, spacing: 4) {
            BridgedTextField(
                label: L10n.password,
                text: keepass.password.text,
                textRevision: keepass.password.textRevision,
                secure: true,
                send: { keepassModel.setKeePassPassword(text: $0) },
                style: .roundedBorder
            )
            .id(keepass.password.id)
            if let error = keepass.password.error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }

    private var keyFileButtons: some View {
        HStack(spacing: 8) {
            Button {
                keepassModel.pickKeePassKeyFile()
            } label: {
                Label(
                    keepass.keyFile != nil ? L10n.replaceKeyFile : L10n.selectKeyFile,
                    systemImage: "key"
                )
            }
            .buttonStyle(.bordered)

            if keepass.canClearKeyFile {
                Button {
                    keepassModel.clearKeePassKeyFile()
                } label: {
                    Label(L10n.clearFile, systemImage: "xmark")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    // MARK: - Footer

    private var disclaimer: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(L10n.backupDisclaimerTitle, systemImage: "info.circle")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(L10n.backupDisclaimerText)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }

    private var submitButton: some View {
        Button(action: submit) {
            if keepass.isLoading {
                ProgressView()
                    .controlSize(.small)
                    .frame(maxWidth: .infinity)
            } else {
                Text(L10n.addaccountSignInButton)
                    .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .accessibilityLabel(L10n.addaccountSignInButton)
        .disabled(!keepass.canSubmit || keepass.isLoading)
    }

    private func submit() {
        guard keepass.canSubmit, !keepass.isLoading else { return }
        keepassModel.submitKeePassLogin()
    }
}
