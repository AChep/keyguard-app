import SwiftUI
import KeyguardShared

/// Only the standalone iPad form opts into draft and focus restoration.
struct GeneratorEditingTextField: View {
    let filter: GeneratorFilterSnapshot
    let actions: GeneratorActions
    let editing: GeneratorEditingState
    @State private var mountToken = UUID()

    var body: some View {
        var field = generatorTextField(filter, actions: actions)
        field.draft = Binding(
            get: { editing.text(key: filter.key, remote: filter.textValue, revision: filter.textRevision) },
            set: {
                // Native TextField can write its current value again when gaining
                // focus. Only a changed draft is an edit, including after a remount.
                guard $0 != editing.text(key: filter.key, remote: filter.textValue, revision: filter.textRevision)
                else {
                    return
                }
                editing.edit(key: filter.key, text: $0, revision: filter.textRevision)
                actions.setText(filter.key, $0)
            }
        )
        #if os(iOS)
        field.requestedFocus = editing.focusedKey == filter.key
        field.onFocusChange = { focused in
            editing.focusChanged(key: filter.key, token: mountToken, focused: focused)
        }
        #endif
        return
            field
            .multilineTextAlignment(.trailing)
            .onAppear { editing.mount(key: filter.key, token: mountToken) }
            .onDisappear { editing.unmount(key: filter.key, token: mountToken) }
            .onChange(of: filter.textRevision, initial: true) { _, revision in
                editing.reconcile(key: filter.key, remote: filter.textValue, revision: revision)
            }
    }
}
