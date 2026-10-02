import SwiftUI

#if os(macOS)
struct VaultDetailPane: View {
    let listModel: VaultListSessionModel
    let selection: VaultSelectionModel
    let model: CipherDetailModel

    /// O(1): observes the selected row's box and the frame revision, never the whole list.
    private var target: ItemDetailTarget? {
        // Boxes track the rendered structure, so a row that left the list resolves to no detail.
        _ = listModel.store.structure.revision
        guard selection.selectedRowIds.count == 1,
            let id = selection.selectedRowIds.first,
            let row = listModel.store.boxes[id]?.row,
            let itemId = row.secretId, let accountId = row.accountId
        else { return nil }
        return ItemDetailTarget(itemId: itemId, accountId: accountId)
    }

    var body: some View {
        Group {
            if let target {
                DetailSessionContent(model: model, target: target) { frame, perform in
                    CipherDetailView(
                        detail: frame.detail,
                        invoke: { id in perform { $0.invokeAction(id: id) } },
                        toggleFavorite: { perform { $0.toggleFavorite() } },
                        totpProvider: { [totp = frame.totp, cipherId = frame.detail.cipherId] rowId in
                            totp.state(cipherId: cipherId, rowId: rowId)
                        }
                    )
                }
            } else {
                ListNoSelectionView(kind: .vault)
            }
        }
        .onChange(of: selection.selectedRowIds, initial: true) { _, selected in
            if selected.count == 1, let rowId = selected.first { listModel.setOpenedRow(rowId: rowId) }
        }
    }
}
#endif
