import SwiftUI
import KeyguardShared

struct AccountClientCertificateScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model: AccountClientCertificateModel

    init(accountId: String, makeModel: (String) -> AccountClientCertificateModel) {
        _model = State(initialValue: makeModel(accountId))
    }

    var body: some View {
        content
            .navigationTitle(L10n.clientCertificate)
            #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    if model.snapshot?.busy == true {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Button(L10n.save) { model.save() }
                            .disabled(model.snapshot?.canSave != true)
                            .keyboardShortcut("s", modifiers: .command)
                    }
                }
            }
            .confirmationDialog(
                L10n.clientCertificateRemoveTitle,
                isPresented: Binding(
                    get: { model.snapshot?.confirmRemoval == true },
                    set: { if !$0 { model.dismissRemoval() } }
                ),
                titleVisibility: .visible
            ) {
                Button(L10n.clientCertificateRemove, role: .destructive) { model.confirmRemoval() }
                Button(L10n.cancel, role: .cancel) { model.dismissRemoval() }
            } message: {
                Text(L10n.clientCertificateRemoveNote)
            }
            .fileDropTarget(text: model.snapshot?.dropText) { model.dropFile(url: $0) }
            #if os(iOS)
        .sheet(
            item: Binding(get: { model.filePicker.pendingFilePicker }, set: { _ in }),
            onDismiss: { model.filePicker.pickerDismissed() }
        ) { request in
            DocumentOpenPicker(request: request) { result in
                model.filePicker.resolve(result: result, requestID: request.id)
            }
            .ignoresSafeArea()
            .interactiveDismissDisabled()
        }
        #endif
        .sheet(
            isPresented: Binding(
                get: { model.dialogs?.confirmation != nil },
                set: { if !$0 { model.dialogs?.closeConfirmation() } }
            )
        ) {
            ConfirmationDialogView().environment(model.dialogs)
        }
        .onAppear { model.start() }
        .onChange(of: model.finished) { _, finished in if finished { dismiss() } }
    }

    @ViewBuilder
    private var content: some View {
        if let state = model.snapshot {
            Form {
                if let note = state.note, let severity = state.noteSeverity {
                    Section {
                        ClientCertificateNoteRow(text: note, severity: severity)
                    }
                }
                if state.configured {
                    certificateSection(state)
                    detailsSection(state)
                } else {
                    emptySection(state)
                }
            }
            .formStyle(.grouped)
            .animation(.default, value: state.configured)
            .animation(.default, value: state.unsaved)
        } else {
            LoadingIndicator()
        }
    }

    private func certificateSection(_ state: AccountClientCertificateSnapshot) -> some View {
        let appearance = ClientCertificateAppearance(status: state.status)
        return Section {
            VStack(spacing: 12) {
                DetailIdentityHeader(
                    title: state.displayName ?? L10n.clientCertificate,
                    subtitle: state.issuerName.map { L10n.clientCertificateIssuer($0) }
                ) { size in
                    ClientCertificateSymbol(appearance: appearance, size: size)
                }
                if state.unsaved {
                    ClientCertificateCapsule(
                        text: L10n.clientCertificateUnsaved, systemImage: "pencil", color: .secondary
                    )
                    .frame(maxWidth: .infinity, alignment: capsuleAlignment)
                }
                if state.status == .expired {
                    Text(L10n.clientCertificateExpired)
                        .foregroundStyle(.red)
                }
                if let progress = state.progress?.doubleValue {
                    ClientCertificateValidity(
                        progress: progress, validFrom: state.validFrom, expiresOn: state.expiresOn,
                        color: appearance.color)
                }
            }
            .padding(.bottom, 8)
            Button {
                model.importCertificate()
            } label: {
                Label(L10n.clientCertificateReplace, systemImage: "doc.badge.arrow.up")
            }
            .disabled(!state.canImport)
            Button(role: .destructive) {
                model.removeCertificate()
            } label: {
                Label(L10n.clientCertificateRemove, systemImage: "trash")
            }
            .disabled(!state.canRemove)
        } footer: {
            // The note closes the form, below the details when there are any.
            if state.details.isEmpty {
                Text(L10n.clientCertificateNote)
            }
        }
    }

    @ViewBuilder
    private func detailsSection(_ state: AccountClientCertificateSnapshot) -> some View {
        if !state.details.isEmpty {
            Section {
                ForEach(state.details, id: \.id) { item in
                    DetailRow(item: item, invoke: { model.invokeAction($0) })
                        .compactControlRowInsets(4)
                }
            } header: {
                Text(L10n.clientCertificateDetails)
            } footer: {
                Text(L10n.clientCertificateNote)
            }
        }
    }

    private func emptySection(_ state: AccountClientCertificateSnapshot) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 16) {
                Image(systemName: "checkmark.seal")
                    .font(.largeTitle)
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(L10n.clientCertificateEmptyTitle)
                        .font(.title2.bold())
                        .accessibilityAddTraits(.isHeader)
                    Text(L10n.clientCertificateEmptyText)
                        .foregroundStyle(.secondary)
                }
                Button {
                    model.importCertificate()
                } label: {
                    Label(L10n.clientCertificateAdd, systemImage: "doc.badge.plus")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!state.canImport)
            }
            .padding(.vertical, 12)
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                Text(L10n.clientCertificateFileTypes)
                Text(L10n.clientCertificateNote)
            }
        }
    }

    private var capsuleAlignment: Alignment {
        #if os(macOS)
        .leading
        #else
        .center
        #endif
    }
}

