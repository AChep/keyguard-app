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
            if concealed { SecureField(title, text: $text) } else { TextField(title, text: $text, axis: .vertical) }
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
}
