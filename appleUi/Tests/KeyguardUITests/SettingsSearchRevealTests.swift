import SwiftUI
import Observation
import XCTest
import KeyguardShared
@testable import KeyguardUI

@MainActor
final class SettingsSearchRevealTests: XCTestCase {
    func testDelayedAndRepeatedRevealsScrollWithoutChangingTheSetting() async throws {
        try await exerciseReveals(textSize: .large)
    }

    func testRevealsAtAccessibilityTextSize() async throws {
        try await exerciseReveals(textSize: .accessibility3)
    }

    private func exerciseReveals(textSize: DynamicTypeSize) async throws {
        #if os(iOS)
        // Lazy UIKit-backed Forms require an application run loop to scroll.
        try XCTSkipUnless(Bundle.main.bundleURL.pathExtension == "app", "Requires an iOS application test host")
        #endif
        let state = RevealState()
        #if os(macOS)
        let host = NSHostingView(rootView: RevealFixture(state: state).environment(\.dynamicTypeSize, textSize))
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 500, height: 350),
            styleMask: [.titled], backing: .buffered, defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = host
        defer { window.close() }
        let layout = { host.layoutSubtreeIfNeeded() }
        #else
        let host = UIHostingController(rootView: RevealFixture(state: state).environment(\.dynamicTypeSize, textSize))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 500, height: 350)
        window.rootViewController = host
        window.isHidden = false
        host.view.frame = window.bounds
        defer { window.isHidden = true }
        let layout = { host.view.layoutIfNeeded() }
        #endif
        layout()
        state.request = SettingsRevealRequest(target: .appVersion)
        try await settle(layout)
        XCTAssertFalse(state.enabled)
        #if os(iOS)
        let scroll = try XCTUnwrap(findScrollView(host.view))
        let initialOffset = scroll.contentOffset.y
        #endif

        state.ready = true
        try await settle(layout)
        #if os(iOS)
        XCTAssertGreaterThan(scroll.contentOffset.y, initialOffset + 500)
        #endif
        assertVisible(state.readOnly, "Loaded destination should reveal its read-only row")
        XCTAssertEqual(state.actionCount, 0)
        XCTAssertFalse(state.enabled)

        state.request = SettingsRevealRequest(target: .theme)
        try await settle(layout)
        assertVisible(state.top, "Selecting another result should scroll back up")
        #if os(iOS)
        XCTAssertEqual(scroll.contentOffset.y, initialOffset, accuracy: 10)
        #endif

        state.request = SettingsRevealRequest(target: .clipboard)
        try await settle(layout)
        assertVisible(state.bottom, "A repeated result should scroll again")
        #if os(iOS)
        XCTAssertGreaterThan(scroll.contentOffset.y, initialOffset + 500)
        #endif
        XCTAssertFalse(state.enabled, "Revealing a toggle must never activate it")

        state.request = SettingsRevealRequest(target: .licenseRemove)
        try await settle(layout)
        assertVisible(state.action, "An action result should reveal its button")
        XCTAssertEqual(state.actionCount, 0, "Revealing an action must never execute it")
        state.request = SettingsRevealRequest(target: .theme)
        try await settle(layout)
        state.request = SettingsRevealRequest(target: .appVersion)
        try await settle(layout)
        assertVisible(state.readOnly, "A repeated read-only result should scroll again")
    }

    #if os(iOS)
    private func findScrollView(_ view: UIView) -> UIScrollView? {
        if let scroll = view as? UIScrollView { return scroll }
        return view.subviews.lazy.compactMap { self.findScrollView($0) }.first
    }
    #endif

    private func settle(_ layout: () -> Void) async throws {
        try await Task.sleep(for: .milliseconds(400))
        layout()
    }

    private func assertVisible(_ frame: CGRect?, _ message: String, file: StaticString = #filePath, line: UInt = #line)
    {
        guard let frame else {
            XCTFail(message, file: file, line: line)
            return
        }
        XCTAssertGreaterThanOrEqual(frame.minY, 0, message, file: file, line: line)
        XCTAssertLessThanOrEqual(frame.maxY, 350, message, file: file, line: line)
    }
}

@MainActor
@Observable
private final class RevealState {
    var ready = false
    var enabled = false
    var request: SettingsRevealRequest?
    var top: CGRect?
    var bottom: CGRect?
    var readOnly: CGRect?
    var action: CGRect?
    var actionCount = 0
}

private struct RevealFixture: View {
    @Bindable var state: RevealState

    var body: some View {
        SettingsForm(ready: state.ready) {
            Section {
                Text("Appearance")
                    .onGeometryChange(for: CGRect.self) {
                        $0.frame(in: .named("form"))
                    } action: {
                        state.top = $0
                    }
                    .settingsSearchTarget(.theme)
                ForEach(0..<30) { index in
                    LabeledContent("Setting \(index)", value: "Value")
                }
                if state.ready {
                    LabeledContent("App version", value: "1.0")
                        .onGeometryChange(for: CGRect.self) {
                            $0.frame(in: .named("form"))
                        } action: {
                            state.readOnly = $0
                        }
                        .settingsSearchTarget(.appVersion)
                    Button("Remove license", role: .destructive) { state.actionCount += 1 }
                        .onGeometryChange(for: CGRect.self) {
                            $0.frame(in: .named("form"))
                        } action: {
                            state.action = $0
                        }
                        .settingsSearchTarget(.licenseRemove)
                    Toggle("Clear clipboard", isOn: $state.enabled)
                        .onGeometryChange(for: CGRect.self) {
                            $0.frame(in: .named("form"))
                        } action: {
                            state.bottom = $0
                        }
                        .settingsSearchTarget(.clipboard)
                }
            }
        }
        // The animated scroll advances on display-link ticks, which stop while the
        // display sleeps. Reduce Motion makes the reveal land in one layout pass.
        .environment(\._accessibilityReduceMotion, true)
        .environment(\.settingsRevealRequest, state.request)
        .coordinateSpace(name: "form")
        .frame(width: 500, height: 350)
    }
}
