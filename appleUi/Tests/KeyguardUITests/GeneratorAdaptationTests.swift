#if os(iOS)
import SwiftUI
import UIKit
import XCTest
import KeyguardShared
@testable import KeyguardUI

@MainActor
final class GeneratorAdaptationTests: XCTestCase {
    func testResizePreservesDraftAndFocusWithoutSendingAnotherEdit() async throws {
        try XCTSkipUnless(Bundle.main.bundleURL.pathExtension == "app", "Requires an iOS application test host")
        let editing = GeneratorEditingState()
        var edits: [String] = []
        var invocations: [String] = []
        let actions = actions(edit: { edits.append($0) }, invoke: { invocations.append($0) })
        let host = UIHostingController(
            rootView: content(width: 1_194, editing: editing, actions: actions))
        let window = makeWindow(host: host)
        defer { window.isHidden = true }
        await settle(host.view)
        var field = try textField(host.view)
        XCTAssertTrue(field.becomeFirstResponder())
        field.insertText("x")
        await settle(host.view)
        let draft = try XCTUnwrap(field.text)
        XCTAssertEqual(edits, [draft])
        XCTAssertEqual(editing.focusedKey, "gpg_key.email")

        // Validate the bridge's delayed echo while the field remains mounted.
        host.rootView = content(width: 1_194, editing: editing, actions: actions, loading: true, error: "Invalid email")
        await settle(host.view)
        XCTAssertTrue(field.isFirstResponder)
        XCTAssertEqual(field.text, draft)

        for width: CGFloat in [390, 834, 1_194] {
            editing.prepareForRemount()
            window.frame.size.width = width
            host.rootView = content(width: width, editing: editing, actions: actions)
            await settle(host.view)
            field = try textField(host.view)
            XCTAssertEqual(field.text, draft, "Draft after resizing to \(width)")
            XCTAssertTrue(field.isFirstResponder, "Focus after resizing to \(width)")
            XCTAssertEqual(edits, [draft], "Mounting must not send another edit")
            XCTAssertTrue(invocations.isEmpty, "Resizing must not generate or copy")
        }

        field.text = ""
        field.sendActions(for: .editingChanged)
        await settle(host.view)
        XCTAssertEqual(edits.last, "")
        editing.prepareForRemount()
        host.rootView = content(width: 390, editing: editing, actions: actions)
        await settle(host.view)
        XCTAssertEqual(try textField(host.view).text, "")
        XCTAssertEqual(edits.count, 2)

        host.rootView = content(width: 390, editing: editing, actions: actions, revision: 1)
        await settle(host.view)
        XCTAssertEqual(try textField(host.view).text, "restored@example.com")
        XCTAssertEqual(edits.count, 2, "Authoritative restores must not be echoed as edits")
    }

    func testIPadLayoutsRenderAtCommonSizesAndLargeText() async throws {
        let editing = GeneratorEditingState()
        var invocations: [String] = []
        let actions = actions(edit: { invocations.append($0) }, invoke: { invocations.append($0) })
        let host = UIHostingController(rootView: content(width: 1_194, editing: editing, actions: actions))
        let window = makeWindow(host: host)
        defer { window.isHidden = true }

        for (width, height): (CGFloat, CGFloat) in [
            (744, 1_133), (1_133, 744), (834, 1_194), (1_194, 834), (1_032, 1_376), (1_376, 1_032), (390, 700),
        ] {
            editing.prepareForRemount()
            window.frame.size = CGSize(width: width, height: height)
            host.rootView = content(width: width, editing: editing, actions: actions)
            await settle(host.view)
            let scrollViews = descendants(host.view).compactMap { $0 as? UIScrollView }
                .filter { $0.bounds.width > 100 && $0.bounds.height > 100 }
            XCTAssertEqual(scrollViews.count, width >= 840 ? 2 : 1)
            XCTAssertTrue(invocations.isEmpty)
            attach(host.view, name: "Generator \(Int(width))x\(Int(height))")
        }

        editing.prepareForRemount()
        window.frame.size = CGSize(width: 1_376, height: 1_032)
        host.rootView = content(width: 1_376, editing: editing, actions: actions, accessible: true)
        host.overrideUserInterfaceStyle = .dark
        await settle(host.view)
        XCTAssertTrue(invocations.isEmpty)
        attach(host.view, name: "Generator accessible RTL dark")
    }

