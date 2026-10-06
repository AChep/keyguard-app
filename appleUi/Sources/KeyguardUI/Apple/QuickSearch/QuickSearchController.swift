#if os(macOS)
import AppKit
import SwiftUI
import Carbon.HIToolbox

/// Owns the global hotkey and the Quick Search panel lifecycle; lives for the whole process.
@MainActor
public final class QuickSearchController {
    private let quickSearchModel: QuickSearchModel
    private let makeRoot: (@escaping () -> Void) -> AnyView
    private var panel: QuickSearchPanel?
    private var hotKey: GlobalHotkey?
    private var resignObserver: NSObjectProtocol?

    /// How long the query + item selection survive after the panel is hidden. The
    /// producer is kept alive (not torn down) across hide/show for this window, so a
    /// reopen within it restores the same query and selection; a vault lock resets it
    /// sooner via `observeQuickSearch`'s `onLocked`.
    private let preservationWindow: TimeInterval = 300
    private var pendingReset: DispatchWorkItem?

    public init(model: AppViewModel) {
        self.quickSearchModel = model.quickSearch
        self.makeRoot = { onDismiss in
            AnyView(QuickSearchView(onDismiss: onDismiss).keyguardEnvironment(model: model))
        }
        do {
            hotKey = try GlobalHotkey(
                keyCode: UInt32(kVK_Space),
                modifiers: UInt32(cmdKey | shiftKey)
            ) { [weak self] in
                Task { @MainActor in self?.toggle() }
            }
        } catch {
            NSLog("Failed to register Quick Search global hotkey: \(error)")
        }
    }

    // Isolated so the teardown runs on the main actor, where the panel, the observer
    // and the Carbon hot-key were all set up.
    isolated deinit {
        pendingReset?.cancel()
        if let resignObserver {
            NotificationCenter.default.removeObserver(resignObserver)
        }
        let status = hotKey?.invalidate() ?? noErr
        if status != noErr {
            NSLog("Failed to invalidate Quick Search global hotkey: OSStatus \(status)")
        }
    }

    func toggle() {
        if panel?.isVisible == true {
            hide()
        } else {
            show()
        }
    }

    func show() {
        let panel = ensurePanel()
        // Cancel a pending teardown: a reopen within the window keeps the live
        // producer, so the previous query + selection are still there.
        pendingReset?.cancel()
        pendingReset = nil
        quickSearchModel.setQuickSearchVisible(true)
        if !quickSearchModel.isObservingQuickSearch {
            // Fresh open (first time, after the window elapsed, or after a lock
            // teardown): start the producer and clear to an empty query.
            quickSearchModel.startQuickSearchObservation()
            quickSearchModel.clearQuickSearchQuery()
        }
        position(panel)
        panel.makeKeyAndOrderFront(nil)
        panel.orderFrontRegardless()
        observeResign(panel)
        // The panel/hosting view is cached, so `QuickSearchView.onAppear` only fires
        // on the first show. Re-request focus on the next runloop tick — by then the
        // panel is key and the SwiftUI view is mounted, so `@FocusState` takes.
        DispatchQueue.main.async { [weak self] in self?.quickSearchModel.requestQuickSearchFocus() }
    }

    func hide() {
        if let resignObserver {
            NotificationCenter.default.removeObserver(resignObserver)
            self.resignObserver = nil
        }
        panel?.orderOut(nil)
        quickSearchModel.setQuickSearchVisible(false)
        scheduleReset()
    }

    /// Tearing the observation down resets the snapshot to `.empty`, which clears the
    /// search field's local buffer (via `bridgedText`), so the next open starts fresh.
    private func scheduleReset() {
        pendingReset?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.pendingReset = nil
            self?.quickSearchModel.stopQuickSearchObservation()
        }
        pendingReset = work
        DispatchQueue.main.asyncAfter(deadline: .now() + preservationWindow, execute: work)
    }

    private func ensurePanel() -> QuickSearchPanel {
        if let panel { return panel }
        let hosting = NSHostingView(rootView: makeRoot { [weak self] in self?.hide() })
        let panel = QuickSearchPanel(contentView: hosting)
        self.panel = panel
        return panel
    }

    private func position(_ panel: NSPanel) {
        guard let screen = NSScreen.main else { return }
        let visible = screen.visibleFrame
        let size = panel.frame.size
        let x = visible.midX - size.width / 2
        // Sit a little above centre, like Spotlight.
        let y = visible.midY - size.height / 2 + visible.height * 0.12
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }

    private func observeResign(_ panel: NSPanel) {
        if let resignObserver {
            NotificationCenter.default.removeObserver(resignObserver)
        }
        resignObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didResignKeyNotification,
            object: panel,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                // An authentication sheet becomes key above the panel. Keep its
                // parent alive until the user completes or cancels the prompt.
                guard self.panel?.attachedSheet == nil else { return }
                self.hide()
            }
        }
    }
}

#endif
