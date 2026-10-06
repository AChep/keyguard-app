#if os(macOS)
import SwiftUI
import XCTest
import KeyguardShared
@testable import KeyguardUI

@MainActor
final class FormFieldEditingTests: XCTestCase {
    func testValidationEchoPreservesDraftFocusAndSelection() async throws {
        var edits: [String] = []
        let host = NSHostingView(rootView: editor(value: "Original", onChange: { edits.append($0) }))
        let window = window(host: host)
        defer { window.close() }
        await settle(host)
        let field = try XCTUnwrap(descendants(host).compactMap { $0 as? NSTextField }.first)
        XCTAssertEqual(field.stringValue, "Original")
        XCTAssertTrue(window.makeFirstResponder(field))
        let input = try XCTUnwrap(field.currentEditor() as? NSTextView)
        input.setSelectedRange(NSRange(location: 3, length: 0))
        input.insertText("x", replacementRange: input.selectedRange())
        await settle(host)
        XCTAssertEqual(edits.last, "Orixginal")

        host.rootView = editor(value: "Original", error: "Invalid value", onChange: { edits.append($0) })
        await settle(host)
        XCTAssertEqual(field.stringValue, "Orixginal")
        XCTAssertTrue(field.currentEditor() === input)
        XCTAssertEqual(input.selectedRange(), NSRange(location: 4, length: 0))

        host.rootView = editor(value: "Generated", revision: 1, onChange: { edits.append($0) })
        await settle(host)
        XCTAssertEqual(field.stringValue, "Generated")
    }

    func testNotesAcceptReturnAsANewline() async throws {
        var edits: [String] = []
        let host = NSHostingView(rootView: editor(value: "Line", multiline: true, onChange: { edits.append($0) }))
        let window = window(host: host)
        defer { window.close() }
        await settle(host)
        let input = try XCTUnwrap(descendants(host).compactMap { $0 as? NSTextView }.first)
        XCTAssertTrue(window.makeFirstResponder(input))
        input.setSelectedRange(NSRange(location: 4, length: 0))
        input.insertNewline(nil)
        await settle(host)
        XCTAssertEqual(edits.last, "Line\n")
    }

    func testSecureValuesUseASecureNativeControl() async throws {
        let host = NSHostingView(rootView: editor(value: "Synthetic", hidden: true, onChange: { _ in }))
        let window = window(host: host)
        defer { window.close() }
        await settle(host)
        XCTAssertTrue(descendants(host).contains { $0 is NSSecureTextField })
    }

    private func editor(
        value: String, revision: Int32 = 0, error: String? = nil,
        hidden: Bool = false, multiline: Bool = false, onChange: @escaping (String) -> Void
    ) -> some View {
        Form {
            Section {
                AddFieldEditor(
                    field: AddTextFieldSnapshot(
                        field: TextFieldSnapshot(
                            id: "field", text: value, textRevision: revision, placeholder: "Placeholder",
                            error: error, vlType: nil, vlText: nil, editable: true
                        ),
                        label: "Field", hidden: hidden, multiline: multiline, autofill: nil
                    ),
                    fallbackLabel: nil, onChange: onChange, onAutofill: { _ in }
                )
            }
        }
        .formStyle(.grouped)
        .frame(width: 360, height: 240)
    }

    private func window<Content: View>(host: NSHostingView<Content>) -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 300),
            styleMask: [.titled], backing: .buffered, defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = host
        return window
    }

    private func settle(_ view: NSView) async {
        view.layoutSubtreeIfNeeded()
        try? await Task.sleep(for: .milliseconds(40))
        view.layoutSubtreeIfNeeded()
    }

    private func descendants(_ view: NSView) -> [NSView] {
        view.subviews.flatMap { [$0] + descendants($0) }
    }
}
#endif
