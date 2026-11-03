#if os(macOS)
import AppKit
import SwiftUI

/// A non-hit-testing observer leaves row clicks, text selection, and context menus intact.
/// Only a primary-button pan beginning in the icon column can prepare an attachment.
@MainActor
final class DetailAttachmentMacDragView: NSView, NSGestureRecognizerDelegate {
    var preparer: (any AttachmentDragPreparing)?
    var rightToLeft = false
    private weak var observedView: NSView?
    private var pan: NSPanGestureRecognizer?
    private var pending: (any AttachmentDragPreparation)?
    private var generation: UUID?
    private var lastEvent: NSEvent?
    private weak var sourceWindow: NSWindow?
    private var escapeMonitor: Any?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setAccessibilityElement(false)
    }

    required init?(coder: NSCoder) { nil }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        detach()
        guard let root = window?.contentView else { return }
        let recognizer = NSPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        recognizer.buttonMask = 1
        recognizer.delaysPrimaryMouseButtonEvents = true
        recognizer.delaysOtherMouseButtonEvents = false
        recognizer.delegate = self
        root.addGestureRecognizer(recognizer)
        observedView = root
        pan = recognizer
    }

    func detach() {
        cancelPreparation()
        if let pan { observedView?.removeGestureRecognizer(pan) }
        pan = nil
        observedView = nil
    }

    func gestureRecognizer(
        _ gestureRecognizer: NSGestureRecognizer,
        shouldAttemptToRecognizeWith event: NSEvent
    ) -> Bool {
        guard preparer != nil, !isHiddenOrHasHiddenAncestor,
            event.type == .leftMouseDown, event.window === window
        else { return false }
        let point = convert(event.locationInWindow, from: nil)
        // DetailRowLabel's icon is 20 points wide, followed by 12 points of spacing.
        let inIconColumn = rightToLeft ? point.x > bounds.maxX - min(28, bounds.width) : point.x < min(28, bounds.width)
        return visibleRect.contains(point) && inIconColumn
    }

    @objc private func panned(_ recognizer: NSPanGestureRecognizer) {
        switch recognizer.state {
        case .began:
            guard let event = NSApp.currentEvent else { return }
            prepare(event: event)
        case .changed:
            if generation != nil { lastEvent = NSApp.currentEvent ?? lastEvent }
        case .ended, .cancelled, .failed:
            cancelPreparation()
        default:
            break
        }
    }

    private func prepare(event: NSEvent) {
        cancelPreparation()
        guard let preparer, let window else { return }
        if let name = preparer.promisedFileName {
            // The drag starts at once; its offer prepares the file while the user drags.
            AttachmentDragMacTransferStore.shared.start(promising: preparer, name: name, from: self, event: event)
            return
        }
        lastEvent = event
        sourceWindow = window
        let id = UUID()
        generation = id
        escapeMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard event.keyCode == 53, self?.generation != nil else { return event }
            self?.cancelPreparation()
            return nil
        }
        pending = preparer.prepare { [weak self] content in
            Task { @MainActor in
                guard let self else {
                    content?.close()
                    return
                }
                self.prepared(content, generation: id)
            }
        }
    }

    private func prepared(_ content: (any AttachmentDragContent)?, generation id: UUID) {
        guard generation == id else {
            content?.close()
            return
        }
        pending = nil
        generation = nil
        removeEscapeMonitor()
        let event = lastEvent
        let expectedWindow = sourceWindow
        sourceWindow = nil
        lastEvent = nil
        guard let content, let event, let expectedWindow,
            window === expectedWindow, expectedWindow.isVisible,
            expectedWindow.isKeyWindow, NSApp.isActive,
            NSEvent.pressedMouseButtons & 1 != 0,
            content.isAvailable()
        else {
            content?.close()
            return
        }
        AttachmentDragMacTransferStore.shared.start(content: content, from: self, event: event)
    }

    private func cancelPreparation() {
        generation = nil
        let previous = pending
        pending = nil
        previous?.cancel()
        sourceWindow = nil
        lastEvent = nil
        removeEscapeMonitor()
    }

    private func removeEscapeMonitor() {
        if let escapeMonitor { NSEvent.removeMonitor(escapeMonitor) }
        escapeMonitor = nil
    }
}

struct DetailAttachmentMacDragHost: NSViewRepresentable {
    let preparer: any AttachmentDragPreparing
    let rightToLeft: Bool

    func makeNSView(context: Context) -> DetailAttachmentMacDragView {
        let view = DetailAttachmentMacDragView(frame: .zero)
        view.preparer = preparer
        view.rightToLeft = rightToLeft
        return view
    }

    func updateNSView(_ nsView: DetailAttachmentMacDragView, context: Context) {
        nsView.preparer = preparer
        nsView.rightToLeft = rightToLeft
    }

    static func dismantleNSView(_ nsView: DetailAttachmentMacDragView, coordinator: ()) {
        nsView.detach()
        nsView.preparer = nil
    }
}
#endif