    private func content(
        width: CGFloat, editing: GeneratorEditingState, actions: GeneratorActions,
        loading: Bool = false, error: String? = nil, revision: Int32 = 0, accessible: Bool = false
    ) -> some View {
        GeneratorContentView(
            generator: snapshot(loading: loading, error: error, revision: revision), actions: actions, editing: editing,
            layout: GeneratorWorkspaceLayout(
                width: width, isPad: true, isRegular: width >= 600, isAccessibilitySize: accessible)
        )
        .environment(\.dynamicTypeSize, accessible ? .accessibility3 : .large)
        .environment(\.layoutDirection, accessible ? .rightToLeft : .leftToRight)
    }

    private func snapshot(loading: Bool, error: String?, revision: Int32) -> GeneratorSnapshot {
        GeneratorSnapshot(
            loaded: !loading, typeTitle: "GPG key",
            types: [GeneratorTypeItemSnapshot(id: "gpg", kind: .type, title: "GPG key", selected: true)],
            value: loading
                ? nil
                : GeneratorValueSnapshot(
                    title: "Fingerprint", value: "ABCD 1234 EFGH 5678 IJKL 9012 MNOP 3456 QRST 7890",
                    showStrength: false, canCopy: true, canRefresh: true,
                    actions: [GeneratorActionSnapshot(id: "export", title: "Export public key", selected: false)]
                ),
            suggestions: [],
            tip: GeneratorTipSnapshot(text: "Keep the private key safe.", canHide: true, canLearnMore: true),
            length: GeneratorLengthSnapshot(value: 24, min: 1, max: 128),
            filters: [
                GeneratorFilterSnapshot(
                    key: "gpg_key.email", kind: .textField, title: "Email", text: nil,
                    switchValue: false, switchEnabled: false,
                    textValue: revision == 0 ? "original@example.com" : "restored@example.com", textRevision: revision,
                    textPlaceholder: "Email", textError: error, enumValue: "", enumOptions: [], counter: nil
                ),
                GeneratorFilterSnapshot(
                    key: "test.counter", kind: .switchField, title: "An option with a long localized title", text: nil,
                    switchValue: true, switchEnabled: true, textValue: "", textRevision: 0,
                    textPlaceholder: nil, textError: nil, enumValue: "", enumOptions: [],
                    counter: GeneratorCounterSnapshot(value: 1, min: 0, max: 128)
                ),
            ], options: [], canOpenHistory: true
        )
    }

    private func actions(
        edit: @escaping @MainActor @Sendable (String) -> Void,
        invoke: @escaping @MainActor @Sendable (String) -> Void
    ) -> GeneratorActions {
        GeneratorActions(
            invoke: invoke, setSwitch: { _, _ in invoke("switch") }, setCounter: { _, _ in invoke("counter") },
            setText: { _, value in edit(value) }, setLength: { _ in invoke("length") }
        )
    }

    private func makeWindow(host: UIViewController) -> UIWindow {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 1_194, height: 834)
        window.rootViewController = host
        host.traitOverrides.horizontalSizeClass = .regular
        window.makeKeyAndVisible()
        return window
    }

    private func textField(_ view: UIView) throws -> UITextField {
        try XCTUnwrap(descendants(view).compactMap { $0 as? UITextField }.first)
    }

    private func settle(_ view: UIView) async {
        view.setNeedsLayout()
        view.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(300))
        view.layoutIfNeeded()
    }

    private func descendants(_ view: UIView) -> [UIView] {
        view.subviews.flatMap { [$0] + descendants($0) }
    }

    private func attach(_ view: UIView, name: String) {
        let image = UIGraphicsImageRenderer(bounds: view.bounds).image { _ in
            view.drawHierarchy(in: view.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
#endif