private struct ClientCertificateAppearance {
    let status: CommonClientCertificateStatus?

    var color: Color {
        if status == .valid { return .green }
        if status == .expiringSoon { return .orange }
        return .red
    }

    var symbol: String {
        if status == .valid { return "checkmark.seal.fill" }
        if status == .expiringSoon { return "exclamationmark.triangle.fill" }
        return "xmark.seal.fill"
    }
}

private struct ClientCertificateSymbol: View {
    let appearance: ClientCertificateAppearance
    let size: CGFloat

    var body: some View {
        Image(systemName: appearance.symbol)
            .font(.system(size: size * 0.5, weight: .medium))
            .foregroundStyle(appearance.color)
            .frame(width: size, height: size)
            .background(
                appearance.color.opacity(0.15),
                in: RoundedRectangle(cornerRadius: size / 4, style: .continuous)
            )
            .accessibilityHidden(true)
    }
}

private struct ClientCertificateCapsule: View {
    let text: String
    let systemImage: String
    let color: Color

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(color.opacity(0.12), in: Capsule())
    }
}

/// How much of the certificate's validity period has passed.
private struct ClientCertificateValidity: View {
    let progress: Double
    let validFrom: String?
    let expiresOn: String?
    let color: Color

    var body: some View {
        VStack(spacing: 8) {
            ProgressView(value: progress)
                .tint(color)
                .accessibilityLabel(L10n.expires)
                .accessibilityValue(expiresOn ?? "")
            HStack(alignment: .firstTextBaseline) {
                date(L10n.validFrom, validFrom, alignment: .leading)
                Spacer(minLength: 16)
                date(L10n.expires, expiresOn, alignment: .trailing)
            }
        }
    }

    private func date(_ label: String, _ value: String?, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value ?? "—")
                .font(.subheadline)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ClientCertificateNoteRow: View {
    let text: String
    let severity: VaultAlertSeverity

    var body: some View {
        let appearance = DetailAlertAppearance(severity: severity)
        Label {
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: appearance.symbol)
                .foregroundStyle(appearance.color)
        }
        .accessibilityValue(appearance.accessibilityLabel)
        #if os(iOS)
        .listRowBackground(appearance.color.opacity(0.10))
        #endif
    }
}
