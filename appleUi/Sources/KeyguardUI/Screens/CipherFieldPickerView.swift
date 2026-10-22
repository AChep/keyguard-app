import SwiftUI
import KeyguardShared

/// Picks a field of another item: the items first, then the fields of the
/// opened item. Values are never shown.
struct CipherFieldPickerView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        let snapshot = dialogsModel.cipherFieldPicker
        let item = snapshot?.item
        ModalSheet(
            title: item == nil ? L10n.cipherFieldPickerItemsTitle : L10n.cipherFieldPickerFieldsTitle,
            width: 480,
            height: 560,
            detents: [.large],
            dismissLabel: L10n.close,
            content: {
                if let snapshot {
                    if let item {
                        fields(item: item, fields: snapshot.fields)
                    } else {
                        items(snapshot: snapshot)
                    }
                }
            },
            actions: { EmptyView() },
            headerActions: {
                if item != nil {
                    Button {
                        dialogsModel.backCipherFieldPicker()
                    } label: {
                        Image(systemName: "chevron.backward")
                    }
                }
            }
        )
    }

    private func items(snapshot: CipherFieldPickerSnapshot) -> some View {
        CipherLinkPickerList(
            query: snapshot.query,
            queryRevision: snapshot.queryRevision,
            items: snapshot.items,
            emptyText: L10n.cipherFieldPickerItemsEmpty,
            setQuery: dialogsModel.setCipherFieldPickerQuery,
            select: { dialogsModel.selectCipherFieldPickerItem(id: $0) }
        )
    }

    private func fields(item: CipherLinkPickerItemSnapshot, fields: [CipherFieldPickerFieldSnapshot]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            CipherLinkPickerTitle(title: item.title, text: item.text)
            Divider()
            // Only items with a field to reference can be opened.
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(fields, id: \.id) { field in
                        Button {
                            dialogsModel.selectCipherFieldPickerField(id: field.id)
                        } label: {
                            Label {
                                CipherLinkPickerTitle(title: field.title, text: field.text)
                            } icon: {
                                Image(systemName: field.password ? "key" : "textformat")
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 12)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!field.enabled)
                    }
                }
            }
        }
        .padding()
    }
}
