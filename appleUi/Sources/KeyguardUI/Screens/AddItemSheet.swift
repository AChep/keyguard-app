import SwiftUI

struct AddItemSheet: View {
    @Environment(SessionFactory.self) private var sessions
    let presentation: AddFormPresentation
    let mode: AddSheetMode
    var prefill: AddCipherPrefill? = nil
    var editRequest: AddEditPrefill? = nil
    var canCreateFileSend = true

    var body: some View {
        Group {
            if let model = presentation.model {
                AddItemSheetContent(
                    mode: mode,
                    prefill: prefill,
                    editRequest: editRequest,
                    canCreateFileSend: canCreateFileSend
                )
                .environment(model)
                .environment(model.dialogs)
                .environment(model.autofillGenerator)
            } else {
                LoadingIndicator()
            }
        }
        .onAppear { presentation.start(makeModel: sessions.makeAddForm) }
    }
}
