#if os(macOS)
import AppKit
import Observation
import SwiftUI

/// Owns the lifecycle of one approval panel: the SSH agent's, the GPG agent's,
/// Environments' or the command-line tool's. Lives for the whole process so approval prompts surface as a
/// floating window regardless of whether the main window is open — including
/// menu-bar-only mode.
///
/// The panel hosts one SwiftUI root for its whole life. The root renders the front
/// request itself, so a queue or vault status change never rebuilds the hosting view.
///
/// Deliberately has no resign-key auto-dismiss — an approval prompt must stay put
/// until the user acts.
@MainActor
public final class AgentApprovalPanelController {
    private let hasRequest: () -> Bool
    private let makeRoot: () -> AnyView
    private var panel: AgentApprovalPanel?
    private var hosting: NSHostingView<AnyView>?

    /// - Parameters:
    ///   - hasRequest: Whether a request is pending. Every change to the state it
    ///     reads shows or hides the panel, and re-fits a shown panel to its content.
    ///   - makeRoot: The hosted root, which renders the front request.
    init(hasRequest: @escaping () -> Bool, makeRoot: @escaping () -> AnyView) {
        self.hasRequest = hasRequest
        self.makeRoot = makeRoot
        sync()
        observe()
    }

    /// A panel shown while `requests` is not empty. It renders `prompt` for the
    /// front request in the app environment.
    convenience init<Request: AgentApprovalRequest, Prompt: View>(
        model: AppViewModel,
        requests: @escaping () -> [Request],
        @ViewBuilder prompt: @escaping (Request) -> Prompt
    ) {
        let authModel = model.auth
        self.init(
            hasRequest: {
                // The prompt grows to unlock a locked vault; re-fit on status changes.
                _ = authModel.status
                return !requests().isEmpty
            },
            makeRoot: {
                AnyView(AgentApprovalPanelRoot(requests: requests, prompt: prompt).keyguardEnvironment(model: model))
            }
        )
    }

    /// Re-arms Observation tracking on the state `hasRequest` reads; each change
    /// syncs the panel and registers the next observation.
    private func observe() {
        withObservationTracking {
            _ = hasRequest()
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.sync()
                self.observe()
            }
        }
    }

    private func sync() {
        if hasRequest() {
            show()
        } else {
            hide()
        }
    }

    private func show() {
        let panel = ensurePanel()
        fit(panel)
        position(panel)
        panel.makeKeyAndOrderFront(nil)
        panel.orderFrontRegardless()
        // Re-fit after SwiftUI applies the (possibly changed) front request, so the
        // panel tracks content height when the queue advances between requests.
        DispatchQueue.main.async { [weak self] in
            guard let self, let panel = self.panel, panel.isVisible else { return }
            self.fit(panel)
            self.position(panel)
        }
    }

    private func hide() {
        panel?.orderOut(nil)
    }

    private func ensurePanel() -> AgentApprovalPanel {
        if let panel { return panel }
        let hosting = NSHostingView(rootView: makeRoot())
        let panel = AgentApprovalPanel(contentView: hosting)
        self.hosting = hosting
        self.panel = panel
        return panel
    }

    private func fit(_ panel: AgentApprovalPanel) {
        guard let hosting else { return }
        let size = hosting.fittingSize
        guard size.width > 0, size.height > 0 else { return }
        panel.setContentSize(size)
    }

    private func position(_ panel: AgentApprovalPanel) {
        guard let screen = NSScreen.main else { return }
        let companionFrames = NSApp.windows
            .filter { $0 !== panel && $0 is AgentApprovalPanel && $0.isVisible }
            .map { $0.frame }
        let origin = AgentApprovalPanelPlacement.origin(
            size: panel.frame.size,
            visibleFrame: screen.visibleFrame,
            companionFrames: companionFrames
        )
        panel.setFrameOrigin(origin)
    }
}

#endif
