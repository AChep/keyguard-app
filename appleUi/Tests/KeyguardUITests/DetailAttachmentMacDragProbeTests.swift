#if os(macOS)
import AppKit
import SwiftUI
import XCTest
@testable import KeyguardUI

/// Opt-in UI harness using the same native gesture, source, and promise delegate as the app.
final class DetailAttachmentMacDragProbeTests: XCTestCase {
    @MainActor
    func testManualNativeFilePromise() async throws {
        guard ProcessInfo.processInfo.environment["KEYGUARD_ATTACHMENT_DRAG_PROBE"] == "1" else {
            throw XCTSkip("Set KEYGUARD_ATTACHMENT_DRAG_PROBE=1 to open the synthetic native drag window.")
        }
        let application = NSApplication.shared
        application.setActivationPolicy(.regular)
        let state = MacPromiseProbeState()
        let window = NSWindow(
            contentRect: NSRect(x: 100, y: 100, width: 620, height: 260),
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Keyguard native attachment promise probe"
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: MacPromiseProbeView(state: state))
        window.center()
        window.makeKeyAndOrderFront(nil)
        application.activate(ignoringOtherApps: true)
        defer { window.close() }
        let deadline = Date().addingTimeInterval(300)
        while !state.finished, window.isVisible, Date() < deadline {
            // XCTest's command-line runner has no NSApplication event loop. Pump short
            // iterations and yield so asynchronous preparation still runs on MainActor.
            if let event = application.nextEvent(
                matching: .any,
                until: Date().addingTimeInterval(0.01),
                inMode: .default,
                dequeue: true
            ) {
                application.sendEvent(event)
            }
            application.updateWindows()
            await Task.yield()
        }
    }
}

@MainActor
private final class MacPromiseProbeState {
    var finished = false
}

@MainActor
private struct MacPromiseProbeView: View {
    @Environment(\.layoutDirection) private var layoutDirection
    let state: MacPromiseProbeState
    @State private var clicks = 0
    private let preparer = AttachmentDragTestPreparer(
        name: "keyguard-native-promise.bin",
        promisedFileName: "keyguard-native-promise.bin",
        content: {
            AttachmentDragTestContent(
                name: "keyguard-native-promise.bin", data: Data((0..<262_144).map { UInt8($0 % 251) }))
        }
    )

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Drag the paperclip into Finder, Mail, or a browser upload target.")
            HStack(spacing: 12) {
                Image(systemName: "paperclip").frame(width: 20)
                Text(preparer.name).textSelection(.enabled)
                Spacer()
            }
            .padding(.vertical, 12)
            .contentShape(.rect)
            .onTapGesture { clicks += 1 }
            .contextMenu { Button("Probe context menu") {} }
            .overlay {
                DetailAttachmentMacDragHost(preparer: preparer, rightToLeft: layoutDirection == .rightToLeft)
            }
            Text("Ordinary row clicks: \(clicks). Payload: 262144 bytes, byte i = i modulo 251.")
            Button("Finish probe") { state.finished = true }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
#endif
