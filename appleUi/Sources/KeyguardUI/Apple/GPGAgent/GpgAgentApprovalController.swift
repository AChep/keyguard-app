#if os(macOS)
import AppKit
import Observation
import SwiftUI
import KeyguardShared

/// Owns the GPG agent approval panel lifecycle. Lives for the whole process
/// so approval prompts surface as a floating window regardless of whether the main
/// window is open — including menu-bar-only mode.
///
/// Deliberately has no resign-key auto-dismiss — an approval prompt must stay put
/// until the user acts.
@MainActor
public final class GpgAgentApprovalController {
    private let gpgAgentModel: GpgAgentModel
    private let authModel: VaultSessionModel
    private let makeRoot: () -> AnyView
    private var panel: GpgAgentApprovalPanel?
    private var hosting: NSHostingView<AnyView>?
    public init(model: AppViewModel) {
        self.gpgAgentModel = model.gpgAgent
        self.authModel = model.auth
        self.makeRoot = { AnyView(GpgAgentApprovalPanelRoot().keyguardEnvironment(model: model)) }
        // A controller owned by the app delegate also observes in menu-bar-only mode.
        gpgAgentModel.startGpgAgentObservation()
        sync(gpgAgentModel.gpgAgentRequests)
        observeRequests()
    }

    /// Re-arms Observation tracking on the requests property; each change
    /// syncs the panel and registers the next observation.
    private func observeRequests() {
        withObservationTracking {
            _ = gpgAgentModel.gpgAgentRequests
            _ = authModel.status
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.sync(self.gpgAgentModel.gpgAgentRequests)
                self.observeRequests()
            }
        }
    }

    private func sync(_ requests: [GpgAgentRequestSnapshot]) {
        if requests.isEmpty {
            hide()
        } else {
            show()
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

    private func ensurePanel() -> GpgAgentApprovalPanel {
        if let panel { return panel }
        let hosting = NSHostingView(rootView: makeRoot())
        let panel = GpgAgentApprovalPanel(contentView: hosting)
        self.hosting = hosting
        self.panel = panel
        return panel
    }

    private func fit(_ panel: GpgAgentApprovalPanel) {
        guard let hosting else { return }
        let size = hosting.fittingSize
        guard size.width > 0, size.height > 0 else { return }
        panel.setContentSize(size)
    }

    private func position(_ panel: NSPanel) {
        guard let screen = NSScreen.main else { return }
        let companion = NSApp.windows.first { $0 is SshAgentApprovalPanel && $0.isVisible }
        let origin = AgentApprovalPanelPlacement.origin(
            size: panel.frame.size,
            visibleFrame: screen.visibleFrame,
            companionFrame: companion?.frame
        )
        panel.setFrameOrigin(origin)
    }
}

#endif
