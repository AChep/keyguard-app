import SwiftUI

struct EnvironmentTextField: View {
    let title: String
    let value: String
    var concealed = false
    let onChange: (String) -> Void
    @State private var text = ""
    @State private var pendingValue: String?

    var body: some View {
        Group {
            if concealed { concealedField } else { TextField(title, text: $text, axis: .vertical) }
        }
        .onAppear { text = value }
        .onChange(of: value) { _, value in
            if value == pendingValue { pendingValue = nil }
            if pendingValue == nil { text = value }
        }
        .onChange(of: text) { _, value in
            if value != self.value {
                pendingValue = value
                onChange(value)
            }
        }
    }

    @ViewBuilder
    private var concealedField: some View {
        #if os(iOS)
        // UIKit's secure field is single-line: it turns pasted line
        // breaks into spaces, and a keystroke after refocusing erases
        // the value. A concealed value is read-only until revealed.
        LabeledContent(title) {
            if !text.isEmpty {
                // A fixed-length mask, so it does not leak the length.
                Text(verbatim: "••••••••")
                    .font(.body.monospaced())
                    .accessibilityLabel(L10n.hidden)
            }
        }
        #else
        // AppKit's secure field keeps pasted line breaks.
        SecureField(title, text: $text)
        #endif
    }
}
