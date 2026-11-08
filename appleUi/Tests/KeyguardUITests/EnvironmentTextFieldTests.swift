import SwiftUI
import XCTest
@testable import KeyguardUI

private let multilineEnv = "ALPHA=one\nEMPTY=\nUNICODE='Привіт 🔐'\n"

#if os(macOS)
@MainActor
final class EnvironmentTextFieldTests: XCTestCase {
    func testConcealedPasteKeepsLineBreaks() async throws {
        let edits = try await paste(concealed: true)
        XCTAssertEqual(edits.last, multilineEnv)
    }

    func testRevealedPasteKeepsLineBreaks() async throws {
        let edits = try await paste(concealed: false)
        XCTAssertEqual(edits.last, multilineEnv)
    }

    private func paste(concealed: Bool) async throws -> [String] {
        var edits: [String] = []
        let host = NSHostingView(
            rootView: Form {
                EnvironmentTextField(title: "Paste", value: "", concealed: concealed) { edits.append($0) }
            }
            .formStyle(.grouped)
            .frame(width: 360, height: 240))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 300),
            styleMask: [.titled], backing: .buffered, defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = host
        defer { window.close() }
        await settle(host)
        let fields = descendants(host).compactMap { $0 as? NSTextField }.filter(\.isEditable)
        let field = try XCTUnwrap(fields.first)
        XCTAssertEqual(field is NSSecureTextField, concealed)
        XCTAssertTrue(window.makeFirstResponder(field))
        let input = try XCTUnwrap(field.currentEditor() as? NSTextView)
        // A private pasteboard runs the paste path without
        // touching the general one.
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("keyguard.test.\(UUID().uuidString)"))
        defer { pasteboard.releaseGlobally() }
        pasteboard.clearContents()
        pasteboard.setString(multilineEnv, forType: .string)
        XCTAssertTrue(input.readSelection(from: pasteboard))
        await settle(host)
        return edits
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

#if os(iOS)
@MainActor
final class EnvironmentTextFieldTests: XCTestCase {
    func testConcealedValueIsAReadOnlyMask() async throws {
        let host = UIHostingController(
            rootView: editor(value: multilineEnv, concealed: true, onChange: { _ in }))
        let window = makeWindow(host)
        defer { window.isHidden = true }
        await settle(host.view)
        let views = descendants(host.view)
        // A secure field would turn line breaks into spaces.
        XCTAssertFalse(views.contains { $0 is UITextField })
        XCTAssertFalse(views.contains { ($0 as? UITextView)?.isEditable == true })
    }

    func testRevealedFieldKeepsLineBreaks() async throws {
        // Swift Package tests normally run in xctest without UIApplicationMain,
        // which cannot dispatch text editing events.
        try XCTSkipUnless(Bundle.main.bundleURL.pathExtension == "app", "Requires an iOS application test host")
        var edits: [String] = []
        let host = UIHostingController(
            rootView: editor(value: "", concealed: false, onChange: { edits.append($0) }))
        let window = makeWindow(host)
        defer { window.isHidden = true }
        await settle(host.view)
        let input = try XCTUnwrap(descendants(host.view).compactMap { $0 as? UITextView }.first)
        XCTAssertTrue(input.becomeFirstResponder())
        input.insertText(multilineEnv)
        await settle(host.view)
        XCTAssertEqual(edits.last, multilineEnv)
    }

    private func editor(value: String, concealed: Bool, onChange: @escaping (String) -> Void) -> some View {
        Form {
            EnvironmentTextField(title: "Paste", value: value, concealed: concealed, onChange: onChange)
        }
    }

    private func makeWindow(_ host: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        window.rootViewController = host
        window.isHidden = false
        return window
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
