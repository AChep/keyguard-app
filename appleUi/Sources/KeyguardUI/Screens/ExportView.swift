import SwiftUI
import KeyguardShared

/// The export wizard. It stays one navigation entry and switches its steps
/// in place, so "View items" can still push onto the same stack.
struct ExportView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    @State private var step: ExportWizardStep = .format
    @State private var password = ""
    @State private var confirmation = ""
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case password
        case confirmation
    }

    private var snapshot: ExportSnapshot {
        // `export` is a Swift reserved word, so Kotlin/Native exported the property as `export_`.
        entry.export_ ?? ExportSnapshot.companion.empty
    }

    var body: some View {
        Group {
            if !snapshot.loaded {
                LoadingIndicator()
            } else {
                wizard
            }
        }
        .navigationTitle(snapshot.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    @ViewBuilder
    private var wizard: some View {
        if let previous = step.previous {
            page(step)
                .navigationBarBackButtonHidden(true)
                .toolbar {
                    ToolbarItem(placement: .navigation) {
                        Button {
                            focusedField = nil
                            withAnimation { step = previous }
                        } label: {
                            Label(L10n.additemKeyGeneratorBackTitle, systemImage: "chevron.backward")
                        }
                        .disabled(snapshot.running)
                    }
                }
        } else {
            // The first step keeps the back button of the
            // stack, which leaves the export.
            page(step)
        }
    }

    private func page(_ step: ExportWizardStep) -> some View {
        Form {
            Section {
                WizardHeader(
                    title: step.title,
                    subtitle: step.subtitle,
                    systemImage: step.systemImage,
                    step: step.number,
                    stepCount: ExportWizardStep.allCases.count
                )
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }
            switch step {
            case .format: formatSection
            case .items: itemsSections
            case .password: passwordSection
            case .review: reviewSection
            }
        }
        .formStyle(.grouped)
        .scrollDismissesKeyboard(.interactively)
        // Before the footer, so Cancel stays enabled.
        .disabled(snapshot.running)
        .id(step)
        .wizardFooter { footer(for: step) }
    }

    private var formatSection: some View {
        Section {
            ForEach(snapshot.formats, id: \.id) { option in
                WizardChoiceRow(
                    title: option.title,
                    systemImage: formatSystemImage(option.id),
                    selected: option.selected
                ) {
                    invoke(option.id)
                }
            }
        } footer: {
            Label(snapshot.formatNote, systemImage: "info.circle")
        }
    }

    @ViewBuilder
    private var itemsSections: some View {
        Section {
            Button {
                invoke("export:view:items")
            } label: {
                HStack {
                    Text(L10n.items)
                    Spacer()
                    Text(L10n.exportaccountItemsCountPlural(Int(snapshot.itemsCount)))
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            FilterMenu(
                filters: snapshot.filters,
                canClearFilters: snapshot.canClearFilters,
                canSaveFilters: snapshot.canSaveFilters,
                activeFilterCount: Int(snapshot.activeFilterCount),
                invoke: invoke,
                clear: { invoke("export:filters:clear") },
                save: { invoke("export:filters:save") }
            )
        }
        if snapshot.canToggleAttachments {
            Section {
                Toggle(
                    isOn: Binding(
                        get: { snapshot.attachmentsEnabled },
                        set: { _ in invoke("export:atts:toggle") }
                    )
                ) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.exportaccountIncludeAttachmentsTitle)
                        if let size = snapshot.attachmentsSize, !size.isEmpty {
                            Text(size)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } footer: {
                if snapshot.attachmentsEnabled {
                    Label(snapshot.attachmentsNote, systemImage: "info.circle")
                }
            }
        }
    }

    private var passwordSection: some View {
        Section {
            SecureField(snapshot.passwordHint ?? snapshot.passwordLabel, text: $password)
                .focused($focusedField, equals: .password)
                .privacySensitive()
                .submitLabel(.next)
                .onSubmit { focusedField = .confirmation }
                .bridgedText(
                    $password,
                    remote: snapshot.passwordValue,
                    remoteRevision: snapshot.passwordRevision
                ) {
                    navigationModel.setExportPassword(instanceId: entry.instanceId, text: $0)
                }
            SecureField(snapshot.confirmationPasswordLabel, text: $confirmation)
                .focused($focusedField, equals: .confirmation)
                .privacySensitive()
                .submitLabel(.continue)
                .onSubmit { advance(from: .password) }
                .bridgedText(
                    $confirmation,
                    remote: snapshot.confirmationPasswordValue,
                    remoteRevision: snapshot.confirmationPasswordRevision
                ) {
                    navigationModel.setExportConfirmationPassword(instanceId: entry.instanceId, text: $0)
                }
        } footer: {
            if let error = passwordError {
                // Pair the message with a leading glyph so the error is not
                // conveyed by color alone (color-blind / VoiceOver users).
                Label {
                    Text(error)
                } icon: {
                    Image(systemName: "exclamationmark.circle")
                }
                .foregroundStyle(Color(platform: .platformDanger))
            }
        }
    }

    private var reviewSection: some View {
        Section {
            LabeledContent(L10n.exportaccountFormatTitle, value: snapshot.formatTitle)
            LabeledContent(L10n.items, value: L10n.exportaccountItemsCountPlural(Int(snapshot.itemsCount)))
            if snapshot.canToggleAttachments {
                LabeledContent(L10n.exportaccountIncludeAttachmentsTitle, value: attachmentsSummary)
            }
        }
    }

    @ViewBuilder
    private func footer(for step: ExportWizardStep) -> some View {
        if snapshot.running {
            HStack(spacing: 12) {
                if let progress = snapshot.progress {
                    ProgressView(value: progress.doubleValue)
                } else {
                    ProgressView()
                }
                Button(L10n.cancel) {
                    invoke("export:cancel")
                }
                .disabled(!snapshot.canCancel)
            }
        } else {
            WizardPrimaryButton {
                advance(from: step)
            } label: {
                if step == .review {
                    Label(L10n.exportaccountExportButton, systemImage: "square.and.arrow.down")
                } else {
                    Text(L10n.continue)
                }
            }
            .disabled(!canAdvance(from: step))
        }
    }

    private var passwordError: String? {
        [snapshot.passwordError, snapshot.confirmationPasswordError]
            .compactMap { $0 }
            .first { !$0.isEmpty }
    }

    private var attachmentsSummary: String {
        guard snapshot.attachmentsEnabled else { return L10n.disabled }
        if let size = snapshot.attachmentsSize, !size.isEmpty {
            return L10n.exportaccountReviewAttachmentsEnabled(size)
        }
        return L10n.enabled
    }

    private func formatSystemImage(_ id: String) -> String {
        id == "export:format:kdbx" ? "key.horizontal" : "doc.zipper"
    }

    private func canAdvance(from step: ExportWizardStep) -> Bool {
        switch step {
        case .format: true
        case .items: snapshot.itemsCount > 0
        case .password: snapshot.passwordValid
        // Kotlin decides if the export can start.
        case .review: snapshot.canExport
        }
    }

    private func advance(from step: ExportWizardStep) {
        guard canAdvance(from: step), !snapshot.running else { return }
        focusedField = nil
        if let next = step.next {
            withAnimation { self.step = next }
        } else {
            invoke("export:run")
        }
    }

    private func invoke(_ id: String) {
        navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: id)
    }
}
