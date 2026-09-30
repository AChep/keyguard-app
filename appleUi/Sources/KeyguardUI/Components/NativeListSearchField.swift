#if os(macOS)
import AppKit
import SwiftUI

/// A native toolbar search field whose cancel action also updates the binding.
/// SwiftUI's restored searchable toolbar can clear visually without publishing
/// an empty query when its native cancel button is activated through accessibility.
struct NativeListSearchField: NSViewRepresentable {
    @Binding var text: String
    let prompt: String
    var focusRequest = 0

    func makeCoordinator() -> Coordinator { Coordinator(text: $text) }

    func makeNSView(context: Context) -> NSSearchField {
        let field = NSSearchField()
        field.placeholderString = prompt
        field.setAccessibilityLabel(prompt)
        field.stringValue = text
        field.maximumRecents = 0
        field.sendsWholeSearchString = false
        field.sendsSearchStringImmediately = true
        field.delegate = context.coordinator
        field.target = context.coordinator
        field.action = #selector(Coordinator.searchChanged(_:))
        context.coordinator.field = field
        // Own the cancel action as well as ordinary text edits. AXPress on the
        // native cancel cell must update the model, even without a text edit event.
        if let cell = field.cell as? NSSearchFieldCell {
            cell.cancelButtonCell?.target = context.coordinator
            cell.cancelButtonCell?.action = #selector(Coordinator.clearSearch(_:))
        }
        return field
    }

    func updateNSView(_ field: NSSearchField, context: Context) {
        if context.coordinator.focusRequest != focusRequest {
            context.coordinator.focusRequest = focusRequest
            DispatchQueue.main.async { [weak field] in
                guard let field else { return }
                field.window?.makeFirstResponder(field)
            }
        }
        context.coordinator.text = $text
        field.placeholderString = prompt
        field.setAccessibilityLabel(prompt)
        if field.stringValue != text {
            field.stringValue = text
        }
    }

    final class Coordinator: NSObject, NSSearchFieldDelegate {
        var focusRequest = 0
        var text: Binding<String>
        weak var field: NSSearchField?

        init(text: Binding<String>) { self.text = text }

        func controlTextDidChange(_ notification: Notification) {
            guard let field = notification.object as? NSSearchField else { return }
            publish(field.stringValue)
        }

        @MainActor @objc func searchChanged(_ sender: NSSearchField) {
            publish(sender.stringValue)
        }

        func searchFieldDidEndSearching(_ sender: NSSearchField) {
            publish(sender.stringValue)
        }

        @MainActor @objc func clearSearch(_ sender: Any?) {
            field?.stringValue = ""
            field?.currentEditor()?.string = ""
            publish("")
        }

        private func publish(_ value: String) {
            if text.wrappedValue != value {
                text.wrappedValue = value
            }
        }
    }
}
#endif
