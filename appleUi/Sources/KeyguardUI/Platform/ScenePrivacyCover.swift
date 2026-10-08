#if os(iOS)
import UIKit

/// Covers each window above its presented sheets before iOS takes a scene snapshot.
@MainActor
final class ScenePrivacyCover: NSObject {
    private let notifications: NotificationCenter
    private weak var scene: UIWindowScene?
    private var isCovering = false
    private var coveredWindows: [CoveredWindow] = []

    private struct CoveredWindow: Sendable {
        weak var window: UIWindow?
        let cover: UIView
        let accessibilityElementsHidden: Bool
    }

    init(notifications: NotificationCenter = .default) {
        self.notifications = notifications
        super.init()
    }

    func attach(to scene: UIWindowScene) {
        guard self.scene !== scene else { return }
        stop()
        self.scene = scene

        // UIKit delivers these notifications synchronously on the main thread.
        // A Task or SwiftUI state update could run after the snapshot is captured.
        for name in [UIScene.willDeactivateNotification, UIScene.didEnterBackgroundNotification] {
            notifications.addObserver(self, selector: #selector(coverScene), name: name, object: scene)
        }
        notifications.addObserver(
            self, selector: #selector(uncoverScene), name: UIScene.didActivateNotification, object: scene)
        notifications.addObserver(
            self, selector: #selector(stop), name: UIScene.didDisconnectNotification, object: scene)
        notifications.addObserver(
            self, selector: #selector(windowBecameVisible), name: UIWindow.didBecomeVisibleNotification, object: nil)

        if scene.activationState != .foregroundActive { coverScene() }
    }

    @objc private func coverScene() {
        isCovering = true
        scene?.windows.forEach(cover)
    }

    @objc private func windowBecameVisible(_ notification: Notification) {
        guard isCovering, let window = notification.object as? UIWindow, window.windowScene === scene else { return }
        cover(window)
    }

    private func cover(_ window: UIWindow) {
        let cover: UIView
        if let existing = coveredWindows.first(where: { $0.window === window }) {
            cover = existing.cover
        } else {
            cover = UIView(frame: window.bounds)
            cover.backgroundColor = .systemBackground
            cover.isOpaque = true
            cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            cover.accessibilityIdentifier = "keyguard.privacy-cover"
            coveredWindows.append(
                CoveredWindow(
                    window: window, cover: cover,
                    accessibilityElementsHidden: window.accessibilityElementsHidden))
            window.addSubview(cover)
        }
        window.accessibilityElementsHidden = true
        // A presentation may have added views since the scene became inactive.
        // Reassert the cover on background entry immediately before the snapshot.
        cover.frame = window.bounds
        window.bringSubviewToFront(cover)
    }

    @objc private func uncoverScene() {
        isCovering = false
        Self.uncover(coveredWindows)
        coveredWindows.removeAll()
    }

    private static func uncover(_ windows: [CoveredWindow]) {
        for covered in windows {
            covered.window?.accessibilityElementsHidden = covered.accessibilityElementsHidden
            covered.cover.removeFromSuperview()
        }
    }

    @objc func stop() {
        notifications.removeObserver(self)
        uncoverScene()
        scene = nil
    }

    deinit {
        let windows = coveredWindows
        notifications.removeObserver(self)
        guard !windows.isEmpty else { return }
        // Isolated deinit crashes in older Swift runtimes when UIKit releases
        // the view outside a task. Transfer only the cleanup state to MainActor.
        if Thread.isMainThread {
            MainActor.assumeIsolated { Self.uncover(windows) }
        } else {
            Task { @MainActor in Self.uncover(windows) }
        }
    }
}
#endif
