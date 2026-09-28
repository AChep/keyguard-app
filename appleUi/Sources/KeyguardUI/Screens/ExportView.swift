import SwiftUI
import KeyguardShared

struct ExportView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    private var snapshot: ExportSnapshot {
        // `export` is a Swift reserved word, so Kotlin/Native exported the property as `export_`.
        entry.export_ ?? ExportSnapshot.companion.empty
    }

    var body: some View {
        Group {
            if !snapshot.loaded {
                LoadingIndicator()
            } else {
                VStack(spacing: 0) {
                    if snapshot.running {
                        HStack {
                            if let progress = snapshot.progress {
                                ProgressView(value: progress.doubleValue)
                            } else {
                                ProgressView()
                            }
                            Button(L10n.cancel) {
                                navigationModel.invokeEntryAction(
                                    instanceId: entry.instanceId, actionId: "export:cancel")
                            }
                            .disabled(!snapshot.canCancel)
                        }
                        .padding()
                    }
                    form.disabled(snapshot.running)
                }
            }
        }
        .navigationTitle(snapshot.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: "export:run")
                } label: {
                    Label(L10n.exportaccountExportButton, systemImage: "square.and.arrow.down")
                }
                .disabled(!snapshot.canExport)
            }
        }
    }

    private var form: some View {
        Form {
            Section {
                BridgedTextField(
                    label: snapshot.passwordHint ?? L10n.exportaccountPasswordLabel,
                    text: snapshot.passwordValue,
                    textRevision: snapshot.passwordRevision,
                    secure: true,
                    send: { navigationModel.setExportPassword(instanceId: entry.instanceId, text: $0) },
                    style: .automatic
                )
                if let error = snapshot.passwordError, !error.isEmpty {
                    // Pair the message with a leading glyph so the error is not
                    // conveyed by color alone (color-blind / VoiceOver users).
                    Label {
                        Text(error)
                    } icon: {
                        Image(systemName: "exclamationmark.circle")
                    }
                    .font(.caption)
                    .foregroundStyle(Color(platform: .platformDanger))
                }
            }
            if snapshot.canToggleAttachments {
                Section {
                    Toggle(
                        isOn: Binding(
                            get: { snapshot.attachmentsEnabled },
                            set: { _ in
                                navigationModel.invokeEntryAction(
                                    instanceId: entry.instanceId,
                                    actionId: "export:atts:toggle"
                                )
                            }
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
                }
            }
            Section {
                Button {
                    navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: "export:view:items")
                } label: {
                    HStack {
                        Text(L10n.items)
                        Spacer()
                        Text("\(snapshot.itemsCount)")
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.forward")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
            }
            Section {
                Label {
                    Text(L10n.exportaccountFormatNote)
                } icon: {
                    Image(systemName: "info.circle")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}
