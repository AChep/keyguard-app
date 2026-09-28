import SwiftUI
#if os(iOS)
import UIKit
#endif

struct BridgedTextField: View {
    enum Style {
        /// System default — defers to the enclosing container (a grouped
        /// `Form` row supplies its own chrome). Avoids double-decoration on iOS.
        case automatic
        /// Explicit rounded-border box, for fields living in plain stacks.
        case roundedBorder
    }

    let label: String
    let prompt: String?
    let text: String
    let textRevision: Int32
    let secure: Bool
    let send: (String) -> Void

    var style: Style
    var submitLabel: SubmitLabel
    var disablesAutocorrection: Bool

    #if os(iOS)
    var contentType: UITextContentType?
    var keyboard: UIKeyboardType
    var autocapitalization: TextInputAutocapitalization?
    var showsClearButton: Bool
    @FocusState private var isFocused: Bool
    #endif

    @State private var buffer = ""

    #if os(iOS)
    init(
        label: String,
        prompt: String? = nil,
        text: String,
        textRevision: Int32,
        secure: Bool,
        send: @escaping (String) -> Void,
        style: Style,
        submitLabel: SubmitLabel = .return,
        contentType: UITextContentType? = nil,
        keyboard: UIKeyboardType = .default,
        autocapitalization: TextInputAutocapitalization? = nil,
        disablesAutocorrection: Bool = false,
        showsClearButton: Bool = false
    ) {
        self.label = label
        self.prompt = prompt
        self.text = text
        self.textRevision = textRevision
        self.secure = secure
        self.send = send
        self.style = style
        self.submitLabel = submitLabel
        self.contentType = contentType
        self.keyboard = keyboard
        self.autocapitalization = autocapitalization
        self.disablesAutocorrection = disablesAutocorrection
        self.showsClearButton = showsClearButton
    }
    #else
    init(
        label: String,
        prompt: String? = nil,
        text: String,
        textRevision: Int32,
        secure: Bool,
        send: @escaping (String) -> Void,
        style: Style,
        submitLabel: SubmitLabel = .return,
        disablesAutocorrection: Bool = false
    ) {
        self.label = label
        self.prompt = prompt
        self.text = text
        self.textRevision = textRevision
        self.secure = secure
        self.send = send
        self.style = style
        self.submitLabel = submitLabel
        self.disablesAutocorrection = disablesAutocorrection
    }
    #endif

    var body: some View {
        styledField
            .submitLabel(submitLabel)
            .bridgedText(
                $buffer,
                remote: text,
                remoteRevision: textRevision,
                send: send
            )
    }

    @ViewBuilder
    private var styledField: some View {
        switch style {
        case .automatic:
            inputField
                .textFieldStyle(.automatic)
        case .roundedBorder:
            inputField
                .textFieldStyle(.roundedBorder)
        }
    }

    @ViewBuilder
    private var inputField: some View {
        let field = Group {
            if secure {
                SecureField(label, text: $buffer, prompt: prompt.map { Text($0) })
            } else {
                TextField(label, text: $buffer, prompt: prompt.map { Text($0) })
            }
        }
        #if os(iOS)
        let configuredField =
            field
            .textContentType(contentType)
            .keyboardType(keyboard)
            .textInputAutocapitalization(autocapitalization)
            .autocorrectionDisabled(disablesAutocorrection)
            .focused($isFocused)
        if showsClearButton {
            configuredField
                // Reserve horizontal space without making the native form row
                // taller to accommodate the clear button's touch target.
                .padding(.trailing, 44)
                .overlay(alignment: .trailing) {
                    Button(action: clearText) {
                        Label(L10n.textClearAction, systemImage: "xmark.circle.fill")
                            .labelStyle(.iconOnly)
                            .foregroundStyle(.secondary)
                            .touchTarget()
                    }
                    .buttonStyle(.borderless)
                    .opacity(canClear ? 1 : 0)
                    .disabled(!canClear)
                    .accessibilityHidden(!canClear)
                }
        } else {
            configuredField
        }
        #else
        field
            .autocorrectionDisabled(disablesAutocorrection)
        #endif
    }

    #if os(iOS)
    private var canClear: Bool { isFocused && !buffer.isEmpty }

    private func clearText() {
        buffer = ""
        isFocused = true
    }
    #endif
}
