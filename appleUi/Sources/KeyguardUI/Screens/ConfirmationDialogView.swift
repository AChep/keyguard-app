import SwiftUI
import KeyguardShared

struct ConfirmationDialogView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.confirmation?.title ?? "",
            width: 440,
            height: 440,
            detents: [.medium, .large],
            dismissLabel: L10n.cancel
        ) {
            if let snapshot = dialogsModel.confirmation {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        } actions: {
            Button(L10n.confirm) {
                dialogsModel.confirmConfirmation()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!(dialogsModel.confirmation?.confirmEnabled ?? false))
        }
    }

    @ViewBuilder
    private func content(_ snapshot: ConfirmationSnapshot) -> some View {
        Form {
            if let message = snapshot.message, !message.isEmpty {
                Section {
                    Text(message)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
            if !snapshot.items.isEmpty {
                Section {
                    ForEach(snapshot.items, id: \.key) { item in
                        HStack {
                            row(item)
                            if item.removable {
                                Button(role: .destructive) {
                                    dialogsModel.removeConfirmationItem(key: item.key)
                                } label: {
                                    Image(systemName: "trash")
                                        .touchTarget()
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(L10n.delete)
                            }
                        }
                    }
                }
            }
            if snapshot.canAddItem {
                Section {
                    Button(L10n.listAdd, systemImage: "plus") {
                        dialogsModel.addConfirmationItem()
                    }
                }
            }
            if let docUrl = snapshot.docUrl, let url = URL(string: docUrl) {
                Section {
                    Link(L10n.uriActionLaunchDocsTitle, destination: url)
                }
            }
        }
        .formStyle(.grouped)
    }

    // Kotlin enums bridge to Swift as classes (not native `enum`s), so dispatch
    // on `kind` with `==` comparisons rather than a `switch`.
    @ViewBuilder
    private func row(_ item: ConfirmationItemSnapshot) -> some View {
        if item.kind == ConfirmationItemKind.boolean {
            booleanRow(item)
        } else if item.kind == ConfirmationItemKind.string {
            stringRow(item)
        } else if item.kind == ConfirmationItemKind.choice {
            choiceRow(item)
        } else if item.kind == ConfirmationItemKind.file {
            fileRow(item)
        }
    }

    @ViewBuilder
    private func booleanRow(_ item: ConfirmationItemSnapshot) -> some View {
        Toggle(
            isOn: Binding(
                get: { item.booleanValue },
                set: { dialogsModel.setConfirmationItemBoolean(key: item.key, value: $0) }
            )
        ) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                if let text = item.text, !text.isEmpty {
                    Text(text)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .disabled(!item.enabled)
    }

    private func stringRow(_ item: ConfirmationItemSnapshot) -> some View {
        ConfirmationStringField(item: item) {
            dialogsModel.setConfirmationItemString(key: item.key, text: $0)
        }
    }

    @ViewBuilder
    private func choiceRow(_ item: ConfirmationItemSnapshot) -> some View {
        let selection = Binding<String>(
            get: { item.options.first(where: { $0.selected })?.key ?? "" },
            set: { dialogsModel.selectConfirmationItemEnum(key: item.key, optionKey: $0) }
        )
        VStack(alignment: .leading, spacing: 4) {
            Picker(item.title, selection: selection) {
                ForEach(item.options, id: \.key) { option in
                    Text(option.title).tag(option.key)
                }
            }
            .pickerStyle(.menu)
            .disabled(!item.enabled)
            if let docText = item.docText, !docText.isEmpty {
                Text(docText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if item.docHasLink {
                Button(L10n.learnMore) {
                    dialogsModel.openConfirmationItemDoc(key: item.key)
                }
                .font(.caption)
                .buttonStyle(.borderless)
            }
        }
    }

    @ViewBuilder
    private func fileRow(_ item: ConfirmationItemSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if !item.title.isEmpty {
                Text(item.title)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            HStack {
                if item.hasFile {
                    Image(systemName: "doc")
                    Text(item.fileName ?? L10n.filePickerSelectedFileLabel)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    Button {
                        dialogsModel.clearConfirmationItemFile(key: item.key)
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                            .touchTarget()
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L10n.clearFile)
                } else {
                    Button(L10n.chooseFile) {
                        dialogsModel.selectConfirmationItemFile(key: item.key)
                    }
                    Spacer()
                }
            }
            .disabled(!item.enabled)
            if let error = item.error, !error.isEmpty {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }
}
