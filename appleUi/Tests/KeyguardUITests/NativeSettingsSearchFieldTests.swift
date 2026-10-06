#if os(macOS)
import SwiftUI
import XCTest
@testable import KeyguardUI

@MainActor
final class NativeSettingsSearchFieldTests: XCTestCase {
    func testFocusRequestAndNativeCancelUpdateTheBoundQuery() async throws {
        var query = "clipboard"
        let binding = Binding(get: { query }, set: { query = $0 })
        let host = NSHostingView(rootView: NativeListSearchField(text: binding, prompt: "Search settings"))
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 300, height: 40),
            styleMask: [.titled], backing: .buffered, defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = host
        defer { window.close() }
        host.layoutSubtreeIfNeeded()
        try await Task.sleep(for: .milliseconds(100))
        let field = try XCTUnwrap(findSearchField(host))
        XCTAssertEqual(field.stringValue, "clipboard")
        XCTAssertEqual(field.maximumRecents, 0)

        host.rootView = NativeListSearchField(text: binding, prompt: "Search settings", focusRequest: 1)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertNotNil(field.currentEditor(), "The focus request should enter the native search field")
        let cell = try XCTUnwrap((field.cell as? NSSearchFieldCell)?.cancelButtonCell)
        let action = try XCTUnwrap(cell.action)
        XCTAssertTrue(NSApp.sendAction(action, to: cell.target, from: field))
        XCTAssertEqual(query, "")
        XCTAssertEqual(field.stringValue, "")
    }

    private func findSearchField(_ view: NSView) -> NSSearchField? {
        if let field = view as? NSSearchField { return field }
        return view.subviews.lazy.compactMap { self.findSearchField($0) }.first
    }
}
#endif
