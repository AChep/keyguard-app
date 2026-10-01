import XCTest
@testable import KeyguardUI

@MainActor
final class GeneratorEditingStateTests: XCTestCase {
    func testRemountIgnoresDelayedEchoIncludingIntentionalDeletion() {
        let editing = GeneratorEditingState()
        editing.reconcile(key: "email", remote: "old@example.com", revision: 0)
        for draft in ["new@example.com", ""] {
            editing.edit(key: "email", text: draft, revision: 0)
            editing.prepareForRemount()
            editing.reconcile(key: "email", remote: "old@example.com", revision: 0)
            XCTAssertEqual(editing.text(key: "email", remote: "old@example.com", revision: 0), draft)
        }
    }

    func testAuthoritativeRevisionReplacesDraftEvenWhileUnmounted() {
        let editing = GeneratorEditingState()
        editing.edit(key: "email", text: "draft", revision: 0)
        editing.prepareForRemount()
        XCTAssertEqual(editing.text(key: "email", remote: "restored", revision: 1), "restored")
        editing.reconcile(key: "email", remote: "restored", revision: 1)
        editing.edit(key: "email", text: "edited restore", revision: 1)
        editing.reconcile(key: "email", remote: "restored", revision: 1)
        XCTAssertEqual(editing.text(key: "email", remote: "restored", revision: 1), "edited restore")
    }

    func testDisappearingFieldCannotClearReplacementFocus() {
        let editing = GeneratorEditingState()
        let old = UUID()
        editing.mount(key: "email", token: old)
        editing.focusChanged(key: "email", token: old, focused: true)
        editing.prepareForRemount()
        editing.focusChanged(key: "email", token: old, focused: false)
        XCTAssertEqual(editing.focusedKey, "email")

        let replacement = UUID()
        editing.mount(key: "email", token: replacement)
        editing.unmount(key: "email", token: old)
        editing.focusChanged(key: "email", token: old, focused: false)
        XCTAssertEqual(editing.focusedKey, "email")
        editing.focusChanged(key: "email", token: replacement, focused: false)
        XCTAssertNil(editing.focusedKey)
    }

    func testSwitchingFieldsAndRemovingATypeClearsOnlyRelevantState() {
        let editing = GeneratorEditingState()
        let email = UUID()
        let name = UUID()
        editing.mount(key: "email", token: email)
        editing.mount(key: "name", token: name)
        editing.edit(key: "email", text: "draft", revision: 0)
        editing.focusChanged(key: "email", token: email, focused: true)
        editing.focusChanged(key: "name", token: name, focused: true)
        editing.focusChanged(key: "email", token: email, focused: false)
        XCTAssertEqual(editing.focusedKey, "name")
        editing.retain(keys: ["email"])
        XCTAssertNil(editing.focusedKey)
        XCTAssertEqual(editing.text(key: "email", remote: "echo", revision: 0), "draft")
        editing.reset()
        XCTAssertEqual(editing.text(key: "email", remote: "another type", revision: 0), "another type")
        // A reset can arrive after the new type's fields have already mounted.
        editing.focusChanged(key: "email", token: email, focused: true)
        XCTAssertEqual(editing.focusedKey, "email")
        editing.endFocus()
        editing.focusChanged(key: "email", token: email, focused: true)
        XCTAssertNil(editing.focusedKey)
    }
}
