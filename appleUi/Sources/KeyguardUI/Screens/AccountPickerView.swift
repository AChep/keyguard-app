import SwiftUI
import KeyguardShared

struct AccountPickerView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.accountPicker?.title ?? L10n.saveTo,
            width: 460,
            height: 480,
            detents: [.medium, .large],
            dismissLabel: L10n.cancel
        ) {
            if let snapshot = dialogsModel.accountPicker {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        } actions: {
            Button(L10n.ok) {
                dialogsModel.confirmAccountPicker()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!(dialogsModel.accountPicker?.confirmEnabled ?? false))
        }
    }

    @ViewBuilder
    private func content(_ snapshot: AccountPickerSnapshot) -> some View {
        Form {
            if let note = snapshot.note, !note.isEmpty {
                Section {
                    LeakNote(kind: .info, text: note)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            ForEach(Array(snapshot.sections.enumerated()), id: \.offset) { _, section in
                Section(section.title ?? "") {
                    ForEach(section.items, id: \.key) { item in
                        row(item)
                    }
                }
            }
            if let name = snapshot.newFolderName {
                Section(L10n.folderNew) {
                    BridgedTextField(
                        label: L10n.folderNew,
                        text: name,
                        textRevision: snapshot.newFolderNameRevision,
                        secure: false,
                        send: { dialogsModel.setAccountPickerNewFolderName($0) },
                        style: .automatic
                    )
                    .accessibilityIdentifier("accountPicker.newFolderName")
                    if let error = snapshot.newFolderNameError {
                        Text(error).font(.footnote).foregroundStyle(.red)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    private func row(_ item: AccountPickerItemSnapshot) -> some View {
        Button {
            dialogsModel.selectAccountPickerItem(key: item.key)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title)
                        .foregroundStyle(.primary)
                    if let text = item.text, !text.isEmpty {
                        Text(text)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if item.selected {
                    Image(systemName: "checkmark")
                        .font(.headline)
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!item.enabled)
        .accessibilityAddTraits(item.selected ? [.isButton, .isSelected] : .isButton)
    }
}
