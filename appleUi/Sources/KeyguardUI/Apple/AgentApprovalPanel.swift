#if os(macOS)
import AppKit

/// A borderless, non-activating floating panel that hosts an approval prompt of
/// the SSH agent, the GPG agent or Environments. `.nonactivatingPanel` +
/// `canBecomeKey == true` lets the Approve / Deny buttons accept clicks and
/// keyboard input without yanking the foreground app's activation.
final class AgentApprovalPanel: NSPanel {
    private let inputGuard: AgentApprovalInputGuard

    /// - Parameter inputGuard: Sees this panel's key presses, clicks and key status.
    init(contentView: NSView, inputGuard: AgentApprovalInputGuard) {
        self.inputGuard = inputGuard
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 240),
            styleMask: [.nonactivatingPanel, .titled, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        isFloatingPanel = true
        level = .floating
        titleVisibility = .hidden
        titlebarAppearsTransparent = true
        standardWindowButton(.closeButton)?.isHidden = true
        standardWindowButton(.miniaturizeButton)?.isHidden = true
        standardWindowButton(.zoomButton)?.isHidden = true
        isMovableByWindowBackground = true
        backgroundColor = .clear
        isOpaque = false
        hasShadow = true
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        isReleasedWhenClosed = false
        animationBehavior = .utilityWindow
        self.contentView = contentView
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    override func sendEvent(_ event: NSEvent) {
        switch event.type {
        case .keyDown, .leftMouseDown, .rightMouseDown, .otherMouseDown:
            inputGuard.onInput()
        default:
            break
        }
        super.sendEvent(event)
    }

    override func becomeKey() {
        super.becomeKey()
        inputGuard.setFocused(true)
    }

    override func resignKey() {
        super.resignKey()
        inputGuard.setFocused(false)
    }
}

#endif
