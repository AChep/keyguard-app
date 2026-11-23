#if os(macOS)
import AppKit
import SwiftUI

/// Controls whether the app shows a Dock icon (regular) or runs as a menu-bar-only
/// agent (accessory). Backed by an `@AppStorage` flag so the choice persists, and
/// applied at runtime via `NSApp.setActivationPolicy` — no `LSUIElement` Info.plist
/// key, so the first launch always shows the normal app.
///
/// Bringing the main window to the front from an accessory app needs the
/// documented recipe: flip to `.regular`, let AppKit settle, activate, then open.
/// `makeKeyAndOrderFront` alone is unreliable from an agent app. A regular app
/// skips the settling delay.
public enum DockIconMode {
    public static let menuBarOnlyKey = "menuBarOnly"

    /// Applies a preference change, preserving hidden windows and their state.
    @MainActor
    public static func apply(menuBarOnly: Bool) {
        NSApp.setActivationPolicy(menuBarOnly ? .accessory : .regular)
        if menuBarOnly {
            for window in NSApp.windows where window.canBecomeMain && !(window is NSPanel) {
                window.orderOut(nil)
            }
        }
    }

    /// A show request waiting for AppKit to settle after the switch to `.regular`.
    @MainActor private static var pendingShow: Task<Void, Never>?

    /// Whether `window` belongs to the `WindowGroup(id: "main")` scene. SwiftUI
    /// names its windows `main-AppWindow-N`; the startup UI tests check that.
    @MainActor
    public static func isMainWindow(_ window: NSWindow) -> Bool {
        window.identifier?.rawValue.hasPrefix("main-") == true
    }

    /// Brings the main window forward, restoring the Dock icon first if we are in
    /// menu-bar-only mode. `open` should call `openWindow(id: "main")`, which
    /// always creates a new window, so it runs only when no main window exists.
    @MainActor
    public static func showMainWindow(open: @escaping () -> Void) {
        // A pending request shows the window; later ones would open a second one.
        guard pendingShow == nil else { return }
        guard NSApp.activationPolicy() != .regular else {
            bringMainWindowForward(open: open)
            return
        }
        NSApp.setActivationPolicy(.regular)
        pendingShow = Task {
            try? await Task.sleep(for: .milliseconds(100))
            pendingShow = nil
            bringMainWindowForward(open: open)
        }
    }

    @MainActor
    private static func bringMainWindowForward(open: () -> Void) {
        NSApp.activate(ignoringOtherApps: true)
        // `NSApp.windows` has no defined order, so prefer `orderedWindows`; fall back
        // for a window that is ordered out or minimized.
        if let window = NSApp.orderedWindows.first(where: { isMainWindow($0) })
            ?? NSApp.windows.first(where: { isMainWindow($0) })
        {
            window.makeKeyAndOrderFront(nil)
        } else {
            open()
        }
        // Opening the window does not turn off the user's Dock preference.
        if UserDefaults.standard.bool(forKey: menuBarOnlyKey) {
            NSApp.setActivationPolicy(.accessory)
        }
    }
}
#endif
