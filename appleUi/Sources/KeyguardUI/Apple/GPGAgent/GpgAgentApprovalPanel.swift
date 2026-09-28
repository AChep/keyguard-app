#if os(macOS)
import AppKit

/// A borderless, non-activating floating panel that hosts the GPG agent approval
/// prompt. Mirrors `QuickSearchPanel`: `.nonactivatingPanel` + `canBecomeKey == true`
/// lets the Approve / Deny buttons accept clicks and keyboard shortcuts without
/// yanking the foreground app's activation. Unlike Quick Search it has no
/// auto-dismiss-on-resign behaviour — the controller closes it only when the
/// pending-request queue empties.
final class GpgAgentApprovalPanel: NSPanel {
    init(contentView: NSView) {
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
}

#endif
