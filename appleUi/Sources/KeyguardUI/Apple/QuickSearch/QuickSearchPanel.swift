#if os(macOS)
import AppKit

/// A borderless, non-activating floating panel that hosts the Quick Search
/// SwiftUI view. `.nonactivatingPanel` + `canBecomeKey == true` lets the search
/// field accept typing without yanking the foreground app's activation (the
/// Spotlight / Maccy pattern).
final class QuickSearchPanel: NSPanel {
    /// Optional explicit dismissal hook. When set (by the controller), Escape routes
    /// through here so the full hide bookkeeping (`setQuickSearchVisible(false)` etc.)
    /// runs. When unset, `cancelOperation` falls back to ordering the panel out, which
    /// fires `didResignKeyNotification` and so still triggers the controller's
    /// resign-key `hide()` — Escape works regardless of which inner control has focus.
    var onCancel: (() -> Void)?

    init(contentView: NSView) {
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 700, height: 460),
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

    /// Escape (`cancelOperation`) dismisses the panel even when focus has moved off the
    /// SwiftUI search field onto a real control inside the panel (an action-strip or
    /// unlock-form button), where `QuickSearchView`'s field-scoped `.onKeyPress(.escape)`
    /// no longer fires. Prefer the controller's `onCancel` hook; otherwise order out,
    /// which trips the resign-key observer that performs the hide.
    override func cancelOperation(_ sender: Any?) {
        if let onCancel {
            onCancel()
        } else {
            orderOut(sender)
        }
    }

    /// Cmd+W: the traffic-light close button is hidden, so route the standard close
    /// command through the same dismissal path rather than letting it no-op/beep.
    override func performClose(_ sender: Any?) {
        cancelOperation(sender)
    }
}

#endif
