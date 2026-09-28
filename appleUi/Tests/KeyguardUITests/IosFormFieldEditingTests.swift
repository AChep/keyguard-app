#if os(iOS)
import SwiftUI
import XCTest
import KeyguardShared
@testable import KeyguardUI

@MainActor
final class IosFormFieldEditingTests: XCTestCase {
    func testValidationEchoPreservesDraftFocusAndSelection() async throws {
        // Swift Package tests normally run in xctest without UIApplicationMain,
        // which cannot dispatch UIControl editing events. Use an isolated app
        // host when running this interaction test.
        try XCTSkipUnless(Bundle.main.bundleURL.pathExtension == "app", "Requires an iOS application test host")
        var edits: [String] = []
        let host = UIHostingController(rootView: editor(value: "Original", onChange: { edits.append($0) }))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        window.rootViewController = host
        window.isHidden = false
        defer { window.isHidden = true }
        await settle(host.view)
        let field = try XCTUnwrap(descendants(host.view).compactMap { $0 as? UITextField }.first)
        XCTAssertEqual(field.text, "Original")
        XCTAssertTrue(field.becomeFirstResponder())
        let position = try XCTUnwrap(field.position(from: field.beginningOfDocument, offset: 3))
        field.selectedTextRange = field.textRange(from: position, to: position)
        field.insertText("x")
        await settle(host.view)
        XCTAssertEqual(edits.last, "Orixginal")

        host.rootView = editor(value: "Original", error: "Invalid value", onChange: { edits.append($0) })
        await settle(host.view)
        XCTAssertEqual(field.text, "Orixginal")
        XCTAssertTrue(field.isFirstResponder)
        let selection = try XCTUnwrap(field.selectedTextRange)
        XCTAssertEqual(field.offset(from: field.beginningOfDocument, to: selection.start), 4)

        host.rootView = editor(value: "Generated", revision: 1, onChange: { edits.append($0) })
        await settle(host.view)
        XCTAssertEqual(field.text, "Generated")
    }

    private func editor(
        value: String, revision: Int32 = 0, error: String? = nil, onChange: @escaping (String) -> Void
    ) -> some View {
        Form {
            Section {
                AddFieldEditor(
                    field: AddTextFieldSnapshot(
                        field: TextFieldSnapshot(
                            id: "field", text: value, textRevision: revision, placeholder: "Placeholder",
                            error: error, vlType: nil, vlText: nil, editable: true
                        ),
                        label: "Field", hidden: false, multiline: false, autofill: nil
                    ),
                    fallbackLabel: nil, onChange: onChange, onAutofill: { _ in }
                )
            }
        }
        .formStyle(.grouped)
    }

    private func settle(_ view: UIView) async {
        view.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(80))
        view.layoutIfNeeded()
    }

    private func descendants(_ view: UIView) -> [UIView] {
        view.subviews.flatMap { [$0] + descendants($0) }
    }
}
#endif
